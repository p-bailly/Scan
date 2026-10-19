package fr.perso.scan.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageFilesTest {

    @Test
    fun `PNG 1 bit lisible par une vraie bibliotheque, puis relu`() {
        val image = Bilevel(13, 5)
        // Damier : noir quand x + y est pair.
        for (y in 0 until 5) for (x in 0 until 13) {
            if ((x + y) % 2 == 1) {
                val i = y * image.rowBytes + x / 8
                image.data[i] = (image.data[i].toInt() or (0x80 ushr (x % 8))).toByte()
            }
        }
        val bytes = ByteArrayOutputStream().also { BilevelPng.write(image, it) }.toByteArray()

        val decoded = ImageIO.read(bytes.inputStream())
        assertEquals(13, decoded.width)
        assertEquals(5, decoded.height)
        for (y in 0 until 5) for (x in 0 until 13) {
            val expected = if ((x + y) % 2 == 0) 0x000000 else 0xFFFFFF
            assertEquals("($x, $y)", expected, decoded.getRGB(x, y) and 0xFFFFFF)
        }

        val data = BilevelPng.read(bytes)!!
        assertEquals(13, data.width)
        assertEquals(5, data.height)
    }

    @Test
    fun `un PNG couleur n'est pas pris pour du 1 bit`() {
        val rgb = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(rgb, "png", it) }.toByteArray()
        assertNull(BilevelPng.read(bytes))
        assertNull(BilevelPng.read(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `en-tete JPEG couleur et gris`() {
        val color = BufferedImage(321, 123, BufferedImage.TYPE_INT_RGB)
        val gray = BufferedImage(50, 70, BufferedImage.TYPE_BYTE_GRAY)
        fun jpeg(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()

        assertEquals(JpegInfo(321, 123, 3), JpegInfo.read(jpeg(color)))
        assertEquals(JpegInfo(50, 70, 1), JpegInfo.read(jpeg(gray)))
        assertNull(JpegInfo.read(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }
}
