package org.jellyfin.mobile.kinolel

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.player.source.ExternalSubtitleStream
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/**
 * KinoLel: Medienquelle für die Wiedergabe eines Downloads – mit heruntergeladenen Untertiteln.
 *
 * Original: Spuren wie auf dem Server; externe Untertitel liegen als Datei daneben.
 * Verkleinert (vom Server umgewandelt): Die Datei enthält nur Video + eine Tonspur; Text-Untertitel liegen als Dateien
 * daneben. Die Spurenliste wird dafür neu durchnummeriert (der Player erwartet Index = Position in der Liste).
 */
object LokaleQuelle {
    data class Ergebnis(val quelle: MediaSourceInfo, val untertitel: List<MediaItem.SubtitleConfiguration>)

    private val UNTERTITEL_NAME = Regex("""^untertitel-(\d+)\.(\w+)$""")

    fun bauen(item: BaseItemDto, qualitaet: DownloadQualitaet, dateien: List<DownloadFileEntity>): Ergebnis {
        val original = requireNotNull(item.mediaSources?.firstOrNull()) { "Download ohne Medienquelle" }
        val streams = original.mediaStreams.orEmpty()

        // Heruntergeladene Untertitel: Original-Index -> Datei
        val untertitelDateien = dateien
            .filter { it.type == DownloadFileType.SUBTITLE && it.status == DownloadStatus.DOWNLOADED }
            .mapNotNull { datei ->
                val treffer = UNTERTITEL_NAME.matchEntire(datei.fileName) ?: return@mapNotNull null
                Triple(treffer.groupValues[1].toInt(), treffer.groupValues[2], datei)
            }
            .sortedBy { it.first }

        if (!qualitaet.umgewandelt) {
            val untertitel = untertitelDateien.mapNotNull { (index, format, datei) ->
                val stream = streams.firstOrNull { it.index == index } ?: return@mapNotNull null
                konfiguration(datei, format, index, stream)
            }
            return Ergebnis(original, untertitel)
        }

        // Verkleinert: neue, lückenlose Spurenliste
        val neu = mutableListOf<MediaStream>()
        streams.firstOrNull { it.type == MediaStreamType.VIDEO }?.let { video ->
            neu += video.copy(index = neu.size, codec = "h264", isExternal = false)
        }
        val ton = streams.firstOrNull { it.index == original.defaultAudioStreamIndex }
            ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }
        var tonIndex: Int? = null
        ton?.let {
            tonIndex = neu.size
            neu += it.copy(index = neu.size, codec = "aac", channels = minOf(it.channels ?: 2, 2), isExternal = false)
        }
        val untertitel = mutableListOf<MediaItem.SubtitleConfiguration>()
        val zuordnung = mutableMapOf<Int, Int>()
        for ((index, format, datei) in untertitelDateien) {
            val stream = streams.firstOrNull { it.index == index } ?: continue
            val neuerIndex = neu.size
            zuordnung[index] = neuerIndex
            neu += stream.copy(
                index = neuerIndex,
                codec = format,
                isExternal = true,
                deliveryMethod = SubtitleDeliveryMethod.EXTERNAL,
            )
            untertitel += konfiguration(datei, format, neuerIndex, stream)
        }
        val quelle = original.copy(
            container = "ts",
            mediaStreams = neu,
            defaultAudioStreamIndex = tonIndex,
            defaultSubtitleStreamIndex = original.defaultSubtitleStreamIndex?.let { zuordnung[it] },
            size = dateien.firstOrNull { it.type == DownloadFileType.ITEM }?.size,
        )
        return Ergebnis(quelle, untertitel)
    }

    private fun konfiguration(datei: DownloadFileEntity, format: String, index: Int, stream: MediaStream) =
        MediaItem.SubtitleConfiguration.Builder(datei.uri).apply {
            setId("${ExternalSubtitleStream.ID_PREFIX}$index")
            setLabel(stream.displayTitle.orEmpty())
            setLanguage(stream.language ?: Constants.LANGUAGE_UNDEFINED)
            setMimeType(
                when (format) {
                    "ass" -> MimeTypes.TEXT_SSA
                    "vtt" -> MimeTypes.TEXT_VTT
                    else -> MimeTypes.APPLICATION_SUBRIP
                },
            )
        }.build()
}
