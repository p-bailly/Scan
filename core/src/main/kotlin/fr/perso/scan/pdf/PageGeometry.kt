package fr.perso.scan.pdf

import kotlin.math.max
import kotlin.math.min

/** Format des pages du PDF. */
enum class PageFormat(val label: String) {
    A4("A4"),
    LETTER("Lettre US"),
    FIT("Taille de l'image"),
}

/** Taille d'une page et position de l'image dessus, en points PDF (1/72 de pouce, origine en bas à gauche). */
data class PageGeometry(
    val pageWidth: Float,
    val pageHeight: Float,
    val imageX: Float,
    val imageY: Float,
    val imageWidth: Float,
    val imageHeight: Float,
) {
    companion object {
        private const val A4_SHORT = 595.28f
        private const val A4_LONG = 841.89f
        private const val LETTER_SHORT = 612f
        private const val LETTER_LONG = 792f

        /**
         * Page pour une image de [width] × [height] pixels.
         * A4 et Lettre : page dans le sens de l'image, image agrandie au maximum et centrée.
         * Taille de l'image : la page épouse l'image, son grand côté mesurant celui d'une A4.
         */
        fun compute(format: PageFormat, width: Int, height: Int): PageGeometry {
            require(width > 0 && height > 0)
            val landscape = width > height
            val (short, long) = when (format) {
                PageFormat.A4 -> A4_SHORT to A4_LONG
                PageFormat.LETTER -> LETTER_SHORT to LETTER_LONG
                PageFormat.FIT -> {
                    val scale = A4_LONG / max(width, height)
                    val w = width * scale
                    val h = height * scale
                    return PageGeometry(w, h, 0f, 0f, w, h)
                }
            }
            val pageWidth = if (landscape) long else short
            val pageHeight = if (landscape) short else long
            val scale = min(pageWidth / width, pageHeight / height)
            val w = width * scale
            val h = height * scale
            return PageGeometry(pageWidth, pageHeight, (pageWidth - w) / 2, (pageHeight - h) / 2, w, h)
        }
    }
}
