package fr.silex.hermesmontre

import org.json.JSONArray
import org.json.JSONObject

/**
 * Protocole Hermes Gadget v1, côté appareil (docs/protocol.md du SDK Hermes Gadget).
 * Fonctions pures, testées sur JVM (app/src/test).
 */
object Protocole {
    const val VERSION = 1
    const val SOUS_PROTOCOLE = "hermes-gadget.v1"
    const val CANAL_AUDIO = 0x01
    const val ENTETE = 4

    /** Trame binaire : canal u8, flux u8, séquence u16 petit-boutiste, puis la charge. */
    fun binaire(canal: Int, flux: Int, seq: Int, charge: ByteArray, longueur: Int = charge.size): ByteArray {
        val t = ByteArray(ENTETE + longueur)
        t[0] = canal.toByte()
        t[1] = (flux and 0xff).toByte()
        t[2] = (seq and 0xff).toByte()
        t[3] = ((seq shr 8) and 0xff).toByte()
        System.arraycopy(charge, 0, t, ENTETE, longueur)
        return t
    }

    data class Trame(val canal: Int, val flux: Int, val seq: Int, val charge: ByteArray)

    fun lireBinaire(d: ByteArray): Trame? {
        if (d.size < ENTETE) return null
        val canal = d[0].toInt() and 0xff
        if (canal !in 1..3) return null
        val seq = (d[2].toInt() and 0xff) or ((d[3].toInt() and 0xff) shl 8)
        return Trame(canal, d[1].toInt() and 0xff, seq, d.copyOfRange(ENTETE, d.size))
    }

    /** Une action que la montre sait faire, annoncée à Hermes dans `hello.actions`. */
    data class Action(val nom: String, val description: String, val params: JSONObject)

    fun hello(
        identifiant: String, nom: String, modele: String, version: String, jeton: String,
        largeur: Int, hauteur: Int, colonnes: Int, lignes: Int, modeParole: String,
        haut_parleur: Boolean, profil: String, actions: List<Action>, capteurs: JSONObject,
        ecranEteint: Boolean,
    ): JSONObject {
        val caps = JSONObject()
            .put("display", JSONObject()
                .put("width", largeur).put("height", hauteur).put("color", true)
                // Android dessine tout l'Unicode : rien à replier côté serveur.
                .put("charset", "utf8")
                .put("text_cols", colonnes).put("text_rows", lignes)
                .put("shape", "round"))
            .put("mic", JSONObject().put("rate", Micro.DEBIT).put("format", "pcm16"))
            .put("inputs", JSONArray(listOf("talk", "cancel", "touch")))
            .put("talk_mode", modeParole)
            .put("profiles", true)
        if (haut_parleur) {
            caps.put("speaker", JSONObject().put("rate", 16000).put("format", "pcm16")
                .put("formats", JSONArray(listOf("pcm16"))))
        }
        val o = JSONObject()
            .put("type", "hello").put("proto", VERSION)
            .put("device_id", identifiant).put("name", nom)
            .put("board", "android-watch:$modele").put("firmware", version)
            .put("caps", caps)
            .put("actions", JSONArray(actions.map {
                JSONObject().put("name", it.nom).put("description", it.description).put("params", it.params)
            }))
            .put("sensors", capteurs)
        if (jeton.isNotEmpty()) o.put("token", jeton)
        if (profil.isNotEmpty() && profil != "default") o.put("profile", profil)
        if (ecranEteint) o.put("display_sleeping", true)
        return o
    }

    fun message(type: String, vararg champs: Pair<String, Any?>): JSONObject {
        val o = JSONObject().put("type", type)
        for ((k, v) in champs) if (v != null) o.put(k, v)
        return o
    }
}
