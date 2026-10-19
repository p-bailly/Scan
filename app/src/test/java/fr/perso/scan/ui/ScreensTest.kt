package fr.perso.scan.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import fr.perso.scan.BurstShot
import fr.perso.scan.data.DocumentStore
import fr.perso.scan.data.ScanDocument
import fr.perso.scan.data.ScanMode
import fr.perso.scan.data.ScanPage
import fr.perso.scan.data.Settings
import fr.perso.scan.render.PageRenderer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Les écrans s'affichent avec de vraies pages, et réagissent aux appuis.
 * Une capture de chaque écran est écrite dans app/build/screenshots (pour vérifier la mise en page).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val store by lazy { DocumentStore(folder.root) }

    private fun page(id: String, mode: ScanMode): ScanPage {
        val bitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(200, 190, 170))
        val ink = Paint().apply { color = Color.rgb(40, 40, 60) }
        val red = Paint().apply { color = Color.rgb(200, 40, 40) }
        canvas.drawRect(120f, 120f, 900f, 200f, ink)
        for (line in 0 until 24) {
            canvas.drawRect(120f, 300f + line * 50, 1100f - (line % 4) * 120, 320f + line * 50, ink)
        }
        canvas.drawCircle(950f, 1550f, 120f, red)
        val file = store.original("doc", id)
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return ScanPage(id, mode).also { PageRenderer(store).render("doc", it) }
    }

    private val noActions = DocumentActions({}, {}, {}, {}, {}, {})

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `accueil, document et page`() {
        val pages = listOf(page("p1", ScanMode.BLACK_WHITE), page("p2", ScanMode.COLOR), page("p3", ScanMode.ORIGINAL))
        val document = ScanDocument("doc", "Facture électricité septembre", 0L, 1_760_000_000_000L, pages)
        val other = ScanDocument("doc2", "Scan 19-10-2026 14h32", 0L, 1_750_000_000_000L, pages.take(1))
        val thumbnail = { page: ScanPage -> store.thumbnail("doc", page) to false }
        var screen by mutableStateOf("home")
        var opened: ScanDocument? = null
        var shared: List<ScanDocument>? = null
        var mode: ScanMode? = null
        var burst = false

        compose.setContent {
            ScanTheme {
                when (screen) {
                    "home" -> HomeScreen(
                        documents = listOf(document, other),
                        thumbnail = { thumbnail(it.pages.first()) },
                        snackbar = SnackbarHostState(),
                        actions = DocumentActions({ opened = it }, {}, {}, {}, {}, {}),
                        onShare = { shared = it },
                        onDelete = {},
                        onScan = {},
                        onBurst = { burst = true },
                        onImport = {},
                        onSettings = {},
                    )
                    "empty" -> HomeScreen(emptyList(), { null }, SnackbarHostState(), noActions, {}, {}, {}, {}, {}, {})
                    "document" -> DocumentScreen(
                        document = document,
                        thumbnail = thumbnail,
                        snackbar = SnackbarHostState(),
                        actions = noActions,
                        onBack = {},
                        onMode = { mode = it },
                        onOpenPage = {},
                        onScanMore = {},
                        onBurstMore = { burst = true },
                        onImportMore = {},
                    )
                    "page" -> PageScreen(
                        document = document,
                        index = 0,
                        image = { store.render("doc", it) to false },
                        snackbar = SnackbarHostState(),
                        onBack = {},
                        onPageChange = {},
                        onMode = { _, _ -> },
                        onRotate = { _, _ -> },
                        onMove = { _, _ -> },
                        onDelete = {},
                    )
                    "settings" -> SettingsDialog(Settings(), onDismiss = {}, onChange = {})
                }
            }
        }
        capture("1-accueil")
        compose.onNodeWithText("Facture électricité septembre").performClick()
        assertEquals(document, opened)

        // « Tout partager » : tous les documents d'un coup.
        compose.onNodeWithContentDescription("Tout partager").performClick()
        assertEquals(listOf(document, other), shared)

        // Appui long : sélection, puis partage de la sélection seule.
        compose.onNodeWithText("Scan 19-10-2026 14h32").performTouchInput { longClick() }
        compose.onNodeWithText("1 sélectionné").assertIsDisplayed()
        capture("1b-selection")
        compose.onNodeWithContentDescription("Partager la sélection").performClick()
        assertEquals(listOf(other), shared)
        compose.onNodeWithContentDescription("Tout partager").assertIsDisplayed()

        // Texte des boutons flottants étendus : seulement dans l'arbre non fusionné.
        compose.onNodeWithText("Rafale", useUnmergedTree = true).performClick()
        assertEquals(true, burst)
        burst = false

        screen = "empty"
        capture("2-accueil-vide")

        screen = "document"
        capture("3-document")
        compose.onNodeWithText("Couleur").performClick()
        assertEquals(ScanMode.COLOR, mode)
        compose.onNodeWithText("Rafale").performClick()
        assertEquals(true, burst)

        screen = "page"
        capture("4-page")

        screen = "settings"
        compose.onNodeWithText("Format des pages du PDF").assertIsDisplayed()
    }

    @Test
    fun `mode rafale - contour de la page, derniere page et compteur`() {
        val pages = listOf(page("p1", ScanMode.ORIGINAL), page("p2", ScanMode.ORIGINAL))
        val shots = listOf(
            BurstShot("p1", store.render("doc", pages[0])),
            BurstShot("p2", store.render("doc", pages[1])),
            BurstShot("p3"),
        )
        var shutter = 0
        var done = false
        compose.setContent {
            ScanTheme {
                BurstLayout(
                    shots = shots,
                    outline = listOf(Offset(150f, 330f), Offset(640f, 300f), Offset(700f, 1180f), Offset(110f, 1210f)),
                    torch = false,
                    flashes = 0,
                    snackbar = SnackbarHostState(),
                    // Pas d'appareil photo sous Robolectric : une « table » en bois à la place.
                    preview = { Box(Modifier.fillMaxSize().background(ComposeColor(0xFF6B5444))) },
                    onShutter = { shutter++ },
                    onTorch = {},
                    onDone = { done = true },
                    onClose = {},
                )
            }
        }
        capture("5-rafale")
        compose.onNodeWithText("Appuyez pour prendre la page 4").assertIsDisplayed()
        compose.onNodeWithContentDescription("3 pages").assertIsDisplayed()
        compose.onNodeWithContentDescription("Prendre la photo").performClick()
        compose.onNodeWithContentDescription("Prendre la photo").performClick()
        assertEquals(2, shutter)
        compose.onNodeWithText("Terminer").performClick()
        assertEquals(true, done)
    }
}
