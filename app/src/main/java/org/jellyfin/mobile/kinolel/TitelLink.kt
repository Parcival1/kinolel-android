package org.jellyfin.mobile.kinolel

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.mobile.BuildConfig
import timber.log.Timber

/**
 * KinoLel (Lel 27.10.2026): „… wenn ich bei einem Inhalt auf ‚Auf Kinolel abspielen‘ klicke und am Android-Gerät bin, es
 * sich automatisch in KinoLel statt im Browser öffnet.“
 *
 * KinoLel Wünsche ruft diese App dafür direkt auf (expliziter Aufruf, kein Intent-Filter – kinolel.de ist als App-Link
 * bestätigt, ein Filter für /web würde sonst jeden kinolel.de-Link auf dem Gerät hierher ziehen) – mit der Adresse aus
 * der Wunschseite, z. B. `https://kinolel.de/web/#/details?id=…&serverId=…` (auch `#!/details`, `/web/index.html`).
 * Angenommen werden nur Detailseiten mit 32-stelliger Kennung; die Weboberfläche springt dann dorthin.
 */
object TitelLink {
    private val host = Uri.parse(BuildConfig.SERVER_URL).host
    private val kennung = Regex("[0-9a-fA-F]{32}")

    private val _ziel = MutableStateFlow<String?>(null)

    /** Noch nicht geöffnete Detailseite (`#/details?id=…`), sonst null */
    val ziel: StateFlow<String?> = _ziel.asStateFlow()

    fun ausIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "https" || uri.host != host) return
        if (uri.path?.startsWith("/web") != true || uri.path?.startsWith(FernseherLink.PFAD) == true) return
        val fragment = uri.fragment?.removePrefix("!") ?: return
        if (!fragment.startsWith("/details?")) return
        val werte = Uri.parse("kinolel://titel" + fragment.removePrefix("/details"))
        val id = werte.getQueryParameter("id")?.takeIf(kennung::matches) ?: return
        val server = werte.getQueryParameter("serverId")?.takeIf(kennung::matches)
        Timber.i("KinoLel: Titel aus KinoLel Wünsche öffnen")
        _ziel.value = "#/details?id=$id" + (server?.let { "&serverId=$it" } ?: "")
    }

    /** JavaScript für die Weboberfläche ([ziel] enthält nur geprüfte Kennungen) */
    fun navigation(ziel: String) = "location.hash = '$ziel'; true"

    fun erledigt() {
        _ziel.value = null
    }
}
