package fr.perso.scan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.perso.scan.R
import fr.perso.scan.data.ScanDocument
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Actions possibles sur un document, partagées par l'accueil et l'écran du document. */
class DocumentActions(
    val open: (ScanDocument) -> Unit,
    val share: (ScanDocument) -> Unit,
    val openPdf: (ScanDocument) -> Unit,
    val save: (ScanDocument) -> Unit,
    val rename: (ScanDocument) -> Unit,
    val delete: (ScanDocument) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    documents: List<ScanDocument>,
    thumbnail: (ScanDocument) -> Pair<File, Boolean>?,
    snackbar: SnackbarHostState,
    actions: DocumentActions,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (searching && query.isNotBlank()) documents.filter { normalize(it.name).contains(normalize(query)) } else documents

    Scaffold(
        topBar = {
            if (searching) {
                val focus = remember { FocusRequester() }
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer la recherche")
                        }
                    },
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Rechercher un document") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    },
                    actions = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Effacer") }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Scan", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        if (documents.isNotEmpty()) {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, contentDescription = "Rechercher") }
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Réglages") }
                    },
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SmallFloatingActionButton(onClick = onImport) {
                    Icon(painterResource(R.drawable.ic_photo_library), contentDescription = "Importer des photos")
                }
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    icon = { Icon(painterResource(R.drawable.ic_scan), contentDescription = null) },
                    text = { Text("Scanner") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            documents.isEmpty() -> EmptyState(Modifier.padding(padding))
            shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Aucun document ne correspond", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 160.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { document ->
                    DocumentRow(document, thumbnail(document), actions, Modifier.animateItem())
                    HorizontalDivider(Modifier.padding(start = 88.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(
    document: ScanDocument,
    thumbnail: Pair<File, Boolean>?,
    actions: DocumentActions,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { actions.open(document) }
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color.White,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(width = 56.dp, height = 76.dp),
        ) {
            if (thumbnail != null) {
                PageImage(
                    file = thumbnail.first,
                    rendering = thumbnail.second,
                    maxSize = 256,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                document.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatDate(document.updatedAt)} · ${pageCount(document.pages.size)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Plus d'options") }
            DocumentMenu(menu, onDismiss = { menu = false }, document, actions)
        }
    }
}

/** Menu « ⋮ » d'un document. */
@Composable
fun DocumentMenu(expanded: Boolean, onDismiss: () -> Unit, document: ScanDocument, actions: DocumentActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Partager le PDF") },
            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
            onClick = { onDismiss(); actions.share(document) },
        )
        DropdownMenuItem(
            text = { Text("Ouvrir le PDF") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_picture_as_pdf), contentDescription = null) },
            onClick = { onDismiss(); actions.openPdf(document) },
        )
        DropdownMenuItem(
            text = { Text("Enregistrer le PDF…") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
            onClick = { onDismiss(); actions.save(document) },
        )
        DropdownMenuItem(
            text = { Text("Renommer") },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = { onDismiss(); actions.rename(document) },
        )
        DropdownMenuItem(
            text = { Text("Supprimer") },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = { onDismiss(); actions.delete(document) },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().padding(32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(28.dp))
                .border(2.dp, MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(28.dp)),
        ) {
            Icon(
                painterResource(R.drawable.ic_scan),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Aucun document", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Appuyez sur « Scanner » pour photographier un document : les bords sont détectés " +
                "automatiquement et la page devient un PDF propre, en noir et blanc ou en couleur.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

fun pageCount(count: Int) = if (count == 1) "1 page" else "$count pages"

private fun formatDate(time: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.FRANCE).format(Date(time))

/** Minuscules sans accents, pour la recherche. */
private fun normalize(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.FRANCE)
