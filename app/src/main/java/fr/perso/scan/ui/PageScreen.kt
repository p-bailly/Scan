package fr.perso.scan.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.perso.scan.R
import fr.perso.scan.data.ScanDocument
import fr.perso.scan.data.ScanMode
import fr.perso.scan.data.ScanPage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(
    document: ScanDocument,
    index: Int,
    image: (ScanPage) -> Pair<File, Boolean>,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onPageChange: (Int) -> Unit,
    onMode: (ScanPage, ScanMode) -> Unit,
    onRotate: (ScanPage, clockwise: Boolean) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDelete: (ScanPage) -> Unit,
) {
    val pages = document.pages
    val pager = rememberPagerState(initialPage = index.coerceIn(0, pages.size - 1)) { pages.size }
    // Le pager suit l'index du modèle (déplacement d'une page) et inversement (balayage).
    LaunchedEffect(index) { if (pager.currentPage != index) pager.scrollToPage(index) }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect(onPageChange) }
    val current = pages[index.coerceIn(0, pages.size - 1)]

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                title = { Text("Page ${index + 1} sur ${pages.size}") },
                actions = {
                    IconButton(onClick = { onDelete(current) }) { Icon(Icons.Default.Delete, contentDescription = "Supprimer la page") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ModeSelector(current.mode, onSelect = { onMode(current, it) })
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Déplacer avant")
                        }
                        IconButton(onClick = { onRotate(current, false) }) {
                            Icon(painterResource(R.drawable.ic_rotate_left), contentDescription = "Tourner à gauche")
                        }
                        IconButton(onClick = { onRotate(current, true) }) {
                            Icon(painterResource(R.drawable.ic_rotate_right), contentDescription = "Tourner à droite")
                        }
                        IconButton(onClick = { onMove(index, index + 1) }, enabled = index < pages.size - 1) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Déplacer après")
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) { padding ->
        HorizontalPager(
            state = pager,
            key = { pages[it].id },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { position ->
            val page = pages[position]
            val (file, rendering) = image(page)
            Zoomable(Modifier.fillMaxSize().padding(16.dp)) {
                PageImage(file, rendering, maxSize = 2400, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Zoom à deux doigts et double tap. Sans zoom, les gestes à un doigt passent au pager (page suivante) ;
 * zoomé, ils déplacent l'image.
 */
@Composable
private fun Zoomable(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(value: Offset, scale: Float): Offset {
        val maxX = (scale - 1) * size.width / 2
        val maxY = (scale - 1) * size.height / 2
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        offset = clamp((center - tap) * (scale - 1), scale)
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = clamp(offset + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    ) {
        content()
    }
}
