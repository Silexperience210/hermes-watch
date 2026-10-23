package fr.silex.hermesmontre

import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Réglages : adresse du pont, jeton, nom, façon de parler, profil Hermes. */
class ReglagesActivity : Activity() {

    private lateinit var r: Reglages
    private var service: HermesService? = null
    private lateinit var profils: LinearLayout

    private val connexion = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as HermesService.Lien).service
            remplirProfils()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Echelle.contexte(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        r = Reglages(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(40))
        }
        col.addView(titre("Réglages"))

        col.addView(etiquette("Adresse du pont Hermes"))
        val adresse = champ(r.adresse, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        col.addView(adresse)
        col.addView(aide("ws://IP-du-PC:8765/gadget — ou bien :"))
        col.addView(Button(this).apply {
            text = "Trouver le pont tout seul"
            setOnClickListener { service?.chercherLePont(manuelle = true); finish() }
        })

        col.addView(etiquette("Jeton d'accès (facultatif)"))
        val jeton = champ(r.jeton, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        col.addView(jeton)

        col.addView(etiquette("Nom de la montre"))
        val nom = champ(r.nom, InputType.TYPE_CLASS_TEXT)
        col.addView(nom)

        val maintenir = interrupteur("Maintenir pour parler", r.modeParole == "hold")
        col.addView(maintenir)
        col.addView(aide("Désactivé : toucher pour parler, la fin est détectée au silence."))
        val secouer = interrupteur("Secouer le poignet pour parler", r.secouerPourParler)
        col.addView(secouer)
        val ecran = interrupteur("Écran allumé dans l'appli", r.ecranAllume)
        col.addView(ecran)
        val reveil = interrupteur("Parler au réveil", r.parlerAuReveil)
        col.addView(reveil)
        col.addView(aide("Rallumer l'écran sur Hermes ouvre le micro tout de suite (fin au silence)."))
        val cadran = interrupteur("Mode cadran au repos", r.cadran)
        col.addView(cadran)
        col.addView(aide("Après 30 s sans rien : grande heure, écran atténué."))

        col.addView(etiquette("Raccourcis (séparés par « | »)"))
        val raccourcis = champ(r.raccourcis.joinToString(" | "), InputType.TYPE_CLASS_TEXT)
        col.addView(raccourcis)
        col.addView(aide("Trois questions au plus, chacune d'un toucher au repos."))

        val voix = interrupteur("Voix de la montre", r.voixMontre)
        col.addView(voix)
        col.addView(aide("Activé : la montre lit les réponses avec sa propre voix Android au lieu de celle du PC."))

        col.addView(Button(this).apply {
            text = "Enregistrer et reconnecter"
            setOnClickListener {
                r.adresse = adresse.text.toString()
                r.jeton = jeton.text.toString()
                r.nom = nom.text.toString()
                r.modeParole = if (maintenir.isChecked) "hold" else "tap"
                r.secouerPourParler = secouer.isChecked
                r.ecranAllume = ecran.isChecked
                r.voixMontre = voix.isChecked
                r.parlerAuReveil = reveil.isChecked
                r.cadran = cadran.isChecked
                r.raccourcis = raccourcis.text.toString().split("|")
                service?.reconnecter()
                finish()
            }
        })

        col.addView(etiquette("Mise à jour"))
        col.addView(aide("Version installée : ${versionInstallee()}. Le pont propose les nouvelles versions toutes les 6 h."))
        col.addView(Button(this).apply {
            text = "Rechercher maintenant"
            setOnClickListener { service?.verifierMiseAJour(manuelle = true); finish() }
        })

        col.addView(etiquette("Profil Hermes"))
        profils = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(profils)

        col.addView(etiquette("Identifiant de la montre"))
        col.addView(TextView(this).apply {
            text = r.identifiant
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
        })
        col.addView(aide("Pour l'approuver sur le PC (pont) :\nbash ~/hermes-voice/outils/approuver.sh ${r.identifiant}"))

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, HermesService::class.java), connexion, 0)
    }

    override fun onStop() {
        unbindService(connexion)
        service = null
        super.onStop()
    }

    private fun remplirProfils() {
        profils.removeAllViews()
        val s = service ?: return
        if (s.vue.profils.isEmpty()) {
            profils.addView(aide("Le serveur ne propose pas de profils."))
            return
        }
        for ((id, nom) in s.vue.profils) {
            profils.addView(Button(this).apply {
                text = if (id == s.vue.profil || (s.vue.profil.isEmpty() && id == "default")) "● $nom" else nom
                setOnClickListener { s.choisirProfil(id); finish() }
            })
        }
    }

    private fun versionInstallee(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun titre(t: String) = TextView(this).apply {
        text = t; gravity = Gravity.CENTER
        setTextColor(Color.rgb(185, 178, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, 0, 0, dp(8))
    }

    private fun etiquette(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.rgb(185, 178, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun aide(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.rgb(140, 140, 154)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextIsSelectable(true)
    }

    private fun champ(valeur: String, type: Int) = EditText(this).apply {
        setText(valeur); inputType = type; setSingleLine()
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setBackgroundResource(R.drawable.champ)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    @Suppress("DEPRECATION")
    private fun interrupteur(t: String, coche: Boolean) = Switch(this).apply {
        text = t; isChecked = coche; setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(10), 0, dp(4))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }
}
