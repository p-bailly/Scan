package fr.perso.scan.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import fr.perso.scan.data.DocumentStore
import fr.perso.scan.data.ScanMode
import fr.perso.scan.data.ScanPage
import fr.perso.scan.imaging.BilevelPng
import fr.perso.scan.imaging.Enhancer
import fr.perso.scan.imaging.JpegInfo
import fr.perso.scan.pdf.PageGeometry
import fr.perso.scan.pdf.PageFormat
import fr.perso.scan.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Calcule le rendu des pages (mode, rotation) et écrit les PDF. */
class PageRenderer(private val store: DocumentStore) {

    /** Écrit le rendu et la miniature de [page] (opération longue : hors du thread principal). */
    fun render(documentId: String, page: ScanPage) {
        val source = store.original(documentId, page.id)
        val target = store.render(documentId, page)

        // Original droit et déjà en JPEG : copié tel quel, sans recompression (qualité intacte, instantané).
        if (page.mode == ScanMode.ORIGINAL && page.rotation % 360 == 0 && isEmbeddableJpeg(source)) {
            writeAtomically(target) { out -> source.inputStream().use { it.copyTo(out) }; true }
            val preview = decode(source, THUMB_WIDTH * 2)
            writeThumbnail(documentId, page, preview)
            preview.recycle()
            return
        }

        var bitmap = decode(source, MAX_SIDE)
        if (page.rotation % 360 != 0) {
            val matrix = Matrix().apply { postRotate(page.rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        if (page.mode == ScanMode.ORIGINAL) {
            writeAtomically(target) { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            writeThumbnail(documentId, page, bitmap)
            bitmap.recycle()
            return
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        if (page.mode == ScanMode.BLACK_WHITE) {
            // Calculé en haute définition (≈ 280 dpi pour une A4) même si la photo est plus petite :
            // le gris est interpolé avant le seuillage, les bords des lettres restent lisses.
            val scale = max(1f, MAX_SIDE.toFloat() / max(width, height))
            val image = Enhancer.blackAndWhite(
                pixels, width, height,
                outWidth = max(1, (width * scale).roundToInt()),
                outHeight = max(1, (height * scale).roundToInt()),
            )
            // PNG 1 bit : quelques dizaines de Ko, inséré tel quel dans le PDF.
            writeAtomically(target) { BilevelPng.write(image, it); true }
            val thumb = image.downscale(THUMB_WIDTH)
            val preview = Bitmap.createBitmap(thumb.pixels, thumb.width, thumb.height, Bitmap.Config.ARGB_8888)
            writeThumbnail(documentId, page, preview)
            preview.recycle()
        } else {
            Enhancer.color(pixels, width, height)
            val result = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            writeAtomically(target) { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            writeThumbnail(documentId, page, result)
            result.recycle()
        }
    }

    /**
     * Écrit le PDF des [pages] (déjà rendues) dans [output]. Les rendus sont insérés tels quels
     * (PNG 1 bit, JPEG) : rien n'est décodé ni recompressé, c'est quasi instantané.
     */
    fun writePdf(documentId: String, title: String, pages: List<ScanPage>, format: PageFormat, output: OutputStream) {
        val writer = PdfWriter(output, title)
        for (page in pages) {
            val file = store.render(documentId, page)
            val bytes = file.readBytes()
            val png = if (page.mode == ScanMode.BLACK_WHITE) BilevelPng.read(bytes) else null
            val jpeg = if (png == null) JpegInfo.read(bytes) else null
            when {
                png != null -> writer.addPage(
                    PdfWriter.Image.PngBilevel(png.zlib, png.width, png.height),
                    PageGeometry.compute(format, png.width, png.height),
                )
                // Rendu noir et blanc d'une version précédente de l'appli (PNG couleur).
                page.mode == ScanMode.BLACK_WHITE -> {
                    val bitmap = BitmapFactory.decodeFile(file.path) ?: throw IOException("Page illisible")
                    val width = bitmap.width
                    val image = PdfWriter.Image.BlackAndWhite(width, bitmap.height) { y, row ->
                        bitmap.getPixels(row, 0, width, 0, y, width, 1)
                    }
                    writer.addPage(image, PageGeometry.compute(format, width, bitmap.height))
                    bitmap.recycle()
                }
                jpeg != null && (jpeg.components == 1 || jpeg.components == 3) -> writer.addPage(
                    PdfWriter.Image.Jpeg(bytes, jpeg.width, jpeg.height, jpeg.components),
                    PageGeometry.compute(format, jpeg.width, jpeg.height),
                )
                // Autre format (JPEG CMJN…) : réencodé en JPEG couleur.
                else -> {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Page illisible")
                    val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    writer.addPage(
                        PdfWriter.Image.Jpeg(encoded.toByteArray(), bitmap.width, bitmap.height),
                        PageGeometry.compute(format, bitmap.width, bitmap.height),
                    )
                    bitmap.recycle()
                }
            }
        }
        writer.finish()
    }

    private fun writeThumbnail(documentId: String, page: ScanPage, image: Bitmap) {
        val width = minOf(THUMB_WIDTH, image.width)
        val height = max(1, (image.height.toFloat() * width / image.width).roundToInt())
        val thumb = Bitmap.createScaledBitmap(image, width, height, true)
        writeAtomically(store.thumbnail(documentId, page)) { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (thumb !== image) thumb.recycle()
    }

    /** JPEG insérable tel quel dans un PDF : RVB ou gris, sans rotation EXIF, de taille raisonnable. */
    private fun isEmbeddableJpeg(file: File): Boolean {
        val info = runCatching { JpegInfo.read(file.readBytes()) }.getOrNull() ?: return false
        if (info.components != 1 && info.components != 3) return false
        if (max(info.width, info.height) > MAX_COPY_SIDE) return false
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
        return orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED
    }

    /** Décode une image (orientation EXIF appliquée), réduite si son grand côté dépasse [side] pixels. */
    private fun decode(file: File, side: Int): Bitmap {
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
        if (long <= side) return bitmap
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

        /** Au-delà, l'original est réduit plutôt que copié (PDF trop lourd). */
        const val MAX_COPY_SIDE = 4200
        const val JPEG_QUALITY = 90
        const val THUMB_WIDTH = 480
    }
}
