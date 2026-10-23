package fr.silex.hermesmontre

import android.view.LayoutInflater
import android.view.View
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Captures de l'écran principal, rendues par le vrai code (layout + EcranPrincipal + MascotteView)
 * sur un écran de montre 410 × 502. `./gradlew recordPaparazziDebug` les écrit dans
 * app/src/test/snapshots/images/.
 */
class CapturesTest {
    companion object {
        /** 21:47:30 heure locale, pour des captures identiques à chaque fois. */
        val MAINTENANT: Long = java.util.Calendar.getInstance().apply { set(2026, 9, 23, 21, 47, 30) }.timeInMillis
    }

    @get:Rule
    val paparazzi = Paparazzi(
        // 410 × 502 à 273 dpi = 240 dp de large : exactement ce qu'Echelle impose à la montre.
        deviceConfig = DeviceConfig(screenWidth = 410, screenHeight = 502, xdpi = 273, ydpi = 273,
            density = com.android.resources.Density.create(410 * 160 / Echelle.LARGEUR_DP)),
        theme = "android:Theme.DeviceDefault.NoActionBar",
        showSystemUi = false,
    )

    private var dernier: EcranPrincipal? = null

    private fun ecran(image: Int = 10, cadran: Boolean = false, novice: Boolean = false,
                      apres: (EcranPrincipal, HermesService.Vue) -> Unit = { _, _ -> },
                      preparer: HermesService.Vue.() -> Unit): View {
        val racine = LayoutInflater.from(paparazzi.context).inflate(R.layout.activity_main, null)
        val e = EcranPrincipal(racine)
        e.mascotte.imageFixe = image
        e.cadranMascotte.imageFixe = image
        val v = HermesService.Vue(connexion = GadgetClient.Etat.PRET, batterie = 72, quota = 41).apply(preparer)
        e.afficher(v, EcranPrincipal.Options(identifiant = "hg-630dcd2966c43366",
            raccourcis = listOf("Météo", "Mon agenda", "Quoi de neuf ?"), novice = novice, cadran = cadran),
            maintenant = MAINTENANT)
        apres(e, v)
        dernier = e
        return racine
    }

    private val reponseMeteo = "Demain à Lyon : 14 °C, nuageux le matin, éclaircies l'après-midi. Prends une veste, " +
        "le vent du nord souffle jusqu'à 30 km/h en soirée. Dimanche s'annonce plus doux, autour de 17 °C."

    @Test fun veille() = paparazzi.snapshot(ecran(novice = true) {})

    @Test fun proposition() = paparazzi.snapshot(ecran {
        entendu = "La sauvegarde ?"; heureReponse = "21:47"
        reponse = "Je vois trois pistes :\n1) Relancer la sauvegarde maintenant\n2) Vérifier le SSD externe\n" +
            "3) Attendre la nuit\nLaquelle tu préfères ?"
        proposition = Propositions.extraire(reponse)
    })

    @Test fun projets() = paparazzi.snapshot(ecran(apres = { e, _ ->
        e.montrerProjets(listOf(
            HermesService.ProjetResume("p1", "Hermes Montre", "⌚", true, "1 bloquée · 2 en cours · 4 à faire"),
            HermesService.ProjetResume("p2", "Site silexperience", "🌐", false, "1 à relire · 3 à faire"),
            HermesService.ProjetResume("p3", "Harness C3", "", false, "rien en attente"),
        )) {}
    }) {})

    @Test fun projet() = paparazzi.snapshot(ecran(apres = { e, _ ->
        e.montrerProjet(HermesService.ProjetDetail("p1", "Hermes Montre", "⌚",
            "Appli Android pour parler à Hermes depuis la montre.", "~/hermes-voice/android", false,
            "1 bloquée · 2 en cours · 4 à faire", 12, listOf(
                HermesService.Tache("Créer le dépôt dédié", "blocked", "⛔"),
                HermesService.Tache("Tester la couronne", "running", "▶"),
                HermesService.Tache("Mode cadran : mesurer la batterie", "running", "▶"),
                HermesService.Tache("Écran d'accueil du réglage", "todo", "○"),
            )), listOf("📋 Fais-moi le point" to {}, "➡ Prochaine étape ?" to {}, "⛔ Débloquer" to {}, "▶ Travailler dessus" to {}))
    }) {})

    @Test fun cadran() = paparazzi.snapshot(ecran(cadran = true) { memos = 1 })

    @Test fun lecture() = paparazzi.snapshot(ecran(apres = { e, _ ->
        e.montrerLecture("21:47  « Quel temps fera-t-il demain ? »", reponseMeteo)
    }) {})

    @Test fun historique() = paparazzi.snapshot(ecran(apres = { e, _ ->
        e.montrerHistorique(listOf(
            HermesService.Echange("Quel temps fera-t-il demain ?", reponseMeteo, "21:47", "✓ 2 outils · 12 s"),
            HermesService.Echange("Rappelle-moi d'appeler Paul à 18 h", "C'est noté, je te le rappelle à 18 h.", "19:02", ""),
            HermesService.Echange("Combien de batterie sur la montre ?", "Batterie à 72 pour cent.", "18:40", ""),
        )) {}
    }) {})

    @Test fun memoHorsLigne() = paparazzi.snapshot(ecran(image = 11) {
        connexion = GadgetClient.Etat.HORS_LIGNE; phase = HermesService.Phase.ECOUTE; memoEnCours = true; niveau = 0.6f
    })

    @Test fun apresReponse() = paparazzi.snapshot(ecran {
        entendu = "Quel temps fera-t-il demain ?"; heureReponse = "21:47"
        reponse = reponseMeteo
        etapes.nouveauTour(MAINTENANT - 14_000)
        etapes.etape("tool", "c1", "web_search", "🔍", true, MAINTENANT - 10_000)
        etapes.etape("tool", "c2", "web_extract", "📄", true, MAINTENANT - 6_000)
        etapes.terminer(MAINTENANT - 2_000)
    })

    @Test fun ecoute() = paparazzi.snapshot(ecran(image = 11) {
        phase = HermesService.Phase.ECOUTE; niveau = 0.8f
    })

    @Test fun reflexion() = paparazzi.snapshot(ecran(image = 2) {
        phase = HermesService.Phase.REFLEXION; entendu = "Quel temps fera-t-il demain ?"
        statut = "Je cherche sur le web"
        val t = MAINTENANT - 9_000
        etapes.nouveauTour(t)
        etapes.transcription(t + 200)
        etapes.transcrit(t + 1_100)
        etapes.etape("thinking", "", "Je réfléchis", "", true, t + 1_300)
        etapes.etape("tool", "c1", "web_search: météo Lyon demain", "🔍", true, t + 3_400)
        etapes.etape("tool", "c1", "", "", false, t + 5_500)
        etapes.etape("tool", "c2", "web_extract: meteofrance.com", "📄", true, t + 5_600)
    })

    @Test fun reponse() = paparazzi.snapshot(ecran(image = 2) {
        phase = HermesService.Phase.REPONSE; entendu = "Quel temps fera-t-il demain ?"
        heureReponse = "21:47"
        reponse = "Demain à Lyon : 14 °C, nuageux le matin, éclaircies l'après-midi. Prends une veste."
    })

    @Test fun question() = paparazzi.snapshot(ecran {
        question = HermesService.Question("q1", "Confirmer", "Relancer la sauvegarde Hermes maintenant ?", 0)
    })

    @Test fun horsLigne() = paparazzi.snapshot(ecran {
        connexion = GadgetClient.Etat.HORS_LIGNE; detailConnexion = "Failed to connect to /192.168.1.10:8765"; memos = 2
    })
}
