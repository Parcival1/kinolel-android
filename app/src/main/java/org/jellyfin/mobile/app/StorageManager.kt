package org.jellyfin.mobile.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.kinolel.OfflineHelfer
import timber.log.Timber

class StorageManager(
    private val context: Context,
    private val appPreferences: AppPreferences
) {
    val defaultStorageLocation
        get() = Environment.getExternalStorageDirectory().resolve(context.getString(R.string.app_name_short)).toUri()

    // KinoLel (Lel 20.10.2026, Downloads wie bei Netflix): immer im geschützten App-Speicher – keine Ordnerwahl, Dateien
    // nicht in Galerie/Dateimanager sichtbar und beim Deinstallieren weg. Eine früher gewählte Ordner-Einstellung gilt nicht.
    fun getStorageLocation(): DocumentFile? = DocumentFile.fromFile(OfflineHelfer.downloadOrdner(context))

    fun isStorageLocationAccessible(): Boolean {
        val documentFile = getStorageLocation()
        return documentFile != null && documentFile.exists() && documentFile.canWrite()
    }

    fun changeStorageLocation(location: Uri): Boolean {
        if (appPreferences.storageLocation?.toUri() == location) return true

        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                location,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )

            appPreferences.storageLocation = location.toString()
            getStorageLocation()?.let(::ensureNoMedia)
        }.onFailure { err ->
            Timber.e(err, "Failed to change storage location to $location")
        }.isFailure
    }

    fun verify(download: DownloadFiles): Boolean {
        if (download.files.isEmpty()) return false

        for (file in download.files) {
            if (file.status != DownloadStatus.DOWNLOADED) return false
            if (OfflineHelfer.laenge(context, file.uri) != file.size) return false
        }

        return true
    }

    private fun ensureNoMedia(documentFile: DocumentFile) {
        if (documentFile.findFile(NOMEDIA_FILE) == null) {
            documentFile.createFile("", NOMEDIA_FILE)
        }
    }

    companion object {
        const val NOMEDIA_FILE = ".nomedia"
    }
}
