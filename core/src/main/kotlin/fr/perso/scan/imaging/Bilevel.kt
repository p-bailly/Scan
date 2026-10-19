package fr.perso.scan.imaging

import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Image noir et blanc, 1 bit par pixel, lignes complétées à l'octet ; bit à 1 = blanc.
 * C'est exactement la disposition d'un PNG 1 bit en niveaux de gris et d'une image PDF DeviceGray 1 bit.
 */
class Bilevel(val width: Int, val height: Int, val data: ByteArray = ByteArray((width + 7) / 8 * height)) {
    val rowBytes: Int = (width + 7) / 8

    init {
        require(data.size == rowBytes * height)
    }

    fun isBlack(x: Int, y: Int): Boolean = (data[y * rowBytes + (x shr 3)].toInt() and (0x80 ushr (x and 7))) == 0

    /**
     * Version réduite en niveaux de gris (pixels ARGB) de [targetWidth] pixels de large : chaque pixel est
     * la moyenne du bloc d'origine, d'où des contours lissés pour les miniatures.
     */
    fun downscale(targetWidth: Int): Thumbnail {
        val tw = targetWidth.coerceIn(1, width)
        val th = max(1, (height.toLong() * tw / width).toInt())
        val pixels = IntArray(tw * th)
        parallelRows(th) { from, to ->
            for (ty in from until to) {
                val y0 = ty * height / th
                val y1 = max(y0 + 1, (ty + 1) * height / th)
                for (tx in 0 until tw) {
                    val x0 = tx * width / tw
                    val x1 = max(x0 + 1, (tx + 1) * width / tw)
                    var white = 0
                    for (y in y0 until y1) for (x in x0 until x1) if (!isBlack(x, y)) white++
                    val v = white * 255 / ((y1 - y0) * (x1 - x0))
                    pixels[ty * tw + tx] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }
        return Thumbnail(tw, th, pixels)
    }

    class Thumbnail(val width: Int, val height: Int, val pixels: IntArray)
}

/** Exécute [body] sur des tranches de lignes [from, to) en parallèle, sur tous les cœurs. */
internal inline fun parallelRows(height: Int, crossinline body: (from: Int, to: Int) -> Unit) {
    if (height <= 0) return
    val bands = min(height, Runtime.getRuntime().availableProcessors() * 4)
    IntStream.range(0, bands).parallel().forEach { band ->
        body(height * band / bands, height * (band + 1) / bands)
    }
}

/**
 * Correspondance entre les colonnes (ou lignes) d'une image de sortie de taille [dst] et une image
 * source de taille [src] : pour chaque pixel de sortie, les deux pixels source voisins et le poids
 * de l'interpolation bilinéaire. [step] > 1 : correspondance avec une grille dont une case couvre
 * [step] pixels source.
 */
internal class Axis(dst: Int, src: Int, step: Int = 1) {
    val i0 = IntArray(dst)
    val i1 = IntArray(dst)
    val t = FloatArray(dst)

    init {
        val cells = (src + step - 1) / step
        for (d in 0 until dst) {
            // Centre du pixel de sortie, en pixels source, puis en cases de la grille.
            val position = ((d + 0.5f) * src / dst / step - 0.5f).coerceIn(0f, (cells - 1).toFloat())
            val lower = position.toInt()
            i0[d] = lower
            i1[d] = min(cells - 1, lower + 1)
            t[d] = position - lower
        }
    }

    companion object {
        /** Taille de sortie de [size] mis à l'échelle [scale] (au moins 1). */
        fun scaled(size: Int, scale: Float) = max(1, (size * scale).roundToInt())
    }
}
