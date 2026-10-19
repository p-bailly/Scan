package fr.perso.scan.data

/** Rendu d'une page dans le PDF. */
enum class ScanMode(val label: String) {
    /** Noir et blanc pur, papier blanc, texte noir net : l'effet « photocopie ». */
    BLACK_WHITE("Noir & blanc"),

    /** Couleurs conservées, papier blanchi et ombres effacées. */
    COLOR("Couleur"),

    /** Photo telle que recadrée par le scanner, sans retouche. */
    ORIGINAL("Original"),
}

/**
 * Une page : l'image d'origine ne change jamais, le rendu (mode, rotation) est recalculé à la demande.
 * [version] augmente à chaque modification : les fichiers de rendu portent ce numéro, ce qui évite
 * d'afficher ou d'exporter un rendu périmé.
 */
data class ScanPage(
    val id: String,
    val mode: ScanMode,
    val rotation: Int = 0,
    val version: Int = 0,
) {
    /** Identifiant du rendu de cette version de la page. */
    val renderKey: String get() = "${id}_$version"
}

data class ScanDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<ScanPage>,
) {
    /** Mode commun à toutes les pages, ou null si elles diffèrent. */
    val mode: ScanMode? get() = pages.map { it.mode }.distinct().singleOrNull()
}
