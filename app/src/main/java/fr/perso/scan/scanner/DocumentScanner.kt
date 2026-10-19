package fr.perso.scan.scanner

import android.content.Context
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning

/** Scanner de Google : détection des bords, recadrage, nettoyage, plusieurs pages, import depuis la galerie. */
object DocumentScanner {
    val client: GmsDocumentScanner by lazy {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build(),
        )
    }

    /**
     * Le scanner est un module des services Google Play, téléchargé à la première utilisation.
     * On le demande dès l'ouverture de l'appli : le premier scan s'ouvre ensuite sans attendre.
     * Sans effet s'il est déjà installé.
     */
    fun preload(context: Context) {
        runCatching {
            ModuleInstall.getClient(context)
                .installModules(ModuleInstallRequest.newBuilder().addApi(client).build())
                .addOnFailureListener { Log.w("Scan", "Préchargement du scanner impossible", it) }
        }.onFailure { Log.w("Scan", "Préchargement du scanner impossible", it) }
    }
}
