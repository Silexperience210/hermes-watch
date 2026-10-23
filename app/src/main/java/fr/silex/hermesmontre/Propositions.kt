package fr.silex.hermesmontre

/**
 * Les choix qu'Hermes propose, transformés en boutons : on répond d'un toucher au lieu de parler.
 *
 * Deux sources :
 *  - le pont les donne tout faits (message `choices` : suggestion « Oui / Non », « tu as des
 *    idées ? » → les trois idées) ;
 *  - sinon la montre les repère dans la réponse d'Hermes : une liste numérotée ou à puces
 *    (« 1) … 2) … »), ou une question fermée en fin de réponse (« Je le lance ? », « Tu veux que… ? »).
 * Toucher un bouton envoie sa réponse à Hermes comme si on l'avait dite. Pur Kotlin : testé sur JVM.
 */
object Propositions {

    data class Option(val libelle: String, val reponse: String) {
        /** Le libellé d'une puce : assez court pour que plusieurs tiennent sur la largeur de la montre. */
        val puce: String get() = court(libelle, 15)
    }
    data class Proposition(val question: String, val options: List<Option>, val source: String)

    private val NUMERO = Regex("""^\s*(?:\*\*)?(\d{1,2})\s*[.)\-:]\s*(?:\*\*)?\s*(.+)$""")
    private val PUCE = Regex("""^\s*[-•*▪◦]\s+(.+)$""")
    private val FERMEE = Regex(
        """^(?:veux[- ]tu|tu veux|voulez[- ]vous|souhaites[- ]tu|dois[- ]je|je (?:le |la |les |t'en |lance|fais|continue|m'en occupe|commence|envoie|crée|ajoute|passe)|""" +
            """on (?:y va|continue|lance|commence|fait)|est[- ]ce que je|je peux|tu préfères que|c'est bon pour toi|ça te va|ok pour|d'accord pour|on part sur)""",
        RegexOption.IGNORE_CASE)

    /** Les choix repérés dans une réponse d'Hermes, ou null s'il n'y en a pas. */
    fun extraire(reponse: String): Proposition? {
        val lignes = reponse.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // 1. Une liste numérotée (2 à 6 éléments) : chaque élément devient un bouton.
        val numerotees = lignes.mapNotNull { NUMERO.matchEntire(it) }
        if (numerotees.size in 2..6) {
            val question = lignes.lastOrNull { it.endsWith("?") } ?: "Lequel ?"
            return Proposition(nettoyer(question), numerotees.map { m ->
                val texte = nettoyer(m.groupValues[2])
                Option("${m.groupValues[1]} · $texte", "Option ${m.groupValues[1]} : $texte")
            }, "liste")
        }
        // 2. Une liste à puces suivie (ou précédée) d'une question : même chose.
        val puces = lignes.mapNotNull { PUCE.matchEntire(it)?.groupValues?.get(1) }
        val question = derniereQuestion(reponse)
        if (puces.size in 2..5 && question != null) {
            return Proposition(question, puces.map { nettoyer(it) }.map { Option(it, it) }, "puces")
        }
        // 3. Une question fermée à la fin : Oui / Non (et « Plus tard » pour ne pas trancher).
        if (question != null && FERMEE.containsMatchIn(question)) {
            return Proposition(question, listOf(Option("Oui", "Oui, vas-y."), Option("Non", "Non, merci."),
                Option("Plus tard", "Pas maintenant, plus tard.")), "oui-non")
        }
        return null
    }

    /** La dernière phrase de la réponse, si c'est une question. */
    fun derniereQuestion(texte: String): String? {
        val t = nettoyer(texte).trim()
        if (!t.endsWith("?")) return null
        val debut = maxOf(t.lastIndexOf(". ", t.length - 2), t.lastIndexOf("! ", t.length - 2),
            t.lastIndexOf("\n", t.length - 2), t.lastIndexOf(": ", t.length - 2))
        return t.substring(if (debut < 0) 0 else debut + 1).trim().trimStart(':', ' ')
    }

    private fun nettoyer(t: String) = t.replace("**", "").replace("__", "").replace("`", "").trim()

    fun court(t: String, max: Int = 34) = if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
}
