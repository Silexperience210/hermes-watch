package fr.silex.hermesmontre

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * L'écran de la montre.
 *
 *  - maintenir la mascotte : parler, relâcher : envoyer (mode « maintenir ») ;
 *    toucher la mascotte : parler, la fin est détectée au silence (mode « toucher ») ;
 *  - toucher la mascotte pendant qu'Hermes réfléchit ou parle : interrompre ;
 *  - toucher la réponse : la lire en grand (couronne ou doigt pour défiler) ;
 *  - 🕘 ou balayer vers le haut depuis le bas de l'écran : l'historique ;
 *  - ↺ maintenu : nouvelle conversation ; balayer à gauche / à droite : écarter la suggestion ;
 *  - appui long sur la réponse : « répète » (le pont la redit sans repasser par le modèle).
 */
class MainActivity : Activity(), HermesService.Ecran {

    private var service: HermesService? = null
    private lateinit var ecranVue: EcranPrincipal
    private lateinit var zoneParole: View
    private lateinit var reponse: TextView
    private lateinit var notice: TextView
    private lateinit var calque: LinearLayout
    private lateinit var gestes: GestureDetector
    private lateinit var reglages: Reglages

    /** Dernier geste de l'utilisateur : 30 s sans rien au repos, l'écran passe en mode cadran. */
    private var dernierGeste = System.currentTimeMillis()
    private var enCadran = false
    /** L'écran s'est éteint pendant que l'appli était ouverte : à son retour, « parler au réveil ». */
    private var ecranEteintA = 0L
    /** Ce que montre le calque de lecture, pour le redessiner quand le pont répond. */
    private enum class Calque { AUCUN, PROJETS, PROJET }
    private var calqueOuvert = Calque.AUCUN
    private var versionVue = -1

