package fr.perso.scan.pdf

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Écrit un PDF dont chaque page est une image, comme un PDF de scanner :
 * - pages couleur : le JPEG est inséré tel quel (DCTDecode), sans recompression ;
 * - pages noir et blanc : 1 bit par pixel compressé (FlateDecode), net et très léger ; un PNG 1 bit
 *   est inséré tel quel, sans décodage.
 *
 * Les pages sont écrites au fur et à mesure : une seule image en mémoire à la fois.
 * Pur Kotlin (sans Android), testé sur la JVM.
 */
class PdfWriter(output: OutputStream, private val title: String) {

    /** Image d'une page. */
    sealed class Image(val width: Int, val height: Int) {
        /** Fichier JPEG complet (RVB ou niveaux de gris selon [components]). */
        class Jpeg(val bytes: ByteArray, width: Int, height: Int, val components: Int = 3) : Image(width, height)

        /**
         * Noir et blanc déjà compressé : données zlib d'un PNG 1 bit (voir BilevelPng), insérées telles quelles.
         */
        class PngBilevel(val zlib: ByteArray, width: Int, height: Int) : Image(width, height)

        /**
         * Noir et blanc : [readRow] remplit les pixels ARGB de la ligne demandée ;
         * un pixel plus sombre que le gris moyen devient noir.
         */
        class BlackAndWhite(width: Int, height: Int, val readRow: (y: Int, pixels: IntArray) -> Unit) : Image(width, height)
    }

    private val out = CountingStream(output)
    private val offsets = mutableMapOf<Int, Long>()
    private val pageRefs = mutableListOf<Int>()
    private var nextObject = FIRST_FREE_OBJECT
    private var closed = false

    init {
        // Commentaire binaire : signale aux outils que le fichier contient des octets non ASCII.
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
    }

    /** Ajoute une page de [geometry] (en points) qui affiche [image]. */
    fun addPage(image: Image, geometry: PageGeometry) {
        check(!closed) { "PDF déjà terminé" }
        val imageObj = nextObject++
        val contentObj = nextObject++
        val pageObj = nextObject++

        when (image) {
            is Image.Jpeg -> writeStream(
                imageObj,
                "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                    "/ColorSpace ${if (image.components == 1) "/DeviceGray" else "/DeviceRGB"} " +
                    "/BitsPerComponent 8 /Filter /DCTDecode",
                image.bytes,
            )
            // Lignes PNG (un octet de filtre en tête de chaque ligne) : prédicteur PNG du PDF.
            is Image.PngBilevel -> writeStream(
                imageObj,
                "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode " +
                    "/DecodeParms << /Predictor 15 /Colors 1 /BitsPerComponent 1 /Columns ${image.width} >>",
                image.zlib,
            )
            is Image.BlackAndWhite -> writeStream(
                imageObj,
                "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode",
                packBits(image),
            )
        }

        val content = "q ${num(geometry.imageWidth)} 0 0 ${num(geometry.imageHeight)} " +
            "${num(geometry.imageX)} ${num(geometry.imageY)} cm /Im0 Do Q\n"
        writeStream(contentObj, "", content.toByteArray(Charsets.ISO_8859_1))

        writeObject(
            pageObj,
            "<< /Type /Page /Parent $PAGES_OBJ 0 R " +
                "/MediaBox [0 0 ${num(geometry.pageWidth)} ${num(geometry.pageHeight)}] " +
                "/Resources << /XObject << /Im0 $imageObj 0 R >> >> " +
                "/Contents $contentObj 0 R >>",
        )
        pageRefs += pageObj
    }

    /** Termine le fichier (arborescence des pages, informations, table xref). Ne ferme pas le flux. */
    fun finish() {
        check(!closed) { "PDF déjà terminé" }
        check(pageRefs.isNotEmpty()) { "Un PDF doit avoir au moins une page" }
        closed = true
        writeObject(
            PAGES_OBJ,
            "<< /Type /Pages /Kids [${pageRefs.joinToString(" ") { "$it 0 R" }}] /Count ${pageRefs.size} >>",
        )
        writeObject(CATALOG_OBJ, "<< /Type /Catalog /Pages $PAGES_OBJ 0 R >>")
        val date = "D:" + SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date())
        writeObject(
            INFO_OBJ,
            "<< /Title ${pdfText(title)} /Producer (Scan) /Creator (Scan) " +
                "/CreationDate ($date) /ModDate ($date) >>",
        )

        val xrefOffset = out.count
        val xref = StringBuilder()
        xref.append("xref\n0 $nextObject\n")
        xref.append("0000000000 65535 f \n")
        for (obj in 1 until nextObject) {
            val offset = offsets.getValue(obj)
            xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
        }
        xref.append("trailer\n<< /Size $nextObject /Root $CATALOG_OBJ 0 R /Info $INFO_OBJ 0 R >>\n")
        xref.append("startxref\n$xrefOffset\n%%EOF\n")
        out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun writeObject(number: Int, body: String) {
        offsets[number] = out.count
        out.write("$number 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeStream(number: Int, dictionary: String, data: ByteArray) {
        offsets[number] = out.count
        val entries = if (dictionary.isEmpty()) "/Length ${data.size}" else "$dictionary /Length ${data.size}"
        out.write("$number 0 obj\n<< $entries >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
    }

    /** Lignes de 1 bit par pixel (1 = blanc en DeviceGray), complétées à l'octet, puis compressées. */
    private fun packBits(image: Image.BlackAndWhite): ByteArray {
        val rowBytes = (image.width + 7) / 8
        val buffer = ByteArrayOutputStream(rowBytes * image.height / 8 + 1024)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        DeflaterOutputStream(buffer, deflater, 64 * 1024).use { stream ->
            val pixels = IntArray(image.width)
            val row = ByteArray(rowBytes)
            for (y in 0 until image.height) {
                image.readRow(y, pixels)
                row.fill(0)
                for (x in 0 until image.width) {
                    val p = pixels[x]
                    val luminance = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    if (luminance >= 128) {
                        row[x shr 3] = (row[x shr 3].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
                stream.write(row)
            }
        }
        deflater.end()
        return buffer.toByteArray()
    }

    private class CountingStream(private val target: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()
    }

    internal companion object {
        const val CATALOG_OBJ = 1
        const val PAGES_OBJ = 2
        const val INFO_OBJ = 3
        const val FIRST_FREE_OBJECT = 4

        /** Nombre lisible par un lecteur PDF : au plus 2 décimales, point décimal. */
        fun num(value: Float): String {
            val rounded = Math.round(value * 100) / 100.0
            return if (rounded == Math.floor(rounded)) rounded.toLong().toString()
            else String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0')
        }

        /** Chaîne PDF : ASCII simple entre parenthèses, sinon UTF-16BE en hexadécimal. */
        fun pdfText(text: String): String {
            if (text.all { it.code in 32..126 }) {
                return "(" + text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
            }
            val bytes = text.toByteArray(Charsets.UTF_16BE)
            return "<FEFF" + bytes.joinToString("") { String.format(Locale.ROOT, "%02X", it) } + ">"
        }
    }
}
