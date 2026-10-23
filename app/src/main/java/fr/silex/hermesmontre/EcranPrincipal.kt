package fr.silex.hermesmontre

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Remplit l'écran principal (res/layout/activity_main.xml) à partir de l'état du service.
 * Séparé de l'activité pour que les captures (src/test, Paparazzi) rendent exactement le même écran.
 */
class EcranPrincipal(private val racine: View) {
    val mascotte: MascotteView = racine.findViewById(R.id.mascotte)
    val cadranMascotte: MascotteView = racine.findViewById(R.id.cadranMascotte)
    private val bord: BordView = racine.findViewById(R.id.bord)
    private val principal: View = racine.findViewById(R.id.principal)
    private val zoneParole: LinearLayout = racine.findViewById(R.id.zoneParole)
    private val etat: TextView = racine.findViewById(R.id.etat)
    private val titre: TextView = racine.findViewById(R.id.titre)
    private val detail: TextView = racine.findViewById(R.id.detail)
    private val etapes: TextView = racine.findViewById(R.id.etapes)
    private val consigne: TextView = racine.findViewById(R.id.consigne)
    private val reponse: TextView = racine.findViewById(R.id.reponse)
    private val notice: TextView = racine.findViewById(R.id.notice)
    private val defile: ScrollView = racine.findViewById(R.id.defile)
    private val raccourcis: LinearLayout = racine.findViewById(R.id.raccourcis)
    private val bandeRaccourcis: View = racine.findViewById(R.id.bandeRaccourcis)
    private val cadran: View = racine.findViewById(R.id.cadran)
    private val cadranHeure: TextView = racine.findViewById(R.id.cadranHeure)
    private val cadranDate: TextView = racine.findViewById(R.id.cadranDate)
    private val cadranInfo: TextView = racine.findViewById(R.id.cadranInfo)
    private val lecture: View = racine.findViewById(R.id.lecture)
    private val lectureTitre: TextView = racine.findViewById(R.id.lectureTitre)
    val lectureDefile: ScrollView = racine.findViewById(R.id.lectureDefile)
    private val lectureContenu: LinearLayout = racine.findViewById(R.id.lectureContenu)
    private val calque: LinearLayout = racine.findViewById(R.id.calque)
    private val calqueTitre: TextView = racine.findViewById(R.id.calqueTitre)
    private val calqueTexte: TextView = racine.findViewById(R.id.calqueTexte)
    private val calqueBoutons: LinearLayout = racine.findViewById(R.id.calqueBoutons)
    private val oui: TextView = racine.findViewById(R.id.oui)
    private val non: TextView = racine.findViewById(R.id.non)
    private val d = racine.resources.displayMetrics.density
    private var derniereReponse = ""
    private var raccourcisAffiches = ""

    /** Ce que l'utilisateur touche, branché par l'activité. */
    var surRaccourci: (String) -> Unit = {}
    var surHistorique: () -> Unit = {}
    var surProjets: () -> Unit = {}
    var surProposition: (Propositions.Option) -> Unit = {}
    var surEcarterProposition: () -> Unit = {}

    /** Ce que l'activité décide (et pas le service) : réglages d'affichage et mode cadran. */
    data class Options(
        val maintenir: Boolean = true,
        val identifiant: String = "",
        val raccourcis: List<String> = emptyList(),
        /** La consigne « maintiens pour parler » n'est plus utile après quelques essais. */
        val novice: Boolean = true,
        val cadran: Boolean = false,
    )

    val enLecture: Boolean get() = lecture.visibility == View.VISIBLE

