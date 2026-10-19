package fr.perso.scan.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancerTest {

    private val width = 600
    private val height = 800

    /** Encre (rectangles de 4 px de large, comme des lettres) sur un papier éclairé de travers. */
    private fun isInk(x: Int, y: Int) = y % 40 in 10..25 && x % 30 in 5..8

    /** Papier : très éclairé en haut à droite, dans l'ombre en bas à gauche, légèrement jauni. */
    private fun paper(x: Int, y: Int): Float = 0.45f + 0.5f * (x.toFloat() / width + (height - y).toFloat() / height) / 2

    private fun photo(inkColor: Int = 0x202020, paperTint: Int = 0xFFF2D8): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val light = paper(x, y)
            val base = if (isInk(x, y)) inkColor else paperTint
            val r = (((base shr 16) and 0xFF) * light).toInt()
            val g = (((base shr 8) and 0xFF) * light).toInt()
            val b = ((base and 0xFF) * light).toInt()
            pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return pixels
    }

    @Test
    fun `noir et blanc - papier blanc partout et encre noire, malgre l'ombre`() {
        val pixels = photo()
        Enhancer.blackAndWhite(pixels, width, height)

        var wrong = 0
        for (y in 0 until height) for (x in 0 until width) {
            val expected = if (isInk(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            if (pixels[y * width + x] != expected) wrong++
        }
        assertEquals("pixels mal classés", 0, wrong)
    }

    @Test
    fun `noir et blanc - un stylo bleu reste lisible`() {
        val pixels = photo(inkColor = 0x2850C8)
        Enhancer.blackAndWhite(pixels, width, height)
        for (y in 0 until height) for (x in 0 until width) {
            if (isInk(x, y)) assertEquals(0xFF000000.toInt(), pixels[y * width + x])
        }
    }

    @Test
    fun `noir et blanc - le grain du papier ne laisse pas de points`() {
        val random = java.util.Random(42)
        val pixels = IntArray(width * height) {
            val v = 200 + random.nextInt(30) - 15
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        Enhancer.blackAndWhite(pixels, width, height)
        assertTrue(pixels.all { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun `noir et blanc - une grande zone sombre reste noire`() {
        // Photo collée sur la page : un aplat sombre bien plus grand que la fenêtre d'estimation du papier.
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val v = if (x in 100..499 && y in 200..599) 40 else 220
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        Enhancer.blackAndWhite(pixels, width, height)
        assertEquals(0xFF000000.toInt(), pixels[400 * width + 300])
        assertEquals(0xFFFFFFFF.toInt(), pixels[50 * width + 50])
    }

    @Test
    fun `couleur - papier blanchi et encre rouge conservee`() {
        val pixels = photo(inkColor = 0xC82020)
        Enhancer.color(pixels, width, height)

        for (y in 0 until height step 7) for (x in 0 until width step 7) {
            val p = pixels[y * width + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (isInk(x, y)) {
                assertTrue("encre rouge en ($x, $y) : $r $g $b", r > 120 && g < 60 && b < 60)
            } else if (!nearInk(x, y)) {
                assertTrue("papier blanc en ($x, $y) : $r $g $b", r >= 245 && g >= 245 && b >= 245)
            }
        }
    }

    private fun nearInk(x: Int, y: Int): Boolean {
        for (dy in -2..2) for (dx in -2..2) {
            if (isInk(x + dx, y + dy)) return true
        }
        return false
    }
}
