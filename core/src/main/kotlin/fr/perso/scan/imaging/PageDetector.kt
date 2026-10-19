package fr.perso.scan.imaging

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Point d'une image, en pixels : le pixel (i, j) couvre le carré [i, i + 1] × [j, j + 1]. */
data class Point(val x: Float, val y: Float)

data class Size(val width: Int, val height: Int)

/** Bords d'une page dans une photo : coins haut-gauche, haut-droit, bas-droit et bas-gauche. */
data class Quad(val topLeft: Point, val topRight: Point, val bottomRight: Point, val bottomLeft: Point) {
    /** Les coins dans le sens des aiguilles d'une montre, en partant du haut-gauche. */
    val corners: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun map(transform: (Point) -> Point) = Quad(transform(topLeft), transform(topRight), transform(bottomRight), transform(bottomLeft))

    /** Le même contour dans l'image [factor] fois plus grande. */
    fun scaled(factor: Float) = map { Point(it.x * factor, it.y * factor) }

    val area: Float get() = abs(signedArea(corners))

    /**
     * Proportions réelles de la page (largeur / hauteur), d'après la perspective de la photo
     * ([imageWidth] × [imageHeight] : centre optique supposé au milieu). Méthode de Zhang et He
     * (« Whiteboard scanning and image enhancement », 2007) : la distance focale est déduite des deux
     * points de fuite ; quand un côté n'en a pas (photo prise bien en face), celle d'un appareil de
     * téléphone. Une page à 5 % près d'une A4 devient exactement une A4.
     */
    fun aspectRatio(imageWidth: Int, imageHeight: Int): Float {
        val cx = imageWidth / 2.0
        val cy = imageHeight / 2.0
        fun vector(p: Point) = doubleArrayOf(p.x - cx, p.y - cy, 1.0)
        val m1 = vector(topLeft)
        val m2 = vector(topRight)
        val m3 = vector(bottomLeft)
        val m4 = vector(bottomRight)
        val k2 = dot(cross(m1, m4), m3) / dot(cross(m2, m4), m3)
        val k3 = dot(cross(m1, m4), m2) / dot(cross(m3, m4), m2)
        // Directions de la largeur et de la hauteur de la page, à la distance focale près.
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }

        val diagonal = hypot(imageWidth.toDouble(), imageHeight.toDouble())
        val estimated = -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
        val focal2 = if (estimated.isFinite() && estimated in (0.3 * diagonal).let { it * it }..(4.0 * diagonal).let { it * it }) {
            estimated
        } else {
            (PHONE_FOCAL * diagonal).let { it * it }
        }
        val width = sqrt((n2[0] * n2[0] + n2[1] * n2[1]) / focal2 + n2[2] * n2[2])
        val height = sqrt((n3[0] * n3[0] + n3[1] * n3[1]) / focal2 + n3[2] * n3[2])
        val ratio = (width / height).toFloat()
        if (!ratio.isFinite() || ratio <= 0f) return measuredRatio()
        return when {
            abs(ratio / A4_PORTRAIT - 1) < A4_SNAP -> A4_PORTRAIT
            abs(ratio / A4_LANDSCAPE - 1) < A4_SNAP -> A4_LANDSCAPE
            else -> ratio
        }
    }

    /**
     * Taille de la page redressée : les proportions réelles ([aspectRatio]), sans perdre de détail
     * (le plus long des côtés mesurés sur la photo est gardé), au plus [maxSide] pixels de grand côté.
     */
    fun pageSize(imageWidth: Int, imageHeight: Int, maxSide: Int): Size {
        val ratio = aspectRatio(imageWidth, imageHeight)
        val measuredWidth = max(distance(topLeft, topRight), distance(bottomLeft, bottomRight))
        val measuredHeight = max(distance(topLeft, bottomLeft), distance(topRight, bottomRight))
        var width = measuredWidth
        var height = measuredHeight
        if (measuredWidth / measuredHeight > ratio) height = measuredWidth / ratio else width = measuredHeight * ratio
        val scale = min(1f, maxSide / max(width, height))
        return Size(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    }

    private fun measuredRatio(): Float =
        (distance(topLeft, topRight) + distance(bottomLeft, bottomRight)) /
            (distance(topLeft, bottomLeft) + distance(topRight, bottomRight))

    companion object {
        /** Distance focale d'un appareil photo de téléphone (≈ 26 mm en équivalent 24 × 36), en diagonales d'image. */
        private const val PHONE_FOCAL = 0.58
        private const val A4_PORTRAIT = 0.70710677f
        private const val A4_LANDSCAPE = 1.4142135f
        private const val A4_SNAP = 0.05f

        /** Quatre coins dans l'ordre d'un parcours du contour (un sens ou l'autre), rangés à partir du haut-gauche. */
        fun ordered(points: List<Point>): Quad {
            require(points.size == 4)
            // Sens des aiguilles d'une montre à l'écran (l'axe y descend) : aire positive.
            val clockwise = if (signedArea(points) >= 0) points else points.reversed()
            val start = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
            val p = List(4) { clockwise[(start + it) % 4] }
            return Quad(p[0], p[1], p[2], p[3])
        }

        private fun signedArea(points: List<Point>): Float {
            var sum = 0f
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                sum += a.x * b.y - b.x * a.y
            }
            return sum / 2
        }

        private fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    }
}