    fun afficher(v: HermesService.Vue, o: Options, maintenant: Long = System.currentTimeMillis()) {
        val enLigne = v.connexion == GadgetClient.Etat.PRET
        val maintenir = o.maintenir
        mascotte.enLigne = enLigne
        mascotte.phase = v.phase
        mascotte.niveau = v.niveau
        val travail = v.phase == HermesService.Phase.ENVOI || v.phase == HermesService.Phase.REFLEXION
        val auRepos = v.phase == HermesService.Phase.VEILLE
        val avecReponse = v.reponse.isNotEmpty() && !travail && v.phase != HermesService.Phase.ECOUTE

        // Les bords : batterie à gauche, quota à droite ; discrets pendant la conversation.
        bord.batterie = v.batterie
        bord.enCharge = v.enCharge
        bord.quota = if (enLigne) v.quota else -1
        bord.discret = !auRepos

        // En haut : le point de connexion (vert = connecté) et l'heure, rien d'autre (les coins).
        val heure = HEURE.format(java.util.Date(maintenant))
        etat.text = "● $heure"
        etat.setTextColor(when (v.connexion) {
            GadgetClient.Etat.PRET -> MascotteView.VERT
            GadgetClient.Etat.CONNEXION, GadgetClient.Etat.APPAIRAGE -> MascotteView.ORANGE
            else -> MascotteView.ROUGE
        })

        // Légende : titre en couleur, détail atténué, consigne en bas (les écrans du firmware).
        val memos = if (v.memos > 0) "✉ ${v.memos} mémo${if (v.memos > 1) "s" else ""} en attente" else ""
        val (t, couleur, dt, c) = when {
            v.memoEnCours -> Legende("Mémo hors ligne", MascotteView.ORANGE,
                "Il partira chez Hermes au retour du pont", if (maintenir) "Relâche pour garder" else "Touche pour garder")
            v.phase == HermesService.Phase.ECOUTE -> Legende("J'écoute", MascotteView.VERT,
                if (maintenir) "Relâche pour envoyer" else "Touche pour envoyer", "")
            travail -> Legende(if (v.etapes.debutTour > 0) "Je réfléchis · ${(maintenant - v.etapes.debutTour) / 1000} s" else "Je réfléchis",
                MascotteView.BLEU, v.entendu.ifEmpty { v.statut }, "Touche pour arrêter")
            v.phase == HermesService.Phase.REPONSE -> Legende("Je parle", MascotteView.ORANGE, v.statut, "Touche pour couper")
            v.connexion == GadgetClient.Etat.CONNEXION -> Legende("Connexion…", MascotteView.ORANGE, v.detailConnexion, memos)
            v.connexion == GadgetClient.Etat.HORS_LIGNE -> Legende("Hors ligne", MascotteView.ROUGE,
                "Parle quand même : ton mémo partira au retour", memos.ifEmpty { "⚙ › Trouver le pont" })
            v.connexion == GadgetClient.Etat.REFUSE -> Legende("Refusé", MascotteView.ROUGE, v.detailConnexion, "⚙ réglages")
            v.connexion == GadgetClient.Etat.APPAIRAGE -> Legende("À approuver", MascotteView.ORANGE, "", "")
            v.reponse.isNotEmpty() -> Legende(if (v.heureReponse.isNotEmpty()) "Hermes · ${v.heureReponse}" else "Hermes",
                MascotteView.TEXTE, v.entendu, memos.ifEmpty { "Touche-la : lecture en grand" })
            else -> Legende("Salut, je suis Hermes", MascotteView.TEXTE, "Demande-moi ce que tu veux",
                memos.ifEmpty { if (!o.novice) "" else if (maintenir) "Maintiens pour parler" else "Touche pour parler" })
        }
        titre.text = t
        titre.setTextColor(couleur)
        detail.text = if (dt.isNotEmpty() && auRepos && v.reponse.isNotEmpty()) "« $dt »" else dt
        detail.visibility = if (dt.isEmpty()) View.GONE else View.VISIBLE
        // Pendant le travail et sous une réponse, la question tient sur une ligne : la place va au contenu.
        detail.maxLines = if (travail || avecReponse) 1 else 2
        // Une proposition à toucher : sa question remplace la consigne, juste au-dessus des boutons.
        val proposition = if (auRepos && enLigne) v.proposition else null
        val cs = if (proposition != null) proposition.question.ifEmpty { "Réponds d'un toucher" } else c
        consigne.text = cs
        consigne.setTextColor(if (proposition != null) MascotteView.VERT else MascotteView.ATTENUE)
        consigne.visibility = if (cs.isEmpty()) View.GONE else View.VISIBLE

        // Les étapes : en direct pendant le travail, résumées en une ligne après la réponse.
        etapes.text = when {
            travail && !v.etapes.vide -> v.etapes.lignes(maintenant, tient = ::tientSurUneLigne).joinToString("\n")
            !travail && v.reponse.isNotEmpty() -> v.etapes.resume()
            else -> ""
        }
        etapes.visibility = if (etapes.text.isEmpty()) View.GONE else View.VISIBLE
        etapes.gravity = if (travail) Gravity.START else Gravity.CENTER_HORIZONTAL

        reponse.text = v.reponse
        defile.visibility = if (avecReponse) View.VISIBLE else View.GONE
        if (v.reponse != derniereReponse) {
            if (!v.reponse.startsWith(derniereReponse) || derniereReponse.isEmpty()) defile.scrollTo(0, 0)
            derniereReponse = v.reponse
        }

        // La mascotte prend la place que le moment lui laisse : grande au repos et à l'écoute,
        // petite quand il y a des étapes ou une réponse à lire.
        val hauteur = (if (racine.height > 0) racine.height else racine.resources.displayMetrics.heightPixels).toFloat()
        val part = when {
            avecReponse -> 0.12f
            travail -> 0.22f
            v.phase == HermesService.Phase.ECOUTE -> 0.40f
            else -> 0.32f
        }
        val lp = mascotte.layoutParams
        val voulue = (hauteur * part).toInt()
        if (lp.height != voulue) { lp.height = voulue; mascotte.layoutParams = lp }
        val lz = zoneParole.layoutParams as LinearLayout.LayoutParams
        val poids = if (avecReponse) 0f else 1f
        if (lz.weight != poids) {
            lz.weight = poids
            lz.height = if (avecReponse) LinearLayout.LayoutParams.WRAP_CONTENT else 0
            zoneParole.layoutParams = lz
        }

        afficherRaccourcis(if (auRepos && enLigne && !avecReponse && proposition == null) o.raccourcis else emptyList(),
            historique = auRepos && !v.memoEnCours, projets = auRepos && enLigne, proposition = proposition)

        notice.text = v.notice
        notice.visibility = if (v.notice.isEmpty()) View.GONE else View.VISIBLE

        afficherCadran(v, o.cadran && auRepos && v.question == null && v.maj.isEmpty(), maintenant)
        afficherCalque(v, o.identifiant)
    }

