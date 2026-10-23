package fr.silex.hermesmontre

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le cœur de l'appli : garde la connexion à Hermes, le micro, le haut-parleur et les capteurs.
 * Service au premier plan (notification permanente) : la conversation survit à l'écran éteint.
 */
class HermesService : Service() {

    enum class Phase { VEILLE, ECOUTE, ENVOI, REFLEXION, REPONSE }

    /** Ce que l'écran doit montrer. Toujours lu et modifié sur le fil principal. */
    data class Vue(
        var connexion: GadgetClient.Etat = GadgetClient.Etat.HORS_LIGNE,
        var detailConnexion: String = "",
        var phase: Phase = Phase.VEILLE,
        var entendu: String = "",
        var reponse: String = "",
        var statut: String = "",
        var notice: String = "",
        var codeAppairage: String = "",
        var commandeAppairage: String = "",
        var question: Question? = null,
        var carte: Carte? = null,
        var profil: String = "",
        var profils: List<Pair<String, String>> = emptyList(),
        var niveau: Float = 0f,
        /** Une mise à jour téléchargée, prête à installer (nom de version), sinon vide. */
        var maj: String = "",
        /** Les étapes du tour en cours (ou du dernier) : transcription, réflexion, outils… */
        val etapes: Etapes = Etapes(),
        /** Heure de la dernière réponse (HH:MM). */
        var heureReponse: String = "",
        var notesMaj: String = "",
        /** Quota Claude 0..100 poussé par le pont (`status.quota`), -1 = inconnu. */
        var quota: Int = -1,
        /** Batterie de la montre 0..100, -1 = inconnue. */
        var batterie: Int = -1,
        var enCharge: Boolean = false,
        /** Mémos vocaux enregistrés hors ligne, en attente d'envoi. */
        var memos: Int = 0,
        /** Vrai pendant un enregistrement hors ligne (le micro va dans un mémo, pas chez Hermes). */
        var memoEnCours: Boolean = false,
        /** Des choix à toucher : donnés par le pont (`choices`) ou repérés dans la réponse. */
        var proposition: Propositions.Proposition? = null,
        /** Les projets d'Hermes (liste et projet ouvert), reçus du pont ; `versionProjets` change à chaque envoi. */
        var projets: List<ProjetResume> = emptyList(),
        var projet: ProjetDetail? = null,
        var versionProjets: Int = 0,
    )

    data class ProjetResume(val id: String, val nom: String, val icone: String, val actif: Boolean, val resume: String)
    data class Tache(val titre: String, val statut: String, val emoji: String)
    data class ProjetDetail(val id: String, val nom: String, val icone: String, val description: String,
                            val chemin: String, val actif: Boolean, val resume: String, val finies: Int,
                            val taches: List<Tache>)

    /** Un échange de la conversation, gardé en mémoire seulement (jamais écrit sur la montre). */
    data class Echange(val question: String, val reponse: String, val heure: String, val resume: String)

    data class Question(val id: String, val titre: String, val texte: String, val finA: Long)
    data class Carte(val titre: String, val corps: String, val finA: Long)

    interface Ecran {
        fun surVue(v: Vue)
        fun surLuminosite(f: Float)
    }

    inner class Lien : Binder() { val service: HermesService get() = this@HermesService }

    private val main = Handler(Looper.getMainLooper())
    val vue = Vue()
    /** Les derniers échanges, du plus récent au plus ancien (20 au plus, effacés à l'arrêt). */
    val historique = ArrayDeque<Echange>()
    private lateinit var memos: Memos
    private var echecsConnexion = 0
    @Volatile private var rechercheEnCours = false
    var ecran: Ecran? = null
        set(v) { field = v; v?.surVue(vue) }

    lateinit var reglages: Reglages; private set
    private lateinit var client: GadgetClient
    private lateinit var micro: Micro
    private lateinit var hautParleur: HautParleur
    private lateinit var capteurs: Capteurs
    private lateinit var actions: Actions
    private var verrou: PowerManager.WakeLock? = null
    private lateinit var miseAJour: MiseAJour
    private var voixMontre: VoixMontre? = null
    private var apkPrete: java.io.File? = null
    @Volatile private var majEnCours = false

    private var fluxMontant = 0
    private var seqMontante = 0
    private var idParole = 0
    private var debutParole = 0L
    private var fluxDescendant = -1
    private var ecranEteint = false
    private var dernierReleve = ""
    private var dernierEnvoiCapteurs = 0L

