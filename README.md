# Scan 📄

Scanner de documents Android personnel inspiré de **Genius Scan** : on photographie une feuille,
les bords sont détectés et la page est redressée, puis le tout devient un **PDF propre, comme passé
au scanner**. Écrit en Kotlin avec Jetpack Compose (Material 3). Paquet : `fr.perso.scan`.

## Télécharger l'APK

**https://github.com/p-bailly/Scan/releases/latest/download/Scan.apk**

1. Ouvrez le lien sur le téléphone Android et téléchargez le fichier.
2. Ouvrez-le : Android demande d'autoriser l'installation depuis le navigateur (« sources inconnues ») — acceptez.
3. L'appli **Scan** apparaît avec les autres applis.

L'APK est reconstruit automatiquement par GitHub Actions (`.github/workflows/android.yml`) à chaque push sur la
branche par défaut, puis publié dans la release « apk » du dépôt. Pour mettre à jour, il suffit de le
retélécharger : il s'installe par-dessus l'ancien, documents conservés.

## Fonctionnalités

**Scanner**
- Scanner de documents de Google (ML Kit, via les services Google Play) : détection automatique des bords,
  recadrage et redressement de la perspective, plusieurs pages d'affilée, effacement des doigts et des taches.
- Import de photos depuis la galerie, ou « Partager › Scan » depuis n'importe quelle appli.

**Rendu des pages** (par document ou page par page)
- **Noir & blanc** : papier blanc pur, texte noir net, ombres et éclairage inégal effacés. Le stylo bleu,
  le crayon et les tampons restent lisibles. Calculé en haute définition (≈ 280 dpi sur une A4) puis
  enregistré en 1 bit dans le PDF : très net et très léger (quelques dizaines de Ko par page).
- **Couleur** : les couleurs sont gardées, le papier est blanchi (fini le jaune ou le gris des photos),
  ombres effacées, encres ravivées.
- **Original** : la photo recadrée, sans retouche ni recompression.

Le traitement est réparti sur tous les cœurs du téléphone, le noir et blanc est calculé directement en
1 bit, et le PDF est assemblé sans rien décoder ni recompresser : quelques fractions de seconde par page.
Le module du scanner Google est préchargé à l'ouverture de l'appli, le premier scan démarre sans attendre.

**Documents**
- Liste des documents avec miniature, date et nombre de pages, recherche (sans tenir compte des accents).
- Ajouter des pages (scanner ou galerie), tourner, réordonner, supprimer une page, zoom à deux doigts.
- Renommer, supprimer.
- **PDF** : partager (mail, messagerie, Drive…), ouvrir dans un lecteur PDF, ou enregistrer où l'on veut.
- **Tout partager** (bouton de partage en haut de l'accueil) : tous les PDF d'un coup vers Drive, Gmail…
  Appui long sur un document pour en sélectionner plusieurs, puis les partager ou les supprimer ensemble.
- Réglages : rendu par défaut des nouveaux scans (noir et blanc, couleur ou original) et format des
  pages du PDF (A4 ou taille de l'image).
- Thème clair / sombre, couleurs Material You (Android 12+).

Android 9 ou plus récent. Le scanner a besoin des services Google Play (présents sur presque tous les
téléphones) ; sans eux, l'import depuis la galerie reste disponible.

## Comment ça marche

- `core/` — sans Android, testé sur la JVM :
  - `imaging/Enhancer.kt` : estime la couleur du papier en tout point (moyenne par blocs, maximum local pour
    effacer l'encre, flou), puis divise chaque pixel par cette valeur. Le papier devient blanc partout et
    l'encre garde son contraste, quelle que soit la lumière. Le noir et blanc seuille ce rapport en
    agrandissant au passage (bords de lettres lisses) et efface les petites taches de bruit ; la couleur
    l'applique à chaque canal avec une courbe de contraste, puis renforce la netteté.
  - `imaging/ImageFiles.kt` : PNG 1 bit et lecture des en-têtes JPEG.
  - `pdf/PdfWriter.kt` : écrit le PDF directement — JPEG et données des PNG 1 bit insérés tels quels.
- `app/` — l'appli Android : scanner ML Kit, stockage des documents (`data/`), rendu des pages en
  arrière-plan (`render/`), écrans Compose (`ui/`).

Les documents restent dans le stockage privé de l'appli (`documents/<id>/`) : l'image d'origine de chaque page
n'est jamais modifiée, le rendu (mode, rotation) est recalculé à chaque changement.

## Construire l'APK soi-même

Android SDK (plateforme 35) + Java 17 ou plus récent :

```bash
./gradlew :core:test testDebugUnitTest   # tests
./gradlew assembleRelease                # → app/build/outputs/apk/release/app-release.apk
```

L'APK est signé avec une clé personnelle volontairement versionnée (`app/scan.keystore`), comme pour l'appli
NBA, pour que les APK construits n'importe où aient la même signature et s'installent les uns par-dessus les
autres. C'est adapté à un usage perso, pas à une publication sur le Play Store.
