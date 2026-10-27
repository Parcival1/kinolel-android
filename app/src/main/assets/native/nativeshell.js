const features = [
    "castmenuhashchange",
    "clientsettings",
    "displaylanguage",
    "downloadmanagement",
    "exit",
    "externallinks",
    "filedownload",
    "fileinput",
    "htmlaudioautoplay",
    "htmlvideoautoplay",
    // KinoLel: kein "multiserver" – fester Server https://kinolel.de, keine Serverauswahl
    "physicalvolumecontrol",
    "remotecontrol",
    "subtitleappearancesettings",
    "subtitleburnsettings"
];

const plugins = [
    'NavigationPlugin',
    'ExoPlayerPlugin',
    'ExternalPlayerPlugin',
    'MediaSegmentsPlugin'
];

// Add plugin loaders
for (const plugin of plugins) {
    window[plugin] = async () => {
        const pluginDefinition = await import(`/native/${plugin}.js`);
        return pluginDefinition[plugin];
    };
}

const { deviceId, deviceName, appName, appVersion } = JSON.parse(window.NativeInterface.getDeviceInformation());
const codecCaps = JSON.parse(window.NativeInterface.getCodecCapabilities());

window.NativeShell = {
    enableFullscreen() {
        window.NativeInterface.enableFullscreen();
    },

    disableFullscreen() {
        window.NativeInterface.disableFullscreen();
    },

    openUrl(url, target) {
        window.NativeInterface.openUrl(url);
    },

    updateMediaSession(mediaInfo) {
        window.NativeInterface.updateMediaSession(JSON.stringify(mediaInfo));
    },

    hideMediaSession() {
        window.NativeInterface.hideMediaSession();
    },

    updateVolumeLevel(value) {
        window.NativeInterface.updateVolumeLevel(value);
    },

    downloadFile(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify([downloadInfo]));
    },

    downloadFiles(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify(downloadInfo));
    },

    openDownloadManager() {
        window.NativeInterface.openDownloadManager();
    },

    openClientSettings() {
        window.NativeInterface.openClientSettings();
    },

    selectServer() {
        // KinoLel: fester Server – statt Serverauswahl nur neu laden
        window.location.reload();
    },

    getPlugins() {
        return plugins;
    },

    async execCast(action, args, callback) {
        this.castCallbacks = this.castCallbacks || {};
        this.castCallbacks[action] = callback;
        window.NativeInterface.execCast(action, JSON.stringify(args));
    },

    async castCallback(action, keep, err, result) {
        const callbacks = this.castCallbacks || {};
        const callback = callbacks[action];
        callback && callback(err || null, result);
        if (!keep) {
            delete callbacks[action];
        }
    }
};

function getDeviceProfile(profileBuilder, item) {
    return profileBuilder();
}

window.NativeShell.AppHost = {
    init() {},
    getDefaultLayout() {
        return "mobile";
    },
    supports(command) {
        command = command.toLowerCase();
        if (command === "chromecast") {
            return window.NativeInterface.hasChromecast();
        }
        return features.includes(command);
    },
    getDeviceProfile,
    deviceName() {
        return deviceName;
    },
    deviceId() {
        return deviceId;
    },
    appName() {
        return appName;
    },
    appVersion() {
        return appVersion;
    },
    exit() {
        window.NativeInterface.exitApp();
    }
};

// KinoLel (Lel 20.10.2026): Knopf „Herunterladen“ wie bei Netflix (native/kinolelDownloads.js)
import('/native/kinolelDownloads.js').catch((e) => console.warn('KinoLel-Downloads nicht geladen', e));
// KinoLel (Lel 27.10.2026): Trailer verlassen, während er noch lädt (native/kinolelTrailer.js)
import('/native/kinolelTrailer.js').catch((e) => console.warn('KinoLel-Trailer nicht geladen', e));
