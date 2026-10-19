package fr.perso.scan.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.perso.scan.R
import fr.perso.scan.data.ScanDocument
import fr.perso.scan.data.ScanMode
import fr.perso.scan.data.ScanPage
import java.io.File

/** Format A4 portrait (largeur / hauteur) pour les vignettes. */
const val A4_RATIO = 0.707f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    document: ScanDocument,
    thumbnail: (ScanPage) -> Pair<File, Boolean>,
    snackbar: SnackbarHostState,
    actions: DocumentActions,
    onBack: () -> Unit,
    onMode: (ScanMode) -> Unit,
    onOpenPage: (Int) -> Unit,
    onScanMore: () -> Unit,
    onBurstMore: () -> Unit,
    onImportMore: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                title = {
                    TextButton(onClick = { actions.rename(document) }) {
                        Text(
                            document.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { actions.share(document) }) {
                        Icon(Icons.Default.Share, contentDescription = "Partager le PDF")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Plus d'options") }
                        DocumentMenu(menu, onDismiss = { menu = false }, document, actions)
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    BarAction(R.drawable.ic_scan, "Scanner", onScanMore)
                    BarAction(R.drawable.ic_burst, "Rafale", onBurstMore)
                    BarAction(R.drawable.ic_photo_library, "Importer", onImportMore)
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { actions.share(document) },
                        icon = { Icon(painterResource(R.drawable.ic_picture_as_pdf), contentDescription = null) },
                        text = { Text("Partager") },
                        elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ModeSelector(
                selected = document.mode,
                onSelect = onMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                document.mode?.let { modeDescription(it) } ?: "Les pages ont des rendus différents",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(document.pages, key = { _, page -> page.id }) { index, page ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.animateItem()) {
                        Surface(
                            onClick = { onOpenPage(index) },
                            shape = RoundedCornerShape(4.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth().aspectRatio(A4_RATIO),
                        ) {
                            val (file, rendering) = thumbnail(page)
                            PageImage(file, rendering, maxSize = 512, modifier = Modifier.fillMaxSize().padding(2.dp))
                        }
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Action de la barre du bas : icône et libellé dessous (trois tiennent à côté du bouton « Partager »). */
@Composable
private fun BarAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** Choix du rendu : Noir & blanc / Couleur / Original. [selected] null : aucun (rendus mélangés). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelector(selected: ScanMode?, onSelect: (ScanMode) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        ScanMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, ScanMode.entries.size),
                label = { Text(mode.label, maxLines = 1) },
            )
        }
    }
}
