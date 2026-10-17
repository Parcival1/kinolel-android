package org.jellyfin.mobile.bridge

import android.webkit.JavascriptInterface
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.interaction.PlayerEvent
import org.jellyfin.mobile.player.interaction.PlayerWebPreferences
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.time.Duration.Companion.milliseconds

@Suppress("unused")
class NativePlayer(
    private val appPreferences: AppPreferences,
    private val activityEventHandler: ActivityEventHandler,
    private val playerEventChannel: Channel<PlayerEvent>,
) : KoinComponent {

    @JavascriptInterface
    fun isEnabled() = appPreferences.videoPlayerType == VideoPlayerType.EXO_PLAYER

    @JavascriptInterface
    fun loadPlayer(playOptionsRaw: String, preferencesRaw: String) {
        val playOptions = PlayOptions.fromJson(playOptionsRaw) ?: return
        val preferences = Json.decodeFromString<PlayerWebPreferences>(preferencesRaw)

        // KinoLel (Funktionstest 16.10.2026, F2): Sicherung - ohne Serveradresse blieb der Player schwarz
        val apiClient: ApiClient = get()
        if (apiClient.baseUrl == null) apiClient.update(baseUrl = BuildConfig.SERVER_URL)

        activityEventHandler.emit(ActivityEvent.LaunchNativePlayer(playOptions, preferences))
    }

    @JavascriptInterface
    fun pausePlayer() {
        playerEventChannel.trySend(PlayerEvent.Pause)
    }

    @JavascriptInterface
    fun resumePlayer() {
        playerEventChannel.trySend(PlayerEvent.Resume)
    }

    @JavascriptInterface
    fun stopPlayer() {
        playerEventChannel.trySend(PlayerEvent.Stop)
    }

    @JavascriptInterface
    fun destroyPlayer() {
        playerEventChannel.trySend(PlayerEvent.Destroy)
    }

    @JavascriptInterface
    fun seekTicks(ticks: Long) {
        playerEventChannel.trySend(PlayerEvent.Seek(ticks.ticks))
    }

    @JavascriptInterface
    fun seekMs(ms: Long) {
        playerEventChannel.trySend(PlayerEvent.Seek(ms.milliseconds))
    }

    @JavascriptInterface
    fun setVolume(volume: Int) {
        playerEventChannel.trySend(PlayerEvent.SetVolume(volume))
    }
}
