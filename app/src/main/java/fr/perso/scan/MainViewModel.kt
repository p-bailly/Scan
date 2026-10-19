package fr.perso.scan

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.perso.scan.data.DocumentStore
import fr.perso.scan.data.ScanDocument
import fr.perso.scan.data.ScanMode
import fr.perso.scan.data.ScanPage
import fr.perso.scan.data.Settings
import fr.perso.scan.data.SettingsStore
import fr.perso.scan.render.PageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Écran affiché. */
sealed interface Screen {
    data object Home : Screen
    data class Document(val id: String) : Screen
    data class Page(val documentId: String, val index: Int) : Screen
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DocumentStore(application.filesDir)
    private val settingsStore = SettingsStore(application)
    private val renderer = PageRenderer(store)

    // Rendus en parallèle limités : chaque page en haute définition occupe ~100 Mo pendant le calcul.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val renderJobs = mutableMapOf<String, Job>()

    private val _documents = MutableStateFlow<List<ScanDocument>>(emptyList())
    val documents: StateFlow<List<ScanDocument>> = _documents

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen

    /** Rendus en cours de calcul (clé : `<document>/<page>_<version>`). */
    private val _rendering = MutableStateFlow<Set<String>>(emptySet())
    val rendering: StateFlow<Set<String>> = _rendering

    /** Rendus en échec, à ne pas relancer en boucle. */
    private val failed = mutableSetOf<String>()

