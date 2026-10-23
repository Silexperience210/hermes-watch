package fr.silex.hermesmontre

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/**
 * Le VRAI client de la montre contre un VRAI hub Hermes Gadget (celui que servent la passerelle
 * et hote/pont_gadget.py). Lancé seulement quand HG_URL est défini :
 *
 *   hermes-gadget devserver --port 8799 --state-dir /tmp/hg &
 *   HG_URL=ws://127.0.0.1:8799/gadget ./gradlew testDebugUnitTest
 *
 * Le serveur de développement renvoie le texte tapé et rejoue la voix enregistrée : on vérifie
 * la poignée de main (clé au premier contact, HMAC ensuite), un tour écrit et un tour parlé.
 */
class ServeurReelTest {
    private val url = System.getenv("HG_URL") ?: ""

    private class FauxCompte(override val adresse: String) : GadgetClient.Compte {
        override val cle = Identite.nouvelleCle()
        override val identifiant = Identite.identifiant(cle)
    }

    private class Ecoute : GadgetClient.Auditeur {
        val etats = LinkedBlockingQueue<GadgetClient.Etat>()
        val messages = LinkedBlockingQueue<JSONObject>()
        val audio = LinkedBlockingQueue<ByteArray>()
        override fun surEtat(etat: GadgetClient.Etat, detail: String) { etats.add(etat) }
        override fun surMessage(m: JSONObject) { messages.add(m) }
        override fun surAudio(flux: Int, pcm: ByteArray) { audio.add(pcm) }

        fun attendreEtat(voulu: GadgetClient.Etat) {
            while (true) {
                val e = etats.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("pas d'état $voulu")
                if (e == voulu) return
                if (e == GadgetClient.Etat.REFUSE) throw AssertionError("refusé")
            }
        }

        fun attendre(type: String, secondes: Long = 10): JSONObject {
            val fin = System.currentTimeMillis() + secondes * 1000
            while (System.currentTimeMillis() < fin) {
                val m = messages.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (m.optString("type") == type) return m
            }
            throw AssertionError("pas de message $type")
        }
    }

    private fun hello(c: GadgetClient.Compte) = Protocole.hello(
        identifiant = c.identifiant, nom = "Montre de test", modele = "test", version = "0.1.0", jeton = "",
        largeur = 410, hauteur = 502, colonnes = 24, lignes = 10, modeParole = "hold", haut_parleur = true,
        profil = "", actions = emptyList(), capteurs = JSONObject().put("battery_pct", 80), ecranEteint = false,
    )

    @Test fun poigneeDeMainTexteEtVoix() {
        assumeTrue("HG_URL non défini : test d'intégration sauté", url.isNotEmpty())
        val compte = FauxCompte(url)

        // 1. Premier contact : la clé est enrôlée.
        val e1 = Ecoute()
        val c1 = GadgetClient(compte, { hello(compte) }, e1)
        c1.demarrer()
        e1.attendreEtat(GadgetClient.Etat.PRET)
        assertTrue(e1.attendre("welcome").optBoolean("paired"))

        // 2. Un tour écrit : le serveur de développement renvoie le texte.
        assertTrue(c1.envoyer(Protocole.message("text", "id" to "t1", "text" to "bonjour Hermes")))
        val reponse = e1.attendre("reply")
        assertTrue(reponse.optString("text"), reponse.optString("text").contains("bonjour Hermes"))
        assertEquals("success", e1.attendre("turn.end").optString("outcome"))

        // 3. Un tour parlé : 1,2 s de PCM 16 kHz en trames de 40 ms, puis la voix revient.
        assertTrue(c1.envoyer(Protocole.message("audio.start", "id" to "a1", "stream" to 3, "rate" to 16000,
            "format" to "pcm16", "mode" to "hold")))
        val trame = ByteArray(Micro.OCTETS_TRAME)
        var seq = 0
        repeat(30) { k ->
            for (i in 0 until trame.size / 2) {
                val s = (8000 * sin(2 * Math.PI * 440 * (k * trame.size / 2 + i) / 16000.0)).toInt()
                trame[2 * i] = (s and 0xff).toByte(); trame[2 * i + 1] = ((s shr 8) and 0xff).toByte()
            }
            assertTrue(c1.envoyerAudio(3, seq++, trame))
        }
        assertTrue(c1.envoyer(Protocole.message("audio.end", "id" to "a1", "stream" to 3, "duration_ms" to 1200)))
        val debut = e1.attendre("audio.start", 15)
        assertEquals(16000, debut.optInt("rate"))
        e1.attendre("audio.end", 15)
        var octets = 0
        while (true) { val a = e1.audio.poll(200, TimeUnit.MILLISECONDS) ?: break; octets += a.size }
        assertTrue("audio reçu : $octets octets", octets >= 16000 * 2)   // au moins ~1 s de voix rejouée
        c1.arreter()

        // 4. Reconnexion : cette fois la montre prouve sa clé par HMAC (enrolled = true).
        val e2 = Ecoute()
        val c2 = GadgetClient(compte, { hello(compte) }, e2)
        c2.demarrer()
        e2.attendreEtat(GadgetClient.Etat.PRET)
        c2.arreter()
    }
}
