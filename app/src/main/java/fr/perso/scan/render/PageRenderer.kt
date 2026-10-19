package fr.perso.scan.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import fr.perso.scan.data.DocumentStore
import fr.perso.scan.data.ScanMode
import fr.perso.scan.data.ScanPage
import fr.perso.scan.imaging.Enhancer
import fr.perso.scan.pdf.PageGeometry
import fr.perso.scan.pdf.PageFormat
import fr.perso.scan.pdf.PdfWriter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Calcule le rendu des pages (mode, rotation) et écrit les PDF. */
class PageRenderer(private val store: DocumentStore) {

    /** Écrit le rendu et la miniature de [page] (opération longue : hors du thread principal). */
    fun render(documentId: String, page: ScanPage) {
        val blackAndWhite = page.mode == ScanMode.BLACK_WHITE
        // Le noir et blanc est calculé en haute définition (≈ 300 dpi pour une A4) même si la photo est
        // plus petite : les bords des lettres, interpolés, restent lisses une fois seuillés.
        var bitmap = decode(store.original(documentId, page.id), MAX_SIDE, upscale = blackAndWhite)
        if (page.rotation % 360 != 0) {
            val matrix = Matrix().apply { postRotate(page.rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }

        val result = if (page.mode == ScanMode.ORIGINAL) {
            bitmap
        } else {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            if (blackAndWhite) Enhancer.blackAndWhite(pixels, width, height) else Enhancer.color(pixels, width, height)
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        // Noir et blanc en PNG (sans perte, il sera remis en 1 bit dans le PDF), le reste en JPEG.
        writeAtomically(store.render(documentId, page)) {
            if (blackAndWhite) result.compress(Bitmap.CompressFormat.PNG, 100, it)
            else result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
        val thumbWidth = minOf(THUMB_WIDTH, result.width)
        val thumbHeight = max(1, (result.height.toFloat() * thumbWidth / result.width).roundToInt())
        val thumb = Bitmap.createScaledBitmap(result, thumbWidth, thumbHeight, true)
        writeAtomically(store.thumbnail(documentId, page)) { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (thumb !== result) thumb.recycle()
        result.recycle()
    }

    /** Écrit le PDF des [pages] (déjà rendues) dans [output]. */
    fun writePdf(documentId: String, title: String, pages: List<ScanPage>, format: PageFormat, output: OutputStream) {
        val writer = PdfWriter(output, title)
        for (page in pages) {
            val file = store.render(documentId, page)
            if (page.mode == ScanMode.BLACK_WHITE) {
                val bitmap = BitmapFactory.decodeFile(file.path) ?: throw IOException("Page illisible")
                val width = bitmap.width
                val image = PdfWriter.Image.BlackAndWhite(width, bitmap.height) { y, row ->
                    bitmap.getPixels(row, 0, width, 0, y, width, 1)
                }
                writer.addPage(image, PageGeometry.compute(format, width, bitmap.height))
                bitmap.recycle()
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0) throw IOException("Page illisible")
                writer.addPage(
                    PdfWriter.Image.Jpeg(file.readBytes(), bounds.outWidth, bounds.outHeight),
                    PageGeometry.compute(format, bounds.outWidth, bounds.outHeight),
                )
            }
        }
        writer.finish()
    }

    /**
     * Décode une image (orientation EXIF appliquée) avec son grand côté à [side] pixels :
     * réduite si elle est plus grande, agrandie seulement si [upscale].
     */
    private fun decode(file: File, side: Int, upscale: Boolean): Bitmap {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = max(info.size.width, info.size.height)
            // Réduction grossière dès le décodage pour les très grandes photos (mémoire),
            // en restant au-dessus de la taille visée.
            if (long >= side * 2) decoder.setTargetSampleSize(long / side)
        }
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded
        else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
        val long = max(bitmap.width, bitmap.height)
        if (long == side || (long < side && !upscale)) return bitmap
        val scale = side.toFloat() / long
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun writeAtomically(target: File, write: (OutputStream) -> Boolean) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        val ok = tmp.outputStream().buffered().use(write)
        if (!ok || !tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Écriture impossible : ${target.name}")
        }
    }

    private companion object {
        /** Grand côté du rendu : 3300 px ≈ 280 dpi sur une A4. */
        const val MAX_SIDE = 3300
        const val JPEG_QUALITY = 88
        const val THUMB_WIDTH = 480
    }
}
