package fr.silex.hermesmontre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MiseAJourTest {
    @Test fun lireVersion() {
        val v = MiseAJour.lire("""{"versionCode":3,"versionName":"0.3.0","sha256":"ab","taille":12,"notes":"Voix"}""")!!
        assertEquals(3, v.code); assertEquals("0.3.0", v.nom); assertEquals("Voix", v.notes)
        assertNull(MiseAJour.lire("pas du json"))
        assertNull(MiseAJour.lire("""{"versionName":"x"}"""))
    }

    @Test fun empreinte() {
        val f = File.createTempFile("maj", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", MiseAJour.sha256(f))
    }

    @Test fun voixNettoyee() {
        assertEquals("Voir la doc ici", VoixMontre.nettoyer("**Voir** la doc https://x.y/z ici 🙂"))
    }
}
