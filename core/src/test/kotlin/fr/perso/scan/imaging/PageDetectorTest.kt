package fr.perso.scan.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PageDetectorTest {

    /**
     * Correspondance perspective du carré unité vers [quad] (u vers la droite, v vers le bas),
     * et son inverse : sert à dessiner le texte de la page photographiée de travers.
     */
    private class Projection(quad: Quad) {
        private val m: DoubleArray
        private val inv: DoubleArray

        init {
            val (p0, p1, p2, p3) = quad.corners
            val sx = (p0.x - p1.x + p2.x - p3.x).toDouble()
            val sy = (p0.y - p1.y + p2.y - p3.y).toDouble()
            val dx1 = (p1.x - p2.x).toDouble()
            val dx2 = (p3.x - p2.x).toDouble()
            val dy1 = (p1.y - p2.y).toDouble()
            val dy2 = (p3.y - p2.y).toDouble()
            val den = dx1 * dy2 - dx2 * dy1
            val g = (sx * dy2 - dx2 * sy) / den
            val h = (dx1 * sy - sx * dy1) / den
            m = doubleArrayOf(
                p1.x - p0.x + g * p1.x, p3.x - p0.x + h * p3.x, p0.x.toDouble(),
                p1.y - p0.y + g * p1.y, p3.y - p0.y + h * p3.y, p0.y.toDouble(),
                g, h, 1.0,
            )
            inv = doubleArrayOf(
                m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
                m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
                m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
            )
        }

        /** Coordonnées (u, v) sur la page du point (x, y) de la photo. */
        fun toPage(x: Double, y: Double): Pair<Double, Double> {
            val w = inv[6] * x + inv[7] * y + inv[8]
            return (inv[0] * x + inv[1] * y + inv[2]) / w to (inv[3] * x + inv[4] * y + inv[5]) / w
        }
    }

    /**
     * Photo en gris : table (bois sombre, veiné et bruité) et page [quad] (texte, éclairage inégal),
     * bords anticipés par sur-échantillonnage 4 × 4.
     */
    private fun photo(
        width: Int,
        height: Int,
        quad: Quad,
        table: Int = 95,
        paper: Int = 215,
        shadow: Float = 0.25f,
        seed: Int = 1,
    ): ByteArray {
        val projection = Projection(quad)
        val random = Random(seed)
        val gray = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0.0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val px = x + (sx + 0.5) / 4
                val py = y + (sy + 0.5) / 4
                val (u, v) = projection.toPage(px, py)
                sum += if (u in 0.0..1.0 && v in 0.0..1.0) {
                    // Lignes de texte dans les marges, ombre qui assombrit le bas de la page.
                    val text = u in 0.12..0.88 && v in 0.1..0.9 && (v * 40) % 1.0 < 0.35 && (u * 60) % 1.0 < 0.7
                    val light = 1 - shadow * v
                    (if (text) 45.0 else paper.toDouble()) * light
                } else {
                    table + 18 * sin(py / 9 + sin(px / 40) * 2)
                }
            }
            val noise = random.nextInt(-6, 7)
            gray[y * width + x] = (sum / 16 + noise).toInt().coerceIn(0, 255).toByte()
        }
        return gray
    }

    private fun assertCorners(expected: Quad, actual: Quad?, tolerance: Float) {
        assertNotNull("page non trouvée", actual)
        expected.corners.zip(actual!!.corners).forEachIndexed { i, (e, a) ->
            assertTrue("coin $i : attendu $e, trouvé $a", distance(e, a) <= tolerance)
        }
    }

    @Test
    fun `page penchee et en perspective - les quatre coins au pixel pres`() {
        val page = Quad(Point(190f, 90f), Point(560f, 130f), Point(610f, 700f), Point(140f, 690f))
        val found = PageDetector.detect(photo(800, 800, page), 800, 800)
        assertCorners(page, found, tolerance = 1.5f)
    }

    @Test
    fun `apercu de l'appareil photo, page tournee d'un tiers de quart de tour`() {
        // 640 × 480 : la taille des images d'analyse de l'aperçu.
        val center = Point(330f, 250f)
        val corners = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (sx, sy) ->
            val x = sx * 120.0
            val y = sy * 175.0
            val angle = Math.toRadians(28.0)
            Point(
                (center.x + x * cos(angle) - y * sin(angle)).toFloat(),
                (center.y + x * sin(angle) + y * cos(angle)).toFloat(),
            )
        }
        val page = Quad.ordered(corners)
        val found = PageDetector.detect(photo(640, 480, page, seed = 2), 640, 480)
        assertCorners(page, found, tolerance = 1.5f)
    }

    @Test
    fun `grande photo - recherche sur l'image reduite puis affinage en pleine resolution`() {
        val page = Quad(Point(260f, 180f), Point(1180f, 230f), Point(1250f, 1500f), Point(200f, 1460f))
        val found = PageDetector.detect(photo(1400, 1700, page, seed = 3), 1400, 1700)
        assertCorners(page, found, tolerance = 2f)
    }

    @Test
    fun `fond clair - la page reste plus claire que la table`() {
        val page = Quad(Point(120f, 100f), Point(480f, 90f), Point(500f, 560f), Point(110f, 580f))
        val found = PageDetector.detect(photo(600, 680, page, table = 150, paper = 235, shadow = 0.1f, seed = 4), 600, 680)
        assertCorners(page, found, tolerance = 2f)
    }

    @Test
    fun `rien a trouver - image unie ou page plus grande que la photo`() {
        val flat = ByteArray(400 * 300) { 128.toByte() }
        assertNull(PageDetector.detect(flat, 400, 300))

        val noise = Random(5).let { r -> ByteArray(400 * 300) { r.nextInt(256).toByte() } }
        assertNull(PageDetector.detect(noise, 400, 300))

        val tooBig = Quad(Point(-50f, -40f), Point(450f, -40f), Point(450f, 340f), Point(-50f, 340f))
        assertNull(PageDetector.detect(photo(400, 300, tooBig), 400, 300))
    }

    @Test
    fun `feuille qui remplit la photo, eclairee d'un cote - pas de faux contour au milieu`() {
        val width = 640
        val height = 480
        val gray = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            // Moitié gauche bien éclairée, moitié droite dans l'ombre, passage progressif entre les deux.
            val t = ((x - 220) / 200.0).coerceIn(0.0, 1.0)
            val light = 1 - 0.4 * t * t * (3 - 2 * t)
            val text = (y / 12) % 2 == 0 && x % 40 < 30 && y in 30..450
            gray[y * width + x] = ((if (text) 50.0 else 225.0) * light).toInt().toByte()
        }
        assertNull(PageDetector.detect(gray, width, height))
    }

    @Test
    fun `page qui depasse de la photo par le bas - les cotes visibles suffisent`() {
        val page = Quad(Point(150f, 60f), Point(470f, 80f), Point(490f, 480f), Point(130f, 480f))
        val found = PageDetector.detect(photo(640, 480, page.copy(
            bottomRight = Point(495f, 560f),
            bottomLeft = Point(125f, 560f),
        ), seed = 6), 640, 480)
        assertCorners(page, found, tolerance = 1.5f)
    }

    @Test
    fun `gris des pixels ARGB`() {
        val gray = PageDetector.gray(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt()))
        assertEquals(255, gray[0].toInt() and 0xFF)
        assertEquals(0, gray[1].toInt() and 0xFF)
        assertEquals(128, gray[2].toInt() and 0xFF)
    }

    // --- Proportions de la page redressée ---

    /**
     * Photographie (appareil de focale [focal] pixels, photo [width] × [height]) d'une page
     * [pageWidth] × [pageHeight] tournée de [tilt] autour de l'axe horizontal, [turn] autour de
     * l'axe vertical et [spin] dans son plan, à la distance [depth].
     */
    private fun shot(
        pageWidth: Double,
        pageHeight: Double,
        tilt: Double,
        turn: Double,
        spin: Double,
        width: Int = 4000,
        height: Int = 3000,
        focal: Double = 0.58 * 5000,
        depth: Double = 500.0,
    ): Quad {
        val (a, b, c) = listOf(tilt, turn, spin).map { Math.toRadians(it) }
        val corners = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (sx, sy) ->
            val x0 = sx * pageWidth / 2
            val y0 = sy * pageHeight / 2
            // Rotation dans le plan, puis autour de l'axe vertical, puis autour de l'axe horizontal.
            val x1 = x0 * cos(c) - y0 * sin(c)
            val y1 = x0 * sin(c) + y0 * cos(c)
            val x2 = x1 * cos(b)
            val z2 = -x1 * sin(b)
            val y3 = y1 * cos(a) - z2 * sin(a)
            val z3 = y1 * sin(a) + z2 * cos(a) + depth
            Point((width / 2 + focal * x2 / z3).toFloat(), (height / 2 + focal * y3 / z3).toFloat())
        }
        return Quad(corners[0], corners[1], corners[2], corners[3])
    }

    @Test
    fun `proportions reelles malgre une forte perspective`() {
        // Page 15 × 30 (pas une A4, pour ne pas être arrondie au format A4).
        val quad = shot(150.0, 300.0, tilt = 30.0, turn = -20.0, spin = 10.0)
        assertEquals(0.5f, quad.aspectRatio(4000, 3000), 0.01f)
    }

    @Test
    fun `proportions reelles - appareil incline dans un seul sens`() {
        // Bords haut et bas parallèles sur la photo : la focale d'un téléphone est supposée.
        val quad = shot(150.0, 300.0, tilt = 35.0, turn = 0.0, spin = 0.0)
        assertEquals(0.5f, quad.aspectRatio(4000, 3000), 0.01f)
    }

    @Test
    fun `une page presque A4 devient une A4, en portrait comme en paysage`() {
        val portrait = shot(210.0, 297.0, tilt = 20.0, turn = 15.0, spin = -5.0)
        assertEquals(0.7071f, portrait.aspectRatio(4000, 3000), 0.0001f)
        val landscape = shot(297.0, 210.0, tilt = 20.0, turn = 15.0, spin = -5.0)
        assertEquals(1.4142f, landscape.aspectRatio(4000, 3000), 0.0001f)
    }

    @Test
    fun `taille de la page redressee - pas de detail perdu, grand cote limite`() {
        val quad = shot(210.0, 297.0, tilt = 25.0, turn = 0.0, spin = 0.0)
        val size = quad.pageSize(4000, 3000, maxSide = 10_000)
        assertEquals(0.7071f, size.width.toFloat() / size.height, 0.002f)
        val longestSide = maxOf(distance(quad.topLeft, quad.bottomLeft), distance(quad.topRight, quad.bottomRight))
        assertTrue(size.height >= longestSide - 1)

        val limited = quad.pageSize(4000, 3000, maxSide = 1000)
        assertEquals(1000, limited.height)
        assertEquals(707, limited.width)
    }

    @Test
    fun `coins ranges a partir du haut-gauche, dans le sens des aiguilles d'une montre`() {
        val tl = Point(10f, 10f)
        val tr = Point(100f, 12f)
        val br = Point(98f, 150f)
        val bl = Point(8f, 148f)
        val expected = Quad(tl, tr, br, bl)
        assertEquals(expected, Quad.ordered(listOf(br, bl, tl, tr)))
        assertEquals(expected, Quad.ordered(listOf(tr, tl, bl, br)))
    }
}
