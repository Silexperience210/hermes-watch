package fr.silex.hermesmontre

import java.io.File
import java.io.FileOutputStream

/**
 * Mémos vocaux enregistrés sans pont, gardés dans le stockage PRIVÉ de l'appli (PCM 16 kHz brut)
 * jusqu'à leur envoi, puis effacés. Au plus [MAXIMUM] en attente, 30 s chacun (≈ 1 Mo).
 */
class Memos(private val dossier: File) {
    private var courant: FileOutputStream? = null
    private var fichierCourant: File? = null

    init { dossier.mkdirs() }

    fun enAttente(): Int = dossier.listFiles { f -> f.name.endsWith(".pcm") }?.size ?: 0

    fun commencer() {
        abandonner()
        val f = File(dossier, "${System.currentTimeMillis()}.part")
        fichierCourant = f
        courant = FileOutputStream(f)
    }

    /** Appelé depuis le fil du micro. */
    @Synchronized fun ecrire(pcm: ByteArray) { try { courant?.write(pcm) } catch (_: Exception) {} }

    @Synchronized fun garder() {
        courant?.close(); courant = null
        fichierCourant?.let { it.renameTo(File(it.path.removeSuffix(".part") + ".pcm")) }
        fichierCourant = null
    }

    @Synchronized fun abandonner() {
        try { courant?.close() } catch (_: Exception) {}
        courant = null
        fichierCourant?.delete(); fichierCourant = null
    }

    /** Le plus ancien mémo et son audio, ou null. */
    fun prochain(): Pair<File, ByteArray>? {
        val f = dossier.listFiles { x -> x.name.endsWith(".pcm") }?.minByOrNull { it.name } ?: return null
        return f to f.readBytes()
    }

    companion object { const val MAXIMUM = 5 }
}