    // ------------------------------------------------------------------ raccourcis

    private fun afficherRaccourcis(liste: List<String>, historique: Boolean, projets: Boolean,
                                   proposition: Propositions.Proposition?) {
        val cle = liste.joinToString("|") + "#" + historique + projets + "#" +
            (proposition?.options?.joinToString("|") { it.reponse } ?: "")
        if (cle == raccourcisAffiches) return
        raccourcisAffiches = cle
        raccourcis.removeAllViews()
        // Les choix d'Hermes d'abord (bordure verte), puis ✕ pour les écarter, puis les raccourcis.
        if (proposition != null) {
            for (o in proposition.options) raccourcis.addView(puce(o.puce, choix = true) { surProposition(o) })
            raccourcis.addView(puce("✕") { surEcarterProposition() })
        }
        if (historique) raccourcis.addView(puce("🕘") { surHistorique() })
        if (projets) raccourcis.addView(puce("📁") { surProjets() })
        for (r in liste) raccourcis.addView(puce(r) { surRaccourci(r) })
        (bandeRaccourcis as? android.widget.HorizontalScrollView)?.scrollTo(0, 0)
        bandeRaccourcis.visibility = if (raccourcis.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun puce(texte: String, choix: Boolean = false, action: () -> Unit) = TextView(racine.context).apply {
        text = texte
        textSize = if (choix) 13f else 12f
        setTextColor(if (choix) MascotteView.VERT else MascotteView.TEXTE)
        maxLines = 1
        gravity = Gravity.CENTER
        setBackgroundResource(if (choix) R.drawable.puce_choix else R.drawable.puce)
        setPadding((10 * d).toInt(), (7 * d).toInt(), (10 * d).toInt(), (7 * d).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = (3 * d).toInt(); marginEnd = (3 * d).toInt() }
        setOnClickListener { action() }
    }

    // ------------------------------------------------------------------ cadran

    private fun afficherCadran(v: HermesService.Vue, actif: Boolean, maintenant: Long) {
        cadran.visibility = if (actif) View.VISIBLE else View.GONE
        principal.visibility = if (actif) View.INVISIBLE else View.VISIBLE
        if (!actif) return
        cadranMascotte.enLigne = false              // grise : au repos, elle se fait discrète
        cadranMascotte.sansEffets = true
        cadranMascotte.phase = HermesService.Phase.VEILLE
        val date = java.util.Date(maintenant)
        cadranHeure.text = HEURE.format(date)
        cadranDate.text = DATE.format(date).replaceFirstChar { it.uppercase() }
        cadranInfo.text = buildString {
            append(if (v.connexion == GadgetClient.Etat.PRET) "● Hermes prêt" else "○ Hermes hors ligne")
            if (v.batterie >= 0) append("  ·  ${if (v.enCharge) "⚡" else "▮"} ${v.batterie} %")
            if (v.memos > 0) append("\n✉ ${v.memos} mémo${if (v.memos > 1) "s" else ""} en attente")
        }
        cadranInfo.setTextColor(if (v.connexion == GadgetClient.Etat.PRET) 0xFF3E7A5E.toInt() else 0xFF5C6874.toInt())
    }

    // ------------------------------------------------------------------ lecture et historique

    /** La réponse en grand : grosse police, défilement à la couronne. */
    fun montrerLecture(titreLecture: String, texte: String, proposition: Propositions.Proposition? = null) {
        lectureContenu.removeAllViews()
        lectureTitre.text = titreLecture
        lectureContenu.addView(TextView(racine.context).apply {
            text = texte
            textSize = 20f
            setTextColor(MascotteView.TEXTE)
            setLineSpacing(4 * d, 1f)
        })
        // Les choix d'Hermes à la fin de la lecture : on lit, on répond, sans revenir en arrière.
        if (proposition != null) {
            for (o in proposition.options) lectureContenu.addView(bouton(o.libelle, MascotteView.VERT) {
                fermerLecture(); surProposition(o)
            })
        }
        lectureDefile.scrollTo(0, 0)
        lecture.visibility = View.VISIBLE
    }

    /** Les derniers échanges, du plus récent au plus ancien ; toucher l'un l'ouvre en grand. */
    fun montrerHistorique(echanges: List<HermesService.Echange>, ouvrir: (HermesService.Echange) -> Unit) {
        lectureContenu.removeAllViews()
        lectureTitre.text = "Historique · ${echanges.size}"
        if (echanges.isEmpty()) {
            lectureContenu.addView(TextView(racine.context).apply {
                text = "Rien encore.\nLes échanges restent en mémoire jusqu'à l'arrêt de l'appli, jamais sur la montre."
                textSize = 14f; setTextColor(MascotteView.ATTENUE); gravity = Gravity.CENTER
            })
        }
        for (e in echanges) {
            lectureContenu.addView(LinearLayout(racine.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
                addView(TextView(context).apply {
                    text = "${e.heure}  « ${e.question.ifEmpty { "…" }} »"
                    textSize = 12f; setTextColor(MascotteView.ATTENUE); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END; typeface = android.graphics.Typeface.MONOSPACE
                })
                addView(TextView(context).apply {
                    text = e.reponse
                    textSize = 15f; setTextColor(MascotteView.TEXTE); maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                setOnClickListener { ouvrir(e) }
            })
        }
        lectureDefile.scrollTo(0, 0)
        lecture.visibility = View.VISIBLE
    }

    fun fermerLecture() { lecture.visibility = View.GONE }

    /** Les projets d'Hermes : icône, nom, ce qui attend ; le projet actif marqué ●. */
    fun montrerProjets(projets: List<HermesService.ProjetResume>, ouvrir: (HermesService.ProjetResume) -> Unit) {
        lectureContenu.removeAllViews()
        lectureTitre.text = "Projets · ${projets.size}"
        if (projets.isEmpty()) {
            lectureContenu.addView(TextView(racine.context).apply {
                text = "Aucun projet trouvé.\nCréer un projet sur le PC : hermes projects create"
                textSize = 14f; setTextColor(MascotteView.ATTENUE); gravity = Gravity.CENTER
            })
        }
        for (p in projets) {
            lectureContenu.addView(LinearLayout(racine.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (9 * d).toInt(), 0, (9 * d).toInt())
                addView(TextView(context).apply {
                    text = "${p.icone.ifEmpty { "📁" }} ${p.nom}${if (p.actif) "  ●" else ""}"
                    textSize = 17f; setTextColor(if (p.actif) MascotteView.VERT else MascotteView.TEXTE)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = p.resume
                    textSize = 12f; setTextColor(MascotteView.ATTENUE); typeface = android.graphics.Typeface.MONOSPACE
                })
                setOnClickListener { ouvrir(p) }
            })
        }
        lectureDefile.scrollTo(0, 0)
        lecture.visibility = View.VISIBLE
    }

    /** Un projet : description, tâches ouvertes (bloquées d'abord), et des actions d'un toucher. */
    fun montrerProjet(p: HermesService.ProjetDetail, actions: List<Pair<String, () -> Unit>>) {
        lectureContenu.removeAllViews()
        lectureTitre.text = "${p.icone.ifEmpty { "📁" }} ${p.nom}${if (p.actif) "  ● actif" else ""}"
        if (p.description.isNotEmpty()) lectureContenu.addView(TextView(racine.context).apply {
            text = p.description; textSize = 15f; setTextColor(MascotteView.TEXTE)
        })
        lectureContenu.addView(TextView(racine.context).apply {
            text = p.resume + if (p.finies > 0) " · ${p.finies} finie${if (p.finies > 1) "s" else ""}" else ""
            textSize = 12f; setTextColor(MascotteView.ATTENUE); typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, (6 * d).toInt(), 0, (4 * d).toInt())
        })
        for ((libelle, action) in actions) lectureContenu.addView(bouton(libelle, MascotteView.BLEU, action))
        for (t in p.taches) lectureContenu.addView(TextView(racine.context).apply {
            text = "${t.emoji}  ${t.titre}"
            textSize = 14f
            setTextColor(when (t.statut) {
                "blocked" -> MascotteView.ROUGE; "review" -> MascotteView.ORANGE; "running" -> MascotteView.BLEU
                else -> MascotteView.TEXTE
            })
            setPadding(0, (5 * d).toInt(), 0, (5 * d).toInt())
        })
        if (p.chemin.isNotEmpty()) lectureContenu.addView(TextView(racine.context).apply {
            text = p.chemin; textSize = 11f; setTextColor(MascotteView.DISCRET); typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, (8 * d).toInt(), 0, 0)
        })
        lectureDefile.scrollTo(0, 0)
        lecture.visibility = View.VISIBLE
    }

    private fun bouton(texte: String, couleur: Int, action: () -> Unit) = TextView(racine.context).apply {
        text = texte
        textSize = 15f
        setTextColor(couleur)
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.puce_choix)
        setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (6 * d).toInt() }
        setOnClickListener { action() }
    }

    // ------------------------------------------------------------------ outils

    /** Une ligne d'étape tient-elle dans la largeur de l'écran ? (mesurée en pixels, emojis compris) */
    private fun tientSurUneLigne(ligne: String): Boolean {
        // Avant la première mise en page, la largeur vaut 0 : on prend l'écran moins ses marges (18 dp de chaque côté).
        val m = etapes.resources.displayMetrics
        val largeur = (if (etapes.width > 0) etapes.width else (m.widthPixels - 36 * m.density).toInt()) -
            etapes.paddingLeft - etapes.paddingRight
        return etapes.paint.measureText(ligne) <= largeur * 0.97f
    }

    companion object {
        private val HEURE = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)
        private val DATE = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.FRANCE)
    }

    private data class Legende(val titre: String, val couleur: Int, val detail: String, val consigne: String)

    private fun afficherCalque(v: HermesService.Vue, identifiant: String) {
        val q = v.question
        val c = v.carte
        when {
            q != null -> {
                calqueTitre.text = q.titre.ifEmpty { "Hermes demande" }
                calqueTitre.setTextColor(MascotteView.ORANGE)
                calqueTexte.text = q.texte
                oui.text = "Oui"; non.text = "Non"
                calqueBoutons.visibility = View.VISIBLE
                calque.visibility = View.VISIBLE
            }
            // Une mise à jour prête, proposée seulement au repos (jamais au milieu d'une réponse).
            v.maj.isNotEmpty() && v.phase == HermesService.Phase.VEILLE -> {
                calqueTitre.text = "Mise à jour ${v.maj}"
                calqueTitre.setTextColor(MascotteView.VERT)
                calqueTexte.text = v.notesMaj.ifEmpty { "Une nouvelle version d'Hermes est prête." } +
                    "\n\nAndroid va demander de confirmer."
                oui.text = "Installer"; non.text = "Plus tard"
                calqueBoutons.visibility = View.VISIBLE
                calque.visibility = View.VISIBLE
            }
            v.connexion == GadgetClient.Etat.APPAIRAGE -> {
                calqueTitre.text = "Montre à approuver"
                calqueTitre.setTextColor(MascotteView.ORANGE)
                calqueTexte.text = buildString {
                    if (v.codeAppairage.isNotEmpty()) {
                        append("Code : ${v.codeAppairage}\n\nSur le PC :\n")
                        append(v.commandeAppairage.ifEmpty { "hermes pairing approve gadget ${v.codeAppairage}" })
                        append("\n\n— ou, avec le pont —\n")
                    } else {
                        append("Sur le PC :\n")
                    }
                    append("bash ~/hermes-voice/outils/approuver.sh $identifiant")
                    append("\n\nIdentifiant : $identifiant")
                    append("\n\nToucher : réessayer · Maintenir : réglages")
                }
                calqueBoutons.visibility = View.GONE
                calque.visibility = View.VISIBLE
            }
            c != null -> {
                calqueTitre.text = c.titre
                calqueTitre.setTextColor(MascotteView.BLEU)
                calqueTexte.text = c.corps
                calqueBoutons.visibility = View.GONE
                calque.visibility = View.VISIBLE
            }
            else -> calque.visibility = View.GONE
        }
    }
}
