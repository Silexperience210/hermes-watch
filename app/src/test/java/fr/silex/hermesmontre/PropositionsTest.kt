package fr.silex.hermesmontre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PropositionsTest {
    @Test fun listeNumerotee() {
        val p = Propositions.extraire("""
            Je vois trois pistes :
            1) Relancer la sauvegarde maintenant
            2. Vérifier le SSD externe
            3 - **Attendre** la prochaine fenêtre de nuit
            Laquelle tu préfères ?
        """.trimIndent())!!
        assertEquals("Laquelle tu préfères ?", p.question)
        assertEquals(listOf("1 · Relancer la sauvegarde maintenant", "2 · Vérifier le SSD externe",
            "3 · Attendre la prochaine fenêtre de nuit"), p.options.map { it.libelle })
        assertEquals("1 · Relancer l…", p.options[0].puce)
        assertEquals("Option 3 : Attendre la prochaine fenêtre de nuit", p.options[2].reponse)
    }

    @Test fun pucesAvecQuestion() {
        val p = Propositions.extraire("Je peux :\n- résumer tes mails\n- lire ton agenda\nTu veux quoi ?")!!
        assertEquals(listOf("résumer tes mails", "lire ton agenda"), p.options.map { it.reponse })
    }

    @Test fun questionFermee() {
        val p = Propositions.extraire("La sauvegarde a 40 heures de retard. Je la relance ?")!!
        assertEquals("Je la relance ?", p.question)
        assertEquals(listOf("Oui", "Non", "Plus tard"), p.options.map { it.libelle })
        assertEquals("oui-non", p.source)
    }

    @Test fun riendAProposer() {
        assertNull(Propositions.extraire("Demain à Lyon : 14 °C, nuageux le matin."))
        assertNull(Propositions.extraire("Quelle heure est-il à Tokyo ? Il est 5 h."))        // question, mais pas à la fin
        assertNull(Propositions.extraire("Pourquoi le ciel est bleu ?"))                      // ouverte : pas de oui/non
        assertNull(Propositions.extraire("Une seule étape :\n1) tout relancer"))              // une liste d'un élément
    }
}
