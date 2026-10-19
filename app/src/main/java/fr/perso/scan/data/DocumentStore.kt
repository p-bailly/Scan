package fr.perso.scan.data

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Documents enregistrés dans le stockage privé de l'appli :
 * `documents/<id>/document.json` et, pour chaque page, l'image d'origine (`<page>.orig`),
 * son rendu (`<page>_<version>.render`) et sa miniature (`<page>_<version>.thumb`).
 */
class DocumentStore(filesDir: File) {
    private val root = File(filesDir, "documents")

    /** Lit tous les documents. À appeler au démarrage, avant tout import. */
    fun loadAll(): List<ScanDocument> =
        root.listFiles().orEmpty()
            .mapNotNull { dir ->
                val metadata = File(dir, METADATA)
                // Restes d'un document supprimé pendant le rendu d'une page.
                if (!metadata.exists()) dir.deleteRecursively()
                runCatching { read(metadata) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    fun save(document: ScanDocument) {
        val json = JSONObject()
            .put("id", document.id)
            .put("name", document.name)
            .put("createdAt", document.createdAt)
            .put("updatedAt", document.updatedAt)
            .put("pages", JSONArray(document.pages.map { page ->
                JSONObject()
                    .put("id", page.id)
                    .put("mode", page.mode.name)
                    .put("rotation", page.rotation)
                    .put("version", page.version)
            }))
        val dir = directory(document.id).apply { mkdirs() }
        val tmp = File(dir, "$METADATA.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(File(dir, METADATA))) throw IOException("Enregistrement impossible")
    }

    fun delete(documentId: String) {
        directory(documentId).deleteRecursively()
    }

    /** Copie l'image [uri] dans le document et renvoie l'identifiant de la nouvelle page. */
    fun importImage(resolver: ContentResolver, documentId: String, uri: Uri): String {
        val pageId = UUID.randomUUID().toString()
        val target = original(documentId, pageId)
        target.parentFile?.mkdirs()
        val input = resolver.openInputStream(uri) ?: throw IOException("Image illisible : $uri")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        return pageId
    }

    fun original(documentId: String, pageId: String) = File(directory(documentId), "$pageId.orig")

    fun render(documentId: String, page: ScanPage) = File(directory(documentId), "${page.renderKey}.render")

    fun thumbnail(documentId: String, page: ScanPage) = File(directory(documentId), "${page.renderKey}.thumb")

    /**
     * Supprime les rendus dépassés (version antérieure à celle de la page).
     * Ne touche jamais aux fichiers plus récents : un rendu ou un import peut être en cours.
     */
    fun cleanUp(document: ScanDocument) {
        val versions = document.pages.associate { it.id to it.version }
        directory(document.id).listFiles().orEmpty().forEach { file ->
            val match = RENDER_FILE.matchEntire(file.name) ?: return@forEach
            val current = versions[match.groupValues[1]] ?: return@forEach
            if (match.groupValues[2].toInt() < current) file.delete()
        }
    }

    /** Supprime tous les fichiers d'une page retirée du document. */
    fun deletePage(documentId: String, pageId: String) {
        directory(documentId).listFiles().orEmpty()
            .filter { it.name.startsWith("$pageId.") || it.name.startsWith("${pageId}_") }
            .forEach { it.delete() }
    }

    private fun directory(documentId: String) = File(root, documentId)

    private fun read(file: File): ScanDocument {
        val json = JSONObject(file.readText())
        val pages = json.getJSONArray("pages")
        return ScanDocument(
            id = json.getString("id"),
            name = json.getString("name"),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            pages = List(pages.length()) { i ->
                val page = pages.getJSONObject(i)
                ScanPage(
                    id = page.getString("id"),
                    mode = runCatching { ScanMode.valueOf(page.getString("mode")) }.getOrDefault(ScanMode.BLACK_WHITE),
                    rotation = page.optInt("rotation", 0),
                    version = page.optInt("version", 0),
                )
            },
        )
    }

    private companion object {
        const val METADATA = "document.json"
        val RENDER_FILE = Regex("(.+)_(\\d+)\\.(render|thumb)")
    }
}
