package fr.perso.scan.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import fr.perso.scan.imaging.PageDetector
import fr.perso.scan.imaging.Point
import fr.perso.scan.imaging.Quad
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photos du mode rafale : la page est détectée, recadrée et redressée (perspective corrigée, proportions
 * réelles), comme le fait le scanner de Google. Sans page détectée, la photo est gardée entière.
 */
object PageCropper {
    /** Grand côté maximal de la page enregistrée (≈ 340 dpi sur une A4). */
    private const val MAX_SIDE = 4000

    /** Grand côté de l'image où la page est cherchée (les coins sont ensuite affinés à cette échelle). */
    private const val DETECT_SIDE = 1024

    private const val JPEG_QUALITY = 92

    /** Écrit la page de la photo [source] dans [target] (JPEG). Renvoie false si aucune page n'a été trouvée. */
    fun crop(source: File, target: File): Boolean {
        val photo = decodeBitmap(source, MAX_SIDE)
        var page = photo
        try {
            val quad = detect(photo)
            if (quad != null) page = straighten(photo, quad)
            target.outputStream().buffered().use { page.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            return quad != null
        } finally {
            if (page !== photo) page.recycle()
            photo.recycle()
        }
    }

    /** Contour de la page dans [photo] (pixels de la photo), ou null. */
    fun detect(photo: Bitmap): Quad? {
        val scale = min(1f, DETECT_SIDE.toFloat() / max(photo.width, photo.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(photo, max(1, (photo.width * scale).roundToInt()), max(1, (photo.height * scale).roundToInt()), true)
        } else {
            photo
        }
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val quad = PageDetector.detect(PageDetector.gray(pixels), small.width, small.height)
        val sx = photo.width.toFloat() / small.width
        val sy = photo.height.toFloat() / small.height
        if (small !== photo) small.recycle()
        return quad?.map { Point(it.x * sx, it.y * sy) }
    }

    /** La page [quad] de [photo], redressée en rectangle. */
    fun straighten(photo: Bitmap, quad: Quad): Bitmap {
        val size = quad.pageSize(photo.width, photo.height, MAX_SIDE)
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val page = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val corners = quad.corners.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val matrix = Matrix().apply { setPolyToPoly(corners, 0, floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height), 0, 4) }
        Canvas(page).apply {
            drawColor(Color.WHITE)
            drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return page
    }
}
