package fr.perso.scan.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.perso.scan.BurstShot
import fr.perso.scan.R
import fr.perso.scan.imaging.PageDetector
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Couleur du contour de la page détectée : visible sur toutes les photos. */
private val OutlineColor = Color(0xFF3D8BFF)

/**
 * Mode rafale, comme Genius Scan : l'appareil photo reste ouvert, chaque appui ajoute une page (recadrée en
 * arrière-plan), sans écran de validation entre deux. Le contour de la page est suivi en direct.
 */
@Composable
fun BurstScreen(
    shots: List<BurstShot>,
    snackbar: SnackbarHostState,
    newPhoto: () -> File,
    onPhoto: (File) -> Unit,
    onError: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    fun cameraAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(cameraAllowed()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    // Retour des réglages d'Android, où l'appareil photo a pu être autorisé.
    LifecycleResumeEffect(Unit) {
        granted = cameraAllowed()
        onPauseOrDispose {}
    }

    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    fun close() {
        if (shots.isEmpty()) onCancel() else confirmCancel = true
    }
    BackHandler { close() }

    // Écran allumé pendant la rafale, icônes des barres système claires sur l'aperçu sombre.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            lightStatus?.let { bars.isAppearanceLightStatusBars = it }
            lightNavigation?.let { bars.isAppearanceLightNavigationBars = it }
        }
    }

    if (granted) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val camera = remember { BurstCamera(context) }
        DisposableEffect(lifecycleOwner) {
            camera.controller.bindToLifecycle(lifecycleOwner)
            onDispose { camera.release() }
        }
        var torch by remember { mutableStateOf(false) }
        LaunchedEffect(torch) { camera.controller.enableTorch(torch) }
        var flashes by remember { mutableIntStateOf(0) }
        BurstLayout(
            shots = shots,
            outline = camera.outline,
            torch = torch,
            flashes = flashes,
            snackbar = snackbar,
            preview = {
                AndroidView(
                    factory = {
                        PreviewView(it).apply {
                            // TextureView : se superpose correctement aux éléments de Compose.
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            controller = camera.controller
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            },
            onShutter = {
                flashes++
                val file = newPhoto()
                camera.takePicture(file, onSaved = { onPhoto(file) }, onFailed = {
                    file.delete()
                    onError("La photo n'a pas pu être prise")
                })
            },
            onTorch = { torch = !torch },
            onDone = onDone,
            onClose = ::close,
        )
    } else {
        PermissionNeeded(
            onAsk = { permission.launch(Manifest.permission.CAMERA) },
            onSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            onClose = ::close,
        )
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = "Abandonner la rafale ?",
            text = if (shots.size == 1) "La page déjà prise sera supprimée." else "Les ${shots.size} pages déjà prises seront supprimées.",
            confirm = "Abandonner",
            onDismiss = { confirmCancel = false },
        ) {
            confirmCancel = false
            onCancel()
        }
    }
}

/** Écran de la rafale, sans l'appareil photo ([preview]) : contour, déclencheur, dernière page, « Terminer ». */
@Composable
fun BurstLayout(
    shots: List<BurstShot>,
    outline: List<Offset>?,
    torch: Boolean,
    flashes: Int,
    snackbar: SnackbarHostState,
    preview: @Composable () -> Unit,
    onShutter: () -> Unit,
    onTorch: () -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        preview()
        PageOutline(outline)

        // Éclair blanc à chaque photo.
        val flash = remember { Animatable(0f) }
        LaunchedEffect(flashes) {
            if (flashes == 0) return@LaunchedEffect
            flash.snapTo(0.6f)
            flash.animateTo(0f, tween(220))
        }
        if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White) }
                Text(
                    "Rafale",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTorch) {
                    Icon(
                        painterResource(if (torch) R.drawable.ic_flash_on else R.drawable.ic_flash_off),
                        contentDescription = if (torch) "Éteindre la lampe" else "Allumer la lampe",
                        tint = Color.White,
                    )
                }
            }
            val last = shots.lastOrNull()
            val hint = when {
                last?.page != null && !last.detected -> "Bords de la page non trouvés : photo gardée entière"
                outline == null -> "Cadrez une page posée sur un fond plus sombre"
                else -> "Appuyez pour prendre la page ${shots.size + 1}"
            }
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            SnackbarHost(snackbar)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    shots.lastOrNull()?.let { LastShot(it, shots.size) }
                }
                ShutterButton(onShutter)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Button(onClick = onDone, enabled = shots.isNotEmpty()) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Terminer")
                    }
                }
            }
        }
    }
}

