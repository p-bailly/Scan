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
        val result = Enhancer.blackAndWhite(photo(), width, height)

        var wrong = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (result.isBlack(x, y) != isInk(x, y)) wrong++
        }
        assertEquals("pixels mal classés", 0, wrong)
    }

    @Test
    fun `noir et blanc agrandi - les traits restent a leur place, deux fois plus grands`() {
        val result = Enhancer.blackAndWhite(photo(), width, height, width * 2, height * 2)
        assertEquals(width * 2, result.width)
        assertEquals(height * 2, result.height)

        // Loin des bords des traits (l'interpolation y décide au demi-pixel près), tout est bien classé.
        var wrong = 0
        for (y in 0 until height * 2) for (x in 0 until width * 2) {
            val inside = isInk(x / 2, y / 2)
            val border = (-1..1).any { dy -> (-1..1).any { dx -> isInk((x + dx) / 2, (y + dy) / 2) != inside } }
            if (!border && result.isBlack(x, y) != inside) wrong++
        }
        assertEquals("pixels mal classés", 0, wrong)
    }

    @Test
    fun `noir et blanc - un stylo bleu reste lisible`() {
        val result = Enhancer.blackAndWhite(photo(inkColor = 0x2850C8), width, height)
        for (y in 0 until height) for (x in 0 until width) {
            if (isInk(x, y)) assertTrue(result.isBlack(x, y))
        }
    }

    @Test
    fun `noir et blanc - le grain du papier ne laisse pas de points`() {
        val random = java.util.Random(42)
        val pixels = IntArray(width * height) {
            val v = 200 + random.nextInt(30) - 15
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val result = Enhancer.blackAndWhite(pixels, width, height)
        assertTrue(result.data.all { it == 0xFF.toByte() || it.toInt() and 0xFF == 0xFF })
    }

    @Test
    fun `noir et blanc - taches de bruit effacees, points de ponctuation gardes`() {
        val pixels = IntArray(width * height) { 0xFFDCDCDC.toInt() }
        fun dot(x0: Int, y0: Int, size: Int) {
            for (y in y0 until y0 + size) for (x in x0 until x0 + size) pixels[y * width + x] = 0xFF282828.toInt()
        }
        dot(100, 100, 1) // pixel isolé
        dot(200, 100, 2) // 2 × 2 = 4 pixels : au-dessus de la limite (2 pixels sans agrandissement), gardée
        dot(300, 300, 1)
        dot(400, 400, 4) // point de ponctuation
        val result = Enhancer.blackAndWhite(pixels, width, height)
        assertTrue(!result.isBlack(100, 100))
        assertTrue(!result.isBlack(300, 300))
        assertTrue(result.isBlack(401, 401))
        assertTrue(result.isBlack(200, 100))
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
        val result = Enhancer.blackAndWhite(pixels, width, height)
        assertTrue(result.isBlack(300, 400))
        assertTrue(!result.isBlack(50, 50))
    }

    @Test
    fun `miniature noir et blanc en niveaux de gris`() {
        val image = Bilevel(8, 4)
        // Moitié gauche noire (bits à 0), moitié droite blanche.
        for (y in 0 until 4) image.data[y] = 0x0F
        val thumb = image.downscale(2)
        assertEquals(2, thumb.width)
        assertEquals(1, thumb.height)
        assertEquals(0xFF000000.toInt(), thumb.pixels[0])
        assertEquals(0xFFFFFFFF.toInt(), thumb.pixels[1])
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