internal fun distance(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

/**
 * Trouve la page dans une photo : la plus grande zone claire et à peu près quadrilatère, sur un fond plus
 * sombre (table, bureau…). Pur Kotlin, assez rapide pour suivre l'aperçu de l'appareil photo.
 *
 * 1. Recherche grossière sur une image réduite et floutée : seuil d'Otsu (clair / sombre), zone claire
 *    au centre de la photo (ou la plus grande), enveloppe convexe, puis le plus grand quadrilatère inscrit.
 * 2. Affinage en pleine résolution : le long de chaque côté, le bord net papier / fond est cherché de part
 *    et d'autre, puis une droite est ajustée sur ces points. Les coins sont les intersections des droites.
 */
object PageDetector {
    /** Grand côté de l'image de la recherche grossière. */
    private const val WORK_SIDE = 320

    /** Écart minimal entre le papier et le fond (niveaux de gris, flou compris). */
    private const val MIN_CONTRAST = 28

    /** La page occupe au moins cette part de la photo. */
    private const val MIN_AREA = 0.1f

    /** Au-delà de cette part du pourtour de la photo touchée, la zone claire est le fond, pas une page. */
    private const val MAX_BORDER = 0.5f

    /** Saut de gris minimal d'un bord de page, sur 2 pixels. */
    private const val MIN_EDGE_STEP = 16f

    /** Niveaux de gris (0–255) des pixels ARGB. */
    fun gray(argb: IntArray): ByteArray = ByteArray(argb.size) { i ->
        val p = argb[i]
        ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
    }

    /** Contour de la page dans l'image [gray] ([width] × [height]), ou null si aucune page n'est visible. */
    fun detect(gray: ByteArray, width: Int, height: Int): Quad? {
        require(gray.size >= width * height)
        val factor = max(1, ceil(max(width, height) / WORK_SIDE.toDouble()).toInt())
        val w = width / factor
        val h = height / factor
        if (w < 16 || h < 16) return null
        val small = blur(downscale(gray, width, factor, w, h), w, h, radius = 2)
        val coarse = coarse(small, w, h)?.scaled(factor.toFloat()) ?: return null
        val quad = refine(coarse, gray, width, height, reach = max(3f, factor * 2.5f)) ?: return null
        return quad.takeIf { plausible(it, width, height) }
    }

    // --- Recherche grossière ---

    private fun coarse(image: IntArray, w: Int, h: Int): Quad? {
        val histogram = IntArray(256)
        for (v in image) histogram[v]++
        // Seuil d'Otsu ; si la zone claire est le fond (table blanche, mur…), nouvel essai parmi les pixels clairs.
        var from = 0
        repeat(2) {
            val threshold = otsu(histogram, from) ?: return null
            region(image, w, h, threshold)?.let { return it }
            from = threshold + 1
        }
        return null
    }

    /** Seuil séparant au mieux les gris ≥ [from] en deux groupes, s'ils sont assez différents. */
    private fun otsu(histogram: IntArray, from: Int): Int? {
        var total = 0L
        var sum = 0.0
        for (v in from..255) {
            total += histogram[v]
            sum += v.toDouble() * histogram[v]
        }
        var below = 0L
        var sumBelow = 0.0
        var best = -1.0
        var threshold = -1
        var contrast = 0.0
        for (t in from until 255) {
            below += histogram[t]
            sumBelow += t.toDouble() * histogram[t]
            val above = total - below
            if (below == 0L) continue
            if (above == 0L) break
            val meanBelow = sumBelow / below
            val meanAbove = (sum - sumBelow) / above
            val between = below.toDouble() * above * (meanAbove - meanBelow) * (meanAbove - meanBelow)
            if (between > best) {
                best = between
                threshold = t
                contrast = meanAbove - meanBelow
            }
        }
        return threshold.takeIf { it >= 0 && contrast >= MIN_CONTRAST }
    }

    /** La page parmi les pixels plus clairs que [threshold], ou null. */
    private fun region(image: IntArray, w: Int, h: Int, threshold: Int): Quad? {
        val size = w * h
        val bright = BooleanArray(size) { image[it] > threshold }
        // Ouverture : coupe les liaisons fines entre la page et d'autres zones claires.
        val opened = morph(bright, w, h, radius = 2, erode = true)
        val component = component(opened, w, h, minArea = (MIN_AREA * size / 2).toInt()) ?: return null
        val grown = morph(component, w, h, radius = 2, erode = false)
        for (i in 0 until size) grown[i] = grown[i] && bright[i]

        var border = 0
        for (x in 0 until w) {
            if (grown[x]) border++
            if (grown[(h - 1) * w + x]) border++
        }
        for (y in 1 until h - 1) {
            if (grown[y * w]) border++
            if (grown[y * w + w - 1]) border++
        }
        if (border > MAX_BORDER * (2 * (w + h) - 4)) return null

        // Contour : extrémités de chaque ligne (les trous du texte et des photos ne comptent pas).
        val points = ArrayList<Point>()
        var filled = 0
        for (y in 0 until h) {
            var left = -1
            var right = -1
            for (x in 0 until w) if (grown[y * w + x]) {
                if (left < 0) left = x
                right = x
            }
            if (left < 0) continue
            filled += right - left + 1
            points += Point(left.toFloat(), y.toFloat())
            points += Point(left.toFloat(), y + 1f)
            points += Point(right + 1f, y.toFloat())
            points += Point(right + 1f, y + 1f)
        }
        val hull = simplify(convexHull(points), maxPoints = 24)
        if (hull.size < 4) return null
        // Une page est convexe : la zone doit remplir son enveloppe.
        val hullArea = abs(polygonArea(hull))
        if (filled < 0.85f * hullArea) return null
        val quad = largestQuad(hull)
        return quad.takeIf { it.area >= MIN_AREA * size }
    }

    /** Composante connexe contenant le centre de l'image si elle est assez grande, sinon la plus grande. */
    private fun component(mask: BooleanArray, w: Int, h: Int, minArea: Int): BooleanArray? {
        val labels = IntArray(w * h)
        val queue = IntArray(w * h)
        val areas = arrayListOf(0)
        for (start in 0 until w * h) {
            if (!mask[start] || labels[start] != 0) continue
            val label = areas.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                if (x > 0 && mask[p - 1] && labels[p - 1] == 0) { labels[p - 1] = label; queue[tail++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && labels[p + 1] == 0) { labels[p + 1] = label; queue[tail++] = p + 1 }
                if (y > 0 && mask[p - w] && labels[p - w] == 0) { labels[p - w] = label; queue[tail++] = p - w }
                if (y < h - 1 && mask[p + w] && labels[p + w] == 0) { labels[p + w] = label; queue[tail++] = p + w }
            }
            areas += tail
        }
        val center = labels[(h / 2) * w + w / 2]
        val chosen = if (center != 0 && areas[center] >= minArea) center
        else areas.indices.drop(1).maxByOrNull { areas[it] }?.takeIf { areas[it] >= minArea } ?: return null
        return BooleanArray(w * h) { labels[it] == chosen }
    }

    /** Érosion (ou dilatation) par un carré de côté 2 × [radius] + 1. */
    private fun morph(mask: BooleanArray, w: Int, h: Int, radius: Int, erode: Boolean): BooleanArray {
        fun pass(src: BooleanArray, horizontal: Boolean): BooleanArray {
            val out = BooleanArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var result = erode
                for (d in -radius..radius) {
                    val xx = if (horizontal) x + d else x
                    val yy = if (horizontal) y else y + d
                    // Hors de l'image : neutre (ni érosion ni dilatation par le bord).
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val v = src[yy * w + xx]
                    if (erode && !v) { result = false; break }
                    if (!erode && v) { result = true; break }
                }
                out[y * w + x] = result
            }
            return out
        }
        return pass(pass(mask, horizontal = true), horizontal = false)
    }

    /** Enveloppe convexe (algorithme de la chaîne monotone). */
    private fun convexHull(points: List<Point>): List<Point> {
        val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted
        fun turn(o: Point, a: Point, b: Point) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val hull = ArrayList<Point>()
        for (pass in 0..1) {
            val start = hull.size
            for (p in if (pass == 0) sorted else sorted.asReversed()) {
                while (hull.size >= start + 2 && turn(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
                hull += p
            }
            hull.removeAt(hull.size - 1)
        }
        return hull
    }

    /** Retire un à un les sommets les moins utiles (plus petit triangle perdu) : il en reste au plus [maxPoints]. */
    private fun simplify(polygon: List<Point>, maxPoints: Int): List<Point> {
        val points = polygon.toMutableList()
        while (points.size > maxPoints) {
            val n = points.size
            val worst = points.indices.minBy { i -> abs(triangleArea(points[(i + n - 1) % n], points[i], points[(i + 1) % n])) }
            points.removeAt(worst)
        }
        return points
    }

    /** Le plus grand quadrilatère dont les sommets sont des sommets du polygone convexe. */
    private fun largestQuad(polygon: List<Point>): Quad {
        val n = polygon.size
        var best = -1f
        var corners = IntArray(4)
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
            val area = abs(triangleArea(polygon[a], polygon[b], polygon[c])) + abs(triangleArea(polygon[a], polygon[c], polygon[d]))
            if (area > best) {
                best = area
                corners = intArrayOf(a, b, c, d)
            }
        }
        return Quad.ordered(corners.map { polygon[it] })
    }

    // --- Affinage ---

    /** Droite passant par [x], [y], de direction unitaire ([dx], [dy]). */
    private class Line(val x: Float, val y: Float, val dx: Float, val dy: Float) {
        fun intersect(other: Line): Point? {
            val det = dx * other.dy - dy * other.dx
            if (abs(det) < 1e-3f) return null
            val t = ((other.x - x) * other.dy - (other.y - y) * other.dx) / det
            return Point(x + t * dx, y + t * dy)
        }

        companion object {
            fun through(a: Point, b: Point): Line {
                val length = distance(a, b)
                return Line(a.x, a.y, (b.x - a.x) / length, (b.y - a.y) / length)
            }
        }
    }

    /**
     * Coins précis de la page, ou null si un côté n'est pas un vrai bord : ni bord net papier / fond, ni bord
     * de la photo (page qui dépasse). La zone claire trouvée n'était alors pas la page (partie éclairée d'une
     * feuille qui remplit la photo, par exemple).
     */
    private fun refine(quad: Quad, gray: ByteArray, width: Int, height: Int, reach: Float): Quad? {
        val corners = quad.corners
        // Côté i : du coin i au coin i + 1 (haut, droite, bas, gauche).
        val lines = List(4) { side ->
            val a = corners[side]
            val b = corners[(side + 1) % 4]
            // Le long du bord de la photo, pas de bord de page à chercher (le texte proche tromperait l'affinage).
            if (alongBorder(a, b, width, height, reach)) Line.through(a, b)
            else fitEdge(a, b, gray, width, height, reach) ?: return null
        }
        val refined = List(4) { i ->
            val corner = lines[(i + 3) % 4].intersect(lines[i])
            // Un coin qui part loin : l'affinage s'est trompé de bord, on garde la recherche grossière.
            if (corner == null || distance(corner, corners[i]) > 3 * reach) return quad
            Point(corner.x.coerceIn(0f, width.toFloat()), corner.y.coerceIn(0f, height.toFloat()))
        }
        return Quad(refined[0], refined[1], refined[2], refined[3])
    }

    /** Le segment [a] → [b] longe un bord de la photo (à [tolerance] près). */
    private fun alongBorder(a: Point, b: Point, width: Int, height: Int, tolerance: Float): Boolean =
        (a.x <= tolerance && b.x <= tolerance) ||
            (a.y <= tolerance && b.y <= tolerance) ||
            (a.x >= width - tolerance && b.x >= width - tolerance) ||
            (a.y >= height - tolerance && b.y >= height - tolerance)

    /** Droite du bord papier / fond près du côté [a] → [b] (papier à droite du sens de parcours), ou null. */
    private fun fitEdge(a: Point, b: Point, gray: ByteArray, width: Int, height: Int, reach: Float): Line? {
        val length = distance(a, b)
        if (length < 8f) return null
        val ux = (b.x - a.x) / length
        val uy = (b.y - a.y) / length
        // Normale vers l'extérieur de la page.
        val nx = uy
        val ny = -ux
        val steps = ceil(reach).toInt()
        val samples = (length / 4).toInt().coerceIn(8, 48)
        val profile = FloatArray(2 * steps + 3)
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (s in 0 until samples) {
            // Loin des coins : les côtés voisins y perturbent le profil.
            val t = 0.1f + 0.8f * (s + 0.5f) / samples
            val px = a.x + (b.x - a.x) * t
            val py = a.y + (b.y - a.y) * t
            for (k in profile.indices) {
                val o = (k - steps - 1).toFloat()
                // Moyenne de 3 points le long du côté : moins de bruit.
                var sum = 0f
                for (j in -1..1) sum += sample(gray, width, height, px + nx * o + ux * j, py + ny * o + uy * j)
                profile[k] = sum / 3
            }
            var bestK = -1
            var best = MIN_EDGE_STEP
            for (k in 1 until profile.size - 1) {
                val step = profile[k - 1] - profile[k + 1]
                if (step > best) {
                    best = step
                    bestK = k
                }
            }
            if (bestK < 0) continue
            // Position au dixième de pixel : sommet de la parabole passant par les trois sauts voisins.
            var offset = (bestK - steps - 1).toFloat()
            if (bestK in 2 until profile.size - 2) {
                val left = profile[bestK - 2] - profile[bestK]
                val right = profile[bestK] - profile[bestK + 2]
                val curvature = left - 2 * best + right
                if (curvature < 0) offset += (0.5f * (left - right) / curvature).coerceIn(-0.5f, 0.5f)
            }
            xs += px + nx * offset
            ys += py + ny * offset
        }
        if (xs.size < max(5, samples / 3)) return null
        var line = fitLine(xs, ys, IntArray(xs.size) { it }.toList())
        // Second ajustement sans les points aberrants (texte près du bord, objet posé sur la page…).
        val kept = xs.indices.filter { abs((xs[it] - line.x) * line.dy - (ys[it] - line.y) * line.dx) <= 1.5f }
        if (kept.size < max(5, samples / 3)) return null
        line = fitLine(xs, ys, kept)
        return line
    }

    /** Droite des moindres carrés (distances perpendiculaires). */
    private fun fitLine(xs: List<Float>, ys: List<Float>, indices: List<Int>): Line {
        var mx = 0.0
        var my = 0.0
        for (i in indices) {
            mx += xs[i]
            my += ys[i]
        }
        mx /= indices.size
        my /= indices.size
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in indices) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        return Line(mx.toFloat(), my.toFloat(), cos(angle).toFloat(), sin(angle).toFloat())
    }

    /** Gris interpolé au point ([x], [y]) (bords de l'image prolongés). */
    private fun sample(gray: ByteArray, width: Int, height: Int, x: Float, y: Float): Float {
        val fx = (x - 0.5f).coerceIn(0f, width - 1f)
        val fy = (y - 0.5f).coerceIn(0f, height - 1f)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val tx = fx - x0
        val ty = fy - y0
        fun at(xx: Int, yy: Int) = (gray[yy * width + xx].toInt() and 0xFF).toFloat()
        val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * tx
        val bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * tx
        return top + (bottom - top) * ty
    }

    // --- Vérifications et outils ---

    /** Un vrai contour de page : convexe, assez grand, angles pas trop écrasés (même en perspective). */
    private fun plausible(quad: Quad, width: Int, height: Int): Boolean {
        if (quad.area < MIN_AREA * width * height) return false
        val p = quad.corners
        for (i in 0 until 4) {
            val prev = p[(i + 3) % 4]
            val corner = p[i]
            val next = p[(i + 1) % 4]
            val ax = prev.x - corner.x
            val ay = prev.y - corner.y
            val bx = next.x - corner.x
            val by = next.y - corner.y
            // Sens des aiguilles d'une montre : chaque coin tourne du même côté.
            if (ax * by - ay * bx > 0) return false
            val cos = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))
            if (abs(cos) > MAX_ANGLE_COS) return false
        }
        return true
    }

    /** Angles entre 30° et 150°. */
    private val MAX_ANGLE_COS = cos(Math.toRadians(30.0)).toFloat()

    private fun triangleArea(a: Point, b: Point, c: Point) = ((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2

    private fun polygonArea(points: List<Point>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2
    }

    /** Moyenne par blocs de [factor] × [factor] pixels. */
    private fun downscale(gray: ByteArray, width: Int, factor: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val count = factor * factor
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in 0 until factor) {
                val row = (y * factor + dy) * width + x * factor
                for (dx in 0 until factor) sum += gray[row + dx].toInt() and 0xFF
            }
            out[y * w + x] = sum / count
        }
        return out
    }

    /** Flou par moyenne sur un carré de côté 2 × [radius] + 1 (bords prolongés). */
    private fun blur(image: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val size = 2 * radius + 1
        val tmp = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (d in -radius..radius) sum += image[y * w + (x + d).coerceIn(0, w - 1)]
            tmp[y * w + x] = sum / size
        }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (d in -radius..radius) sum += tmp[(y + d).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = sum / size
        }
        return out
    }
}
