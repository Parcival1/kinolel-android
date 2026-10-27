// KinoLel (Lel 27.10.2026): „… wenn ich aus der Kinolel App nach einem Trailer rausgehe, kann es dazu kommen, dass die
// Interfaces sich überlagern, da ein Trailer noch am Laden ist. Kein Ausweg außer App-Neustart.“
//
// YouTube-Trailer spielt die Weboberfläche selbst (Modul „youtubeplayer“, nicht der native Player). Dessen stop() greift
// erst, wenn der Player fertig geladen ist: „Zurück“ während des Ladens ließ den Ladevorgang weiterlaufen, danach lag die
// YouTube-Ebene (.youtubePlayerContainer, „onTop“) mit Ladekreis über der Seite und nichts schloss sie mehr.
// Hier: Zurück (Taste der App, siehe NavigationPlugin.goBack) oder Seitenwechsel während des Ladens bricht den Trailer ab;
// eine YouTube-Ebene, die erst danach fertig lädt, wird sofort gestoppt und geschlossen.
(function () {
    'use strict';
    if (window.KinoLelTrailer) return;

    let laedt = false;      // Trailer angetippt, aber noch nicht im Player (#/video)
    let abgebrochen = 0;    // Zeitpunkt des Abbruchs – bis zum nächsten Trailer jede neue YouTube-Ebene schließen
    let wache = null;

    const imPlayer = () => /^#\/video/.test(location.hash);
    const ebenen = () => Array.from(document.querySelectorAll('.youtubePlayerContainer'));

    function aufraeumen() {
        const exo = window.ExoPlayer;
        const pm = exo && exo.playbackManager;
        const player = pm && pm.getCurrentPlayer && pm.getCurrentPlayer();
        if (player && player.id === 'youtubeplayer') {
            try { pm.stop(player); } catch (e) { /* weiter aufräumen */ }
        }
        for (const ebene of ebenen()) {
            ebene.style.display = 'none';
            const rahmen = ebene.querySelector('iframe');
            try {
                if (rahmen && rahmen.contentWindow) {
                    rahmen.contentWindow.postMessage(JSON.stringify({ event: 'command', func: 'stopVideo', args: [] }), '*');
                }
            } catch (e) { /* egal */ }
        }
        if (!imPlayer()) document.body.classList.remove('hide-scroll');
        try { if (exo && exo.loading) exo.loading.hide(); } catch (e) { /* egal */ }
    }

    function abbrechen(grund) {
        laedt = false;
        abgebrochen = Date.now();
        console.info('KinoLel: ladender Trailer abgebrochen (' + grund + ')');
        aufraeumen();
        if (wache) clearInterval(wache);
        const bis = Date.now() + 30000;
        wache = setInterval(() => {
            aufraeumen();
            if (Date.now() > bis) { clearInterval(wache); wache = null; }
        }, 300);
    }

    // Trailer angetippt: Zustand zurücksetzen (eine früher versteckte Ebene nutzt das Modul wieder)
    document.addEventListener('click', (e) => {
        if (e.target && e.target.closest && e.target.closest('.btnPlayTrailer')) {
            laedt = true;
            abgebrochen = 0;
            if (wache) { clearInterval(wache); wache = null; }
            for (const ebene of ebenen()) ebene.style.display = '';
        }
    }, true);

    // Seitenwechsel (z. B. Zurück-Pfeil der Kopfleiste) während des Ladens
    window.addEventListener('hashchange', () => {
        if (imPlayer()) {
            laedt = false; // Trailer läuft normal – außer er wurde vorher abgebrochen (dann stoppt aufraeumen ihn)
            return;
        }
        if (laedt) abbrechen('Seite verlassen');
    });

    // Lädt das YouTube-Skript erst nach dem Abbruch fertig, würde das Modul den Player trotzdem anlegen (und z. B. eine
    // Fehlermeldung zeigen). YouTube ruft dafür window.onYouTubeIframeAPIReady auf – nach einem Abbruch nicht weiterreichen.
    let bereitRueckruf;
    try {
        Object.defineProperty(window, 'onYouTubeIframeAPIReady', {
            configurable: true,
            get() {
                return function () {
                    if (abgebrochen && Date.now() - abgebrochen < 60000) {
                        console.info('KinoLel: YouTube nach Abbruch geladen – Trailer wird nicht mehr gestartet');
                        return undefined;
                    }
                    return bereitRueckruf ? bereitRueckruf.apply(this, arguments) : undefined;
                };
            },
            set(fn) { bereitRueckruf = fn; }
        });
    } catch (e) { /* ältere WebView: dann greift nur das Aufräumen */ }

    // YouTube-Ebene, die nach dem Abbruch noch fertig lädt
    new MutationObserver(() => {
        if (abgebrochen && Date.now() - abgebrochen < 60000 && ebenen().some((e) => e.style.display !== 'none')) aufraeumen();
    }).observe(document.body, { childList: true });

    window.KinoLelTrailer = {
        /** Zurück-Taste der App: true = nur den ladenden Trailer abgebrochen (auf der Seite bleiben) */
        zurueck() {
            const sichtbar = ebenen().some((e) => e.style.display !== 'none');
            if (laedt || (sichtbar && !imPlayer())) {
                abbrechen('Zurück');
                return true;
            }
            return false;
        }
    };
})();
