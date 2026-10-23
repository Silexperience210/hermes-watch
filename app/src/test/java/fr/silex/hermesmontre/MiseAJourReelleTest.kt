package fr.silex.hermesmontre

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * La vraie recherche de mise à jour contre le vrai serveur du pont (ServeurMajMontre).
 * Lancée seulement avec HG_MAJ_URL, par exemple http://127.0.0.1:8767/montre/
 */
class MiseAJourReelleTest {
    @Test fun trouverTelechargerVerifier() {
        val base = System.getenv("HG_MAJ_URL") ?: ""
        assumeTrue("HG_MAJ_URL non défini", base.isNotEmpty())
        // Pas d'appli installée dans un test JVM : version installée = 0, tout est « plus récent ».
        val dossier = kotlin.io.path.createTempDirectory("maj").toFile()
        val maj = MiseAJour(object : ContextWrapper(null) {
            override fun getCacheDir(): File = dossier
        })
        assertEquals("http://192.168.1.10:8767/montre/", maj.base("ws://192.168.1.10:8765/gadget"))
        val v = maj.chercher(base)
        assertNotNull("le pont devrait proposer une version", v)
        val apk = maj.telecharger(base, v!!)
        assertEquals(v.taille, apk.length())
        assertEquals(v.sha256, MiseAJour.sha256(apk))
        dossier.deleteRecursively()
    }
}
