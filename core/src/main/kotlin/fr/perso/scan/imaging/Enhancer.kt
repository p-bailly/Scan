package fr.perso.scan.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Traitements « scanner » appliqués aux pixels d'une page (ARGB, une ligne après l'autre).
 *
 * Les deux modes reposent sur la même idée : estimer la couleur du papier en tout point
 * (ombres, éclairage inégal, papier jauni) puis diviser chaque pixel par cette valeur.
 * Le papier devient blanc partout et l'encre garde son contraste, quelle que soit la lumière.
 *
 * Pur Kotlin, sans dépendance à Android : testé sur la JVM.
 */
object Enhancer {

    /** Seuil par défaut du noir et blanc : un pixel 25 % plus sombre que son papier devient noir. */
    const val DEFAULT_BW_THRESHOLD = 0.75f

    /**
     * Noir et blanc pur (chaque pixel devient 0xFF000000 ou 0xFFFFFFFF), comme un scanner de bureau.
     * [threshold] : rapport pixel / papier en dessous duquel le pixel est noir (plus haut = trait plus gras).
     */
    fun blackAndWhite(pixels: IntArray, width: Int, height: Int, threshold: Float = DEFAULT_BW_THRESHOLD) {
        require(pixels.size >= width * height)
        // Les pixels deviennent leur niveau de gris (0–255) : pas de second tableau de 4 octets par pixel.
        val gray = pixels
        for (i in 0 until width * height) gray[i] = luminance(pixels[i])

        val background = Background(gray, width, height, shift = 0)
        // Rapport en entier (×1024) pour la boucle principale.
        val limit = (threshold * 1024).roundToInt()
        val ink = ByteArray(width * height)
        val bgRow = FloatArray(width)
        for (y in 0 until height) {
            background.row(y, bgRow)
            val offset = y * width
            for (x in 0 until width) {
                val ratio = (gray[offset + x] * 1024 / bgRow[x]).toInt()
                if (ratio < limit) ink[offset + x] = 1
            }
        }
        despeckle(ink, width, height)
        for (i in 0 until width * height) {
            pixels[i] = if (ink[i].toInt() == 1) BLACK else WHITE
        }
    }

    /**
     * Couleur nettoyée : papier blanc sans ombre ni dominante, encres et images en couleur,
     * un peu plus contrastées et saturées pour rester lisibles.
     */
    fun color(pixels: IntArray, width: Int, height: Int) {
        require(pixels.size >= width * height)
        val backgrounds = Array(3) { c -> Background(pixels, width, height, shift = 16 - 8 * c) }
        val rows = Array(3) { FloatArray(width) }
        // Courbe : à partir de 90 % du papier c'est du blanc, à 12 % du noir, gamma pour foncer l'encre.
        val curve = IntArray(1025) { r ->
            val t = ((r / 1024f - 0.12f) / (0.90f - 0.12f)).coerceIn(0f, 1f)
            (255f * Math.pow(t.toDouble(), 1.25).toFloat()).roundToInt()
        }
        for (y in 0 until height) {
            for (c in 0..2) backgrounds[c].row(y, rows[c])
            val offset = y * width
            for (x in 0 until width) {
                val p = pixels[offset + x]
                var r = curve[min(1024, (((p shr 16) and 0xFF) * 1024 / rows[0][x]).toInt())]
                var g = curve[min(1024, (((p shr 8) and 0xFF) * 1024 / rows[1][x]).toInt())]
                var b = curve[min(1024, ((p and 0xFF) * 1024 / rows[2][x]).toInt())]
                // Saturation +25 % autour de la luminance, pour raviver les encres pâles.
                val l = (r * 299 + g * 587 + b * 114) / 1000
                r = clamp(l + (r - l) * 5 / 4)
                g = clamp(l + (g - l) * 5 / 4)
                b = clamp(l + (b - l) * 5 / 4)
                pixels[offset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    internal fun luminance(p: Int): Int =
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /** Supprime les points noirs isolés (grain du papier, bruit du capteur). */
    private fun despeckle(ink: ByteArray, width: Int, height: Int) {
        val source = ink.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (source[i].toInt() == 0) continue
                var neighbours = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= height) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if ((dx != 0 || dy != 0) && xx in 0 until width && source[yy * width + xx].toInt() == 1) {
                            neighbours++
                        }
                    }
                }
                if (neighbours == 0) ink[i] = 0
            }
        }
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}

