// KinoLel (Lel 27.10.2026): „… wenn ich aus der Kinolel App nach einem Trailer rausgehe, kann es dazu kommen, dass die
// Interfaces sich überlagern, da ein Trailer noch am Laden ist. Kein Ausweg außer App-Neustart.“
//
// YouTube-Trailer spielt die Weboberfläche selbst (Modul „youtubeplayer“, nicht der native Player). Solange der Trailer
// lädt, kennt die Wiedergabe-Steuerung ihn noch nicht: „Zurück“ verließ nur die Seite, der Trailer lud weiter, legte
// seine Ebene (.youtubePlayerContainer) über die Oberfläche und sprang beim Start in den Player. Das Stoppen des Moduls
// scheitert, solange YouTube nicht fertig ist (stopVideo fehlt noch) – deshalb kam man nicht mehr heraus.
// Hier: Zurück (Taste der App, siehe NavigationPlugin.goBack) oder Seitenwechsel während des Ladens bricht den Trailer
// ab – Modul zurücksetzen, Ebene entfernen, spätere Meldungen von YouTube zu diesem Trailer verwerfen.
(function () {
    'use strict';
    if (window.KinoLelTrailer) return;

    let laedt = false;              // Trailer gestartet, aber noch nicht im Player (#/video)
    let versuch = 0;                // jeder Trailer-Start zählt hoch
    let abgebrochenerVersuch = -1;  // dieser Versuch wurde abgebrochen: alles, was von ihm noch kommt, verwerfen
    let abgebrochenUm = 0;
    let letzterKlick = 0;
    let wache = null;
    const protokoll = []; // für den Test: was ist passiert
    const merken = (text) => { protokoll.push(Math.round(performance.now()) + ' ' + text); if (protokoll.length > 50) protokoll.shift(); };

    const imPlayer = () => /^#\/video/.test(location.hash);
    const ebenen = () => Array.from(document.querySelectorAll('.youtubePlayerContainer'));
    const wiedergabe = () => window.ExoPlayer && window.ExoPlayer.playbackManager;
    const abgebrochen = () => versuch === abgebrochenerVersuch;

    function youtubeModul() {
        const pm = wiedergabe();
        try {
            return (pm && pm.getPlayers && pm.getPlayers().find((p) => p && p.id === 'youtubeplayer')) || null;
        } catch (e) {
            return null;
        }
    }

    function neuerVersuch(grund) {
        versuch++;
        laedt = true;
        merken('Trailer gestartet (' + grund + ')');
        if (wache) { clearInterval(wache); wache = null; }
    }

    // Start des Moduls abfangen – egal, von wo der Trailer kommt (Knopf, Menü)
    function modulBeobachten() {
        const yt = youtubeModul();
        if (!yt || yt.__kinolel) return !!yt;
        yt.__kinolel = true;
        const abspielen = yt.play;
        yt.play = function () {
            if (abgebrochen() && Date.now() - abgebrochenUm < 15000 && letzterKlick < abgebrochenUm) {
                // gehört noch zum abgebrochenen Antippen – nicht mehr starten
                merken('Trailer-Start nach Abbruch verworfen');
                return new Promise(() => {});
            }
            if (!laedt || abgebrochen()) neuerVersuch('Modul');
            return abspielen.apply(this, arguments);
        };
        return true;
    }

    // YouTube-Player dieses Versuchs: Meldungen (bereit, spielt, Fehler) nach einem Abbruch nicht mehr weitergeben
    function youtubeBeobachten() {
        const YT = window.YT;
        if (!YT || typeof YT.Player !== 'function' || YT.Player.__kinolel) return;
        const Original = YT.Player;
        const Ersatz = function (element, optionen) {
            const meiner = versuch;
            const ereignisse = (optionen && optionen.events) || {};
            const gefiltert = {};
            for (const name of Object.keys(ereignisse)) {
                const weiter = ereignisse[name];
                gefiltert[name] = function (e) {
                    if (meiner === abgebrochenerVersuch) {
                        merken('YouTube-Meldung nach Abbruch verworfen: ' + name);
                        try { if (e && e.target && e.target.destroy) e.target.destroy(); } catch (x) { /* egal */ }
                        return undefined;
                    }
                    if (name === 'onStateChange' && e && e.data === 1) laedt = false; // spielt -> gleich im Player
                    return weiter.apply(this, arguments);
                };
            }
            return new Original(element, Object.assign({}, optionen, { events: gefiltert }));
        };
        Ersatz.prototype = Original.prototype;
        Ersatz.__kinolel = true;
        try { YT.Player = Ersatz; } catch (e) { /* dann greift nur das Aufräumen */ }
    }

    function aufraeumen() {
        const yt = youtubeModul();
        if (yt && !yt.started) {
            // wie stop() des Moduls, aber ohne YouTube (noch nicht bereit) und ohne „gestoppt“ zu melden
            if (yt.timeUpdateInterval) { clearInterval(yt.timeUpdateInterval); yt.timeUpdateInterval = null; }
            if (yt.resizeListener) {
                window.removeEventListener('resize', yt.resizeListener);
                window.removeEventListener('orientationChange', yt.resizeListener);
                yt.resizeListener = null;
            }
            try { if (yt.currentYoutubePlayer && yt.currentYoutubePlayer.destroy) yt.currentYoutubePlayer.destroy(); } catch (e) { /* egal */ }
            yt.currentYoutubePlayer = null;
            yt._currentSrc = null;
            yt.videoDialog = null; // Ebene entfernen wir unten (destroy() des Moduls würde auch den Hintergrund der Seite abschalten)
        }
        for (const ebene of ebenen()) {
            const rahmen = ebene.querySelector('iframe');
            try {
                if (rahmen && rahmen.contentWindow) {
                    rahmen.contentWindow.postMessage(JSON.stringify({ event: 'command', func: 'stopVideo', args: [] }), '*');
                }
            } catch (e) { /* egal */ }
            ebene.remove();
        }
        if (!imPlayer()) document.body.classList.remove('hide-scroll');
        try { const exo = window.ExoPlayer; if (exo && exo.loading) exo.loading.hide(); } catch (e) { /* egal */ }
    }

    function abbrechen(grund) {
        laedt = false;
        abgebrochenerVersuch = versuch;
        abgebrochenUm = Date.now();
        console.info('KinoLel: ladender Trailer abgebrochen (' + grund + ')');
        merken('abgebrochen: ' + grund);
        aufraeumen();
        // Teile, die erst danach fertig laden (Ebene, YouTube-Skript), ebenfalls wegräumen – bis zum nächsten Trailer
        if (wache) clearInterval(wache);
        const bis = Date.now() + 30000;
        wache = setInterval(() => {
            if (!abgebrochen() || Date.now() > bis) { clearInterval(wache); wache = null; return; }
            const yt = youtubeModul();
            if (ebenen().length || (yt && yt._currentSrc && !yt.started)) aufraeumen();
        }, 250);
    }

    // Trailer-Knopf angetippt: neuer Versuch (früher als das Modul, das erst nachlädt)
    document.addEventListener('click', (e) => {
        letzterKlick = Date.now();
        modulBeobachten();
        if (e.target && e.target.closest && e.target.closest('.btnPlayTrailer')) neuerVersuch('Knopf');
    }, true);

    // Seitenwechsel (z. B. Zurück-Pfeil der Kopfleiste) während des Ladens
    window.addEventListener('hashchange', () => {
        if (imPlayer()) {
            laedt = false;
            return;
        }
        if (laedt) abbrechen('Seite verlassen');
    });

    // YouTube ruft window.onYouTubeIframeAPIReady auf, sobald sein Skript da ist (bzw. das Modul sofort, wenn es schon
    // da ist): YouTube-Player beobachten und nach einem Abbruch gar nicht erst anlegen
    let bereitRueckruf;
    try {
        Object.defineProperty(window, 'onYouTubeIframeAPIReady', {
            configurable: true,
            get() {
                return function () {
                    youtubeBeobachten();
                    if (abgebrochen()) {
                        merken('YouTube-Skript nach Abbruch: Player nicht angelegt');
                        return undefined;
                    }
                    return bereitRueckruf ? bereitRueckruf.apply(this, arguments) : undefined;
                };
            },
            set(fn) { bereitRueckruf = fn; merken('Modul wartet auf YouTube'); }
        });
    } catch (e) { /* ältere WebView: dann greift nur das Aufräumen */ }

    // Modul ist erst nach dem Start der Weboberfläche angemeldet
    let suche = 0;
    const sucher = setInterval(() => { if (modulBeobachten() || ++suche > 120) clearInterval(sucher); }, 1000);

    window.KinoLelTrailer = {
        protokoll,
        /** Zurück-Taste der App: true = nur den ladenden Trailer abgebrochen (auf der Seite bleiben) */
        zurueck() {
            const yt = youtubeModul();
            const sichtbar = ebenen().length > 0;
            const modulLaedt = !!(yt && yt._currentSrc && !yt.started);
            merken('Zurück (lädt: ' + laedt + ', Modul lädt: ' + modulLaedt + ', Ebene: ' + sichtbar + ')');
            if (!imPlayer() && (laedt || modulLaedt || sichtbar)) {
                abbrechen('Zurück');
                return true;
            }
            return false;
        }
    };
})();