    /** Opération bloquante en cours (création du PDF…), affichée par-dessus l'écran. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<Settings> = _settings

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    /** Document auquel ajouter les pages du prochain scan (null : nouveau document). */
    var scanTarget: String? = null

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.loadAll() }
            // Un document a pu être créé pendant le chargement (photos partagées au démarrage).
            _documents.update { created ->
                (loaded.filterNot { old -> created.any { it.id == old.id } } + created).sortedByDescending { it.updatedAt }
            }
            loaded.forEach { renderMissing(it) }
        }
    }

    fun document(id: String): ScanDocument? = _documents.value.firstOrNull { it.id == id }

    fun renderFile(documentId: String, page: ScanPage): File = store.render(documentId, page)

    fun thumbnailFile(documentId: String, page: ScanPage): File = store.thumbnail(documentId, page)

    fun isRendering(documentId: String, page: ScanPage) = "$documentId/${page.renderKey}" in _rendering.value

    // --- Navigation ---

    fun open(screen: Screen) {
        _screen.value = screen
    }

    /** Retour : page → document → accueil. Renvoie false si on est déjà à l'accueil. */
    fun back(): Boolean {
        _screen.value = when (val current = _screen.value) {
            Screen.Home -> return false
            is Screen.Document -> Screen.Home
            is Screen.Page -> Screen.Document(current.documentId)
        }
        return true
    }

    fun message(text: String) {
        messageChannel.trySend(text)
    }

    // --- Ajout de pages ---

    /** Ajoute des images (scanner, galerie, partage) au document [scanTarget] ou à un nouveau document. */
    fun addImages(uris: List<Uri>) {
        val target = scanTarget
        scanTarget = null
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val existing = target?.let { document(it) }
            val documentId = existing?.id ?: UUID.randomUUID().toString()
            val mode = _settings.value.defaultMode
            val newPages = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { ScanPage(store.importImage(resolver, documentId, uri), mode) }
                        .onFailure { Log.w(TAG, "Import impossible : $uri", it) }
                        .getOrNull()
                }
            }
            if (newPages.isEmpty()) {
                message("Impossible de lire les images")
                return@launch
            }
            if (newPages.size < uris.size) message("${uris.size - newPages.size} image(s) illisible(s) ignorée(s)")
            val now = System.currentTimeMillis()
            // Version à jour du document : il a pu être modifié pendant l'import.
            val current = existing?.let { document(it.id) }
            val document = current?.copy(updatedAt = now, pages = current.pages + newPages)
                ?: ScanDocument(documentId, defaultName(now), now, now, newPages)
            commit(document)
            _screen.value = Screen.Document(document.id)
        }
    }

    // --- Modifications ---

    /** Applique [mode] à toutes les pages du document. */
    fun setDocumentMode(documentId: String, mode: ScanMode) = edit(documentId) { document ->
        document.copy(pages = document.pages.map { if (it.mode == mode) it else it.copy(mode = mode, version = it.version + 1) })
    }

    fun setPageMode(documentId: String, pageId: String, mode: ScanMode) = editPage(documentId, pageId) {
        if (it.mode == mode) it else it.copy(mode = mode, version = it.version + 1)
    }

    /** Tourne la page d'un quart de tour ([clockwise] : sens des aiguilles d'une montre). */
    fun rotatePage(documentId: String, pageId: String, clockwise: Boolean) = editPage(documentId, pageId) {
        it.copy(rotation = (it.rotation + if (clockwise) 90 else 270) % 360, version = it.version + 1)
    }

    fun movePage(documentId: String, from: Int, to: Int) {
        edit(documentId) { document ->
            if (to !in document.pages.indices) return@edit document
            val pages = document.pages.toMutableList()
            pages.add(to, pages.removeAt(from))
            document.copy(pages = pages)
        }
        (_screen.value as? Screen.Page)?.let { if (it.documentId == documentId) _screen.value = it.copy(index = to) }
    }

    /** Supprime une page ; le document disparaît avec sa dernière page. */
    fun deletePage(documentId: String, pageId: String) {
        val document = document(documentId) ?: return
        val pages = document.pages.filterNot { it.id == pageId }
        if (pages.isEmpty()) {
            deleteDocument(documentId)
            return
        }
        commit(document.copy(pages = pages, updatedAt = System.currentTimeMillis()))
        viewModelScope.launch(Dispatchers.IO) { store.deletePage(documentId, pageId) }
        (_screen.value as? Screen.Page)?.let {
            if (it.documentId == documentId) _screen.value = it.copy(index = it.index.coerceAtMost(pages.size - 1))
        }
    }

    fun rename(documentId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        edit(documentId) { it.copy(name = trimmed) }
    }

    fun deleteDocuments(documentIds: Collection<String>) = documentIds.forEach(::deleteDocument)

    fun deleteDocument(documentId: String) {
        _documents.update { list -> list.filterNot { it.id == documentId } }
        val screen = _screen.value
        if ((screen is Screen.Document && screen.id == documentId) || (screen is Screen.Page && screen.documentId == documentId)) {
            _screen.value = Screen.Home
        }
        viewModelScope.launch(Dispatchers.IO) { store.delete(documentId) }
    }

    fun updateSettings(settings: Settings) {
        _settings.value = settings
        settingsStore.save(settings)
    }

    // --- PDF ---

    /**
     * Crée dans le cache le PDF de chaque document (pour les partager ou les ouvrir) et renvoie les fichiers,
     * dans le même ordre. Null en cas d'erreur (message affiché).
     */
    suspend fun buildPdfs(documentIds: List<String>): List<File>? = withBusy("Création du PDF…") {
        val documents = documentIds.mapNotNull { document(it) }
        val dir = File(getApplication<Application>().cacheDir, "pdf")
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            // Anciens PDF partagés : gardés une journée, l'appli destinataire peut encore les lire.
            val yesterday = System.currentTimeMillis() - 24 * 3600 * 1000L
            dir.listFiles()?.filter { it.lastModified() < yesterday }?.forEach { it.delete() }
        }
        val names = mutableSetOf<String>()
        documents.mapIndexed { index, document ->
            if (documents.size > 1) _busy.value = "Création des PDF (${index + 1} sur ${documents.size})…"
            awaitRendered(document)
            // Deux documents du même nom ne doivent pas s'écraser.
            val base = pdfFileName(document).removeSuffix(".pdf")
            var name = "$base.pdf"
            var n = 2
            while (!names.add(name)) name = "$base (${n++}).pdf"
            withContext(Dispatchers.IO) {
                val file = File(dir, name)
                file.outputStream().buffered().use {
                    renderer.writePdf(document.id, document.name, document.pages, _settings.value.pageFormat, it)
                }
                file
            }
        }
    }

    /** Écrit le PDF du document à l'emplacement choisi par l'utilisateur. */
    fun savePdf(documentId: String, target: Uri) {
        viewModelScope.launch {
            val ok = withBusy("Enregistrement du PDF…") {
                val document = document(documentId) ?: return@withBusy false
                awaitRendered(document)
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(target, "wt")?.buffered()?.use {
                        renderer.writePdf(document.id, document.name, document.pages, _settings.value.pageFormat, it)
                        true
                    } ?: false
                }
            }
            message(if (ok == true) "PDF enregistré" else "Enregistrement impossible")
        }
    }

    fun pdfFileName(document: ScanDocument): String {
        val base = document.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "Scan" }
        return "$base.pdf"
    }

    // --- Interne ---

    private suspend fun <T> withBusy(label: String, block: suspend () -> T): T? {
        _busy.value = label
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, label, e)
            message("Erreur : ${e.message ?: e.javaClass.simpleName}")
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, label, e)
            message("Mémoire insuffisante")
            null
        } finally {
            _busy.value = null
        }
    }

    private fun edit(documentId: String, change: (ScanDocument) -> ScanDocument) {
        val document = document(documentId) ?: return
        val changed = change(document)
        if (changed != document) commit(changed.copy(updatedAt = System.currentTimeMillis()))
    }

    private fun editPage(documentId: String, pageId: String, change: (ScanPage) -> ScanPage) = edit(documentId) { document ->
        document.copy(pages = document.pages.map { if (it.id == pageId) change(it) else it })
    }

    /** Publie le document modifié, l'enregistre et lance le rendu des pages qui en ont besoin. */
    private fun commit(document: ScanDocument) {
        _documents.update { list ->
            (list.filterNot { it.id == document.id } + document).sortedByDescending { it.updatedAt }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.save(document) }.onFailure {
                Log.e(TAG, "Enregistrement", it)
                message("Enregistrement du document impossible")
            }
        }
        renderMissing(document)
    }

    private fun renderMissing(document: ScanDocument) {
        for (page in document.pages) {
            val key = "${document.id}/${page.renderKey}"
            if (key in renderJobs || key in failed) continue
            renderJobs[key] = viewModelScope.launch {
                _rendering.update { it + key }
                try {
                    withContext(renderDispatcher) {
                        if (!store.thumbnail(document.id, page).exists()) renderer.render(document.id, page)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Rendu de la page $key", e)
                    failed += key
                    message(if (e is OutOfMemoryError) "Mémoire insuffisante pour une page" else "Une page n'a pas pu être traitée")
                } finally {
                    renderJobs.remove(key)
                    _rendering.update { it - key }
                    cleanUpLater(document.id)
                }
            }
        }
    }

    /** Une fois les rendus du document terminés, supprime ses anciens fichiers. */
    private fun cleanUpLater(documentId: String) {
        if (renderJobs.keys.any { it.startsWith("$documentId/") }) return
        val document = document(documentId) ?: return
        viewModelScope.launch(Dispatchers.IO) { store.cleanUp(document) }
    }

    /** Attend la fin des rendus du document ; calcule ceux qui manquent (échecs précédents). */
    private suspend fun awaitRendered(document: ScanDocument) {
        for (page in document.pages) {
            val key = "${document.id}/${page.renderKey}"
            renderJobs[key]?.join()
            if (!store.render(document.id, page).exists() || !store.thumbnail(document.id, page).exists()) {
                withContext(renderDispatcher) { renderer.render(document.id, page) }
                failed -= key
            }
        }
    }

    private fun defaultName(time: Long): String =
        "Scan " + SimpleDateFormat("dd-MM-yyyy HH'h'mm", Locale.FRANCE).format(Date(time))

    private companion object {
        const val TAG = "Scan"
    }
}
