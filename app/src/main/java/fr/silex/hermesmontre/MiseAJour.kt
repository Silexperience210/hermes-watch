package fr.silex.hermesmontre

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Mise à jour automatique de l'appli, servie par le pont sur le réseau local
 * (hote/pont_gadget.py, `ServeurMajMontre`, port 8767 par défaut) :
 *
 *  1. `version.json` est lu au démarrage puis toutes les 6 h ;
 *  2. si la version est plus récente, l'APK est téléchargée et son SHA-256 vérifié ;
 *  3. l'installation est proposée : Android demande une confirmation (un toucher sur
 *     « Mettre à jour »), et refuse d'office une APK signée par une autre clé.
 */
class MiseAJour(private val context: Context) {

    data class Version(val code: Int, val nom: String, val sha256: String, val taille: Long, val notes: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val versionInstallee: Int
        get() = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        } catch (e: Exception) { 0 }

    /** http://hôte-du-pont:8767/montre/ — l'hôte est celui de l'adresse WebSocket. */
    fun base(adresse: String, port: Int = PORT): String? = try {
        val hote = URI(adresse.trim()).host ?: return null
        "http://$hote:$port/montre/"
    } catch (e: Exception) { null }

    /** La version proposée par le pont, ou null (rien de neuf, ou pont injoignable). Fil d'arrière-plan. */
    fun chercher(base: String): Version? {
        val corps = http.newCall(Request.Builder().url(base + "version.json").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string() ?: return null
        }
        val v = lire(corps) ?: return null
        return if (v.code > versionInstallee) v else null
    }

    /** Télécharge l'APK et vérifie son empreinte. Fil d'arrière-plan. */
    fun telecharger(base: String, v: Version): File {
        val fichier = File(context.cacheDir, "maj-${v.code}.apk")
        http.newCall(Request.Builder().url(base + "hermes-montre.apk").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val corps = r.body ?: throw IllegalStateException("réponse vide")
            fichier.outputStream().use { sortie -> corps.byteStream().copyTo(sortie) }
        }
        val empreinte = sha256(fichier)
        if (!empreinte.equals(v.sha256, ignoreCase = true)) {
            fichier.delete()
            throw SecurityException("empreinte SHA-256 différente : fichier abîmé ou mauvaise APK")
        }
        return fichier
    }

    /** Ouvre l'écran d'Android « Mettre à jour cette application ? ». */
    fun installer(apk: File) {
        val installeur = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+ : sans confirmation si l'appli s'est déjà installée elle-même une fois.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installeur.createSession(params)
        installeur.openSession(id).use { session ->
            session.openWrite("hermes-montre.apk", 0, apk.length()).use { sortie ->
                apk.inputStream().use { it.copyTo(sortie) }
                session.fsync(sortie)
            }
            val retour = PendingIntent.getBroadcast(context, id,
                Intent(context, RetourInstallation::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            session.commit(retour.intentSender)
        }
    }

    companion object {
        const val PORT = 8767
        const val INTERVALLE_MS = 6 * 3600 * 1000L

        fun lire(json: String): Version? = try {
            val o = JSONObject(json)
            Version(o.getInt("versionCode"), o.optString("versionName"), o.getString("sha256"),
                o.optLong("taille"), o.optString("notes"))
        } catch (e: Exception) { null }

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { entree ->
                val tampon = ByteArray(64 * 1024)
                while (true) { val n = entree.read(tampon); if (n < 0) break; md.update(tampon, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

/** Réponse d'Android à l'installation : il faut parfois que l'utilisateur confirme. */
class RetourInstallation : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val statut = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmer = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirmer.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmer)
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("MiseAJour", "mise à jour installée")
            else -> Log.w("MiseAJour", "installation refusée ($statut) : ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

/** Après une mise à jour, Android arrête l'appli : on relance le service tout de suite. */
class ApresMiseAJour : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            context.startForegroundService(Intent(context, HermesService::class.java))
        } catch (e: Exception) {
            Log.w("MiseAJour", "relance du service refusée, ouvre l'appli", e)
        }
    }
}
