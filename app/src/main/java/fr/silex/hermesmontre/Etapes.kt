package fr.silex.hermesmontre

import java.util.Locale

/**
 * Ce que fait Hermes pendant un tour, étape par étape : transcription, réflexion, chaque outil
 * (lancé puis fini), rédaction. Rempli par les messages `step` du pont (événements en direct
 * d'Hermes) ou, à défaut, par les phrases `status` du serveur. Pur Kotlin : testé sur JVM.
 */
class Etapes {

    class Etape(val id: String, val genre: String, var libelle: String, val emoji: String,
                val debut: Long, var fin: Long = 0L) {
        val finie: Boolean get() = fin > 0L
    }

    val liste = ArrayList<Etape>()
    var debutTour = 0L; private set
    var finTour = 0L; private set
    /** Vrai dès qu'un `step` est arrivé : les `status` ne créent plus d'étapes (doublons sinon). */
    private var structurees = false

    val vide: Boolean get() = liste.isEmpty()

    fun nouveauTour(maintenant: Long) {
        liste.clear(); structurees = false; debutTour = maintenant; finTour = 0L
    }

    /** La voix est partie : le serveur la transcrit. */
    fun transcription(maintenant: Long) = ajouter(Etape("stt", "stt", "Je transcris ta voix", "", maintenant), maintenant)

    fun transcrit(maintenant: Long) { liste.find { it.id == "stt" && !it.finie }?.fin = maintenant }

    /** Une phrase `status` (« Searching the web », « Je réfléchis ») : une étape si rien de mieux n'arrive. */
    fun statut(texte: String, maintenant: Long) {
        val t = texte.trim()
        if (structurees || t.isEmpty() || t.startsWith("Lecture") || t.startsWith("Quota")) return
        if (liste.lastOrNull()?.libelle.equals(t, ignoreCase = true)) return
        ajouter(Etape("s${liste.size}", "status", t, "", maintenant), maintenant)
    }

    /** Un message `step` du pont : kind = thinking | tool | writing, state = running | done. */
    fun etape(kind: String, id: String, libelle: String, emoji: String, enCours: Boolean, maintenant: Long) {
        structurees = true
        if (kind == "tool") {
            val existante = liste.find { it.id == "outil:$id" }
            if (!enCours) { existante?.let { if (!it.finie) it.fin = maintenant }; return }
            if (existante != null) return
            ajouter(Etape("outil:$id", "tool", libelle, emoji, maintenant), maintenant)
            return
        }
        val nom = libelle.ifEmpty { if (kind == "writing") "J'écris la réponse" else "Je réfléchis" }
        val derniere = liste.lastOrNull()
        // Déjà affichée (même phase, ou la même phrase venue d'un `status` juste avant) : on la garde.
        if (derniere != null && !derniere.finie && (derniere.genre == kind || derniere.libelle.equals(nom, ignoreCase = true))) return
        ajouter(Etape("$kind${liste.size}", kind, nom, "", maintenant), maintenant)
    }

    /** Fin du tour : tout ce qui tournait est fini. */
    fun terminer(maintenant: Long) {
        for (e in liste) if (!e.finie) e.fin = maintenant
        if (finTour == 0L && debutTour > 0L) finTour = maintenant
    }

    /** Une nouvelle étape clôt les étapes « de phase » en cours (réflexion, transcription…) ;
     *  un outil, lui, reste ouvert jusqu'à son propre « fini ». */
    private fun ajouter(e: Etape, maintenant: Long) {
        for (x in liste) if (!x.finie && x.genre != "tool") x.fin = maintenant
        liste.add(e)
    }

    /**
     * Les dernières lignes à afficher : « ✓ 🔍 web_search: météo  2,1 s » ou « ◐ Je réfléchis  4 s ».
     * Une étape = une ligne : tant que `tient(ligne)` est faux, le libellé est raccourci (avec « … »),
     * la durée, elle, reste toujours visible.
     */
    fun lignes(maintenant: Long, maximum: Int = 3, tient: (String) -> Boolean = { true }): List<String> {
        val tourne = arrayOf("◐", "◓", "◑", "◒")[((maintenant / 250) % 4).toInt()]
        return liste.takeLast(maximum).map { e ->
            val marque = if (e.finie) "✓" else tourne
            // Espaces insécables : la durée ne part jamais seule à la ligne.
            val duree = duree((if (e.finie) e.fin else maintenant) - e.debut).replace(' ', '\u00A0')
            val emoji = if (e.emoji.isNotEmpty()) "${e.emoji} " else ""
            var libelle = e.libelle
            var ligne = "$marque $emoji$libelle\u00A0\u00A0$duree"
            while (!tient(ligne) && libelle.length > 4) {
                libelle = libelle.dropLast(if (libelle.endsWith("…")) 2 else 1).trimEnd() + "…"
                ligne = "$marque $emoji$libelle\u00A0\u00A0$duree"
            }
            ligne
        }
    }

    /** Une ligne après la réponse : « 3 étapes · 12 s ». */
    fun resume(): String {
        if (liste.isEmpty() || finTour == 0L) return ""
        val outils = liste.count { it.genre == "tool" }
        val n = liste.size
        val partie = if (outils > 0) "$outils outil${if (outils > 1) "s" else ""}" else "$n étape${if (n > 1) "s" else ""}"
        return "✓ $partie · ${duree(finTour - debutTour)}"
    }

    companion object {
        fun duree(ms: Long): String {
            val s = ms.coerceAtLeast(0) / 1000.0
            return when {
                s < 10 -> String.format(Locale.FRANCE, "%.1f s", s)
                s < 60 -> "${s.toInt()} s"
                else -> "${(s / 60).toInt()} min ${(s % 60).toInt()} s"
            }
        }
    }
}
