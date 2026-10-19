package fr.perso.scan.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import fr.perso.scan.data.DocumentStore
import fr.perso.scan.data.ScanDocument
import fr.perso.scan.data.ScanMode
import fr.perso.scan.data.ScanPage
import fr.perso.scan.imaging.BilevelPng
import fr.perso.scan.imaging.JpegInfo
import fr.perso.scan.pdf.PageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Chaîne complète sur les vraies classes graphiques d'Android : photo → rendu → miniature → PDF. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PageRendererTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Photo 1200 × 900 (paysage) : papier gris-beige avec une bande d'encre noire à gauche. */
    private fun writePhoto(store: DocumentStore, documentId: String, pageId: String) {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(190, 180, 160))
        canvas.drawRect(100f, 100f, 300f, 800f, Paint().apply { color = Color.rgb(30, 30, 30) })
        val file = store.original(documentId, pageId)
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    @Test
    fun `rendu noir et blanc tourne, puis couleur, puis PDF`() {
        val store = DocumentStore(folder.root)
        val renderer = PageRenderer(store)
        writePhoto(store, "doc", "p1")
        writePhoto(store, "doc", "p2")

        // Noir et blanc tourné d'un quart de tour : portrait, agrandi à 3300 px, PNG 1 bit lisible par Android.
        val bw = ScanPage("p1", ScanMode.BLACK_WHITE, rotation = 90)
        renderer.render("doc", bw)
        val png = BilevelPng.read(store.render("doc", bw).readBytes())
        assertEquals(2475, png?.width)
        assertTrue("PNG 1 bit léger : ${store.render("doc", bw).length()}", store.render("doc", bw).length() < 100_000)
        val bwBitmap = BitmapFactory.decodeFile(store.render("doc", bw).path)
        assertEquals(2475, bwBitmap.width)
        assertEquals(3300, bwBitmap.height)
        // Après rotation horaire, la bande d'encre (à gauche) se retrouve en haut.
        assertEquals(Color.BLACK, bwBitmap.getPixel(1200, 550))
        assertEquals(Color.WHITE, bwBitmap.getPixel(1200, 2500))
        assertTrue(store.thumbnail("doc", bw).exists())

        // Couleur : taille d'origine (pas d'agrandissement), papier blanchi.
        val color = ScanPage("p2", ScanMode.COLOR)
        renderer.render("doc", color)
        val colorBitmap = BitmapFactory.decodeFile(store.render("doc", color).path)
        assertEquals(1200, colorBitmap.width)
        assertEquals(900, colorBitmap.height)
        val paper = colorBitmap.getPixel(800, 450)
        assertTrue("papier blanc : ${Integer.toHexString(paper)}", Color.red(paper) > 245 && Color.blue(paper) > 245)

        val out = ByteArrayOutputStream()
        renderer.writePdf("doc", "Test", listOf(bw, color), PageFormat.A4, out)
        val pdf = out.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(pdf.startsWith("%PDF-1.4"))
        assertEquals(2, Regex("/Type /Page ").findAll(pdf).count())
        assertTrue(pdf.contains("/BitsPerComponent 1 /Filter /FlateDecode /DecodeParms"))
        assertTrue(pdf.contains("/Filter /DCTDecode"))
        // Page noir et blanc en 1 bit : quelques Ko, pas des Mo.
        assertTrue("taille : ${out.size()}", out.size() < 400_000)
    }

    @Test
    fun `original droit copie sans recompression, original tourne reencode`() {
        val store = DocumentStore(folder.root)
        val renderer = PageRenderer(store)
        writePhoto(store, "doc", "p1")
        val original = store.original("doc", "p1").readBytes()

        val straight = ScanPage("p1", ScanMode.ORIGINAL)
        renderer.render("doc", straight)
        assertTrue(original.contentEquals(store.render("doc", straight).readBytes()))
        assertTrue(store.thumbnail("doc", straight).exists())

        val turned = ScanPage("p1", ScanMode.ORIGINAL, rotation = 270, version = 1)
        renderer.render("doc", turned)
        assertEquals(JpegInfo(900, 1200, 3), JpegInfo.read(store.render("doc", turned).readBytes()))

        // Dans le PDF, le JPEG d'origine est inséré octet pour octet.
        val out = ByteArrayOutputStream()
        renderer.writePdf("doc", "Test", listOf(straight), PageFormat.A4, out)
        val pdf = out.toByteArray()
        assertTrue(pdf.size < original.size + 2_000)
        assertTrue(String(pdf, Charsets.ISO_8859_1).contains(String(original, Charsets.ISO_8859_1)))
    }

    @Test
    fun `documents enregistres puis relus, anciens rendus supprimes`() {
        val store = DocumentStore(folder.root)
        writePhoto(store, "doc", "p1")
        val page = ScanPage("p1", ScanMode.COLOR, rotation = 180, version = 2)
        val document = ScanDocument("doc", "Facture été", 1L, 2L, listOf(page))
        store.save(document)
        assertEquals(listOf(document), store.loadAll())

        val renderer = PageRenderer(store)
        val old = page.copy(version = 1)
        renderer.render("doc", old)
        renderer.render("doc", page)
        store.cleanUp(document)
        assertTrue(store.render("doc", page).exists())
        assertTrue(!store.render("doc", old).exists() && !store.thumbnail("doc", old).exists())
        assertTrue(store.original("doc", "p1").exists())
    }
}
