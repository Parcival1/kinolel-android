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
        // der Wiedergabe-Grenze) gibt die Weboberflaeche an den playbackManager weiter. Den nativen Player fuehrt sie aber
        // nicht als aktuellen Player - ohne den verpufften die Befehle (Spulen warf sogar "player cannot be null").
        this._kinolelWeiterleiten();
    }

    async play(options) {
        // Sanitize input
        options.ids = options.items.map(item => item.Id);
        delete options.items;

        const preferences = {
            maxStreamingBitrateLocal: this.appSettings.maxStreamingBitrate(true, 'Video'),
            maxStreamingBitrateRemote: this.appSettings.maxStreamingBitrate(false, 'Video'),
        };

        // KinoLel (17.10.2026): Fokus von der angetippten Kachel nehmen. Sonst spielt die Weboberflaeche bei "Fortsetzen"
        // vom Server (Befehl "play") die fokussierte Kachel ein zweites Mal ab - ein zweiter nativer Player startet.
        try {
            if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
        } catch (e) { /* egal */ }
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

    // Ohne aktuellen Web-Player an den nativen Player. Laeuft der nicht, verwirft die App den Befehl
    // (NativePlayer -> Ereigniskanal ohne Empfaenger).
    _kinolelWeiterleiten() {
        const pm = this.playbackManager;
        const umleiten = (name, playerIndex, nativ) => {
            const original = pm[name];
            if (typeof original !== 'function') return;
            pm[name] = (...args) => {
                if (!args[playerIndex] && !pm.getCurrentPlayer()) return nativ(...args);
                return original.apply(pm, args);
            };
        };
        umleiten('pause', 0, () => this.pause());
        umleiten('unpause', 0, () => this.unpause());
        umleiten('playPause', 0, () => this.playPause());
        umleiten('stop', 0, () => this.stop());
        umleiten('seek', 1, ticks => this.seek(ticks));
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
