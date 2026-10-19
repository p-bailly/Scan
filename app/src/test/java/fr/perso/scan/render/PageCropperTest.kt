package fr.perso.scan.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Photos du mode rafale, sur les vraies classes graphiques d'Android : page trouvée, recadrée et redressée. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PageCropperTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Page A4 (texte noir, cadre rouge en haut à gauche) photographiée de biais, sur une table en bois. */
    private fun photo(): File {
        val page = Bitmap.createBitmap(1050, 1485, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.rgb(235, 232, 225))
            val ink = Paint().apply { color = Color.rgb(30, 30, 40) }
            for (line in 0 until 30) drawRect(120f, 200f + line * 40, 930f - (line % 3) * 150, 215f + line * 40, ink)
            drawRect(60f, 60f, 260f, 140f, Paint().apply { color = Color.rgb(200, 30, 30) })
        }
        val photo = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.rgb(110, 80, 55))
            val grain = Paint().apply { color = Color.rgb(90, 64, 42) }
            for (y in 0 until 1500 step 23) drawRect(0f, y.toFloat(), 2000f, y + 6f, grain)
            val matrix = Matrix().apply {
                setPolyToPoly(floatArrayOf(0f, 0f, 1050f, 0f, 1050f, 1485f, 0f, 1485f), 0, shot(), 0, 4)
            }
            drawBitmap(page, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return folder.newFile("photo.jpg").apply { outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
    }

    /**
     * Coins (haut-gauche, haut-droit, bas-droit, bas-gauche) d'une feuille A4 photographiée par un téléphone
     * (photo 2000 × 1500, focale 0,58 diagonale) : penchée de 25° (le bas plus loin), tournée de 10° et de 4°.
     * Sur la photo, elle paraît 9 % plus large qu'une A4 : seule la correction de perspective retrouve le format.
     */
    private fun shot(): FloatArray {
        val focal = 0.58 * 2500
        val (tilt, turn, spin) = listOf(25.0, 10.0, 4.0).map { Math.toRadians(it) }
        return listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).flatMap { (sx, sy) ->
            val x0 = sx * 105.0
            val y0 = sy * 148.5
            val x1 = x0 * cos(spin) - y0 * sin(spin)
            val y1 = x0 * sin(spin) + y0 * cos(spin)
            val x2 = x1 * cos(turn)
            val z2 = -x1 * sin(turn)
            val y3 = y1 * cos(tilt) - z2 * sin(tilt)
            val z3 = y1 * sin(tilt) + z2 * cos(tilt) + 420
            listOf((1000 + focal * x2 / z3).toFloat(), (750 + focal * y3 / z3).toFloat())
        }.toFloatArray()
    }

    @Test
    fun `page trouvee, redressee au format A4, sans la table autour`() {
        val target = folder.newFile("page.jpg")
        val photo = photo()
        assertTrue(PageCropper.crop(photo, target))
        // Pour vérifier à l'œil : app/build/screenshots.
        val dir = File("build/screenshots").apply { mkdirs() }
        photo.copyTo(File(dir, "rafale-photo.jpg"), overwrite = true)
        target.copyTo(File(dir, "rafale-page.jpg"), overwrite = true)

        val page = BitmapFactory.decodeFile(target.path)
        assertEquals(0.7071f, page.width.toFloat() / page.height, 0.005f)
        assertTrue("assez grande : ${page.width} × ${page.height}", page.height >= 1100)

        // Tout le tour de la page est du papier : plus de bois.
        fun isPaper(x: Int, y: Int): Boolean {
            val c = page.getPixel(x, y)
            return Color.red(c) > 180 && Color.green(c) > 180 && Color.blue(c) > 170
        }
        val inset = 6
        for (i in 0 until 50) {
            val x = inset + i * (page.width - 2 * inset - 1) / 49
            val y = inset + i * (page.height - 2 * inset - 1) / 49
            assertTrue("bord haut ($x)", isPaper(x, inset))
            assertTrue("bord bas ($x)", isPaper(x, page.height - 1 - inset))
            assertTrue("bord gauche ($y)", isPaper(inset, y))
            assertTrue("bord droit ($y)", isPaper(page.width - 1 - inset, y))
        }
        // Le cadre rouge est resté en haut à gauche : la page n'est ni tournée ni retournée.
        val red = page.getPixel((160 * page.width) / 1050, (100 * page.height) / 1485)
        assertTrue("cadre rouge : ${Integer.toHexString(red)}", Color.red(red) > 150 && Color.green(red) < 90)
    }

    @Test
    fun `sans page visible, la photo est gardee entiere`() {
        val plain = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120, 120, 120)) }
        val source = folder.newFile("plain.jpg").apply { outputStream().use { plain.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        val target = folder.newFile("page.jpg")
        assertFalse(PageCropper.crop(source, target))
        val page = BitmapFactory.decodeFile(target.path)
        assertEquals(800, page.width)
        assertEquals(600, page.height)
    }
}
