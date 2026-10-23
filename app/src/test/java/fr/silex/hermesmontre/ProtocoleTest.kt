package fr.silex.hermesmontre

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mêmes vecteurs que le SDK Hermes Gadget (tests/test_protocol.py et firmware/tests/test_basics.cpp). */
class ProtocoleTest {
    private val cle = ByteArray(32) { it.toByte() }
    private val nonce = "bm9uY2Utbm9uY2Utbm9uY2U="

    @Test fun identifiantDeriveDeLaCle() {
        assertEquals("hg-630dcd2966c43366", Identite.identifiant(cle))
    }

    @Test fun macCommeLeServeur() {
        assertEquals("AMUEF53Phk8+1vHw7R8PgiDwlPd8rXUx+cgyYv1YJX0=",
            Identite.macAuth(cle, "hg-630dcd2966c43366", nonce))
    }

    @Test fun trameBinaireAllerRetour() {
        val t = Protocole.binaire(Protocole.CANAL_AUDIO, 7, 0x1234, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 7, 0x34, 0x12, 1, 2), t)
        val l = Protocole.lireBinaire(t)!!
        assertEquals(1, l.canal); assertEquals(7, l.flux); assertEquals(0x1234, l.seq)
        assertArrayEquals(byteArrayOf(1, 2), l.charge)
        assertNull(Protocole.lireBinaire(byteArrayOf(9, 0, 0, 0)))
        assertNull(Protocole.lireBinaire(byteArrayOf(1)))
    }

    @Test fun cleNeuveDonneUnIdentifiantValide() {
        val id = Identite.identifiant(Identite.nouvelleCle())
        assert(Regex("^hg-[0-9a-f]{16}$").matches(id))
    }

    @Test fun niveauDuSilenceEstNul() {
        assertEquals(0f, Micro.niveau(ByteArray(1280), 1280), 0f)
    }
}
