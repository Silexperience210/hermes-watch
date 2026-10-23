package fr.silex.hermesmontre

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Identité de la montre, à la manière du firmware Hermes Gadget (protocole v1, docs/protocol.md) :
 * une clé aléatoire de 32 octets, tirée une fois et gardée sur la montre, et un identifiant qui en
 * DÉCOULE (`hg-` + 16 premiers caractères hex du SHA-256) — personne ne peut prendre l'identifiant
 * de la montre sans sa clé.
 */
object Identite {
    const val CONTEXTE_AUTH = "hermes-gadget/v1|"
    const val TAILLE_CLE = 32

    fun nouvelleCle(): ByteArray = ByteArray(TAILLE_CLE).also { SecureRandom().nextBytes(it) }

    fun identifiant(cle: ByteArray): String {
        val empreinte = MessageDigest.getInstance("SHA-256").digest(cle)
        return "hg-" + hex(empreinte).substring(0, 16)
    }

    /** mac = HMAC-SHA256(clé, "hermes-gadget/v1|" + id + "|" + nonce), en base64. Le nonce est la chaîne reçue telle quelle. */
    fun macAuth(cle: ByteArray, identifiant: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cle, "HmacSHA256"))
        val message = (CONTEXTE_AUTH + identifiant + "|" + nonce).toByteArray(Charsets.UTF_8)
        return b64(mac.doFinal(message))
    }

    fun b64(octets: ByteArray): String = Base64.getEncoder().encodeToString(octets)

    fun deB64(texte: String): ByteArray? = try {
        Base64.getDecoder().decode(texte)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun hex(octets: ByteArray): String = buildString(octets.size * 2) {
        for (b in octets) append("%02x".format(b.toInt() and 0xff))
    }
}
