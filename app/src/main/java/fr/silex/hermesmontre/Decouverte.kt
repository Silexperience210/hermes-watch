package fr.silex.hermesmontre

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Trouve le pont sur le Wi-Fi, sans adresse à taper (le `Phare` de hote/pont_gadget.py).
 *
 * La montre crie `HERMES-GADGET?` en diffusion UDP sur le port 8768 ; le pont répond l'adresse
 * WebSocket où le joindre. Fil d'arrière-plan uniquement (bloque au plus `delaiMs`).
 */
object Decouverte {
    const val PORT = 8768
    private val QUESTION = "HERMES-GADGET?".toByteArray()

    data class Pont(val ws: String, val nom: String)

    fun chercher(delaiMs: Int = 2500, cibles: List<InetAddress> = diffusions()): Pont? {
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = delaiMs
            for (cible in cibles) {
                try { s.send(DatagramPacket(QUESTION, QUESTION.size, cible, PORT)) } catch (_: Exception) {}
            }
            val tampon = ByteArray(1024)
            val fin = System.currentTimeMillis() + delaiMs
            while (System.currentTimeMillis() < fin) {
                val paquet = DatagramPacket(tampon, tampon.size)
                try { s.receive(paquet) } catch (e: SocketTimeoutException) { return null }
                lire(String(paquet.data, 0, paquet.length, Charsets.UTF_8))?.let { return it }
            }
        }
        return null
    }

    fun lire(json: String): Pont? = try {
        val o = JSONObject(json)
        val ws = o.getString("ws")
        if (ws.startsWith("ws://") || ws.startsWith("wss://")) Pont(ws, o.optString("nom")) else null
    } catch (e: Exception) { null }

    /** 255.255.255.255 et l'adresse de diffusion de chaque réseau (certaines box filtrent la première). */
    fun diffusions(): List<InetAddress> {
        val liste = mutableListOf(InetAddress.getByName("255.255.255.255"))
        try {
            for (interfaceReseau in NetworkInterface.getNetworkInterfaces()) {
                if (!interfaceReseau.isUp || interfaceReseau.isLoopback) continue
                for (a in interfaceReseau.interfaceAddresses) a.broadcast?.let { if (it !in liste) liste.add(it) }
            }
        } catch (_: Exception) {}
        return liste
    }
}
