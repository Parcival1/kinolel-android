export class NavigationPlugin {
    constructor({ playbackManager }) {
        window['NavigationHelper'] = this;

        this.playbackManager = playbackManager;
    }

    goBack() {
        // KinoLel (27.10.2026): Zurück, während ein Trailer noch lädt -> nur den Trailer abbrechen (kinolelTrailer.js)
        if (window['KinoLelTrailer'] && window['KinoLelTrailer'].zurueck()) {
            return;
        }
        var appRouter = window['Emby']['Page'];
        if (appRouter.canGoBack()) {
            appRouter.back();
        } else {
            window['NativeInterface'].exitApp();
        }
    }
}