    override fun onBind(intent: Intent?): IBinder = Lien()

    override fun onCreate() {
        super.onCreate()
        reglages = Reglages(this)
        hautParleur = HautParleur(
            surEtat = { parle -> main.post { finLecture(parle) } },
            surNiveau = { n -> main.post { vue.niveau = n; publier() } },
        )
        micro = Micro(
            surTrame = { pcm ->
                if (vue.memoEnCours) memos.ecrire(pcm)
                else client.envoyerAudio(fluxMontant, seqMontante++ and 0xffff, pcm)
            },
            surNiveau = { n -> main.post { vue.niveau = n; publier() } },
            surFinAuto = { raison -> main.post { finParole(raison) } },
        )
        capteurs = Capteurs(this) { main.post { if (vue.phase == Phase.VEILLE) commencerParole(auSilence = true) } }
        actions = Actions(this, capteurs,
            surLuminosite = { f -> main.post { ecran?.surLuminosite(f) } },
            surMinuteur = { etiquette -> main.post { minuteurFini(etiquette) } },
        )
        client = GadgetClient(reglages, ::fabriquerHello, object : GadgetClient.Auditeur {
            override fun surEtat(etat: GadgetClient.Etat, detail: String) = main.post { changementConnexion(etat, detail) }.let {}
            override fun surMessage(m: JSONObject) = main.post { recu(m) }.let {}
            override fun surAudio(flux: Int, pcm: ByteArray) {
                if (flux == fluxDescendant) hautParleur.ecrire(flux, pcm)
            }
        })
        miseAJour = MiseAJour(this)
        memos = Memos(java.io.File(filesDir, "memos"))
        vue.memos = memos.enAttente()
        if (reglages.voixMontre) voixMontre = VoixMontre(this) { parle -> main.post { finLecture(parle) } }
        enPremierPlan()
        demarrerCapteurs()
        surveillerReseau()
        client.demarrer()
        battementCapteurs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        micro.fermer()
        hautParleur.arreter()
        voixMontre?.fermer()
        capteurs.arreter()
        client.arreter()
        verrou?.let { if (it.isHeld) it.release() }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ ce que fait l'utilisateur

    fun commencerParole(auSilence: Boolean) {
        reglages.utilisations = reglages.utilisations + 1
        if (micro.ouvert) return
        if (!client.pret) {
            if (vue.connexion == GadgetClient.Etat.APPAIRAGE) { montrerNotice("Montre pas encore approuvée"); return }
            commencerMemo(auSilence)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            montrerNotice("Autorise le micro dans l'appli"); return
        }
        // Reprendre la parole coupe la voix d'Hermes (le serveur coupe aussi de son côté).
        hautParleur.arreter()
        voixMontre?.taire()
        fluxDescendant = -1
        fluxMontant = (fluxMontant % 250) + 1
        seqMontante = 0
        idParole++
        val ok = client.envoyer(Protocole.message("audio.start",
            "id" to "a$idParole", "stream" to fluxMontant, "rate" to Micro.DEBIT, "format" to "pcm16",
            "mode" to if (auSilence) "tap" else "hold"))
        if (!ok || !micro.ouvrir(finAuSilence = auSilence)) {
            if (ok) client.envoyer(Protocole.message("audio.cancel", "id" to "a$idParole", "stream" to fluxMontant, "reason" to "mic"))
            montrerNotice("Micro indisponible"); return
        }
        debutParole = System.currentTimeMillis()
        verrouiller(true)
        actions.signal(Actions.Signal.ECOUTE)
        vue.phase = Phase.ECOUTE
        vue.entendu = ""; vue.reponse = ""; vue.statut = ""; vue.carte = null; vue.proposition = null
        vue.etapes.nouveauTour(System.currentTimeMillis())
        publier()
    }

    /** Relâché (mode « hold ») ou toucher à nouveau (mode « tap »). */
    fun finirParole() = finParole("relache")

    private fun finParole(raison: String) {
        if (vue.phase != Phase.ECOUTE) return
        micro.fermer()
        if (vue.memoEnCours) { finMemo(raison); return }
        val duree = System.currentTimeMillis() - debutParole
        if (duree < 250 || raison == "rien") {
            client.envoyer(Protocole.message("audio.cancel", "id" to "a$idParole", "stream" to fluxMontant,
                "reason" to if (raison == "rien") "silence" else "too short"))
            vue.phase = Phase.VEILLE
            montrerNotice(if (raison == "rien") "Rien entendu" else "Maintiens pour parler")
            verrouiller(false)
            return
        }
        client.envoyer(Protocole.message("audio.end", "id" to "a$idParole", "stream" to fluxMontant, "duration_ms" to duree))
        actions.signal(Actions.Signal.ENVOI)
        vue.phase = Phase.ENVOI
        vue.etapes.transcription(System.currentTimeMillis())
        vue.statut = "Envoi…"
        publier()
    }

    fun annuler() {
        if (vue.phase == Phase.ECOUTE && vue.memoEnCours) {
            micro.fermer(); memos.abandonner(); vue.memoEnCours = false
        } else if (vue.phase == Phase.ECOUTE) {
            micro.fermer()
            client.envoyer(Protocole.message("audio.cancel", "id" to "a$idParole", "stream" to fluxMontant, "reason" to "cancel"))
        } else {
            client.envoyer(Protocole.message("cancel"))
        }
        hautParleur.arreter()
        voixMontre?.taire()
        fluxDescendant = -1
        vue.phase = Phase.VEILLE
        vue.statut = ""
        verrouiller(false)
        publier()
    }

    fun nouvelleConversation() {
        annuler()
        client.envoyer(Protocole.message("session.new"))
        vue.entendu = ""; vue.reponse = ""
        montrerNotice("Nouvelle conversation")
        actions.signal(Actions.Signal.ENVOI)
    }

    fun envoyerTexte(texte: String) {
        if (texte.isBlank()) return
        if (!client.envoyer(Protocole.message("text", "id" to "t${++idParole}", "text" to texte))) {
            montrerNotice("Hermes injoignable"); return
        }
        vue.entendu = texte; vue.reponse = ""; vue.phase = Phase.ENVOI; vue.proposition = null
        vue.etapes.nouveauTour(System.currentTimeMillis())
        actions.signal(Actions.Signal.ENVOI)
        publier()
    }

    fun repondreQuestion(oui: Boolean) {
        val q = vue.question ?: return
        client.envoyer(Protocole.message("prompt.reply", "id" to q.id, "answer" to if (oui) "yes" else "no"))
        vue.question = null
        publier()
    }

    fun fermerCarte() { vue.carte = null; publier() }

    /** Un bouton de proposition touché : sa réponse part chez Hermes comme si on l'avait dite. */
    fun repondreProposition(o: Propositions.Option) {
        vue.proposition = null
        envoyerTexte(o.reponse)
        vue.entendu = o.libelle
        publier()
    }

    fun ecarterProposition() { vue.proposition = null; publier() }

    // Explorer les projets : la montre demande, le pont répond `projets` / `projet`.
    fun demanderProjets() = evenementProjets("projets.liste", JSONObject())
    fun demanderProjet(id: String) = evenementProjets("projets.detail", JSONObject().put("id", id))
    fun utiliserProjet(id: String) = evenementProjets("projets.utiliser", JSONObject().put("id", id))

    private fun evenementProjets(nom: String, donnees: JSONObject) {
        if (!client.envoyer(Protocole.message("event", "name" to nom, "data" to donnees, "notify" to false))) {
            montrerNotice("Hermes injoignable")
        }
    }

    fun choisirProfil(id: String) { client.envoyer(Protocole.message("profile.select", "profile" to id)) }

    /** gauche / droite : écarter (pont) ; haut + long : « répète » (pont). */
    fun balayage(direction: String, long: Boolean = false) {
        val d = JSONObject().put("direction", direction)
        if (long) d.put("long", true)
        client.envoyer(Protocole.message("event", "name" to "gesture.swipe", "data" to d, "notify" to false))
    }

    /** L'appli passe au premier plan ou en sort. */
    fun ecranVisible(visible: Boolean) {
        if (ecranEteint == !visible) return
        ecranEteint = !visible
        client.envoyer(Protocole.message("state", "display_sleeping" to !visible))
        // Le pont suit l'écran par un événement `screen` (contrat de la carte Waveshare).
        client.envoyer(Protocole.message("event", "name" to "screen",
            "data" to JSONObject().put("state", if (visible) "awake" else "asleep"), "notify" to false))
    }

    fun reconnecter() {
        if (reglages.voixMontre && voixMontre == null) {
            voixMontre = VoixMontre(this) { parle -> main.post { finLecture(parle) } }
        } else if (!reglages.voixMontre && voixMontre != null) {
            voixMontre?.fermer(); voixMontre = null
        }
        demarrerCapteurs()
        client.relancer()
    }

    // ------------------------------------------------------------------ ce que dit Hermes

    private fun changementConnexion(etat: GadgetClient.Etat, detail: String) {
        vue.connexion = etat
        vue.detailConnexion = detail
        if (etat != GadgetClient.Etat.APPAIRAGE) { vue.codeAppairage = ""; vue.commandeAppairage = "" }
        if (etat == GadgetClient.Etat.APPAIRAGE && detail.isNotEmpty()) vue.codeAppairage = detail
        // Un mémo hors ligne s'enregistre justement sans pont : les tentatives de connexion ne le coupent pas.
        if (etat != GadgetClient.Etat.PRET && vue.phase != Phase.VEILLE && !vue.memoEnCours) {
            if (micro.ouvert) micro.fermer()
            vue.phase = Phase.VEILLE
            verrouiller(false)
        }
        if (etat == GadgetClient.Etat.APPAIRAGE) relancerSiApprouvee()
        if (etat == GadgetClient.Etat.HORS_LIGNE && ++echecsConnexion % 3 == 0) chercherLePont(manuelle = false)
        if (etat == GadgetClient.Etat.PRET) {
            echecsConnexion = 0
            main.postDelayed({ envoyerProchainMemo() }, 1500)
            dernierReleve = ""
            envoyerCapteurs(force = true)
            if (ecranEteint) client.envoyer(Protocole.message("state", "display_sleeping" to true))
            verifierMiseAJour(manuelle = false)
        }
        majNotification()
        publier()
    }

    private fun recu(m: JSONObject) {
        when (m.optString("type")) {
            "welcome" -> {
                lireProfils(m)
            }
            "pairing" -> {
                vue.codeAppairage = m.optString("code")
                vue.commandeAppairage = m.optString("command")
            }
            "paired" -> { actions.signal(Actions.Signal.REPONSE); montrerNotice("Montre approuvée") }
            "profile" -> {
                if (m.has("error")) montrerNotice("Profil refusé : ${m.optString("error")}")
                else { vue.profil = m.optString("profile"); reglages.profil = vue.profil }
            }
            "turn.start" -> {
                vue.phase = Phase.REFLEXION; vue.reponse = ""
                // Un tour lancé par le serveur (une question, « répète »…) : nouvelle liste d'étapes.
                if (vue.etapes.debutTour == 0L || vue.etapes.finTour > 0L) vue.etapes.nouveauTour(System.currentTimeMillis())
            }
            "transcript" -> {
                vue.entendu = m.optString("text")
                vue.etapes.transcrit(System.currentTimeMillis())
                if (vue.phase == Phase.ENVOI) vue.phase = Phase.REFLEXION
            }
            "status" -> {
                vue.statut = m.optString("text")
                // Le pont pousse le quota Claude sur `status` (arc de droite de la carte) : la montre
                // le dessine le long de son bord droit.
                if (m.has("quota")) vue.quota = m.optInt("quota", -1).coerceIn(-1, 100)
                if (vue.phase == Phase.ENVOI || vue.phase == Phase.REFLEXION) {
                    vue.etapes.statut(vue.statut, System.currentTimeMillis())
                }
            }
            // Étape en direct d'Hermes, relayée par le pont : réflexion, outil lancé ou fini, rédaction.
            "step" -> vue.etapes.etape(
                kind = m.optString("kind"), id = m.optString("id", m.optString("tool")),
                libelle = m.optString("label"), emoji = m.optString("emoji"),
                enCours = m.optString("state") != "done", maintenant = System.currentTimeMillis())
            "reply.delta" -> { vue.reponse = m.optString("text"); vue.statut = "" }
            "reply" -> {
                vue.reponse = m.optString("text")
                if (!m.optBoolean("interim")) {
                    vue.etapes.terminer(System.currentTimeMillis())
                    vue.heureReponse = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date())
                    historique.addFirst(Echange(vue.entendu, vue.reponse, vue.heureReponse, vue.etapes.resume()))
                    // Hermes propose des choix dans sa réponse ? Ils deviennent des boutons.
                    if (vue.proposition == null) vue.proposition = Propositions.extraire(vue.reponse)
                    while (historique.size > 20) historique.removeLast()
                }
                vue.statut = ""
                if (!m.optBoolean("interim")) {
                    actions.signal(Actions.Signal.REPONSE)
                    val voix = voixMontre
                    if (voix != null && voix.dire(vue.reponse)) {
                        vue.phase = Phase.REPONSE
                        verrouiller(true)
                    } else if (!hautParleur.parle && vue.phase != Phase.REPONSE) {
                        vue.phase = Phase.VEILLE
                    }
                }
            }
            "turn.end" -> {
                vue.statut = ""
                vue.etapes.terminer(System.currentTimeMillis())
                if (!hautParleur.parle && vue.phase != Phase.REPONSE) { vue.phase = Phase.VEILLE; verrouiller(false) }
                if (m.optString("outcome") == "failure") { montrerNotice("Hermes a échoué"); actions.signal(Actions.Signal.ERREUR) }
                // Un mémo enregistré hors ligne attend peut-être son tour : un à la fois, sinon le pont
                // annulerait le précédent.
                main.postDelayed({ envoyerProchainMemo() }, 800)
            }
            "notice" -> {
                montrerNotice(m.optString("text"), (m.optInt("ttl_s", 6)).coerceIn(2, 30) * 1000L)
                if (m.optString("urgence") == "urgent" || m.optString("kind") == "alert") actions.signal(Actions.Signal.URGENT)
            }
            "prompt" -> {
                val ttl = m.optInt("ttl_s", 0)
                vue.question = Question(m.optString("id"), m.optString("title"), m.optString("text"),
                    if (ttl > 0) System.currentTimeMillis() + ttl * 1000L else 0L)
                actions.signal(Actions.Signal.QUESTION)
                if (ttl > 0) main.postDelayed({ if (vue.question?.id == m.optString("id")) { vue.question = null; publier() } }, ttl * 1000L)
            }
            "prompt.close" -> if (vue.question?.id == m.optString("id")) vue.question = null
            "display" -> {
                val ttl = m.optInt("ttl_s", 15)
                val c = Carte(m.optString("title"), m.optString("body"), if (ttl > 0) System.currentTimeMillis() + ttl * 1000L else 0L)
                vue.carte = c
                actions.vibrer(50)
                if (ttl > 0) main.postDelayed({ if (vue.carte === c) { vue.carte = null; publier() } }, ttl * 1000L)
            }
            "audio.start" -> {
                if (m.optString("format", "pcm16") != "pcm16") return
                if (vue.phase == Phase.ECOUTE) return     // on parle : on ne se coupe pas soi-même
                fluxDescendant = m.optInt("stream")
                hautParleur.commencer(fluxDescendant, m.optInt("rate", 16000))
                vue.phase = Phase.REPONSE
                verrouiller(true)
            }
            "audio.end" -> hautParleur.terminer(m.optInt("stream"))
            "audio.abort" -> if (m.optInt("stream") == fluxDescendant) { hautParleur.arreter(); fluxDescendant = -1; finLecture(false) }
            "action" -> executerAction(m)
            // Des choix tout faits par le pont : « Oui / Non » à une suggestion, les trois idées…
            "choices" -> {
                val options = m.optJSONArray("options") ?: JSONArray()
                val liste = (0 until options.length()).mapNotNull { i ->
                    options.optJSONObject(i)?.let { Propositions.Option(it.optString("label"), it.optString("reply", it.optString("label"))) }
                }.filter { it.libelle.isNotEmpty() }
                if (liste.isNotEmpty()) {
                    val p = Propositions.Proposition(m.optString("question"), liste, "pont")
                    vue.proposition = p
                    val ttl = m.optInt("ttl_s", 0)
                    if (ttl > 0) main.postDelayed({ if (vue.proposition === p) { vue.proposition = null; publier() } }, ttl * 1000L)
                }
            }
            "projets" -> {
                val liste = m.optJSONArray("projets") ?: JSONArray()
                vue.projets = (0 until liste.length()).mapNotNull { i ->
                    liste.optJSONObject(i)?.let {
                        ProjetResume(it.optString("id"), it.optString("nom"), it.optString("icone"),
                            it.optBoolean("actif"), it.optString("resume"))
                    }
                }
                vue.versionProjets++
            }
            "projet" -> {
                if (m.has("erreur")) { montrerNotice("Projet introuvable"); return }
                val t = m.optJSONArray("taches") ?: JSONArray()
                vue.projet = ProjetDetail(m.optString("id"), m.optString("nom"), m.optString("icone"),
                    m.optString("description"), m.optString("chemin"), m.optBoolean("actif"), m.optString("resume"),
                    m.optInt("finies"), (0 until t.length()).mapNotNull { i ->
                        t.optJSONObject(i)?.let { Tache(it.optString("titre"), it.optString("statut"), it.optString("emoji")) }
                    })
                vue.versionProjets++
            }
            "error" -> montrerNotice(m.optString("message").ifEmpty { m.optString("code") })
        }
        publier()
    }

