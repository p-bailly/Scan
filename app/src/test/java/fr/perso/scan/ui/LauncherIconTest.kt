package fr.perso.scan.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import fr.perso.scan.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** L'icône de l'appli se dessine (tracés valides) avec les bonnes couleurs ; captures dans app/build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LauncherIconTest {

    private fun draw(vararg layers: Drawable?): Bitmap {
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (layer in layers) {
            layer!!.setBounds(0, 0, 432, 432)
            layer.draw(canvas)
        }
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun near(actual: Int, expected: Int) =
        listOf(Color::red, Color::green, Color::blue).all { abs(it(actual) - it(expected)) < 24 }

    @Test
    fun `icone au crayon`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val full = draw(icon.background, icon.foreground)
        save(full, "icone")
        save(draw(icon.monochrome), "icone-monochrome")

        // Repère 108 × 108 dessiné en 432 px : facteur 4.
        val beam = full.getPixel(54 * 4, (54.5f * 4).toInt())
        val paper = full.getPixel(54 * 4, 36 * 4)
        val corner = full.getPixel(8, 8)
        assertTrue("rayon ${Integer.toHexString(beam)}", near(beam, Color.rgb(0x2A, 0x9D, 0x8F)))
        assertTrue("page ${Integer.toHexString(paper)}", near(paper, Color.WHITE))
        assertTrue("fond ${Integer.toHexString(corner)}", near(corner, Color.rgb(0xF6, 0xF1, 0xE6)))
    }
}
