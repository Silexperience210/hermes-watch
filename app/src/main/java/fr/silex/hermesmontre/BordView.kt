package fr.silex.hermesmontre

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Deux jauges qui épousent le BORD de l'écran (rectangle aux coins très arrondis) :
 *  - à gauche, la batterie de la montre (verte, orange sous 30 %, rouge sous 15 %, bleue en charge) ;
 *  - à droite, le quota Claude poussé par le pont (bleu, orange au-delà de 75 %, rouge au-delà de 90 %).
 * Chacune monte du milieu du bas vers le milieu du haut, en suivant l'arrondi des coins : elle
 * occupe la place que les coins rendent inutilisable pour du texte.
 */
class BordView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 0..100, ou -1 pour ne rien dessiner. */
    var batterie = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    var enCharge = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var quota = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Atténuée pendant la conversation : l'œil va au contenu. */
    var discret = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = resources.displayMetrics.density
    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3.5f * d
    }
    private val gauche = Path()
    private val droite = Path()
    private val morceau = Path()
    private var mesureG = PathMeasure()
    private var mesureD = PathMeasure()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val m = trait.strokeWidth / 2 + 1.5f * d
        val r = min(w, h) * RAYON
        val b = RectF(m, m, w - m, h - m)
        // Gauche : milieu du bas → coin bas gauche → côté gauche → coin haut gauche → milieu du haut.
        gauche.reset()
        gauche.moveTo(b.centerX(), b.bottom)
        gauche.lineTo(b.left + r, b.bottom)
        gauche.arcTo(RectF(b.left, b.bottom - 2 * r, b.left + 2 * r, b.bottom), 90f, 90f)
        gauche.lineTo(b.left, b.top + r)
        gauche.arcTo(RectF(b.left, b.top, b.left + 2 * r, b.top + 2 * r), 180f, 90f)
        gauche.lineTo(b.centerX() - LACUNE * w, b.top)
        // Droite : le miroir.
        droite.reset()
        droite.moveTo(b.centerX(), b.bottom)
        droite.lineTo(b.right - r, b.bottom)
        droite.arcTo(RectF(b.right - 2 * r, b.bottom - 2 * r, b.right, b.bottom), 90f, -90f)
        droite.lineTo(b.right, b.top + r)
        droite.arcTo(RectF(b.right - 2 * r, b.top, b.right, b.top + 2 * r), 0f, -90f)
        droite.lineTo(b.centerX() + LACUNE * w, b.top)
        mesureG = PathMeasure(gauche, false)
        mesureD = PathMeasure(droite, false)
    }

    override fun onDraw(c: Canvas) {
        val alpha = if (discret) 90 else 255
        if (batterie >= 0) jauge(c, mesureG, batterie / 100f, when {
            enCharge -> MascotteView.BLEU
            batterie < 15 -> MascotteView.ROUGE
            batterie < 30 -> MascotteView.ORANGE
            else -> MascotteView.VERT
        }, alpha)
        if (quota >= 0) jauge(c, mesureD, quota / 100f, when {
            quota >= 90 -> MascotteView.ROUGE
            quota >= 75 -> MascotteView.ORANGE
            else -> MascotteView.BLEU
        }, alpha)
    }

    private fun jauge(c: Canvas, mesure: PathMeasure, part: Float, couleur: Int, alpha: Int) {
        val longueur = mesure.length
        trait.color = MascotteView.DISCRET
        trait.alpha = alpha / 2
        morceau.reset(); mesure.getSegment(0f, longueur, morceau, true); c.drawPath(morceau, trait)
        trait.color = couleur
        trait.alpha = alpha
        morceau.reset(); mesure.getSegment(0f, longueur * part.coerceIn(0f, 1f), morceau, true); c.drawPath(morceau, trait)
    }

    companion object {
        /** Rayon des coins de l'écran, en fraction du petit côté (montres « Ultra » : très arrondis). */
        const val RAYON = 0.22f
        /** Les jauges s'arrêtent avant le milieu du haut : la barre ↺ · heure · ⚙ reste dégagée. */
        const val LACUNE = 0.30f
    }
}