    private fun lireProfils(m: JSONObject) {
        val liste = m.optJSONArray("profiles") ?: JSONArray()
        vue.profils = (0 until liste.length()).mapNotNull { i ->
            liste.optJSONObject(i)?.let { it.optString("id") to it.optString("name", it.optString("id")) }
        }.take(8)
        if (m.has("profile")) { vue.profil = m.optString("profile"); reglages.profil = vue.profil }
    }

    private fun executerAction(m: JSONObject) {
        val id = m.optString("id")
        val reponse = try {
            val r = actions.executer(m.optString("name"), m.optJSONObject("args") ?: JSONObject())
            Protocole.message("action.result", "id" to id, "ok" to true, "result" to r)
        } catch (e: Exception) {
            Protocole.message("action.result", "id" to id, "ok" to false, "error" to (e.message ?: e.javaClass.simpleName))
        }
        client.envoyer(reponse)
    }

    private fun finLecture(parle: Boolean) {
        if (parle || vue.phase != Phase.REPONSE) return
        vue.phase = Phase.VEILLE
        verrouiller(false)
        publier()
    }

    /**
     * Le pont (hote/pont_gadget.py) relit sa liste d'appareils approuvés à chaque CONNEXION, et ne
     * prévient pas quand on approuve : on se reconnecte donc toutes les 20 s tant qu'on attend.
     * Le plugin de la passerelle, lui, envoie `paired` (et un code) : pas besoin d'insister.
     */
    private fun relancerSiApprouvee() {
        main.removeCallbacks(reessai)
        main.postDelayed(reessai, 20_000)
    }

