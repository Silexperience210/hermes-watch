package fr.silex.hermesmontre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

class DecouverteTest {
    @Test fun lireLaReponse() {
        assertEquals("ws://192.168.1.10:8765/gadget",
            Decouverte.lire("""{"ws":"ws://192.168.1.10:8765/gadget","maj":8767,"nom":"pc"}""")!!.ws)
        assertNull(Decouverte.lire("""{"ws":"http://piege"}"""))
        assertNull(Decouverte.lire("n'importe quoi"))
    }

    /** Contre le vrai Phare du pont : HG_PHARE=1 avec le phare lancé sur 127.0.0.1:8768. */
    @Test fun trouverLeVraiPont() {
        assumeTrue("HG_PHARE non défini", System.getenv("HG_PHARE") != null)
        val pont = Decouverte.chercher(2000, listOf(InetAddress.getByName("127.0.0.1")))
        assertNotNull(pont)
        assertEquals("ws://127.0.0.1:8765/gadget", pont!!.ws)
    }
}
