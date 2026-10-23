package fr.silex.hermesmontre

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * La montre vue par Hermes : un appareil Hermes Gadget comme la carte Waveshare.
 *
 * Une seule WebSocket vers le pont (`hote/pont_gadget.py`) ou le plugin Gadget de la passerelle.
 * Poignée de main : hello → challenge → auth (clé au premier contact, HMAC ensuite) → welcome.
 * Le client se reconnecte seul, avec un délai qui s'allonge (1 s → 30 s).
 */
class GadgetClient(
    private val reglages: Compte,
    private val fabriquerHello: () -> JSONObject,
    private val auditeur: Auditeur,
) {
    /** Ce que le client doit savoir de la montre (Reglages, ou un faux compte dans les tests). */
    interface Compte {
        val adresse: String
        val cle: ByteArray
        val identifiant: String
    }

    interface Auditeur {
        fun surEtat(etat: Etat, detail: String)
        /** Tout message JSON reçu après la poignée de main. */
        fun surMessage(m: JSONObject)
        fun surAudio(flux: Int, pcm: ByteArray)
    }

    enum class Etat { HORS_LIGNE, CONNEXION, APPAIRAGE, PRET, REFUSE }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)       // la WebSocket vit longtemps ; le battement de cœur veille
        .pingInterval(0, TimeUnit.SECONDS)           // le protocole a son propre ping
        .build()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ws: WebSocket? = null
    @Volatile private var enPoignee = true
    @Volatile private var voulu = false
    @Volatile var etat = Etat.HORS_LIGNE; private set
    @Volatile var battementS = 20; private set
    @Volatile private var dernierRecu = 0L
    private var delai = 1000L
    private var generation = 0

    fun demarrer() {
        voulu = true
        delai = 1000L
        connecter()
    }

    fun arreter() {
        voulu = false
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, "au revoir")
        ws = null
        poser(Etat.HORS_LIGNE, "")
    }

    /** Ferme et rouvre tout de suite (réglages changés, réseau revenu). */
    fun relancer() {
        ws?.cancel()
        ws = null
        main.removeCallbacksAndMessages(null)
        if (voulu) { delai = 1000L; connecter() }
    }

    fun envoyer(m: JSONObject): Boolean {
        val s = ws ?: return false
        if (enPoignee) return false
        return s.send(m.toString())
    }

    fun envoyerAudio(flux: Int, seq: Int, pcm: ByteArray): Boolean {
        val s = ws ?: return false
        if (enPoignee) return false
        return s.send(Protocole.binaire(Protocole.CANAL_AUDIO, flux, seq, pcm).toByteString())
    }

    val pret: Boolean get() = etat == Etat.PRET

    private fun poser(e: Etat, detail: String) {
        etat = e
        auditeur.surEtat(e, detail)
    }

    private fun connecter() {
        if (!voulu) return
        val adresse = reglages.adresse
        val requete = try {
            Request.Builder().url(adresse.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://"))
                .header("Sec-WebSocket-Protocol", Protocole.SOUS_PROTOCOLE)
                .build()
        } catch (e: IllegalArgumentException) {
            poser(Etat.REFUSE, "Adresse invalide : $adresse")
            return
        }
        val ma = ++generation
        enPoignee = true
        poser(Etat.CONNEXION, adresse)
        ws = http.newWebSocket(requete, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (ma != generation) return
                dernierRecu = System.currentTimeMillis()
                webSocket.send(fabriquerHello().toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ma != generation) return
                dernierRecu = System.currentTimeMillis()
                val m = try { JSONObject(text) } catch (e: Exception) { return }
                traiter(webSocket, m)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (ma != generation) return
                dernierRecu = System.currentTimeMillis()
                val t = Protocole.lireBinaire(bytes.toByteArray()) ?: return
                if (t.canal == Protocole.CANAL_AUDIO) auditeur.surAudio(t.flux, t.charge)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ma != generation) return
                perdu(if (reason.isNotEmpty()) reason else "connexion fermée ($code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ma != generation) return
                Log.w(TAG, "échec WebSocket", t)
                perdu(t.message ?: t.javaClass.simpleName)
            }
        })
    }

    private fun traiter(s: WebSocket, m: JSONObject) {
        when (m.optString("type")) {
            "challenge" -> {
                val cle = reglages.cle
                val auth = if (m.optBoolean("enrolled")) {
                    Protocole.message("auth", "mac" to Identite.macAuth(cle, reglages.identifiant, m.optString("nonce")))
                } else {
                    // Premier contact : la clé traverse le réseau une seule fois (confiance au premier usage).
                    Protocole.message("auth", "key" to Identite.b64(cle))
                }
                s.send(auth.toString())
            }
            "welcome" -> {
                enPoignee = false
                delai = 1000L
                battementS = m.optInt("heartbeat_s", 20).coerceIn(5, 300)
                surveiller(generation)
                poser(if (m.optBoolean("paired")) Etat.PRET else Etat.APPAIRAGE, "")
                auditeur.surMessage(m)
            }
            "pairing" -> { poser(Etat.APPAIRAGE, m.optString("code")); auditeur.surMessage(m) }
            "paired" -> { poser(Etat.PRET, ""); auditeur.surMessage(m) }
            "unpaired" -> { poser(Etat.APPAIRAGE, ""); auditeur.surMessage(m) }
            "ping" -> s.send(Protocole.message("pong", "ts" to m.opt("ts")).toString())
            "error" -> {
                val code = m.optString("code")
                if (enPoignee || code == "auth_failed" || code == "bad_token") {
                    poser(Etat.REFUSE, explication(code, m.optString("message")))
                }
                auditeur.surMessage(m)
            }
            else -> if (!enPoignee) auditeur.surMessage(m)
        }
    }

    /** Trois battements sans rien recevoir : la connexion est morte, on la refait. */
    private fun surveiller(ma: Int) {
        main.postDelayed({
            if (ma != generation || ws == null) return@postDelayed
            if (System.currentTimeMillis() - dernierRecu > 3L * battementS * 1000) {
                Log.i(TAG, "serveur silencieux : reconnexion")
                ws?.cancel()
                perdu("serveur silencieux")
            } else {
                surveiller(ma)
            }
        }, battementS * 1000L)
    }

    private fun perdu(raison: String) {
        ws = null
        enPoignee = true
        generation++
        if (etat != Etat.REFUSE) poser(Etat.HORS_LIGNE, raison)
        if (!voulu) return
        val attente = if (etat == Etat.REFUSE) 30_000L else delai
        delai = (delai * 2).coerceAtMost(30_000L)
        main.postDelayed({ connecter() }, attente)
    }

    private fun explication(code: String, message: String): String = when (code) {
        "auth_failed" -> "Clé refusée. Sur le PC : hermes gadget forget ${reglages.identifiant}"
        "bad_token" -> "Jeton d'accès manquant ou faux (Réglages)"
        "busy" -> "Trop d'appareils en attente d'appairage"
        else -> message.ifEmpty { code }
    }

    companion object { private const val TAG = "GadgetClient" }
}
