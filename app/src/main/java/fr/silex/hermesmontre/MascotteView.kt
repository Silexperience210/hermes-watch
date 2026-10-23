package fr.silex.hermesmontre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * La mascotte d'Hermes Agent, dessinée comme le firmware Hermes Gadget la dessine (firmware/core/src/ui.cpp) :
 * trois images 1 bit (repos, clignement, parole) agrandies sans lissage, un clignement toutes les
 * ~4,5 s, et les mêmes effets aux mêmes endroits :
 *  - écoute : ondes VERTES qui partent de l'écouteur, plus nombreuses quand la voix est forte ;
 *  - réflexion : trois points BLEUS qui s'allument tour à tour au-dessus de la tête ;
 *  - réponse : la bouche bouge et des ondes ORANGE partent de la bouche.
 *
 * Image : la mascotte d'Hermes Agent (licence MIT, Copyright (c) 2025 Nous Research), exportée du
 * SDK Hermes Gadget avec la même géométrie que le firmware.
 */
class MascotteView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var phase = HermesService.Phase.VEILLE
        set(v) { if (field != v) { field = v; invalidate() } }
    var enLigne = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Niveau 0..1 du micro : plus la voix est forte, plus d'ondes s'allument. */
    var niveau = 0f
    /** Sans ondes ni points : juste le portrait (mode cadran). */
    var sansEffets = false
    /** Pour les captures : une image d'animation fixe (≥ 0) au lieu de l'horloge. */
    var imageFixe = -1

    private val repos = charger(R.drawable.mascotte_idle)
    private val clin = charger(R.drawable.mascotte_blink)
    private val parole = charger(R.drawable.mascotte_talk)

    // Sans lissage : l'image garde ses pixels nets, comme sur l'écran de la carte.
    private val pinceauImage = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val pinceauHorsLigne = Paint().apply {
        isFilterBitmap = false
        colorFilter = android.graphics.PorterDuffColorFilter(Color.rgb(84, 94, 108), android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val plein = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val source = Rect()
    private val cible = Rect()
    private val boite = RectF()

    override fun onDraw(canvas: Canvas) {
        // Le firmware avance d'une image toutes les 100 ms : on garde la même horloge.
        val image = if (imageFixe >= 0) imageFixe else (System.currentTimeMillis() / 100).toInt()
        // Un peu de marge en haut à droite : les points de réflexion dépassent du dessin.
        val taille = (min(width, height) * 0.9f).toInt()
        if (taille <= 0) return
        val mx = (width - taille) / 2
        val my = height - taille

        val bitmap = when {
            phase == HermesService.Phase.REPONSE -> if ((image / 2) % 2 == 1) parole else repos
            image % 45 < 2 -> clin                              // un clignement toutes les ~4,5 s
            else -> repos
        }
        source.set(0, 0, bitmap.width, bitmap.height)
        cible.set(mx, my, mx + taille, my + taille)
        canvas.drawBitmap(bitmap, source, cible, if (enLigne) pinceauImage else pinceauHorsLigne)

        val epaisseur = max(2f, taille / 48f)
        if (!sansEffets) when (phase) {
            HermesService.Phase.ECOUTE -> {
                val allumees = 1 + min(2, (niveau * 100).toInt() / 34)
                ondes(canvas, mx + taille * OREILLE_X, my + taille * OREILLE_Y, +1, allumees, image % 3,
                    VERT, VERT_SOMBRE, 90, taille, epaisseur)
            }
            HermesService.Phase.REPONSE -> {
                // Les ondes partent hors du visage : on lit du son, pas des traits sur la joue.
                ondes(canvas, mx + taille * BOUCHE_X, my + taille * BOUCHE_Y, -1, 3, (image / 2) % 3,
                    ORANGE, ORANGE_SOMBRE, 130, taille, epaisseur)
            }
            HermesService.Phase.ENVOI, HermesService.Phase.REFLEXION ->
                points(canvas, mx + taille * TETE_X, my + taille * TETE_Y, taille, image, BLEU, BLEU_SOMBRE)
            HermesService.Phase.VEILLE -> if (!enLigne) {
                points(canvas, mx + taille * TETE_X, my + taille * TETE_Y, taille, image, ORANGE, ORANGE_SOMBRE)
            }
        }
        // 10 images par seconde, comme la carte : assez pour l'animation, sobre pour la batterie.
        postInvalidateDelayed(100)
    }

    /** Trois arcs de ±55°, tournés vers la droite (+1) ou la gauche (-1) : `Canvas::arc` du firmware. */
    private fun ondes(c: Canvas, cx: Float, cy: Float, sens: Int, allumees: Int, temps: Int,
                      vif: Int, sombre: Int, r0: Int, taille: Int, epaisseur: Float) {
        trait.strokeWidth = epaisseur
        for (i in 0 until 3) {
            val r = taille * (r0 + 60 * i) / 1000f - epaisseur / 2
            trait.color = if (i >= allumees) DISCRET else if (i == temps) vif else sombre
            boite.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(boite, if (sens > 0) -55f else 125f, 110f, false, trait)
        }
    }

    /** Trois points en diagonale montante au coin de la tête ; un seul allumé à la fois. */
    private fun points(c: Canvas, tx: Float, ty: Float, taille: Int, image: Int, vif: Int, sombre: Int) {
        val r = max(2f, taille / 30f)
        val ecart = 3 * r
        val actif = (image / 2) % 4
        for (i in 0 until 3) {
            plein.color = if (i == actif) vif else sombre
            c.drawCircle(tx + i * ecart, ty - i * ecart / 2, r, plein)
        }
    }

    private fun charger(id: Int): Bitmap =
        BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })

    companion object {
        // Ancres du firmware (millièmes de la taille dessinée) : écouteur, bouche, coin de la tête.
        private const val OREILLE_X = 0.607f
        private const val OREILLE_Y = 0.293f
        private const val BOUCHE_X = 0.262f
        private const val BOUCHE_Y = 0.620f
        private const val TETE_X = 0.801f
        private const val TETE_Y = 0.107f

        // Couleurs du firmware (ui.cpp).
        val VERT = Color.rgb(61, 214, 140)
        val VERT_SOMBRE = Color.rgb(28, 92, 62)
        val BLEU = Color.rgb(90, 169, 242)
        val BLEU_SOMBRE = Color.rgb(52, 96, 140)
        val ORANGE = Color.rgb(242, 179, 61)
        val ORANGE_SOMBRE = Color.rgb(110, 82, 30)
        val ROUGE = Color.rgb(242, 95, 92)
        val TEXTE = Color.rgb(232, 238, 242)
        val ATTENUE = Color.rgb(132, 146, 160)
        val DISCRET = Color.rgb(52, 62, 76)
        val FOND = Color.rgb(10, 14, 20)
    }
}
