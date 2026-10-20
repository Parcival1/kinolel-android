package org.jellyfin.mobile.kinolel

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.format.Formatter
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.downloads.DownloadManager
import org.jellyfin.mobile.downloads.DownloadMethod
import org.jellyfin.mobile.utils.requestPermission
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.android.ext.android.get
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.resume

private const val VORLIEBEN = "kinolel_offline_einstellungen"
private const val LETZTE_QUALITAET = "letzte_qualitaet"

/**
 * KinoLel: Download anfragen – Titel auflösen (Staffel -> Folgen), Qualität mit Größe wählen, einreihen.
 * Ersetzt die Ordnerwahl von jellyfin-android (Downloads liegen immer im App-Speicher).
 */
suspend fun MainActivity.kinolelDownloadAnfragen(itemIds: Collection<UUID>) {
    if (itemIds.isEmpty()) return
    val appPreferences: AppPreferences = get()
    val downloadManager: DownloadManager = get()
    val server = mainViewModel.serverState.value.server ?: return
    val user = mainViewModel.userState.value.user ?: return

    // Benachrichtigung über den Fortschritt (Android 13+): fragen, aber auch ohne Erlaubnis herunterladen
    suspendCancellableCoroutine { fortsetzung ->
        requestPermission(Manifest.permission.POST_NOTIFICATIONS) { if (fortsetzung.isActive) fortsetzung.resume(Unit) }
    }

    val titel = try {
        downloadManager.aufloesen(itemIds)
    } catch (e: Exception) {
        Timber.w(e, "KinoLel: Titel für Download nicht ladbar")
        Toast.makeText(this, R.string.kinolel_dl_keine_verbindung, Toast.LENGTH_LONG).show()
        return
    }
    if (titel.isEmpty()) {
        Toast.makeText(this, R.string.kinolel_dl_nichts, Toast.LENGTH_LONG).show()
        return
    }

    val qualitaet = qualitaetWaehlen(titel, appPreferences) ?: return
    downloadManager.enqueueItems(server, user, titel, qualitaet)

    val wartetAufWlan = appPreferences.downloadMethod == DownloadMethod.WIFI_ONLY && !imWlan()
    Toast.makeText(
        this,
        getString(if (wartetAufWlan) R.string.kinolel_dl_wartet_wlan else R.string.kinolel_dl_gestartet, titel.size),
        Toast.LENGTH_LONG,
    ).show()
}

private fun Context.imWlan(): Boolean {
    val cm = getSystemService<ConnectivityManager>() ?: return false
    val faehigkeiten = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return faehigkeiten.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

private fun bezeichnung(context: Context, q: DownloadQualitaet) = context.getString(
    when (q) {
        DownloadQualitaet.ORIGINAL -> R.string.kinolel_dl_q_original
        DownloadQualitaet.HOCH -> R.string.kinolel_dl_q_hoch
        DownloadQualitaet.MITTEL -> R.string.kinolel_dl_q_mittel
        DownloadQualitaet.KLEIN -> R.string.kinolel_dl_q_klein
    },
)

private suspend fun MainActivity.qualitaetWaehlen(titel: List<BaseItemDto>, appPreferences: AppPreferences): DownloadQualitaet? {
    val vorlieben = getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
    val frei = OfflineHelfer.freierSpeicher(this)
    val original = titel.sumOf { it.mediaSources?.firstOrNull()?.size ?: 0L }

    // Kleinere Stufen nur anbieten, wenn sie wirklich kleiner sind (mindestens 15 %)
    val groessen = DownloadQualitaet.entries.associateWith { q ->
        titel.sumOf { q.geschaetzteGroesse(it.runTimeTicks, it.mediaSources?.firstOrNull()?.size) ?: 0L }
    }
    val angebot = DownloadQualitaet.entries.filter { q ->
        q == DownloadQualitaet.ORIGINAL || original <= 0 || (groessen[q] ?: 0L) in 1..(original * 85 / 100)
    }
    val letzte = DownloadQualitaet.von(vorlieben.getInt(LETZTE_QUALITAET, DownloadQualitaet.ORIGINAL.wert))
    val vorwahl = (if (letzte in angebot) letzte else DownloadQualitaet.ORIGINAL).takeIf { (groessen[it] ?: 0L) < frei }
        ?: angebot.lastOrNull { (groessen[it] ?: 0L) < frei }

    val dp = { wert: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, wert.toFloat(), resources.displayMetrics).toInt() }
    val gruppe = RadioGroup(this)
    val knoepfe = angebot.associateWith { q ->
        val groesse = groessen[q] ?: 0L
        RadioButton(this).apply {
            id = 1000 + q.wert
            val zuGross = groesse >= frei
            text = buildString {
                append(bezeichnung(context, q))
                if (groesse > 0) append(" · ").append(if (q.umgewandelt) "~" else "").append(Formatter.formatShortFileSize(context, groesse))
                if (zuGross) append(" · ").append(getString(R.string.kinolel_dl_zu_gross))
            }
            isEnabled = !zuGross
            setPadding(paddingLeft, dp(6), paddingRight, dp(6))
            gruppe.addView(this)
        }
    }
    vorwahl?.let { knoepfe[it]?.isChecked = true }

    val inhalt = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
        addView(gruppe)
        addView(
            TextView(context).apply {
                text = buildString {
                    append(getString(R.string.kinolel_dl_frei, Formatter.formatShortFileSize(context, frei)))
                    if (angebot.size > 1) append("\n").append(getString(R.string.kinolel_dl_hinweis_klein))
                    if (appPreferences.downloadMethod == DownloadMethod.WIFI_ONLY) append("\n").append(getString(R.string.kinolel_dl_nur_wlan))
                }
                setPadding(0, dp(12), 0, 0)
                alpha = 0.75f
            },
        )
    }

    val einzeln = titel.singleOrNull()
    val ueberschrift = when {
        einzeln != null -> einzeln.seriesName?.let { "$it – ${einzeln.name}" } ?: einzeln.name.orEmpty()
        titel.all { it.type == BaseItemKind.EPISODE } -> getString(R.string.kinolel_dl_folgen, titel.size)
        else -> getString(R.string.kinolel_dl_titel, titel.size)
    }

    return suspendCancellableCoroutine { fortsetzung ->
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.kinolel_dl_ueberschrift, ueberschrift))
            .setView(inhalt)
            .setPositiveButton(R.string.kinolel_dl_starten) { _, _ ->
                val gewaehlt = DownloadQualitaet.von(gruppe.checkedRadioButtonId - 1000)
                if (gruppe.checkedRadioButtonId != -1) {
                    vorlieben.edit().putInt(LETZTE_QUALITAET, gewaehlt.wert).apply()
                    if (fortsetzung.isActive) fortsetzung.resume(gewaehlt)
                } else if (fortsetzung.isActive) {
                    fortsetzung.resume(null)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> if (fortsetzung.isActive) fortsetzung.resume(null) }
            .setOnCancelListener { if (fortsetzung.isActive) fortsetzung.resume(null) }
            .show()
        fortsetzung.invokeOnCancellation { dialog.dismiss() }
    }
}
