package fr.perso.scan.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import fr.perso.scan.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Images déjà décodées, pour un défilement fluide. Taille limitée à 1/6 de la mémoire de l'appli. */
private object ImageCache : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 6 / 1024).toInt()) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2 / 1024
}

/** Décode [file], réduit par puissances de 2 tant que ses côtés dépassent le double de [maxSize]. */
private fun decode(file: File, maxSize: Int): ImageBitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
    // Pas de transparence : 2 octets par pixel au lieu de 4.
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}

/**
 * Affiche l'image d'une page : roue de chargement pendant le rendu ([rendering]),
 * puis l'image, chargée en arrière-plan.
 */
@Composable
fun PageImage(
    file: File,
    rendering: Boolean,
    maxSize: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (rendering) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
            return@Box
        }
        val key = "${file.path}@$maxSize"
        var image by remember(key) { mutableStateOf(ImageCache.get(key)) }
        var missing by remember(key) { mutableStateOf(false) }
        LaunchedEffect(key) {
            if (image != null) return@LaunchedEffect
            val loaded = withContext(Dispatchers.IO) { runCatching { decode(file, maxSize) }.getOrNull() }
            if (loaded != null) {
                ImageCache.put(key, loaded)
                image = loaded
            } else {
                missing = true
            }
        }
        val current = image
        when {
            current != null -> Image(
                bitmap = current,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.background(Color.White),
            )
            missing -> Icon(
                painterResource(R.drawable.ic_description),
                contentDescription = "Page indisponible",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
