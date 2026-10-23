package fr.silex.hermesmontre

import android.content.Context
import android.os.Build

/** Réglages gardés sur la montre (préférences privées de l'appli, jamais sauvegardées ailleurs). */
class Reglages(context: Context) : GadgetClient.Compte {
    private val p = context.applicationContext.getSharedPreferences("hermes", Context.MODE_PRIVATE)

    /** ws://IP-du-PC:8765/gadget — l'adresse du pont (hote/pont_gadget.py) ou du plugin Gadget. */
    override var adresse: String
        get() = p.getString("adresse", "ws://192.168.1.10:8765/gadget") ?: ""
        set(v) = p.edit().putString("adresse", v.trim()).apply()

    /** Jeton partagé optionnel (GADGET_ACCESS_TOKEN / --jeton du pont). Vide = aucun. */
    var jeton: String
        get() = p.getString("jeton", "") ?: ""
        set(v) = p.edit().putString("jeton", v.trim()).apply()

    var nom: String
        get() = p.getString("nom", "Montre ${Build.MODEL}") ?: "Montre"
        set(v) = p.edit().putString("nom", v.trim().ifEmpty { "Montre" }).apply()

    /** « hold » : maintenir pour parler. « tap » : toucher pour commencer, la fin est détectée au silence. */
    var modeParole: String
        get() = p.getString("mode", "hold") ?: "hold"
        set(v) = p.edit().putString("mode", v).apply()

    /** Secouer le poignet ouvre le micro (mode « tap », coupure au silence). */
    var secouerPourParler: Boolean
        get() = p.getBoolean("secouer", false)
        set(v) = p.edit().putBoolean("secouer", v).apply()

    /** Garder l'écran allumé pendant que l'appli est au premier plan. */
    var ecranAllume: Boolean
        get() = p.getBoolean("ecran", true)
        set(v) = p.edit().putBoolean("ecran", v).apply()

    /** Voix de la montre (synthèse Android) au lieu de celle du pont. */
    var voixMontre: Boolean
        get() = p.getBoolean("voixMontre", false)
        set(v) = p.edit().putBoolean("voixMontre", v).apply()

    /** Nombre de prises de parole : la consigne « maintiens pour parler » disparaît après 5. */
    var utilisations: Int
        get() = p.getInt("utilisations", 0)
        set(v) = p.edit().putInt("utilisations", v).apply()

    /** Questions favorites, une touche chacune au repos (séparées par « | »). */
    var raccourcis: List<String>
        get() = (p.getString("raccourcis", null) ?: "Météo|Mon agenda|Quoi de neuf ?")
            .split("|").map { it.trim() }.filter { it.isNotEmpty() }.take(3)
        set(v) = p.edit().putString("raccourcis", v.joinToString("|") { it.replace("|", " ").trim() }).apply()

    /** Écouter tout de suite quand on rallume l'écran sur l'appli (le geste « lever et parler »). */
    var parlerAuReveil: Boolean
        get() = p.getBoolean("reveil", false)
        set(v) = p.edit().putBoolean("reveil", v).apply()

    /** Mode cadran : après 30 s sans rien, grande heure, écran atténué. */
    var cadran: Boolean
        get() = p.getBoolean("cadran", true)
        set(v) = p.edit().putBoolean("cadran", v).apply()

    /** Dernière recherche de mise à jour (ms). */
    var derniereVerifMaj: Long
        get() = p.getLong("verifMaj", 0L)
        set(v) = p.edit().putLong("verifMaj", v).apply()

    /** Profil Hermes choisi en dernier (renvoyé dans `hello.profile`). */
    var profil: String
        get() = p.getString("profil", "") ?: ""
        set(v) = p.edit().putString("profil", v).apply()

    /**
     * La clé de 32 octets de la montre, tirée au premier lancement. La perdre (effacer les données
     * de l'appli) oblige à ré-enrôler la montre côté hôte (`hermes gadget forget <id>`).
     */
    override val cle: ByteArray
        @Synchronized get() {
            val existante = p.getString("cle", null)?.let { Identite.deB64(it) }
            if (existante != null && existante.size == Identite.TAILLE_CLE) return existante
            val neuve = Identite.nouvelleCle()
            p.edit().putString("cle", Identite.b64(neuve)).commit()
            return neuve
        }

    override val identifiant: String get() = Identite.identifiant(cle)
}
