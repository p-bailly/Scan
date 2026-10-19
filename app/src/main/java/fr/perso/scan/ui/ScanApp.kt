package fr.perso.scan.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import fr.perso.scan.MainViewModel
import fr.perso.scan.Screen
import fr.perso.scan.data.ScanDocument
import fr.perso.scan.data.ScanPage
import kotlinx.coroutines.launch
import java.io.File

/** Scanner de Google : détection des bords, recadrage, nettoyage, plusieurs pages, import depuis la galerie. */
private val scannerOptions = GmsDocumentScannerOptions.Builder()
    .setGalleryImportAllowed(true)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
    .build()

@Composable
fun ScanApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val rendering by viewModel.rendering.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingPage by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pages = if (result.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty().map { it.imageUri }
        } else {
            emptyList()
        }
        viewModel.addImages(pages)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.addImages(uris)
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = saving
        saving = null
        if (uri != null && id != null) viewModel.savePdf(id, uri)
    }

    fun scan(target: String?) {
        val activity = context.findActivity() ?: return
        viewModel.scanTarget = target
        GmsDocumentScanning.getClient(scannerOptions).getStartScanIntent(activity)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener {
                viewModel.scanTarget = null
                viewModel.message("Scanner indisponible (services Google Play requis) : utilisez « Importer »")
            }
    }

    fun import(target: String?) {
        viewModel.scanTarget = target
        importLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun withPdf(document: ScanDocument, action: (File) -> Unit) {
        scope.launch { viewModel.buildPdf(document.id)?.let(action) }
    }

    val actions = DocumentActions(
        open = { viewModel.open(Screen.Document(it.id)) },
        share = { document ->
            withPdf(document) { file ->
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, document.name)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Partager le PDF"))
            }
        },
        openPdf = { document ->
            withPdf(document) { file ->
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(view)
                } catch (e: ActivityNotFoundException) {
                    viewModel.message("Aucune appli pour lire les PDF")
                }
            }
        },
        save = { document ->
            saving = document.id
            saveLauncher.launch(viewModel.pdfFileName(document))
        },
        rename = { renaming = it.id },
        delete = { deleting = it.id },
    )

    BackHandler(enabled = screen != Screen.Home) { viewModel.back() }

    Box(Modifier.fillMaxSize()) {
        fun pageFiles(document: ScanDocument, full: Boolean) = { page: ScanPage ->
            val file = if (full) viewModel.renderFile(document.id, page) else viewModel.thumbnailFile(document.id, page)
            file to ("${document.id}/${page.renderKey}" in rendering)
        }

        when (val current = screen) {
            Screen.Home -> HomeScreen(
                documents = documents,
                thumbnail = { document -> document.pages.firstOrNull()?.let { pageFiles(document, full = false)(it) } },
                snackbar = snackbar,
                actions = actions,
                onScan = { scan(null) },
                onImport = { import(null) },
                onSettings = { showSettings = true },
            )
            is Screen.Document -> {
                val document = documents.firstOrNull { it.id == current.id }
                if (document == null) {
                    LaunchedEffect(Unit) { viewModel.open(Screen.Home) }
                } else {
                    DocumentScreen(
                        document = document,
                        thumbnail = pageFiles(document, full = false),
                        snackbar = snackbar,
                        actions = actions,
                        onBack = { viewModel.back() },
                        onMode = { viewModel.setDocumentMode(document.id, it) },
                        onOpenPage = { viewModel.open(Screen.Page(document.id, it)) },
                        onScanMore = { scan(document.id) },
                        onImportMore = { import(document.id) },
                    )
                }
            }
            is Screen.Page -> {
                val document = documents.firstOrNull { it.id == current.documentId }
                if (document == null || document.pages.isEmpty()) {
                    LaunchedEffect(Unit) { viewModel.open(Screen.Home) }
                } else {
                    PageScreen(
                        document = document,
                        index = current.index.coerceIn(0, document.pages.size - 1),
                        image = pageFiles(document, full = true),
                        snackbar = snackbar,
                        onBack = { viewModel.back() },
                        onPageChange = { viewModel.open(Screen.Page(document.id, it)) },
                        onMode = { page, mode -> viewModel.setPageMode(document.id, page.id, mode) },
                        onRotate = { page, clockwise -> viewModel.rotatePage(document.id, page.id, clockwise) },
                        onMove = { from, to -> viewModel.movePage(document.id, from, to) },
                        onDelete = { deletingPage = it.id },
                    )
                }
            }
        }

        busy?.let { BusyOverlay(it) }
    }

    renaming?.let { id ->
        val document = viewModel.document(id)
        if (document == null) {
            renaming = null
        } else {
            RenameDialog(document.name, onDismiss = { renaming = null }) {
                viewModel.rename(id, it)
                renaming = null
            }
        }
    }
    deleting?.let { id ->
        val document = viewModel.document(id)
        if (document == null) {
            deleting = null
        } else {
            ConfirmDialog(
                title = "Supprimer le document ?",
                text = "« ${document.name} » (${pageCount(document.pages.size)}) sera définitivement supprimé.",
                confirm = "Supprimer",
                onDismiss = { deleting = null },
            ) {
                viewModel.deleteDocument(id)
                deleting = null
            }
        }
    }
    deletingPage?.let { pageId ->
        val document = (screen as? Screen.Page)?.let { viewModel.document(it.documentId) }
        if (document == null) {
            deletingPage = null
        } else {
            val last = document.pages.size == 1
            ConfirmDialog(
                title = "Supprimer la page ?",
                text = if (last) "C'est la seule page : le document sera supprimé." else "Cette page sera retirée du document.",
                confirm = "Supprimer",
                onDismiss = { deletingPage = null },
            ) {
                viewModel.deletePage(document.id, pageId)
                deletingPage = null
            }
        }
    }
    if (showSettings) {
        SettingsDialog(settings, onDismiss = { showSettings = false }, onChange = viewModel::updateSettings)
    }
}

@Composable
private fun BusyOverlay(label: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            // Bloque les appuis pendant l'opération.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                CircularProgressIndicator()
                Text(label, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
