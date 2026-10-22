package org.jellyfin.mobile.downloads

import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.io.IOException
import kotlin.coroutines.resumeWithException

class FileDownloader(
    private val okHttpClient: OkHttpClient,
) {
    fun interface ProgressCallback {
        suspend fun onProgress(downloaded: Long, total: Long)

        companion object Empty : ProgressCallback {
            override suspend fun onProgress(downloaded: Long, total: Long) = Unit
        }
    }

    private suspend fun download(
        api: ApiClient,
        from: Uri,
        rangeStart: Long? = null,
    ): Response {
        val authorizationHeader = AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        )

        val request = Request.Builder().apply {
            url(from.toString())

            header("Authorization", authorizationHeader)
            rangeStart?.let { header("Range", "bytes=$rangeStart-") }
        }.build()

        val response = okHttpClient.newCall(request).await()

        // 416 (Requested Range Not Satisfiable) can happen when we've already fully downloaded the file
        if (response.code == 416 && rangeStart != null && rangeStart >= response.getContentRange().total) return response

        // KinoLel: Fehlerantwort des Servers (z. B. 500, wenn die Umwandlung scheitert) ist kein Netzproblem – nicht endlos
        // als „wartet“ wiederholen, sondern als Fehler zeigen („tippen zum Wiederholen“). Nur 408/429/5xx-Überlast bleiben IO.
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 408 || code == 429 || code == 502 || code == 503 || code == 504) throw IOException("Server vorübergehend: $code")
            throw ServerFehler(code)
        }

        return response
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { cause, response, _ ->
                        response.close()
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            },
        )

        continuation.invokeOnCancellation {
            cancel()
        }
    }

    private fun Response.getContentRange() = when (code) {
        // KinoLel: Bei Umwandlung durch den Server (fortlaufender Datenstrom) fehlt die Länge -> Gesamtgröße unbekannt (0)
        200 -> header("Content-Length")?.let(ContentRange::fromContentLengthHeader) ?: ContentRange(0, 0, 0)
        206, 416 -> requireNotNull(header("Content-Range")).let(ContentRange::fromContentRangeHeader)
        else -> error("Invalid response code $code")
    }

    private suspend fun save(
        response: Response,
        to: ParcelFileDescriptor,
        progressCallback: ProgressCallback,
    ) = withContext(Dispatchers.IO) {
        val contentRange = response.getContentRange()

        val output = ParcelFileDescriptor.AutoCloseOutputStream(to)
        output.channel.position(contentRange.start)

        val inputStream = response.body?.byteStream() ?: error("Response does not contain a body")
        inputStream.use { inputStream ->
            output.use { outputFile ->
                val buffer = ByteArray(10240)
                var totalRead = contentRange.start
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    coroutineContext.ensureActive()

                    outputFile.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    progressCallback.onProgress(totalRead, contentRange.total)
                }
            }
        }
    }

    suspend fun downloadAndSave(
        api: ApiClient,
        from: Uri,
        to: ParcelFileDescriptor,
        progressCallback: ProgressCallback = ProgressCallback.Empty,
        fortsetzbar: Boolean = true,
    ) {
        // KinoLel: nicht fortsetzbare Downloads (Umwandlung, Untertitel) immer von vorn, ohne Range
        val rangeStart = if (fortsetzbar) to.statSize else null
        val response = download(api, from, rangeStart)
        save(response, to, progressCallback)
    }
}

/** KinoLel: Der Server hat den Download abgelehnt bzw. ist dabei gescheitert (HTTP-Fehler). */
class ServerFehler(val code: Int) : Exception("Server antwortet mit HTTP $code")