/** Contour de la page détectée dans l'aperçu, en douceur d'une image à l'autre. */
@Composable
private fun PageOutline(outline: List<Offset>?) {
    if (outline == null) return
    val corners = outline.map { animateOffsetAsState(it, tween(120), label = "coin").value }
    Canvas(Modifier.fillMaxSize()) {
        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            for (corner in corners.drop(1)) lineTo(corner.x, corner.y)
            close()
        }
        drawPath(path, OutlineColor.copy(alpha = 0.2f))
        drawPath(path, OutlineColor, style = Stroke(width = 3.dp.toPx()))
    }
}

/** Gros bouton blanc rond, comme l'appareil photo du téléphone. */
@Composable
private fun ShutterButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(76.dp).border(4.dp, Color.White, CircleShape),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.White,
            modifier = Modifier.size(60.dp).semantics { contentDescription = "Prendre la photo" },
        ) {}
    }
}

/** Miniature de la dernière page et nombre de pages prises. */
@Composable
private fun LastShot(shot: BurstShot, count: Int) {
    Box {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color.White,
            border = BorderStroke(2.dp, Color.White),
            modifier = Modifier.size(width = 52.dp, height = 68.dp),
        ) {
            // Roue de chargement pendant le recadrage.
            PageImage(shot.page ?: File(""), rendering = shot.page == null, maxSize = 160, modifier = Modifier.fillMaxSize())
        }
        Badge(
            containerColor = OutlineColor,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp),
        ) {
            Text("$count", modifier = Modifier.semantics { contentDescription = pageCount(count) })
        }
    }
}

@Composable
private fun PermissionNeeded(onAsk: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        IconButton(onClick = onClose, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(32.dp),
        ) {
            Icon(painterResource(R.drawable.ic_burst), contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                "Le mode rafale prend les photos avec l'appareil photo du téléphone : autorisez-le pour continuer.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAsk) { Text("Autoriser l'appareil photo") }
            TextButton(onClick = onSettings, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                Text("Réglages de l'appli")
            }
        }
    }
}

/** Appareil photo de la rafale : aperçu, analyse des images (contour de la page) et prise de vue. */
private class BurstCamera(private val context: Context) {
    val controller = LifecycleCameraController(context).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
        imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
    }

    /** Contour de la page dans l'aperçu (pixels de l'écran), null si aucune page n'est visible. */
    var outline by mutableStateOf<List<Offset>?>(null)
        private set

    private val analysis: ExecutorService = Executors.newSingleThreadExecutor()

    /** Plus de rappels une fois l'écran quitté (photos en cours, erreurs de fermeture de l'appareil). */
    @Volatile
    private var released = false

    init {
        controller.setImageAnalysisAnalyzer(analysis, PageAnalyzer { if (!released) outline = it })
    }

    fun takePicture(file: File, onSaved: () -> Unit, onFailed: () -> Unit) {
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved()

                override fun onError(exception: ImageCaptureException) {
                    if (released) file.delete() else onFailed()
                }
            },
        )
    }

    fun release() {
        released = true
        controller.clearImageAnalysisAnalyzer()
        controller.unbind()
        analysis.shutdown()
    }
}

/**
 * Cherche la page dans chaque image de l'aperçu (plan de luminance, 640 × 480) et convertit son contour
 * en coordonnées de l'écran, grâce à la transformation fournie par l'appareil photo.
 */
private class PageAnalyzer(private val onOutline: (List<Offset>?) -> Unit) : ImageAnalysis.Analyzer {
    @Volatile
    private var sensorToView: Matrix? = null

    /** Images de suite sans page : le contour reste affiché quelques images (flou de bougé). */
    private var misses = 0

    override fun getTargetCoordinateSystem(): Int = ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED

    override fun updateTransform(matrix: Matrix?) {
        sensorToView = matrix?.let(::Matrix)
    }

    override fun analyze(image: ImageProxy) {
        try {
            val toView = sensorToView ?: return
            val quad = PageDetector.detect(luminance(image), image.width, image.height)
            if (quad == null) {
                if (++misses >= 4) onOutline(null)
                return
            }
            misses = 0
            val imageToView = Matrix()
            image.imageInfo.sensorToBufferTransformMatrix.invert(imageToView)
            imageToView.postConcat(toView)
            val points = quad.corners.flatMap { listOf(it.x, it.y) }.toFloatArray()
            imageToView.mapPoints(points)
            onOutline(List(4) { Offset(points[2 * it], points[2 * it + 1]) })
        } finally {
            image.close()
        }
    }

    /** Niveaux de gris de l'image : son plan Y, sans le remplissage en fin de ligne. */
    private fun luminance(image: ImageProxy): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val out = ByteArray(width * image.height)
        for (y in 0 until image.height) {
            buffer.position(y * plane.rowStride)
            buffer.get(out, y * width, width)
        }
        return out
    }
}
