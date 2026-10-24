package org.jellyfin.mobile.kinolel

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.mobile.BuildConfig
import timber.log.Timber

/**
 * KinoLel (Lel 24.10.2026): Der QR-Code der Fernseher-App (KinoLel TV) zeigt
 * `https://kinolel.de/web/kinolel/qc.html?code=123456`. Android öffnet diesen Link direkt in dieser App (App Link,
 * verifiziert über `https://kinolel.de/.well-known/assetlinks.json`); die App zeigt dann die Quick-Connect-Seite mit
 * eingetragenem Code. Bestätigen tippt der Nutzer selbst. Ohne Anmeldung fragt die Weboberfläche erst nach dem Konto
 * und kehrt danach dorthin zurück. Ohne App (oder am iPhone) übernimmt der Browser dieselbe Seite.
 */
object FernseherLink {
    const val PFAD = "/web/kinolel/qc"
    const val WARTEN_MS = 300L
    private val host = Uri.parse(BuildConfig.SERVER_URL).host

    private val _code = MutableStateFlow<String?>(null)

    /** Noch nicht in der Weboberfläche geöffneter Code ("" = Link ohne Code), sonst null. */
    val code: StateFlow<String?> = _code.asStateFlow()

    /** Prüft einen Aufruf von außen (Kamera/QR-Scanner, Browser, Messenger) auf den Quick-Connect-Link. */
    fun ausIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "https" || uri.host != host) return
        if (uri.path?.startsWith(PFAD) != true) return
        // nur Ziffern übernehmen (Quick-Connect-Codes sind sechsstellig), wie qc.html im Browser
        val code = uri.getQueryParameter("code").orEmpty().filter(Char::isDigit).take(12)
        Timber.i("KinoLel: Quick-Connect-Link vom Fernseher geöffnet")
        _code.value = code
    }

    /**
     * JavaScript für die Weboberfläche: zur Quick-Connect-Seite (ohne Anmeldung leitet Jellyfin über die Anmeldung).
     * Steht die Seite schon offen (zweiter QR-Code), liest Jellyfin den Code aus der Adresse nicht neu ein – dann das
     * Feld direkt setzen (nativer Setter + input-Ereignis für React) und „Autorisieren“ fokussieren. Nie absenden.
     * [code] enthält nur Ziffern.
     */
    fun navigation(code: String): String {
        val ziel = "#/quickconnect" + (if (code.isEmpty()) "" else "?code=$code")
        return """
            (function () {
              var schonOffen = /^#\/quickconnect/.test(location.hash);
              location.hash = '$ziel';
              var f = document.querySelector('#txtQuickConnectCode');
              if (!schonOffen || !'$code' || !f || f.value === '$code') return;
              Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(f, '$code');
              f.dispatchEvent(new Event('input', { bubbles: true }));
              var knopf = document.querySelector('#quickConnectPreferencesPage .button-submit');
              if (knopf) knopf.focus();
            })()
        """.trimIndent()
    }

    fun erledigt() {
        _code.value = null
    }
}
