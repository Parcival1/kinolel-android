package org.jellyfin.mobile.kinolel

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.UUID

/*
 * KinoLel (Lel 20.10.2026): „Downloadfunktion wie bei Netflix und Co. … direkt in der App anschauen, auch wenn kein
 * Internet vorhanden ist.“ Ergänzungen zum Download-Modul von jellyfin-android:
 *  - Qualität wählbar (Original oder vom Server verkleinert), geschätzte Größe
 *  - Fortschritt in Bytes für die Download-Liste und die Knöpfe in der Weboberfläche
 *  - Wiedergabeposition offline merken und später an den Server melden
 *  - Speicherort immer im geschützten App-Speicher (keine Ordnerwahl, weg beim Deinstallieren)
 */

/** Download-Qualität. Original = Datei unverändert; sonst wandelt der Server beim Herunterladen um (H.264/AAC, MPEG-TS). */
enum class DownloadQualitaet(
    val wert: Int,
    val maxBreite: Int,
    val maxHoehe: Int,
    val videoBitrate: Int,
    val audioBitrate: Int,
) {
    ORIGINAL(0, 0, 0, 0, 0),
    HOCH(1, 1920, 1080, 8_000_000, 192_000),
    MITTEL(2, 1280, 720, 4_000_000, 160_000),
    KLEIN(3, 854, 480, 1_500_000, 128_000),
    ;

    val umgewandelt: Boolean get() = this != ORIGINAL

    /** Geschätzte Größe in Bytes (Original: echte Größe) */
    fun geschaetzteGroesse(laufzeitTicks: Long?, originalGroesse: Long?): Long? = when {
        this == ORIGINAL -> originalGroesse
        laufzeitTicks == null || laufzeitTicks <= 0 -> null
        else -> (videoBitrate.toLong() + audioBitrate) * (laufzeitTicks / TICKS_PRO_SEKUNDE) / 8
    }

    companion object {
        const val TICKS_PRO_SEKUNDE = 10_000_000L
        fun von(wert: Int) = entries.find { it.wert == wert } ?: ORIGINAL
    }
}

/** Live-Fortschritt laufender Downloads (Download-ID -> geladen/gesamt in Bytes; gesamt 0 = unbekannt) */
object DownloadFortschritt {
    data class Stand(val geladen: Long, val gesamt: Long) {
        val prozent: Int get() = if (gesamt > 0) (geladen * 100 / gesamt).toInt().coerceIn(0, 100) else -1
    }

    private val _staende = MutableStateFlow<Map<Long, Stand>>(emptyMap())
    val staende: StateFlow<Map<Long, Stand>> = _staende.asStateFlow()

    fun setzen(downloadId: Long, geladen: Long, gesamt: Long) {
        _staende.value = _staende.value + (downloadId to Stand(geladen, gesamt))
    }

    fun entfernen(downloadId: Long) {
        _staende.value = _staende.value - downloadId
    }
}

/**
 * Zusatzangaben je heruntergeladenem Titel (Item-ID), in SharedPreferences statt in der Datenbank (keine Migration):
 * Qualität, erwartete Größe, offline erreichte Position, gesehen, noch an den Server zu melden.
 */
class OfflineSpeicher(context: Context) {
    data class Info(
        val qualitaet: Int = 0,
        val erwartet: Long = 0,
        val positionTicks: Long = 0,
        val gesehen: Boolean = false,
        val syncOffen: Boolean = false,
        val zuletzt: Long = 0,
    )

    private val prefs = context.getSharedPreferences("kinolel_offline", Context.MODE_PRIVATE)

    fun lesen(itemId: UUID): Info = prefs.getString(itemId.toString(), null)?.let(::ausJson) ?: Info()

    fun schreiben(itemId: UUID, info: Info) {
        prefs.edit().putString(itemId.toString(), inJson(info)).apply()
    }

    fun entfernen(itemId: UUID) {
        prefs.edit().remove(itemId.toString()).apply()
    }

    fun offeneMeldungen(): Map<UUID, Info> = prefs.all.mapNotNull { (schluessel, wert) ->
        val id = runCatching { UUID.fromString(schluessel) }.getOrNull() ?: return@mapNotNull null
        val info = (wert as? String)?.let(::ausJson) ?: return@mapNotNull null
        if (info.syncOffen) id to info else null
    }.toMap()

    /** Position aus der Offline-Wiedergabe merken (wird später an den Server gemeldet) */
    fun positionMerken(itemId: UUID, positionTicks: Long, gesehen: Boolean) {
        schreiben(
            itemId,
            lesen(itemId).copy(
                positionTicks = if (gesehen) 0 else positionTicks,
                gesehen = gesehen,
                syncOffen = true,
                zuletzt = System.currentTimeMillis(),
            ),
        )
    }

    private fun inJson(info: Info) = JSONObject().apply {
        put("q", info.qualitaet)
        put("e", info.erwartet)
        put("p", info.positionTicks)
        put("g", info.gesehen)
        put("s", info.syncOffen)
        put("z", info.zuletzt)
    }.toString()

    private fun ausJson(text: String) = runCatching {
        val j = JSONObject(text)
        Info(
            qualitaet = j.optInt("q"),
            erwartet = j.optLong("e"),
            positionTicks = j.optLong("p"),
            gesehen = j.optBoolean("g"),
            syncOffen = j.optBoolean("s"),
            zuletzt = j.optLong("z"),
        )
    }.getOrNull()
}

object OfflineHelfer {
    /** Geschützter App-Speicher für Downloads (keine Berechtigung nötig, nicht in Galerie/Dateien, weg beim Deinstallieren) */
    fun downloadOrdner(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Downloads").apply { mkdirs() }

    fun freierSpeicher(context: Context): Long = runCatching { StatFs(downloadOrdner(context).path).availableBytes }.getOrDefault(0L)

    /** Länge einer Datei hinter file:// oder content:// (DocumentFile.fromSingleUri kann kein file://) */
    fun laenge(context: Context, uri: Uri): Long? = runCatching {
        if (uri.scheme == "file") {
            uri.toFile().takeIf { it.exists() }?.length()
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }
    }.getOrNull()

    /** Offline gesehene Positionen an den Server melden (sobald wieder Internet da ist). Fehler: später erneut. */
    suspend fun nachmelden(api: ApiClient, speicher: OfflineSpeicher) = withContext(Dispatchers.IO) {
        if (api.accessToken.isNullOrEmpty()) return@withContext
        for ((itemId, info) in speicher.offeneMeldungen()) {
            runCatching {
                api.itemsApi.updateItemUserData(
                    itemId = itemId,
                    data = UpdateUserItemDataDto(
                        playbackPositionTicks = info.positionTicks,
                        played = info.gesehen,
                    ),
                )
            }.onSuccess {
                // Nur zurücksetzen, wenn inzwischen nicht weitergeschaut wurde
                val aktuell = speicher.lesen(itemId)
                if (aktuell.zuletzt == info.zuletzt) speicher.schreiben(itemId, aktuell.copy(syncOffen = false))
                Timber.i("KinoLel-Offline: Position von %s gemeldet", itemId)
            }.onFailure { e ->
                Timber.i("KinoLel-Offline: Melden später (%s)", e.message)
                return@withContext
            }
        }
    }
}
