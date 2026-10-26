# Scan — notes pour travailler dans le repo

## Commandes et CI
- Tests : `./gradlew :core:test testDebugUnitTest` ; APK : `./gradlew assembleRelease`.
- `.github/workflows/android.yml` : sur push de la branche par défaut (ou `workflow_dispatch`), lance les tests
  puis publie `Scan.apk` dans la release « apk » (notes = 5 derniers commits). JDK 21 en CI, code compilé en 17.
- **Numéro de version = nombre de commits** (`git rev-list --count HEAD`, `versionCode` et `versionName 1.0.N`
  dans `app/build.gradle.kts`). Le CI fait `fetch-depth: 0` ; dans un clone shallow le numéro est faux
  (une mise à jour doit avoir un numéro supérieur à la version installée).
- Signature : `app/scan.keystore` est **volontairement versionné** (mots de passe en clair dans
  `app/build.gradle.kts`), comme pour l'appli NBA, pour que tous les APK aient la même signature et s'installent
  les uns sur les autres. Ne pas régénérer la clé. Usage perso, pas de publication Play Store.

## Architecture
- `core/` : Kotlin JVM pur (sans Android), testé sur la JVM (tests PDF relus avec PDFBox).
  - `imaging/Enhancer.kt` : estime la couleur du papier en chaque point (moyenne par blocs, maximum local pour
    effacer l'encre, flou) puis divise chaque pixel par cette valeur : papier blanc, encre contrastée, quelle
    que soit la lumière. Noir et blanc = seuillage de ce rapport avec agrandissement (bords lisses) et
    effacement du bruit ; couleur = division par canal + courbe de contraste + netteté.
  - `imaging/PageDetector.kt` : détection de la page (rafale). Grossier sur image réduite (Otsu, zone claire
    centrale, plus grand quadrilatère inscrit dans l'enveloppe convexe), puis affinage des 4 côtés (droite
    ajustée sur le bord papier/fond). Proportions réelles retrouvées par la perspective (Zhang et He).
  - `imaging/ImageFiles.kt` (PNG 1 bit, en-têtes JPEG), `imaging/Bilevel.kt`, `pdf/PdfWriter.kt` : PDF écrit à
    la main, une seule image en mémoire à la fois ; JPEG (DCTDecode) et données zlib des PNG 1 bit insérés
    tels quels, sans décodage ni recompression.
- `app/` : `scanner/` (ML Kit, module préchargé via `ModuleInstall`), `ui/BurstScreen.kt` (CameraX),
  `render/PageCropper.kt` (redressement des photos de rafale en arrière-plan, côté max 4000 px, JPEG q92,
  page cherchée sur 1024 px ; sans page trouvée la photo est gardée entière), `render/PageRenderer.kt`
  (rendu en arrière-plan, N&B calculé à 3300 px de grand côté ≈ 280 dpi puis stocké en 1 bit, multicœur),
  `data/` (stockage, réglages), `ui/` (écrans Compose).

## Données
- Stockage privé : `filesDir/documents/<id>/document.json` + par page `<page>.orig` (image d'origine, **jamais
  modifiée**), `<page>_<version>.render` et `.thumb`. Le rendu (mode, rotation) est recalculé à chaque changement.
  Mode « Original » droit + JPEG : copie telle quelle.
- Réglages (SharedPreferences `settings`) : `defaultMode` (N&B par défaut) et `pageFormat` (A4 ou taille image).

## Tests et divers
- Tests `app/` : Robolectric (`pixelCopyRenderMode=hardware`) ; `ScreensTest` et `LauncherIconTest` écrivent des
  captures dans `app/build/screenshots`.
- Permission CAMERA demandée à la première rafale ; `minSdk 28`, `largeHeap` activé.
- Logo/icône : `python3 design/logo/generer.py` écrit `design/logo/logo.svg` et les calques
  `res/drawable/ic_launcher_{foreground,monochrome}.xml` (ne pas les éditer à la main). Style « crayon » commun à
  Basket Live, Scan, Météo, Nimbus et Mes applis (repris de p-bailly/Meteo) ; tremblé aléatoire mais reproductible.

## Comportement produit (retiré du README)
- Rafale : contour de la page suivi en direct (bleu), compteur, lampe, « Terminer » ajoute tout au document ;
  marche mieux sur fond plus sombre que la feuille. Sans services Google Play : rafale et galerie seulement.
- Accueil : recherche insensible aux accents, appui long = sélection multiple (partager/supprimer), bouton
  « tout partager » (tous les PDF vers Drive, Gmail…). Zoom à deux doigts sur les pages ; thème clair/sombre,
  Material You (Android 12+).
