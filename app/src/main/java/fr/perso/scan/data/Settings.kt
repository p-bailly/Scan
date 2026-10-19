package fr.perso.scan.data

import android.content.Context
import fr.perso.scan.pdf.PageFormat

/** Réglages de l'appli. */
data class Settings(
    /** Mode appliqué aux pages des nouveaux scans. */
    val defaultMode: ScanMode = ScanMode.BLACK_WHITE,
    val pageFormat: PageFormat = PageFormat.A4,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        defaultMode = enumOrDefault(prefs.getString("defaultMode", null), Settings().defaultMode),
        pageFormat = enumOrDefault(prefs.getString("pageFormat", null), Settings().pageFormat),
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putString("defaultMode", settings.defaultMode.name)
            .putString("pageFormat", settings.pageFormat.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