    private val connexion = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as HermesService.Lien).service.also {
                it.ecran = this@MainActivity
                it.ecranVisible(true)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Echelle.contexte(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        reglages = Reglages(this)
        ecranVue = EcranPrincipal(findViewById(R.id.racine))
        zoneParole = findViewById(R.id.zoneParole)
        reponse = findViewById(R.id.reponse)
        notice = findViewById(R.id.notice)
        calque = findViewById(R.id.calque)

        brancherZoneParole()
        findViewById<View>(R.id.reglages).setOnClickListener { startActivity(Intent(this, ReglagesActivity::class.java)) }
        findViewById<View>(R.id.nouvelle).apply {
            setOnClickListener { montrerConsigne("Maintiens ↺ pour une nouvelle conversation") }
            setOnLongClickListener { service?.nouvelleConversation(); true }
        }
        reponse.setOnClickListener { ouvrirReponse() }
        reponse.setOnLongClickListener { service?.balayage("up", long = true); montrerConsigne("Je répète"); true }
        ecranVue.surRaccourci = { texte -> service?.envoyerTexte(texte) }
        ecranVue.surHistorique = { ouvrirHistorique() }
        ecranVue.surProjets = { ouvrirProjets() }
        ecranVue.surProposition = { o -> service?.repondreProposition(o) }
        ecranVue.surEcarterProposition = { service?.ecarterProposition() }
        findViewById<View>(R.id.lectureTitre).setOnClickListener { retourCalque() }
        findViewById<View>(R.id.lectureConsigne).setOnClickListener { retourCalque() }
        findViewById<View>(R.id.cadran).setOnClickListener { reveiller() }

        // Les deux boutons du calque servent à la question d'Hermes, sinon à la mise à jour.
        findViewById<Button>(R.id.oui).setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.vue.question != null) s.repondreQuestion(true) else s.installerMiseAJour()
        }
        findViewById<Button>(R.id.non).setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.vue.question != null) s.repondreQuestion(false) else s.reporterMiseAJour()
        }
        calque.setOnClickListener {
            val s = service ?: return@setOnClickListener
            when {
                s.vue.question != null -> {}
                s.vue.connexion == GadgetClient.Etat.APPAIRAGE -> { s.reconnecter(); montrerConsigne("Nouvel essai…") }
                else -> s.fermerCarte()
            }
        }
        calque.setOnLongClickListener {
            if (service?.vue?.question == null) startActivity(Intent(this, ReglagesActivity::class.java))
            true
        }

        gestes = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || ecranVue.enLecture) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) * 1.5f && abs(dx) > 80 && abs(vx) > 600) {
                    service?.balayage(if (dx < 0) "left" else "right")
                    montrerConsigne("Écarté")
                    return true
                }
                // Vers le haut, parti du bas de l'écran : l'historique (comme le tiroir d'un téléphone).
                val bas = window.decorView.height * 0.8f
                if (dy < -120 && abs(dy) > abs(dx) * 1.5f && e1.y > bas) {
                    ouvrirHistorique()
                    return true
                }
                return false
            }
        })
        demanderPermissions()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun brancherZoneParole() {
        zoneParole.setOnTouchListener { _, e ->
            val s = service ?: return@setOnTouchListener true
            val maintenir = s.reglages.modeParole == "hold"
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> when (s.vue.phase) {
                    HermesService.Phase.VEILLE -> s.commencerParole(auSilence = !maintenir)
                    HermesService.Phase.ECOUTE -> if (!maintenir) s.finirParole()
                    // Toucher pendant la réflexion ou la réponse : interrompre (le BOOT de la carte).
                    else -> s.annuler()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (maintenir && s.vue.phase == HermesService.Phase.ECOUTE) s.finirParole()
            }
            true
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            dernierGeste = System.currentTimeMillis()
            // Le premier toucher en mode cadran ne fait que réveiller : il ne parle pas à Hermes.
            if (enCadran) { reveiller(); return true }
        }
        if (calque.visibility != View.VISIBLE) gestes.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** La couronne : sur Android, elle arrive en défilement (`AXIS_SCROLL`) ; elle fait défiler ce qui se lit. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            dernierGeste = System.currentTimeMillis()
            if (enCadran) { reveiller(); return true }
            defiler((-event.getAxisValue(MotionEvent.AXIS_SCROLL) * 60 * resources.displayMetrics.density).toInt())
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun defiler(dy: Int) {
        if (ecranVue.enLecture) ecranVue.lectureDefile.smoothScrollBy(0, dy)
        else findViewById<android.widget.ScrollView>(R.id.defile).smoothScrollBy(0, dy)
    }

    /** Boutons physiques : « assistant » ou casque pour parler ; flèches (couronne de certaines montres) pour défiler. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        dernierGeste = System.currentTimeMillis()
        val s = service
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS -> { defiler(-(80 * resources.displayMetrics.density).toInt()); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_NAVIGATE_NEXT -> { defiler((80 * resources.displayMetrics.density).toInt()); return true }
            KeyEvent.KEYCODE_BACK -> if (ecranVue.enLecture) { retourCalque(); return true }
        }
        if (s != null && (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_STEM_1 || keyCode == KeyEvent.KEYCODE_STEM_PRIMARY)) {
            when (s.vue.phase) {
                HermesService.Phase.VEILLE -> s.commencerParole(auSilence = true)
                HermesService.Phase.ECOUTE -> s.finirParole()
                else -> s.annuler()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        lancerService()
    }

    override fun onResume() {
        super.onResume()
        dernierGeste = System.currentTimeMillis()
        enCadran = false
        zoneParole.removeCallbacks(horloge)
        zoneParole.post(horloge)
        val s = service
        if (s != null) { s.ecran = this; s.ecranVisible(true) }
        if (reglages.ecranAllume) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ouverte par le bouton « assistant », ou écran rallumé avec « parler au réveil » : on écoute.
        val assistant = intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND
        val reveil = reglages.parlerAuReveil && ecranEteintA > 0 && System.currentTimeMillis() - ecranEteintA > 3000
        ecranEteintA = 0
        if (assistant || reveil) {
            intent.action = Intent.ACTION_MAIN
            zoneParole.postDelayed({
                service?.let { if (it.vue.phase == HermesService.Phase.VEILLE) it.commencerParole(auSilence = true) }
            }, 600)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onPause() {
        zoneParole.removeCallbacks(horloge)
        super.onPause()
    }

    override fun onStop() {
        ecranEteintA = System.currentTimeMillis()
        service?.let {
            if (it.vue.phase == HermesService.Phase.ECOUTE) it.finirParole()
            it.ecranVisible(false)
            it.ecran = null
        }
        if (service != null) unbindService(connexion)
        service = null
        super.onStop()
    }

    private fun lancerService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val i = Intent(this, HermesService::class.java)
        startForegroundService(i)
        bindService(i, connexion, Context.BIND_AUTO_CREATE)
    }

    private fun demanderPermissions() {
        val voulues = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (voulues.isNotEmpty()) requestPermissions(voulues.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (service == null) lancerService() else service?.reconnecter()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ecranVue.montrerLecture("Micro refusé",
                "Hermes a besoin du micro.\nAutorise-le dans Paramètres › Applis › Hermes › Autorisations.")
        }
    }

    // ------------------------------------------------------------------ lecture, historique, cadran

    private fun ouvrirReponse() {
        val v = service?.vue ?: return
        if (v.reponse.isEmpty()) return
        val titre = listOf(v.heureReponse, if (v.entendu.isNotEmpty()) "« ${v.entendu} »" else "").filter { it.isNotEmpty() }
        calqueOuvert = Calque.AUCUN
        ecranVue.montrerLecture(titre.joinToString("  "), v.reponse, v.proposition)
    }

    // ------------------------------------------------------------------ explorer les projets

    private fun ouvrirProjets() {
        val s = service ?: return
        calqueOuvert = Calque.PROJETS
        dessinerCalque(s.vue)                 // la dernière liste connue tout de suite…
        s.demanderProjets()                    // … puis la liste fraîche dès que le pont répond
    }

    private fun dessinerCalque(v: HermesService.Vue) {
        versionVue = v.versionProjets
        when (calqueOuvert) {
            Calque.PROJETS -> ecranVue.montrerProjets(v.projets) { p ->
                calqueOuvert = Calque.PROJET
                service?.vue?.projet = null
                service?.demanderProjet(p.id)
                ecranVue.montrerLecture("${p.icone.ifEmpty { "📁" }} ${p.nom}", "…")
            }
            Calque.PROJET -> {
                val p = v.projet ?: return
                val s = service ?: return
                ecranVue.montrerProjet(p, buildList {
                    // Chaque action est une vraie demande à Hermes, dite à sa place : la réponse arrive
                    // comme d'habitude (étapes, voix, propositions à toucher).
                    add("📋 Fais-moi le point" to { lancer(s, "Fais-moi le point sur le projet « ${p.nom} » : où on en est, ce qui bloque.") })
                    add("➡ Prochaine étape ?" to { lancer(s, "Sur le projet « ${p.nom} », quelle est la prochaine chose à faire ? Propose-moi des options.") })
                    if (p.taches.any { it.statut == "blocked" }) {
                        add("⛔ Débloquer" to { lancer(s, "Sur le projet « ${p.nom} », qu'est-ce qui bloque et comment le débloquer ?") })
                    }
                    if (!p.actif) add("▶ Travailler dessus" to { s.utiliserProjet(p.id); calqueOuvert = Calque.PROJETS })
                })
            }
            Calque.AUCUN -> {}
        }
    }

    private fun lancer(s: HermesService, demande: String) {
        fermerCalque()
        s.envoyerTexte(demande)
    }

    /** Retour : du projet à la liste, de la liste à l'écran principal. */
    private fun retourCalque() {
        if (calqueOuvert == Calque.PROJET) { calqueOuvert = Calque.PROJETS; service?.let { dessinerCalque(it.vue) } }
        else fermerCalque()
    }

    private fun fermerCalque() {
        calqueOuvert = Calque.AUCUN
        ecranVue.fermerLecture()
    }

    private fun ouvrirHistorique() {
        val s = service ?: return
        calqueOuvert = Calque.AUCUN
        ecranVue.montrerHistorique(s.historique.toList()) { e ->
            ecranVue.montrerLecture("${e.heure}  « ${e.question} »", e.reponse + if (e.resume.isNotEmpty()) "\n\n${e.resume}" else "")
        }
    }

    private fun reveiller() {
        dernierGeste = System.currentTimeMillis()
        if (!enCadran) return
        enCadran = false
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        service?.let { surVue(it.vue) }
    }

    // ------------------------------------------------------------------ affichage

    /** L'heure et les durées des étapes avancent même sans message : on redessine
     *  chaque seconde pendant le travail, toutes les 10 s au repos. */
    private val horloge = object : Runnable {
        override fun run() {
            val v = service?.vue
            // Mode cadran : 30 s au repos sans geste, rien à l'écran qui attende une action.
            if (v != null && reglages.cadran && !enCadran && v.phase == HermesService.Phase.VEILLE &&
                v.question == null && !ecranVue.enLecture && System.currentTimeMillis() - dernierGeste > 30_000) {
                enCadran = true
                window.attributes = window.attributes.apply { screenBrightness = 0.12f }
            }
            if (v != null && v.phase != HermesService.Phase.VEILLE && enCadran) reveiller()
            if (v != null) surVue(v)
            val travail = v != null && v.phase != HermesService.Phase.VEILLE
            zoneParole.postDelayed(this, if (travail) 1000L else 10_000L)
        }
    }

    override fun surVue(v: HermesService.Vue) {
        // Le pont a répondu (liste ou projet) : le calque ouvert se redessine.
        if (calqueOuvert != Calque.AUCUN && v.versionProjets != versionVue && ecranVue.enLecture) dessinerCalque(v)
        // Une question, une réponse qui arrive : on sort du cadran.
        if (enCadran && (v.phase != HermesService.Phase.VEILLE || v.question != null)) {
            enCadran = false
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
        ecranVue.afficher(v, EcranPrincipal.Options(
            maintenir = reglages.modeParole != "tap",
            identifiant = service?.reglages?.identifiant ?: "",
            raccourcis = reglages.raccourcis,
            novice = reglages.utilisations < 5,
            cadran = enCadran,
        ))
    }

    override fun surLuminosite(f: Float) {
        window.attributes = window.attributes.apply { screenBrightness = f.coerceIn(0.02f, 1f) }
    }

    private fun montrerConsigne(texte: String) {
        notice.text = texte
        notice.visibility = View.VISIBLE
        notice.postDelayed({ if (notice.text == texte) notice.visibility = View.GONE }, 2500)
    }
}
