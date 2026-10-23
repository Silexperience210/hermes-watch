package fr.silex.hermesmontre

import android.content.Context
import android.content.res.Configuration
import kotlin.math.min

/**
 * Même mise en page sur toutes les montres, quelle que soit la densité qu'elles annoncent.
 *
 * Les montres chinoises annoncent des densités très variables : sur l'Ultra I3 AI, l'écran fait
 * l'équivalent de ~500 dp de large et tout paraissait minuscule. On redéfinit donc la densité pour
 * que le petit côté de l'écran vaille toujours [LARGEUR_DP] dp : textes, boutons et marges gardent
 * la même proportion de l'écran partout (et les captures de src/test montrent le vrai rendu).
 */
object Echelle {
    const val LARGEUR_DP = 240

    fun contexte(base: Context): Context {
        val m = base.resources.displayMetrics
        val cote = min(m.widthPixels, m.heightPixels)
        if (cote <= 0) return base
        val dpi = (cote * 160 / LARGEUR_DP).coerceIn(80, 640)
        val config = Configuration(base.resources.configuration).apply {
            densityDpi = dpi
            fontScale = 1f                       // l'écran est trop petit pour suivre le réglage système
        }
        // Null dans le rendu de test (Paparazzi) : on garde alors le contexte tel quel.
        @Suppress("UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
        return base.createConfigurationContext(config) ?: base
    }
}
