package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.kinolel.DownloadFortschritt
import org.jellyfin.mobile.kinolel.DownloadQualitaet
import org.jellyfin.mobile.kinolel.OfflineHelfer
import org.jellyfin.mobile.kinolel.OfflineSpeicher
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.io.IOException

class DownloadQueue(
    private val context: Context,
    private val apiClientController: ApiClientController,
    private val downloadDao: DownloadDao,
    private val downloadNotificationManager: DownloadNotificationManager,
    private val storageManager: StorageManager,
    okHttpClient: OkHttpClient,
) {
    /** KinoLel: [fortsetzbar] = false bei vom Server umgewandelten Downloads (kein Byte-Bereich möglich, immer von vorn) */
    private data class QueuedFile(val file: DownloadFileEntity, val remoteUri: Uri, val fortsetzbar: Boolean = true)

    private val _downloader = FileDownloader(okHttpClient)
    private val _downloads = mutableListOf<DownloadFiles>()
    private val offlineSpeicher by lazy { OfflineSpeicher(context) }

    suspend fun prepare(): Boolean {
        val queuedDownloads = downloadDao.getQueuedDownloads()
        _downloads.clear()
        _downloads.addAll(queuedDownloads)
        return _downloads.any()
    }

    suspend fun process() {
        while (_downloads.any()) {
            val iterator = _downloads.iterator()
            while (iterator.hasNext()) {
                val downloadWithFiles = iterator.next()
                try {
                    process(downloadWithFiles)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    throw e // Netz weg: ganzer Lauf später erneut (WorkManager)
                } catch (e: Exception) {
                    // KinoLel: Fehler bei einem Titel (z. B. Server-Fehler) -> als Fehler markiert, die anderen laufen weiter
                    Timber.w(e, "KinoLel: Download %s fehlgeschlagen", downloadWithFiles.download.itemId)
                }
                iterator.remove()
            }

            // Refetch the queued downloads
            prepare()
        }
    }

    private suspend fun process(downloadWithFiles: DownloadFiles) {
        val download = downloadWithFiles.download
        // Mark as downloading
        downloadDao.update(download.copy(status = DownloadStatus.DOWNLOADING))
        val api = apiClientController.getApiClient(download.serverId, download.userId)

        try {
            val queuedFiles = prepareFiles(api, downloadWithFiles)

            val notificationProgressCallback = downloadNotificationManager.downloadFile(
                download.id,
                download.getDisplayName(context).orEmpty(),
            )
            // KinoLel: Fortschritt auch für die Download-Liste/Knöpfe; ohne Dateigröße (Umwandlung) gegen die Schätzung
            val erwartet = offlineSpeicher.lesen(download.itemId).erwartet
            val fortschritt = FileDownloader.ProgressCallback { geladen, gesamt ->
                val bekannt = if (gesamt > 0) gesamt else erwartet
                DownloadFortschritt.setzen(download.id, geladen, bekannt)
                notificationProgressCallback.onProgress(geladen, if (bekannt > 0) bekannt else Long.MAX_VALUE)
            }
            DownloadFortschritt.setzen(download.id, 0, erwartet)

            for (queuedFile in queuedFiles) {
                when (queuedFile.file.type) {
                    DownloadFileType.ITEM -> download(api, queuedFile, fortschritt)
                    // Untertitel sind Beiwerk: schlägt einer fehl, wird er weggelassen statt den ganzen Download abzubrechen
                    DownloadFileType.SUBTITLE -> try {
                        download(api, queuedFile, FileDownloader.ProgressCallback.Empty)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "KinoLel: Untertitel %s ausgelassen", queuedFile.file.fileName)
                        downloadDao.deleteFile(queuedFile.file.id)
                        runCatching { if (queuedFile.file.uri.scheme == "file") java.io.File(queuedFile.file.uri.path!!).delete() }
                    }
                    else -> download(api, queuedFile, FileDownloader.ProgressCallback.Empty)
                }
            }

            notificationProgressCallback.onEnd()
            downloadDao.update(download.copy(status = DownloadStatus.DOWNLOADED))
        } catch (e: CancellationException) {
            // The download could've been canceled by the app, in which case we need to refresh it before making changes
            val aktuell = downloadDao.getDownload(download.id)
            if (aktuell?.status == DownloadStatus.DOWNLOADING) {
                downloadDao.update(aktuell.copy(status = DownloadStatus.QUEUED))
            }
            throw e
        } catch (e: IOException) {
            downloadDao.update(download.copy(status = DownloadStatus.QUEUED))
            throw e
        } catch (e: Exception) {
            downloadDao.update(download.copy(status = DownloadStatus.ERROR))
            throw e
        } finally {
            DownloadFortschritt.entfernen(download.id)
        }
    }

    private suspend fun download(
        api: ApiClient,
        queuedFile: QueuedFile,
        progressCallback: FileDownloader.ProgressCallback,
    ) {
        val (file, remoteUri, fortsetzbar) = queuedFile

        // Verify downloaded files and skip if valid
        if (file.status == DownloadStatus.DOWNLOADED && file.size > 0) {
            if (OfflineHelfer.laenge(context, file.uri) == file.size) return
        }

        // Umgewandelte Downloads lassen sich nicht fortsetzen -> Datei leeren und von vorn
        val fileDescriptor = context.contentResolver.openFileDescriptor(file.uri, if (fortsetzbar) "rw" else "rwt")
            ?: error("Unable to open file descriptor for ${file.fileName}")

        downloadDao.updateFile(file.copy(status = DownloadStatus.DOWNLOADING))

        try {
            _downloader.downloadAndSave(
                api = api,
                from = remoteUri,
                to = fileDescriptor,
                progressCallback = progressCallback,
                fortsetzbar = fortsetzbar,
            )

            // Update file record with final size and status
            downloadDao.updateFile(
                file.copy(
                    size = OfflineHelfer.laenge(context, file.uri) ?: 0L,
                    status = DownloadStatus.DOWNLOADED,
                ),
            )
        } catch (e: IOException) {
            downloadDao.updateFile(file.copy(status = DownloadStatus.QUEUED))
            throw e
        } catch (e: Exception) {
            downloadDao.updateFile(file.copy(status = DownloadStatus.ERROR))
            throw e
        }
    }

    private suspend fun prepareFiles(api: ApiClient, downloadWithFiles: DownloadFiles): List<QueuedFile> {
        val storageLocation = storageManager.getStorageLocation()
        val itemLocation = storageLocation?.findFile(downloadWithFiles.download.path)
            ?: storageLocation?.createDirectory(downloadWithFiles.download.path)
            ?: error("Unable to find or create folder ${downloadWithFiles.download.path}")

        val qualitaet = DownloadQualitaet.von(offlineSpeicher.lesen(downloadWithFiles.download.itemId).qualitaet)

        return buildList {
            // Add image as first item so it can be shown in UI during downloads
            preparePrimaryImageFile(api, downloadWithFiles, itemLocation)?.let(::add)

            // Add main item second as it is (often) the largest and important file
            prepareMainFile(api, downloadWithFiles, itemLocation, qualitaet).let(::add)

            // KinoLel: Untertitel (externe Dateien; bei Umwandlung alle Text-Untertitel, die sonst fehlen würden)
            addAll(prepareSubtitleFiles(api, downloadWithFiles, itemLocation, qualitaet))
        }
    }

    private suspend fun prepareMainFile(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile,
        qualitaet: DownloadQualitaet,
    ): QueuedFile {
        val download = downloadWithFiles.download
        val item = download.item
        val quelle = item.mediaSources?.firstOrNull()
        val mediaSourceId = quelle?.id ?: item.id.toString()

        // KinoLel: über den Wiedergabe-Endpunkt (wie Direktwiedergabe), nicht über /Items/{id}/Download – der Datei-Download
        // im Browser bleibt damit getrennt steuerbar (Benutzerrecht „Herunterladen“, Lel 14.10.).
        if (!qualitaet.umgewandelt) {
            val dateiname = item.path?.replace(Regex("^.*[\\\\/]"), "")
                ?: "video.${quelle?.container?.substringBefore(',') ?: "mkv"}"
            return QueuedFile(
                file = createOrUpdateFile(
                    filter = { it.type == DownloadFileType.ITEM },
                    downloadWithFiles = downloadWithFiles,
                    itemLocation = itemLocation,
                    type = DownloadFileType.ITEM,
                    fileName = dateiname,
                ),
                remoteUri = api.createUrl(
                    "/Videos/{itemId}/stream",
                    pathParameters = mapOf("itemId" to item.id),
                    queryParameters = mapOf("static" to true, "mediaSourceId" to mediaSourceId),
                ).toUri(),
            )
        }

        // Verkleinert: der Server wandelt beim Herunterladen um (H.264 + AAC Stereo in MPEG-TS, im Player spulbar)
        val parameter = buildMap<String, Any?> {
            put("static", false)
            put("container", "ts")
            put("mediaSourceId", mediaSourceId)
            put("videoCodec", "h264")
            put("audioCodec", "aac")
            put("maxWidth", qualitaet.maxBreite)
            put("maxHeight", qualitaet.maxHoehe)
            put("videoBitRate", qualitaet.videoBitrate)
            put("audioBitRate", qualitaet.audioBitrate)
            put("maxAudioChannels", 2)
            put("deviceId", api.deviceInfo.id)
            put("playSessionId", "kinolel-download-${download.id}")
            quelle?.defaultAudioStreamIndex?.let { put("audioStreamIndex", it) }
        }
        return QueuedFile(
            file = createOrUpdateFile(
                filter = { it.type == DownloadFileType.ITEM },
                downloadWithFiles = downloadWithFiles,
                itemLocation = itemLocation,
                type = DownloadFileType.ITEM,
                fileName = "video.ts",
            ),
            remoteUri = api.createUrl(
                "/Videos/{itemId}/stream.ts",
                pathParameters = mapOf("itemId" to item.id),
                queryParameters = parameter,
            ).toUri(),
            fortsetzbar = false,
        )
    }

    private suspend fun prepareSubtitleFiles(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile,
        qualitaet: DownloadQualitaet,
    ): List<QueuedFile> {
        val item = downloadWithFiles.download.item
        val quelle = item.mediaSources?.firstOrNull() ?: return emptyList()
        val mediaSourceId = quelle.id ?: item.id.toString()
        return quelle.mediaStreams.orEmpty()
            .filter { it.type == MediaStreamType.SUBTITLE && it.isTextSubtitleStream && (it.isExternal || qualitaet.umgewandelt) }
            .map { stream ->
                val format = untertitelFormat(stream)
                QueuedFile(
                    file = createOrUpdateFile(
                        filter = { it.type == DownloadFileType.SUBTITLE && it.fileName.startsWith("untertitel-${stream.index}.") },
                        downloadWithFiles = downloadWithFiles,
                        itemLocation = itemLocation,
                        type = DownloadFileType.SUBTITLE,
                        fileName = "untertitel-${stream.index}.$format",
                    ),
                    remoteUri = api.createUrl(
                        "/Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.{format}",
                        pathParameters = mapOf(
                            "itemId" to item.id,
                            "mediaSourceId" to mediaSourceId,
                            "index" to stream.index,
                            "format" to format,
                        ),
                    ).toUri(),
                    fortsetzbar = false,
                )
            }
    }

    private suspend fun preparePrimaryImageFile(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile
    ): QueuedFile? = downloadWithFiles.download.item.imageTags?.get(ImageType.PRIMARY)?.let { imageTag ->
        QueuedFile(
            file = createOrUpdateFile(
                filter = { it.type == DownloadFileType.IMAGE_PRIMARY },
                downloadWithFiles = downloadWithFiles,
                itemLocation = itemLocation,
                type = DownloadFileType.IMAGE_PRIMARY,
                fileName = "primary.webp",
            ),
            remoteUri = api.imageApi.getItemImageUrl(
                itemId = downloadWithFiles.download.item.id,
                imageType = ImageType.PRIMARY,
                tag = imageTag,
                format = ImageFormat.WEBP,
            ).toUri()
        )
    }

    private suspend fun createOrUpdateFile(
        filter: (DownloadFileEntity) -> Boolean,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile,
        type: DownloadFileType,
        fileName: String,
    ): DownloadFileEntity {
        var downloadFile = downloadWithFiles.files.firstOrNull(filter)

        val file = itemLocation.findFile(fileName)
            ?: itemLocation.createFile("", fileName)
            ?: error("Unable to create file $fileName")

        if (downloadFile != null) {
            // KinoLel: fertige Dateien nicht zurücksetzen (sonst lädt ein erneuter Anstoß alles neu)
            if (downloadFile.status == DownloadStatus.DOWNLOADED && downloadFile.fileName == fileName &&
                OfflineHelfer.laenge(context, downloadFile.uri) == downloadFile.size && downloadFile.size > 0
            ) {
                return downloadFile
            }
            // Andere Qualität gewählt -> alte Datei (anderer Name) entfernen, sonst bliebe sie liegen
            val alteDatei = downloadFile.uri
            if (downloadFile.fileName != fileName && alteDatei.scheme == "file") {
                runCatching { java.io.File(alteDatei.path!!).delete() }
            }
            downloadFile = downloadFile.copy(
                type = type,
                size = 0L,
                fileName = fileName,
                uri = file.uri,
                status = DownloadStatus.QUEUED,
            )
            downloadDao.updateFile(downloadFile)
            return downloadFile
        } else {
            downloadFile = DownloadFileEntity(
                downloadId = downloadWithFiles.download.id,
                type = type,
                size = 0L,
                fileName = fileName,
                uri = file.uri,
                status = DownloadStatus.QUEUED,
            )

            val id = downloadDao.insertFile(downloadFile)
            downloadFile = downloadFile.copy(id = id)
            return downloadFile
        }
    }

    companion object {
        /** Untertitelformat für Datei und Server-Umwandlung: ASS/SSA bleiben, WebVTT bleibt, sonst SRT */
        fun untertitelFormat(stream: MediaStream): String = when (stream.codec?.lowercase()) {
            "ass", "ssa" -> "ass"
            "vtt", "webvtt" -> "vtt"
            else -> "srt"
        }
    }
}
