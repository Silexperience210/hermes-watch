package fr.silex.hermesmontre

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * Le micro de la montre : PCM16 mono 16 kHz, par trames de 40 ms (1 280 octets), exactement ce que
 * le protocole Gadget attend sur le canal audio.
 *
 * Jamais d'écoute permanente : le micro n'est ouvert que le temps d'une prise de parole,
 * plafonnée à [PLAFOND_MS]. En mode « tap » (toucher ou secouer), la fin est détectée au silence.
 */
class Micro(
    private val surTrame: (ByteArray) -> Unit,
    private val surNiveau: (Float) -> Unit,
    /** Appelé depuis le fil du micro quand il s'arrête de lui-même (silence ou plafond). */
    private val surFinAuto: (raison: String) -> Unit,
) {
    @Volatile private var actif = false
    private var fil: Thread? = null

    val ouvert: Boolean get() = actif

    /** @param finAuSilence true en mode « tap » : on coupe après ~1,2 s de silence suivant la voix. */
    @SuppressLint("MissingPermission")   // vérifiée par l'appelant
    @Synchronized
    fun ouvrir(finAuSilence: Boolean): Boolean {
        if (actif) return true
        val minimum = AudioRecord.getMinBufferSize(DEBIT, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return false
        val enregistreur = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, DEBIT, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, OCTETS_TRAME * 4))
        } catch (e: Exception) {
            Log.w(TAG, "micro indisponible", e); return false
        }
        if (enregistreur.state != AudioRecord.STATE_INITIALIZED) {
            enregistreur.release(); return false
        }
        try {
            enregistreur.startRecording()
        } catch (e: Exception) {
            enregistreur.release(); return false
        }
        actif = true
        fil = Thread({ boucle(enregistreur, finAuSilence) }, "micro").apply { start() }
        return true
    }

    @Synchronized
    fun fermer() {
        actif = false
        // Attendre la dernière trame : `audio.end` ne doit partir qu'après elle.
        fil?.let { if (it !== Thread.currentThread()) it.join(500) }
        fil = null
    }

    private fun boucle(enregistreur: AudioRecord, finAuSilence: Boolean) {
        val debut = System.currentTimeMillis()
        var voixEntendue = false
        var dernierSon = debut
        var raison = ""
        try {
            while (actif) {
                val trame = ByteArray(OCTETS_TRAME)
                var lu = 0
                while (lu < trame.size && actif) {
                    val n = enregistreur.read(trame, lu, trame.size - lu)
                    if (n <= 0) break
                    lu += n
                }
                if (lu <= 0) break
                val niveau = niveau(trame, lu)
                surNiveau(niveau)
                surTrame(if (lu == trame.size) trame else trame.copyOf(lu))
                val maintenant = System.currentTimeMillis()
                if (niveau > SEUIL_VOIX) { voixEntendue = true; dernierSon = maintenant }
                if (maintenant - debut > PLAFOND_MS) { raison = "plafond"; break }
                if (finAuSilence) {
                    if (voixEntendue && maintenant - dernierSon > SILENCE_FIN_MS) { raison = "silence"; break }
                    if (!voixEntendue && maintenant - debut > ATTENTE_VOIX_MS) { raison = "rien"; break }
                }
            }
        } finally {
            try { enregistreur.stop() } catch (_: Exception) {}
            enregistreur.release()
            surNiveau(0f)
            val coupeSeul = actif
            actif = false
            if (coupeSeul && raison.isNotEmpty()) surFinAuto(raison)
        }
    }

    companion object {
        private const val TAG = "Micro"
        const val DEBIT = 16000
        const val OCTETS_TRAME = DEBIT * 2 * 40 / 1000   // 40 ms
        const val PLAFOND_MS = 30_000L
        const val SILENCE_FIN_MS = 1_200L
        const val ATTENTE_VOIX_MS = 6_000L
        const val SEUIL_VOIX = 0.06f

        /** Niveau 0..1 (RMS, avec une courbe douce pour l'affichage). */
        fun niveau(pcm: ByteArray, longueur: Int): Float {
            var somme = 0.0
            var n = 0
            var i = 0
            while (i + 1 < longueur) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
                somme += s * s; n++; i += 2
            }
            if (n == 0) return 0f
            val rms = sqrt(somme / n) / 32768.0
            return (sqrt(rms) * 1.6).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
