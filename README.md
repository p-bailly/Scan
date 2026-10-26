# Scan

Scanner de documents Android personnel, inspiré de Genius Scan : on photographie une feuille, les bords
sont détectés, la page est redressée, et le tout devient un PDF propre. Kotlin, Jetpack Compose,
paquet `fr.perso.scan`.

Usage : scanner Google ou **rafale** (une page par appui), import galerie ou « Partager › Scan » ; rendu par
page en noir et blanc, couleur ou original ; partage ou enregistrement des documents en PDF.

## Installer

APK reconstruit par GitHub Actions à chaque push sur la branche par défaut :
**https://github.com/p-bailly/Scan/releases/latest/download/Scan.apk**
Ouvrir le lien sur le téléphone (Android 9+) et autoriser l'installation depuis le navigateur ; une mise à
jour s'installe par-dessus l'ancienne, documents conservés. Le scanner Google demande les services Google
Play ; la rafale et l'import galerie marchent sans.

## Construire

Android SDK 35 + Java 17 ou plus récent :

```bash
./gradlew :core:test testDebugUnitTest   # tests
./gradlew assembleRelease                # → app/build/outputs/apk/release/app-release.apk
```

Architecture, signature et pièges : voir [CLAUDE.md](CLAUDE.md).
