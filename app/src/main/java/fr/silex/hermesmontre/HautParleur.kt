package fr.silex.hermesmontre

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Lit la voix d'Hermes : PCM16 mono, au débit annoncé par `audio.start`, en flux.
 *
 * Le serveur envoie l'audio au rythme du temps réel avec un peu d'avance (0,5 s pour le plugin,
 * 1,5 s pour le pont). On attend ~250 ms de réserve avant de lancer la lecture, puis un fil
 * pousse les morceaux dans l'AudioTrack au fur et à mesure : un Wi-Fi qui hoquette ne coupe pas la voix.
 */
class HautParleur(private val surEtat: (parle: Boolean) -> Unit, private val surNiveau: (Float) -> Unit) {

    private class Flux(val id: Int, val debit: Int) {
        val file = LinkedBlockingQueue<ByteArray>()
        @Volatile var fini = false
        @Volatile var abandonne = false
    }

    @Volatile private var courant: Flux? = null
    private var fil: Thread? = null

    @Synchronized
    fun commencer(id: Int, debit: Int) {
        arreter()
        val flux = Flux(id, debit)
        courant = flux
        fil = Thread({ lire(flux) }, "haut-parleur").apply { start() }
    }

    fun ecrire(id: Int, pcm: ByteArray) {
        val flux = courant ?: return
        if (flux.id == id && !flux.fini) flux.file.offer(pcm)
    }

    fun terminer(id: Int) {
        val flux = courant ?: return
        if (flux.id == id) flux.fini = true
    }

    /** Coupe net (`audio.abort`, annulation, nouvelle prise de parole). */
    @Synchronized
    fun arreter() {
        courant?.let { it.abandonne = true; it.fini = true }
        courant = null
        fil?.interrupt()
        fil = null
    }

    val parle: Boolean get() = courant != null

    private fun lire(flux: Flux) {
        val minimum = AudioTrack.getMinBufferSize(flux.debit, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val piste = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(flux.debit)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum * 4, flux.debit))   // ~0,5 s de tampon
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "haut-parleur indisponible", e)
            if (courant === flux) courant = null
            return
        }
        surEtat(true)
        var ecrits = 0L
        try {
            // Réserve de départ : ~250 ms, ou tout ce qu'il y a si le flux est déjà fini.
            val reserve = flux.debit / 2   // octets pour 250 ms (2 octets par échantillon)
            val attente = ArrayList<ByteArray>()
            var taille = 0
            val limite = System.currentTimeMillis() + 1500
            while (taille < reserve && !flux.fini && !flux.abandonne && System.currentTimeMillis() < limite) {
                val morceau = flux.file.poll(50, TimeUnit.MILLISECONDS) ?: continue
                attente.add(morceau); taille += morceau.size
            }
            piste.play()
            for (m in attente) { ecrire(piste, m); ecrits += m.size }
            while (!flux.abandonne) {
                val morceau = flux.file.poll(100, TimeUnit.MILLISECONDS)
                if (morceau == null) {
                    if (flux.fini && flux.file.isEmpty()) break
                    continue
                }
                ecrire(piste, morceau)
                ecrits += morceau.size
            }
            if (!flux.abandonne) {
                // Laisser la fin du tampon sortir avant de dire « j'ai fini ».
                val total = (ecrits / 2).toInt()
                val butoir = System.currentTimeMillis() + 3000
                while (!flux.abandonne && piste.playbackHeadPosition < total && System.currentTimeMillis() < butoir) {
                    Thread.sleep(30)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "lecture interrompue", e)
        } finally {
            try { piste.pause(); piste.flush(); piste.stop() } catch (_: Exception) {}
            piste.release()
            surNiveau(0f)
            synchronized(this) { if (courant === flux) courant = null }
            surEtat(courant != null)
        }
    }

    private fun ecrire(piste: AudioTrack, pcm: ByteArray) {
        surNiveau(Micro.niveau(pcm, pcm.size))
        var fait = 0
        while (fait < pcm.size) {
            val n = piste.write(pcm, fait, pcm.size - fait)
            if (n <= 0) break
            fait += n
        }
    }

    companion object { private const val TAG = "HautParleur" }
}
