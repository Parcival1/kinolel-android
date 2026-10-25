package org.jellyfin.mobile.kinolel

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.appcompat.view.ContextThemeWrapper
import java.util.Locale

/**
 * KinoLel (Lel 25.10.: „Sind Updatenachrichten auch auf Ungarisch … wenn die ausgewählte Sprache auf Ungarisch ist?“):
 * Die eigenen Texte der App (Was ist neu, Update, Download-Dialog, Hinweise) folgen der Sprache, die kinolel.de gerade
 * anzeigt (KinoLel-Wahl Deutsch/Magyar bzw. Jellyfins Anzeigesprache) – die WebView meldet sie über `<html lang>`.
 * Noch nichts bekannt (vor dem ersten Laden): Gerätesprache, sonst Englisch.
 */
object Sprache {
    private const val VORLIEBEN = "kinolel_sprache"
    private const val SCHLUESSEL = "seite"
    private val erlaubt = setOf("de", "hu", "en")
    private val warten = mutableListOf<() -> Unit>()

    fun gemerkt(context: Context): String? =
        context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).getString(SCHLUESSEL, null)?.takeIf { it in erlaubt }

    fun geraet(context: Context): String =
        context.resources.configuration.locales[0].language.takeIf { it == "de" || it == "hu" } ?: "en"

    fun aktuell(context: Context): String = gemerkt(context) ?: geraet(context)

    /** Sprache aus `<html lang>` von kinolel.de („hu“, „de“, „en-us“ …) merken */
    fun merken(context: Context, lang: String?) {
        val s = lang?.trim()?.trim('"')?.take(2)?.lowercase() ?: return
        if (s !in erlaubt) return
        context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).edit().putString(SCHLUESSEL, s).apply()
        val faellig = synchronized(warten) { warten.toList().also { warten.clear() } }
        faellig.forEach { Handler(Looper.getMainLooper()).post(it) }
    }

    /** [aktion] ausführen, sobald die Sprache der Seite bekannt ist – höchstens [frist] ms warten (dann Gerätesprache) */
    fun wennBekannt(context: Context, frist: Long, aktion: () -> Unit) {
        if (gemerkt(context) != null) return aktion()
        var erledigt = false
        val einmal = { if (!erledigt) { erledigt = true; aktion() } }
        synchronized(warten) { warten.add(einmal) }
        Handler(Looper.getMainLooper()).postDelayed(einmal, frist)
    }

    /** Kontext für KinoLel-Texte in dieser Sprache, mit dem Design der Activity (Dialoge, Toasts) */
    fun kontext(context: Context): Context {
        val konfiguration = Configuration(context.resources.configuration).apply { setLocale(Locale(aktuell(context))) }
        return ContextThemeWrapper(context, context.theme).apply { applyOverrideConfiguration(konfiguration) }
    }
}

/** KinoLel-Texte in der Sprache von kinolel.de (siehe [Sprache]) */
val Context.inKinoLelSprache: Context get() = Sprache.kontext(this)
