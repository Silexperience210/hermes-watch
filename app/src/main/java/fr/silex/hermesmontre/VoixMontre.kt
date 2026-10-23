package fr.silex.hermesmontre

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * La voix de la MONTRE (synthèse Android), à la place de celle du pont.
 *
 * Utile quand la montre a une bonne voix française (Google, Samsung…) : souvent plus naturelle
 * que Piper, sans Internet, et rien à installer sur le PC. La montre annonce alors qu'elle n'a pas
 * de haut-parleur au pont, qui n'envoie plus d'audio, et lit elle-même le texte de la réponse.
 */
class VoixMontre(context: Context, private val surParle: (Boolean) -> Unit) {

    @Volatile var prete = false; private set
    var nomVoix = ""; private set
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { statut ->
        if (statut == TextToSpeech.SUCCESS) configurer()
    }
    private var numero = 0

    private fun configurer() {
        val francais = Locale.FRANCE
        if (tts.isLanguageAvailable(francais) < TextToSpeech.LANG_AVAILABLE) return
        tts.language = francais
        // La meilleure voix française installée : sans réseau d'abord, puis la qualité la plus haute.
        val voix: Voice? = try {
            tts.voices?.filter { it.locale.language == "fr" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?.sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { -(if (it.locale.country == "FR") 1 else 0) }))
                ?.firstOrNull()
        } catch (e: Exception) { null }
        if (voix != null) { tts.voice = voix; nomVoix = voix.name }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = surParle(true)
            override fun onDone(id: String?) { if (id == "h$numero") surParle(false) }
            @Deprecated("API 21") override fun onError(id: String?) = surParle(false)
            override fun onError(id: String?, code: Int) = surParle(false)
            override fun onStop(id: String?, interrupted: Boolean) = surParle(false)
        })
        prete = true
    }

    fun dire(texte: String): Boolean {
        if (!prete || texte.isBlank()) return false
        numero++
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC) }
        return tts.speak(nettoyer(texte), TextToSpeech.QUEUE_FLUSH, params, "h$numero") == TextToSpeech.SUCCESS
    }

    fun taire() { if (prete) tts.stop() }

    fun fermer() { tts.shutdown() }

    companion object {
        /** Ce qu'un humain lirait : sans liens, puces ni symboles (même idée que texte_pour_voix du pont). */
        fun nettoyer(t: String): String = t
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_`#>|•·▪◦]"), " ")
            .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