    private val reessai = Runnable {
        if (vue.connexion == GadgetClient.Etat.APPAIRAGE && vue.codeAppairage.isEmpty()) client.relancer()
    }

    // ------------------------------------------------------------------ mémos hors ligne

    /**
     * Pas de pont (métro, pas de Wi-Fi) : on enregistre quand même. Le mémo est gardé sur la montre
     * et part chez Hermes dès que le pont revient, comme si on venait de parler.
     */
    private fun commencerMemo(auSilence: Boolean) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            montrerNotice("Autorise le micro dans l'appli"); return
        }
        if (vue.memos >= Memos.MAXIMUM) { montrerNotice("Déjà ${Memos.MAXIMUM} mémos en attente"); return }
        memos.commencer()
        vue.memoEnCours = true
        if (!micro.ouvrir(finAuSilence = auSilence)) {
            vue.memoEnCours = false; memos.abandonner(); montrerNotice("Micro indisponible"); return
        }
        debutParole = System.currentTimeMillis()
        actions.signal(Actions.Signal.ECOUTE)
        vue.phase = Phase.ECOUTE
        publier()
    }

    private fun finMemo(raison: String) {
        vue.memoEnCours = false
        vue.phase = Phase.VEILLE
        val duree = System.currentTimeMillis() - debutParole
        if (duree < 400 || raison == "rien") {
            memos.abandonner(); montrerNotice("Rien enregistré"); publier(); return
        }
        memos.garder()
        vue.memos = memos.enAttente()
        actions.signal(Actions.Signal.ENVOI)
        montrerNotice("Mémo gardé : il partira au retour du pont", 5000)
        publier()
    }

    /** Envoie le plus ancien mémo en attente, si le pont est là et que rien d'autre n'est en cours. */
    private fun envoyerProchainMemo() {
        if (!client.pret || vue.phase != Phase.VEILLE || micro.ouvert) return
        val (fichier, pcm) = memos.prochain() ?: return
        fluxMontant = (fluxMontant % 250) + 1
        idParole++
        val id = "m$idParole"
        if (!client.envoyer(Protocole.message("audio.start", "id" to id, "stream" to fluxMontant,
                "rate" to Micro.DEBIT, "format" to "pcm16", "mode" to "hold"))) return
        var seq = 0
        var i = 0
        while (i < pcm.size) {
            val n = minOf(Micro.OCTETS_TRAME, pcm.size - i)
            client.envoyerAudio(fluxMontant, seq++ and 0xffff, pcm.copyOfRange(i, i + n))
            i += n
        }
        client.envoyer(Protocole.message("audio.end", "id" to id, "stream" to fluxMontant,
            "duration_ms" to pcm.size / 32))
        fichier.delete()
        vue.memos = memos.enAttente()
        vue.entendu = ""; vue.reponse = ""
        vue.etapes.nouveauTour(System.currentTimeMillis())
        vue.etapes.transcription(System.currentTimeMillis())
        vue.phase = Phase.ENVOI
        montrerNotice("Mémo envoyé" + if (vue.memos > 0) " (encore ${vue.memos})" else "")
        publier()
    }

    // ------------------------------------------------------------------ trouver le pont

    /**
     * Cherche le pont sur le Wi-Fi (UDP, `Phare` du pont). Automatique après trois échecs de
     * connexion ; à la demande depuis les réglages. Une adresse trouvée remplace l'ancienne.
     */
    fun chercherLePont(manuelle: Boolean) {
        if (rechercheEnCours) return
        rechercheEnCours = true
        if (manuelle) montrerNotice("Recherche du pont…")
        Thread({
            val trouve = try { Decouverte.chercher() } catch (e: Exception) { null }
            main.post {
                rechercheEnCours = false
                when {
                    trouve == null -> if (manuelle) montrerNotice("Pont introuvable sur ce Wi-Fi")
                    trouve.ws == reglages.adresse -> if (manuelle) montrerNotice("Pont trouvé : adresse déjà bonne")
                    else -> {
                        reglages.adresse = trouve.ws
                        montrerNotice("Pont trouvé : ${trouve.nom.ifEmpty { trouve.ws }}")
                        client.relancer()
                    }
                }
            }
        }, "decouverte").start()
    }

    // ------------------------------------------------------------------ mises à jour

    /**
     * Demande au pont s'il a une version plus récente (au plus toutes les 6 h, sauf demande
     * explicite), la télécharge, vérifie son empreinte, puis la propose à l'écran.
     */
    fun verifierMiseAJour(manuelle: Boolean) {
        val maintenant = System.currentTimeMillis()
        if (majEnCours || (!manuelle && maintenant - reglages.derniereVerifMaj < MiseAJour.INTERVALLE_MS)) return
        val base = miseAJour.base(reglages.adresse) ?: return
        majEnCours = true
        reglages.derniereVerifMaj = maintenant
        if (manuelle) montrerNotice("Recherche d'une mise à jour…")
        Thread({
            val resultat = try {
                val v = miseAJour.chercher(base)
                if (v == null) null else Pair(v, miseAJour.telecharger(base, v))
            } catch (e: Exception) {
                android.util.Log.w("MiseAJour", "vérification impossible", e)
                if (manuelle) main.post { montrerNotice("Pont injoignable pour la mise à jour") }
                majEnCours = false
                return@Thread
            }
            main.post {
                majEnCours = false
                if (resultat == null) {
                    if (manuelle) montrerNotice("Déjà à jour")
                    return@post
                }
                val (v, apk) = resultat
                apkPrete = apk
                vue.maj = v.nom.ifEmpty { v.code.toString() }
                vue.notesMaj = v.notes
                actions.vibrer(60, 2)
                notifierMaj(vue.maj)
                publier()
            }
        }, "mise-a-jour").start()
    }

    fun installerMiseAJour() {
        val apk = apkPrete ?: return
        vue.maj = ""
        publier()
        try {
            miseAJour.installer(apk)
        } catch (e: Exception) {
            montrerNotice("Installation impossible : ${e.message}")
        }
    }

    fun reporterMiseAJour() { vue.maj = ""; publier() }

    private fun notifierMaj(version: String) {
        val ouvrir = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Mise à jour d'Hermes")
            .setContentText("Version $version prête : ouvre l'appli pour l'installer")
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        try { getSystemService(NotificationManager::class.java).notify(2, n) } catch (_: Exception) {}
    }

    private fun minuteurFini(etiquette: String) {
        vue.carte = Carte("Minuteur", etiquette.ifEmpty { "Temps écoulé" }, System.currentTimeMillis() + 60_000)
        client.envoyer(Protocole.message("event", "name" to "timer.done",
            "data" to JSONObject().put("label", etiquette), "notify" to false))
        publier()
    }

    // ------------------------------------------------------------------ capteurs

    private fun demarrerCapteurs() {
        capteurs.arreter()
        capteurs.demarrer(
            cardio = checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED,
            pas = Build.VERSION.SDK_INT < 29 ||
                checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED,
        )
        capteurs.secousseActive = reglages.secouerPourParler
    }

    /** Relevés toutes les 60 s, envoyés seulement s'ils ont changé (et au moins toutes les 10 min). */
    private fun battementCapteurs() {
        envoyerCapteurs(force = false)
        main.postDelayed(::battementCapteurs, 60_000)
    }

    private fun envoyerCapteurs(force: Boolean) {
        val r = capteurs.releve()
        // La batterie sert aussi à l'écran (bord gauche), même hors ligne.
        vue.batterie = r.optInt("battery_pct", -1)
        vue.enCharge = r.optBoolean("charging", false)
        publier()
        if (!client.pret) return
        val texte = r.toString()
        val maintenant = System.currentTimeMillis()
        if (!force && texte == dernierReleve && maintenant - dernierEnvoiCapteurs < 600_000) return
        if (client.envoyer(Protocole.message("state", "sensors" to r))) {
            dernierReleve = texte
            dernierEnvoiCapteurs = maintenant
        }
    }

    private fun fabriquerHello(): JSONObject {
        val m = Resources.getSystem().displayMetrics
        val largeur = m.widthPixels
        val hauteur = m.heightPixels
        val colonnes = (largeur / (m.density * 9)).toInt().coerceIn(12, 40)
        val lignes = (hauteur / (m.density * 20)).toInt().coerceIn(4, 20)
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val hp = packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT) ||
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).isNotEmpty()
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
        return Protocole.hello(
            identifiant = reglages.identifiant, nom = reglages.nom, modele = Build.MODEL.replace(' ', '-'),
            version = version, jeton = reglages.jeton,
            largeur = largeur, hauteur = hauteur, colonnes = colonnes, lignes = lignes,
            // Voix de la montre : on dit au pont qu'on n'a pas de haut-parleur, il n'envoie plus d'audio.
            modeParole = reglages.modeParole, haut_parleur = hp && !reglages.voixMontre, profil = reglages.profil,
            actions = actions.liste, capteurs = capteurs.releve(), ecranEteint = ecranEteint,
        )
    }

    // ------------------------------------------------------------------ plomberie

    private fun montrerNotice(texte: String, duree: Long = 4000) {
        if (texte.isEmpty()) return
        vue.notice = texte
        publier()
        main.postDelayed({ if (vue.notice == texte) { vue.notice = ""; publier() } }, duree)
    }

    private fun publier() { ecran?.surVue(vue) }

    /** Garde le processeur éveillé pendant un tour (écran éteint, Wi-Fi de la montre en veille). */
    private fun verrouiller(oui: Boolean) {
        if (oui) {
            if (verrou == null) {
                verrou = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:tour").apply { setReferenceCounted(false) }
            }
            verrou?.acquire(3 * 60_000L)
        } else {
            verrou?.let { if (it.isHeld) it.release() }
        }
    }

    private fun surveillerReseau() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post { if (vue.connexion == GadgetClient.Etat.HORS_LIGNE) client.relancer() }
                }
            })
        } catch (_: Exception) {}
    }

    private fun enPremierPlan() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Hermes", NotificationManager.IMPORTANCE_LOW))
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, n)
        } catch (e: Exception) {
            // Android 14 refuse un service « micro » lancé sans l'autorisation du micro : l'appli
            // ne lance le service qu'une fois l'autorisation donnée, mais on ne plante pas pour autant.
            android.util.Log.w("HermesService", "premier plan refusé", e)
        }
    }

    private fun notification(): Notification {
        val ouvrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val texte = when (vue.connexion) {
            GadgetClient.Etat.PRET -> "Connecté à Hermes"
            GadgetClient.Etat.APPAIRAGE -> "En attente d'approbation"
            GadgetClient.Etat.CONNEXION -> "Connexion…"
            GadgetClient.Etat.REFUSE -> "Refusé : ${vue.detailConnexion}"
            GadgetClient.Etat.HORS_LIGNE -> "Hors ligne"
        }
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Hermes")
            .setContentText(texte)
            .setContentIntent(ouvrir)
            .setOngoing(true)
            .build()
    }

    private fun majNotification() {
        try { getSystemService(NotificationManager::class.java).notify(1, notification()) } catch (_: Exception) {}
    }

    companion object {
        private const val CANAL = "hermes"
    }
}
