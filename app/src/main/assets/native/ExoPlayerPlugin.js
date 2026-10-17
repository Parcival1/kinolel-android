export class ExoPlayerPlugin {
    constructor({ events, playbackManager, appSettings, loading }) {
        window['ExoPlayer'] = this;

        this.events = events;
        this.playbackManager = playbackManager;
        this.appSettings = appSettings;
        this.loading = loading;

        this.name = 'ExoPlayer';
        this.type = 'mediaplayer';
        this.id = 'exoplayer';

        // Prioritize first
        this.priority = -1;
        this.isLocalPlayer = true;

        // Current playback position in milliseconds
        this._currentTime = 0;
        this._paused = true;

        this._nativePlayer = window['NativePlayer'];

        // KinoLel (Funktionstest 17.10.2026, F1): Server-Befehle (Pause/Fortsetzen/Stopp/Spulen, z. B. vom Dashboard oder
        // der Wiedergabe-Grenze) kommen ueber die Live-Verbindung der Weboberflaeche. Die fuehrt den nativen Player nicht
        // als ihren aktuellen Player und verwirft die Befehle - deshalb hier direkt an den nativen Player weiterreichen.
        this._kinolelBefehl = this._kinolelBefehl.bind(this);
        this._kinolelBinden();
    }

    async play(options) {
        // Sanitize input
        options.ids = options.items.map(item => item.Id);
        delete options.items;

        const preferences = {
            maxStreamingBitrateLocal: this.appSettings.maxStreamingBitrate(true, 'Video'),
            maxStreamingBitrateRemote: this.appSettings.maxStreamingBitrate(false, 'Video'),
        };

        this._kinolelBinden();
        this._paused = false;
        this._nativePlayer.loadPlayer(JSON.stringify(options), JSON.stringify(preferences));
        this.loading.hide();
    }

    shuffle(item) {}

    instantMix(item) {}

    queue(options) {}

    queueNext(options) {}

    canPlayMediaType(mediaType) {
        return mediaType === 'Video';
    }

    canQueueMediaType(mediaType) {
        return this.canPlayMediaType(mediaType);
    }

    canPlayItem(item, playOptions) {
        return this._nativePlayer.isEnabled() &&
            playOptions.fullscreen &&
            !this.playbackManager.syncPlayEnabled;
    }

    async stop(destroyPlayer) {
        this._nativePlayer.stopPlayer();

        if (destroyPlayer) {
            this.destroy();
        }
    }

    nextTrack() {}

    previousTrack() {}

    seek(ticks) {
        this._nativePlayer.seekTicks(ticks);
    }

    currentTime(ms) {
        if (ms !== undefined) {
            this._nativePlayer.seekMs(ms);
        }
        return this._currentTime;
    }

    duration(val) {
        return null;
    }

    /**
     * Get or set volume percentage as as string
     */
    volume(volume) {
        if (volume !== undefined) {
            this.setVolume(volume);
        }
        return null;
    }

    getVolume() {}

    setVolume(vol) {
        let volume = parseInt(vol);
        this._nativePlayer.setVolume(volume);
    }

    volumeUp() {}

    volumeDown() {}

    isMuted() {
        return false;
    }

    setMute(mute) {
        // Assume 30% as default when unmuting
        this._nativePlayer.setVolume(mute ? 0 : 30);
    }

    toggleMute() {}

    paused() {
        return this._paused;
    }

    pause() {
        this._paused = true;
        this._nativePlayer.pausePlayer();
    }

    unpause() {
        this._paused = false;
        this._nativePlayer.resumePlayer();
    }

    playPause() {
        if (this._paused) {
            this.unpause();
        } else {
            this.pause();
        }
    }

    canSetAudioStreamIndex() {
        return false;
    }

    setAudioStreamIndex(index) {}

    setSubtitleStreamIndex(index) {}

    async changeAudioStream(index) {}

    async changeSubtitleStream(index) {}

    getPlaylist() {
        return Promise.resolve([]);
    }

    getCurrentPlaylistItemId() {}

    setCurrentPlaylistItem() {
        return Promise.resolve();
    }

    removeFromPlaylist() {
        return Promise.resolve();
    }

    destroy() {
        this._nativePlayer.destroyPlayer();
    }

    // KinoLel (Funktionstest 16.10.2026, F3): Der native Player meldet sein Ende (PlayerViewModel.reportPlaybackStop).
    // Ohne diese Meldung hielt die Weboberflaeche den Player fuer weiter aktiv - der naechste Tipp auf
    // "Abspielen/Fortsetzen" ging ins Leere. Die echte Position mitgeben: die Weboberflaeche meldet beim Ereignis
    // "stopped" selbst einen Stopp an den Server und wuerde sonst die Fortsetzen-Stelle mit 0 ueberschreiben.
    kinolelBeendet(positionMs) {
        this._currentTime = positionMs || 0;
        this._paused = true;
        // keine automatische naechste Folge aus der Weboberflaeche - das macht der native Player selbst
        this.playbackManager._playNextAfterEnded = false;
        this.events.trigger(this, 'stopped', [{}]);
    }

    _kinolelBinden() {
        const api = window['ApiClient'];
        if (!api || api === this._kinolelApi) return;
        if (this._kinolelApi) this.events.off(this._kinolelApi, 'message', this._kinolelBefehl);
        this.events.on(api, 'message', this._kinolelBefehl);
        this._kinolelApi = api;
    }

    _kinolelBefehl(e, msg) {
        if (!msg || msg.MessageType !== 'Playstate' || !msg.Data) return;
        // Spielt ein Player der Weboberflaeche, bekommt der den Befehl selbst (sonst doppelt, z. B. PlayPause)
        if (this.playbackManager.getCurrentPlayer()) return;
        switch (msg.Data.Command) {
            case 'Stop': this.stop(); break;
            case 'Pause': this.pause(); break;
            case 'Unpause': this.unpause(); break;
            case 'PlayPause': this.playPause(); break;
            case 'Seek':
                if (msg.Data.SeekPositionTicks != null) this.seek(msg.Data.SeekPositionTicks);
                break;
        }
    }

    async getDeviceProfile() {
        return {
            Name: 'ExoPlayer Stub',
            MaxStreamingBitrate: 100000000,
            MaxStaticBitrate: 100000000,
            MusicStreamingTranscodingBitrate: 320000,
            DirectPlayProfiles: [{Type: 'Video'}, {Type: 'Audio'}],
            CodecProfiles: [],
            SubtitleProfiles: [],
            TranscodingProfiles: []
        };
    }
}
