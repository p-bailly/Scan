package fr.perso.scan.imaging

import java.util.stream.IntStream
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
 * Calculs répartis sur tous les cœurs. Pur Kotlin, sans dépendance à Android : testé sur la JVM.
 */
object Enhancer {

    /** Seuil par défaut du noir et blanc : un pixel 25 % plus sombre que son papier devient noir. */
    const val DEFAULT_BW_THRESHOLD = 0.75f

    /**
     * Noir et blanc pur, comme un scanner de bureau, en [outWidth] × [outHeight] pixels.
     *
     * Une sortie plus grande que la photo est calculée directement à la bonne taille : le niveau de gris
     * est interpolé avant le seuillage, ce qui donne des bords de lettres lisses (et non des gros pixels).
     * [threshold] : rapport pixel / papier en dessous duquel le pixel est noir (plus haut = trait plus gras).
     * [pixels] est modifié (il sert de tableau de travail).
     */
    fun blackAndWhite(
        pixels: IntArray,
        width: Int,
        height: Int,
        outWidth: Int = width,
        outHeight: Int = height,
        threshold: Float = DEFAULT_BW_THRESHOLD,
    ): Bilevel {
        require(pixels.size >= width * height)
        // Les pixels deviennent leur niveau de gris (0–255) : pas de second tableau de 4 octets par pixel.
        val gray = pixels
        parallelRows(height) { from, to ->
            for (i in from * width until to * width) gray[i] = luminance(pixels[i])
        }
        val background = Background(gray, width, height, shift = 0)

        val xs = Axis(outWidth, width)
        val ys = Axis(outHeight, height)
        val gridXs = Axis(outWidth, width, background.step)
        val gridYs = Axis(outHeight, height, background.step)
        val ink = ByteArray(outWidth * outHeight)
        parallelRows(outHeight) { from, to ->
            val bgRow = FloatArray(outWidth)
            for (oy in from until to) {
                background.row(gridYs, oy, gridXs, bgRow)
                val top = ys.i0[oy] * width
                val bottom = ys.i1[oy] * width
                val ty = ys.t[oy]
                val offset = oy * outWidth
                for (ox in 0 until outWidth) {
                    val x0 = xs.i0[ox]
                    val x1 = xs.i1[ox]
                    val tx = xs.t[ox]
                    val upper = gray[top + x0] + (gray[top + x1] - gray[top + x0]) * tx
                    val lower = gray[bottom + x0] + (gray[bottom + x1] - gray[bottom + x0]) * tx
                    val value = upper + (lower - upper) * ty
                    if (value < threshold * bgRow[ox]) ink[offset + ox] = 1
                }
            }
        }

        // Taches plus petites qu'un point de ponctuation : grain du papier, bruit du capteur.
        val scale = outWidth.toFloat() / width
        removeSpecks(ink, outWidth, outHeight, maxSize = max(2, (2 * scale * scale).roundToInt()))

        val result = Bilevel(outWidth, outHeight)
        val rowBytes = result.rowBytes
        parallelRows(outHeight) { from, to ->
            for (y in from until to) {
                val offset = y * outWidth
                val rowStart = y * rowBytes
                for (x in 0 until outWidth) {
                    if (ink[offset + x].toInt() == 0) {
                        val i = rowStart + (x shr 3)
                        result.data[i] = (result.data[i].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
            }
        }
        return result
    }

    /**
     * Couleur nettoyée : papier blanc sans ombre ni dominante, encres et images en couleur,
     * un peu plus contrastées, saturées et nettes pour rester lisibles.
     */
    fun color(pixels: IntArray, width: Int, height: Int) {
        require(pixels.size >= width * height)
        val backgrounds = Array(3) { c -> Background(pixels, width, height, shift = 16 - 8 * c) }
        val xs = Axis(width, width, backgrounds[0].step)
        val ys = Axis(height, height, backgrounds[0].step)
        // Courbe : à partir de 90 % du papier c'est du blanc, à 12 % du noir, gamma pour foncer l'encre.
        val curve = IntArray(1025) { r ->
            val t = ((r / 1024f - 0.12f) / (0.90f - 0.12f)).coerceIn(0f, 1f)
            (255f * Math.pow(t.toDouble(), 1.25).toFloat()).roundToInt()
        }
        parallelRows(height) { from, to ->
            val rows = Array(3) { FloatArray(width) }
            for (y in from until to) {
                for (c in 0..2) backgrounds[c].row(ys, y, xs, rows[c])
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
        sharpen(pixels, width, height)
    }

    internal fun luminance(p: Int): Int =
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * Netteté (masque flou 3 × 3, intensité 60 %) : les photos de téléphone sont un peu douces,
     * le texte en ressort plus franc.
     */
    private fun sharpen(pixels: IntArray, width: Int, height: Int) {
        if (width < 3 || height < 3) return
        val source = pixels.copyOf(width * height)
        parallelRows(height) { from, to ->
            for (y in max(1, from) until min(height - 1, to)) {
                for (x in 1 until width - 1) {
                    val i = y * width + x
                    pixels[i] = (0xFF shl 24) or
                        (sharpened(source, i, width, 16) shl 16) or
                        (sharpened(source, i, width, 8) shl 8) or
                        sharpened(source, i, width, 0)
                }
            }
        }
    }

    /** Canal [shift] du pixel [i] renforcé : v + 0,6 × (v − moyenne 3 × 3), en entiers. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun sharpened(source: IntArray, i: Int, width: Int, shift: Int): Int {
        var sum = 0
        var row = i - width - 1
        for (dy in 0..2) {
            sum += ((source[row] shr shift) and 0xFF) + ((source[row + 1] shr shift) and 0xFF) +
                ((source[row + 2] shr shift) and 0xFF)
            row += width
        }
        val v = (source[i] shr shift) and 0xFF
        return clamp(v + (v * 9 - sum) * 6 / 90)
    }

    /**
     * Efface les taches d'encre de [maxSize] pixels ou moins (voisinage à 8).
     * Les vraies marques (lettres, points, virgules) sont plus grandes et restent.
     */
    private fun removeSpecks(ink: ByteArray, width: Int, height: Int, maxSize: Int) {
        // État de chaque pixel d'encre : 0 inconnu, ERASE tache à effacer, KEEP partie d'une vraie marque.
        val state = ByteArray(ink.size)
        parallelRows(height) { from, to ->
            val component = IntArray(maxSize + 1)
            for (y in from until to) {
                for (x in 0 until width) {
                    val start = y * width + x
                    if (ink[start].toInt() == 0 || state[start].toInt() != 0) continue
                    // Parcours limité : on s'arrête dès que la tache dépasse la taille maximale.
                    component[0] = start
                    var size = 1
                    var next = 0
                    while (next < size && size <= maxSize) {
                        val i = component[next++]
                        val cx = i % width
                        val cy = i / width
                        for (dy in -1..1) {
                            val yy = cy + dy
                            if (yy < 0 || yy >= height) continue
                            for (dx in -1..1) {
                                val xx = cx + dx
                                if (xx < 0 || xx >= width || size > maxSize) continue
                                val j = yy * width + xx
                                if (ink[j].toInt() == 0) continue
                                var seen = false
                                for (k in 0 until size) if (component[k] == j) { seen = true; break }
                                if (!seen) component[size++] = j
                            }
                        }
                    }
                    // Les pixels parcourus ne seront plus points de départ : coût borné par pixel d'encre.
                    val verdict = if (size <= maxSize) ERASE else KEEP
                    for (k in 0 until min(size, maxSize + 1)) state[component[k]] = verdict
                }
            }
        }
        IntStream.range(0, ink.size).parallel().forEach { if (state[it] == ERASE) ink[it] = 0 }
    }

    private const val ERASE: Byte = 1
    private const val KEEP: Byte = 2
}

/**
 * Valeur du papier en tout point d'un canal (octet de [values] décalé de [shift] bits), calculée sur une grille réduite
 * (~512 cases de long) puis interpolée :
 * 1. moyenne par blocs (lisse le bruit) ;
 * 2. maximum local : efface l'encre, plus fine que la fenêtre, pour ne garder que le papier ;
 * 3. flou : transitions douces entre zones éclairées et zones d'ombre.
 * Une grande zone sombre (photo, aplat) ne doit pas devenir « du papier » : la valeur
 * ne descend jamais sous 35 % de la luminosité générale du papier (une ombre est rarement plus sombre).
 */
internal class Background(values: IntArray, width: Int, height: Int, shift: Int) {
    /** Nombre de pixels source couverts par une case de la grille, dans chaque direction. */
    val step: Int = max(1, (max(width, height) + GRID - 1) / GRID)
    private val gw = (width + step - 1) / step
    private val gh = (height + step - 1) / step
    private val grid: FloatArray

    init {
        val means = FloatArray(gw * gh)
        parallelRows(gh) { from, to ->
            for (gy in from until to) {
                val counts = IntArray(gw)
                for (y in gy * step until min(height, (gy + 1) * step)) {
                    val offset = y * width
                    for (x in 0 until width) {
                        means[gy * gw + x / step] += ((values[offset + x] ushr shift) and 0xFF).toFloat()
                        counts[x / step]++
                    }
                }
                for (gx in 0 until gw) means[gy * gw + gx] /= counts[gx]
            }
        }

        val radius = max(2, max(gw, gh) / 25)
        var g = maxFilter(means, gw, gh, radius)
        g = boxBlur(g, gw, gh, radius)

        val paper = percentile(means, 0.95f)
        val floor = max(8f, paper * 0.35f)
        for (i in g.indices) if (g[i] < floor) g[i] = floor
        grid = g
    }

    /** Remplit [out] avec la valeur du papier pour chaque pixel de la ligne [y] d'une sortie décrite par [ys] et [xs]. */
    fun row(ys: Axis, y: Int, xs: Axis, out: FloatArray) {
        val top = ys.i0[y] * gw
        val bottom = ys.i1[y] * gw
        val ty = ys.t[y]
        for (x in out.indices) {
            val x0 = xs.i0[x]
            val x1 = xs.i1[x]
            val tx = xs.t[x]
            val upper = grid[top + x0] + (grid[top + x1] - grid[top + x0]) * tx
            val lower = grid[bottom + x0] + (grid[bottom + x1] - grid[bottom + x0]) * tx
            out[x] = upper + (lower - upper) * ty
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
