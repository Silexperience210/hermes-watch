package fr.silex.hermesmontre

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ce qu'Hermes peut faire SUR la montre (`action` → `action.result`).
 * Les noms `speaker.volume` et `screen.brightness` (paramètre `percent`) sont ceux que le pont
 * hote/pont_gadget.py utilise déjà pour la carte : « monte le volume » marche tel quel.
 */
class Actions(
    private val context: Context,
    private val capteurs: Capteurs,
    /** Luminosité 0..1 à poser sur la fenêtre de l'appli. */
    private val surLuminosite: (Float) -> Unit,
    /** Minuteur échu : (étiquette). */
    private val surMinuteur: (String) -> Unit,
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val lampe: String? = trouverLampe()

    val liste: List<Protocole.Action> = buildList {
        add(Protocole.Action("speaker.volume", "Set the watch speaker volume (0-100 percent).",
            schema("percent" to "integer")))
        add(Protocole.Action("screen.brightness", "Set the watch screen brightness (5-100 percent).",
            schema("percent" to "integer")))
        add(Protocole.Action("haptic.vibrate",
            "Vibrate the watch on the user's wrist, to get their attention silently. ms: duration (default 400), repeat: pulses (default 1).",
            schema("ms" to "integer", "repeat" to "integer", requis = emptyList())))
        add(Protocole.Action("sensors.read",
            "Read the watch sensors now: battery, charging, heart rate (bpm), steps since boot, ambient light (lux), air pressure (hPa).",
            schema(requis = emptyList())))
        add(Protocole.Action("timer.set",
            "Start a countdown on the watch. When it ends the watch vibrates and shows the label. seconds: duration, label: optional text.",
            schema("seconds" to "integer", "label" to "string", requis = listOf("seconds"))))
        if (lampe != null) add(Protocole.Action("torch.set", "Turn the watch flashlight on or off.",
            schema("on" to "boolean")))
    }

    fun executer(nom: String, args: JSONObject): JSONObject = when (nom) {
        "speaker.volume" -> {
            val pct = args.optInt("percent", -1)
            require(pct in 0..100) { "percent must be 0-100" }
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100f), 0)
            JSONObject().put("percent", pct)
        }
        "screen.brightness" -> {
            val pct = args.optInt("percent", -1).coerceIn(5, 100)
            surLuminosite(pct / 100f)
            JSONObject().put("percent", pct)
        }
        "haptic.vibrate" -> {
            val ms = args.optLong("ms", 400).coerceIn(20, 3000)
            val fois = args.optInt("repeat", 1).coerceIn(1, 10)
            vibrer(ms, fois)
            JSONObject().put("ms", ms).put("repeat", fois)
        }
        "sensors.read" -> capteurs.releve()
        "timer.set" -> {
            val s = args.optLong("seconds", -1)
            require(s in 1..86_400) { "seconds must be 1-86400" }
            val etiquette = args.optString("label", "")
            main.postDelayed({ vibrer(500, 3); surMinuteur(etiquette) }, s * 1000)
            JSONObject().put("seconds", s).put("label", etiquette)
        }
        "torch.set" -> {
            val id = lampe ?: throw IllegalStateException("no flashlight")
            val on = args.optBoolean("on", true)
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on)
            JSONObject().put("on", on)
        }
        else -> throw IllegalArgumentException("unknown action $nom")
    }

    /**
     * Un langage de vibrations : chaque événement a sa forme, on sait ce qui se passe sans regarder.
     *   ÉCOUTE  ▁        un toc bref         le micro s'ouvre
     *   ENVOI   ▁ ▁      deux tocs légers    c'est parti chez Hermes
     *   RÉPONSE ▂▁▂      court-long-court    la réponse est là
     *   QUESTION ▂ ▂ ▂   trois égaux         Hermes demande oui / non
     *   URGENT  ███ ███  deux longs          alerte
     *   ERREUR  ████     un long             ça n'a pas marché
     */
    enum class Signal(val motif: LongArray) {
        ECOUTE(longArrayOf(0, 25)),
        ENVOI(longArrayOf(0, 15, 90, 15)),
        REPONSE(longArrayOf(0, 40, 80, 90, 80, 40)),
        QUESTION(longArrayOf(0, 70, 110, 70, 110, 70)),
        URGENT(longArrayOf(0, 380, 180, 380)),
        ERREUR(longArrayOf(0, 450)),
    }

    fun signal(s: Signal) {
        val v = vibreur() ?: return
        v.vibrate(VibrationEffect.createWaveform(s.motif, -1))
    }

    private fun vibreur(): Vibrator? {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        return if (v.hasVibrator()) v else null
    }

    fun vibrer(ms: Long, fois: Int = 1) {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) return
        val motif = LongArray(fois * 2) { i -> if (i % 2 == 0) (if (i == 0) 0L else 150L) else ms }
        v.vibrate(VibrationEffect.createWaveform(motif, -1))
    }

    private fun trouverLampe(): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) return null
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (e: Exception) {
            null
        }
    }

    private fun schema(vararg champs: Pair<String, String>, requis: List<String>? = null): JSONObject {
        val props = JSONObject()
        for ((nom, type) in champs) props.put(nom, JSONObject().put("type", type))
        return JSONObject().put("type", "object").put("properties", props)
            .put("required", JSONArray(requis ?: champs.map { it.first }))
    }
}