/**
 * Valeur du papier en tout point d'un canal (octet de [values] décalé de [shift] bits), calculée sur une grille réduite
 * (~512 px de long) puis interpolée :
 * 1. moyenne par blocs (lisse le bruit) ;
 * 2. maximum local : efface l'encre, plus fine que la fenêtre, pour ne garder que le papier ;
 * 3. flou : transitions douces entre zones éclairées et zones d'ombre.
 * Une grande zone sombre (photo, aplat) ne doit pas devenir « du papier » : la valeur
 * ne descend jamais sous 35 % de la luminosité générale du papier (une ombre est rarement plus sombre).
 */
internal class Background(values: IntArray, private val width: Int, private val height: Int, shift: Int) {
    private val step: Int = max(1, (max(width, height) + GRID - 1) / GRID)
    private val gw = (width + step - 1) / step
    private val gh = (height + step - 1) / step
    private val grid: FloatArray

    init {
        val means = FloatArray(gw * gh)
        val counts = IntArray(gw * gh)
        for (y in 0 until height) {
            val gy = (y / step) * gw
            val offset = y * width
            for (x in 0 until width) {
                val g = gy + x / step
                means[g] += ((values[offset + x] ushr shift) and 0xFF).toFloat()
                counts[g]++
            }
        }
        for (i in means.indices) means[i] /= counts[i]

        val radius = max(2, max(gw, gh) / 25)
        var g = maxFilter(means, gw, gh, radius)
        g = boxBlur(g, gw, gh, radius)

        val paper = percentile(means, 0.95f)
        val floor = max(8f, paper * 0.35f)
        for (i in g.indices) if (g[i] < floor) g[i] = floor
        grid = g
    }

    /** Remplit [out] avec la valeur du papier pour chaque pixel de la ligne [y]. */
    fun row(y: Int, out: FloatArray) {
        val fy = ((y + 0.5f) / step - 0.5f).coerceIn(0f, (gh - 1).toFloat())
        val y0 = fy.toInt()
        val y1 = min(gh - 1, y0 + 1)
        val ty = fy - y0
        for (x in 0 until width) {
            val fx = ((x + 0.5f) / step - 0.5f).coerceIn(0f, (gw - 1).toFloat())
            val x0 = fx.toInt()
            val x1 = min(gw - 1, x0 + 1)
            val tx = fx - x0
            val top = grid[y0 * gw + x0] * (1 - tx) + grid[y0 * gw + x1] * tx
            val bottom = grid[y1 * gw + x0] * (1 - tx) + grid[y1 * gw + x1] * tx
            out[x] = top * (1 - ty) + bottom * ty
        }
    }

    private companion object {
        const val GRID = 512

        fun maxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            val tmp = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var m = 0f
                for (xx in max(0, x - r)..min(w - 1, x + r)) m = max(m, src[y * w + xx])
                tmp[y * w + x] = m
            }
            val out = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var m = 0f
                for (yy in max(0, y - r)..min(h - 1, y + r)) m = max(m, tmp[yy * w + x])
                out[y * w + x] = m
            }
            return out
        }

        fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            val tmp = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                var n = 0
                for (xx in max(0, x - r)..min(w - 1, x + r)) { s += src[y * w + xx]; n++ }
                tmp[y * w + x] = s / n
            }
            val out = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                var n = 0
                for (yy in max(0, y - r)..min(h - 1, y + r)) { s += tmp[yy * w + x]; n++ }
                out[y * w + x] = s / n
            }
            return out
        }

        fun percentile(values: FloatArray, p: Float): Float {
            val sorted = values.copyOf()
            sorted.sort()
            return sorted[((sorted.size - 1) * p).roundToInt()]
        }
    }
}
