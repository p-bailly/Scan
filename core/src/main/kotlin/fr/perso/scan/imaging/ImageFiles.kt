package fr.perso.scan.imaging

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * PNG noir et blanc 1 bit : quelques dizaines de Ko par page, lisible par toutes les visionneuses,
 * et dont les données compressées s'insèrent telles quelles dans un PDF (sans décodage ni recompression).
 */
object BilevelPng {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** Données d'un PNG 1 bit en niveaux de gris : [zlib] est la concaténation de ses blocs IDAT. */
    class Data(val width: Int, val height: Int, val zlib: ByteArray)

    fun write(image: Bilevel, output: OutputStream) {
        // Chaque ligne commence par l'octet de filtre PNG 0 (« aucun »).
        val compressed = ByteArrayOutputStream(image.data.size / 8 + 1024)
        val deflater = Deflater(6)
        DeflaterOutputStream(compressed, deflater, 64 * 1024).use { zlib ->
            for (y in 0 until image.height) {
                zlib.write(0)
                zlib.write(image.data, y * image.rowBytes, image.rowBytes)
            }
        }
        deflater.end()

        val out = DataOutputStream(output)
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream(13)
        DataOutputStream(header).apply {
            writeInt(image.width)
            writeInt(image.height)
            writeByte(1) // 1 bit par pixel
            writeByte(0) // niveaux de gris
            writeByte(0) // compression deflate
            writeByte(0) // filtres standard
            writeByte(0) // non entrelacé
        }
        chunk(out, "IHDR", header.toByteArray())
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        out.flush()
    }

    /** Lit un PNG 1 bit en niveaux de gris non entrelacé ; null pour tout autre fichier. */
    fun read(bytes: ByteArray): Data? {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) return null
        var width = 0
        var height = 0
        val zlib = ByteArrayOutputStream(bytes.size)
        var i = 8
        while (i + 8 <= bytes.size) {
            val length = int(bytes, i)
            val type = String(bytes, i + 4, 4, Charsets.ISO_8859_1)
            val start = i + 8
            if (length < 0 || start + length > bytes.size) return null
            when (type) {
                "IHDR" -> {
                    width = int(bytes, start)
                    height = int(bytes, start + 4)
                    val bitDepth = bytes[start + 8].toInt()
                    val colorType = bytes[start + 9].toInt()
                    val interlace = bytes[start + 12].toInt()
                    if (bitDepth != 1 || colorType != 0 || interlace != 0) return null
                }
                "IDAT" -> zlib.write(bytes, start, length)
                "IEND" -> break
            }
            i = start + length + 4 // données + CRC
        }
        if (width <= 0 || height <= 0 || zlib.size() == 0) return null
        return Data(width, height, zlib.toByteArray())
    }

    private fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }

    private fun int(bytes: ByteArray, i: Int): Int =
        ((bytes[i].toInt() and 0xFF) shl 24) or ((bytes[i + 1].toInt() and 0xFF) shl 16) or
            ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
}

/** En-tête d'un fichier JPEG : dimensions et nombre de composantes (1 gris, 3 couleur, 4 CMJN). */
data class JpegInfo(val width: Int, val height: Int, val components: Int) {
    companion object {
        /** Lit l'en-tête ; null si ce n'est pas un JPEG valide. */
        fun read(bytes: ByteArray): JpegInfo? {
            if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
            var i = 2
            while (i + 4 <= bytes.size) {
                if (bytes[i] != 0xFF.toByte()) return null
                val marker = bytes[i + 1].toInt() and 0xFF
                when {
                    marker == 0xFF -> { i++; continue } // octet de remplissage
                    marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> { i += 2; continue }
                    marker == 0xD9 || marker == 0xDA -> return null // fin ou données avant l'en-tête de trame
                }
                val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                // En-têtes de trame (SOF) : tous les C0–CF sauf C4 (Huffman), C8 (réservé) et CC (arithmétique).
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    if (i + 10 > bytes.size) return null
                    val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                    val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                    val components = bytes[i + 9].toInt() and 0xFF
                    return if (width > 0 && height > 0) JpegInfo(width, height, components) else null
                }
                i += 2 + length
            }
            return null
        }
    }
}
