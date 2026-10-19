package fr.perso.scan.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

class PdfWriterTest {

    private fun jpeg(width: Int, height: Int, rgb: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    /** Page noir et blanc : moitié gauche noire, moitié droite blanche. */
    private fun halfBlack(width: Int, height: Int) = PdfWriter.Image.BlackAndWhite(width, height) { _, row ->
        for (x in row.indices) row[x] = if (x < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    @Test
    fun `le PDF se relit avec ses pages, ses formats et ses images`() {
        val out = ByteArrayOutputStream()
        val writer = PdfWriter(out, "Facture électricité (octobre)")
        writer.addPage(
            PdfWriter.Image.Jpeg(jpeg(210, 297, 0xD03030), 210, 297),
            PageGeometry.compute(PageFormat.A4, 210, 297),
        )
        writer.addPage(halfBlack(803, 400), PageGeometry.compute(PageFormat.A4, 803, 400))
        writer.addPage(halfBlack(100, 300), PageGeometry.compute(PageFormat.FIT, 100, 300))
        writer.finish()

        Loader.loadPDF(out.toByteArray()).use { document ->
            assertEquals(3, document.numberOfPages)
            assertEquals("Facture électricité (octobre)", document.documentInformation.title)

            val portrait = document.getPage(0).mediaBox
            assertEquals(595.28f, portrait.width, 0.01f)
            assertEquals(841.89f, portrait.height, 0.01f)
            val landscape = document.getPage(1).mediaBox
            assertEquals(841.89f, landscape.width, 0.01f)
            assertEquals(595.28f, landscape.height, 0.01f)
            val fit = document.getPage(2).mediaBox
            assertEquals(280.63f, fit.width, 0.01f)
            assertEquals(841.89f, fit.height, 0.01f)

            val renderer = PDFRenderer(document)
            val colorPage = renderer.renderImage(0, 0.5f)
            val center = colorPage.getRGB(colorPage.width / 2, colorPage.height / 2)
            assertTrue("rouge attendu : ${Integer.toHexString(center)}", close(center, 0xD03030))

            val bwPage = renderer.renderImage(1, 0.5f)
            val y = bwPage.height / 2
            assertEquals(0x000000, bwPage.getRGB(bwPage.width / 4, y) and 0xFFFFFF)
            assertEquals(0xFFFFFF, bwPage.getRGB(bwPage.width * 3 / 4, y) and 0xFFFFFF)
        }
    }

    @Test
    fun `une page noir et blanc A4 a 300 dpi reste legere`() {
        val width = 2480
        val height = 3508
        val out = ByteArrayOutputStream()
        val writer = PdfWriter(out, "Texte")
        // Lignes de « texte » : traits noirs réguliers.
        writer.addPage(
            PdfWriter.Image.BlackAndWhite(width, height) { y, row ->
                for (x in row.indices) {
                    val ink = y % 50 in 10..30 && x % 20 in 3..6 && x in 200..2280
                    row[x] = if (ink) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            },
            PageGeometry.compute(PageFormat.A4, width, height),
        )
        writer.finish()
        assertTrue("taille : ${out.size()} octets", out.size() < 200_000)
        Loader.loadPDF(out.toByteArray()).use { assertEquals(1, it.numberOfPages) }
    }

    @Test
    fun `nombres et textes PDF`() {
        assertEquals("595.28", PdfWriter.num(595.28f))
        assertEquals("12", PdfWriter.num(12f))
        assertEquals("0.5", PdfWriter.num(0.5f))
        assertEquals("(Scan \\(1\\))", PdfWriter.pdfText("Scan (1)"))
        assertEquals("<FEFF00E9>", PdfWriter.pdfText("é"))
    }

    private fun close(argb: Int, rgb: Int): Boolean =
        (0..2).all { abs(((argb shr (it * 8)) and 0xFF) - ((rgb shr (it * 8)) and 0xFF)) < 12 }
}
