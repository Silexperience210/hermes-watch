package fr.silex.hermesmontre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtapesTest {
    /** Une heure réelle quelconque (0 veut dire « pas de tour » pour Etapes). */
    private val T = 1_700_000_000_000L

    @Test fun unTourComplet() {
        val e = Etapes()
        e.nouveauTour(T + 0)
        e.transcription(T + 100)
        e.transcrit(T + 900)
        e.statut("Je réfléchis", T + 1000)                         // phrase du pont avant les étapes
        e.etape("thinking", "", "Je réfléchis", "", true, T + 1100)  // même libellé : pas de doublon
        e.etape("tool", "c1", "web_search: météo", "🔍", true, T + 2000)
        e.statut("Je cherche sur le web", T + 2000)                  // ignoré : les étapes structurées priment
        e.etape("tool", "c1", "", "", false, T + 4100)
        e.etape("writing", "", "J'écris la réponse", "", true, T + 4200)
        e.terminer(T + 5000)
        assertEquals(listOf("Je transcris ta voix", "Je réfléchis", "web_search: météo", "J'écris la réponse"),
            e.liste.map { it.libelle })
        assertTrue(e.liste.all { it.finie })
        assertEquals("✓ 🔍 web_search: météo\u00A0\u00A02,1\u00A0s", e.lignes(T + 6000)[1])
        assertEquals("✓ 1 outil · 5,0 s", e.resume())
    }

    @Test fun sansPontLesStatusSuffisent() {
        val e = Etapes()
        e.nouveauTour(T + 0)
        e.statut("Searching the web", T + 0)
        e.statut("Reading a page", T + 3000)
        e.statut("Lecture", T + 3500)                               // progression de lecture : pas une étape
        assertEquals(2, e.liste.size)
        assertTrue(e.liste[0].finie)
        assertTrue(e.lignes(T + 4000)[1].endsWith("Reading a page\u00A0\u00A01,0\u00A0s"))
    }

    @Test fun uneEtapeTientSurUneLigne() {
        val e = Etapes()
        e.nouveauTour(T)
        e.etape("tool", "c1", "web_extract: https://www.meteofrance.com/previsions-meteo-france/lyon", "📄", true, T)
        val ligne = e.lignes(T + 3_400, tient = { it.length <= 30 })[0]
        assertTrue(ligne, ligne.length <= 30)
        assertTrue(ligne, ligne.endsWith("…\u00A0\u00A03,4\u00A0s"))
    }

    @Test fun durees() {
        assertEquals("0,4 s", Etapes.duree(400))
        assertEquals("12 s", Etapes.duree(12_300))
        assertEquals("1 min 5 s", Etapes.duree(65_000))
    }
}
