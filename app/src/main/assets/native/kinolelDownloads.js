// KinoLel (Lel 20.10.2026): „Downloadfunktion wie bei Netflix und Co. … direkt in der App anschauen, auch ohne Internet.“
// Sichtbarer Knopf „Herunterladen“ auf Film-, Folgen-, Staffel- und Serienseiten (statt nur im „…“-Menü), mit Status und
// Fortschritt; kleine Knöpfe an den Folgen einer Staffel. Herunterladen/Öffnen übernimmt die App (NativeInterface).
(function () {
    'use strict';
    if (window.__kinolelDownloads) return;
    window.__kinolelDownloads = true;
    const NI = window.NativeInterface;
    if (!NI || !NI.kinolelDownloadStatus) return;

    const TEXTE = {
        de: {
            laden: 'Herunterladen', staffel: 'Staffel herunterladen', serie: 'Alle Folgen herunterladen',
            fertig: 'Heruntergeladen – in „Downloads“ ansehen', laedt: 'Wird heruntergeladen', wartet: 'Wartet auf den Download (WLAN?)',
            fehler: 'Download fehlgeschlagen – erneut versuchen', teil: '%1 von %2 Folgen heruntergeladen'
        },
        hu: {
            laden: 'Letöltés', staffel: 'Évad letöltése', serie: 'Összes epizód letöltése',
            fertig: 'Letöltve – megnézhető a „Letöltések” között', laedt: 'Letöltés folyamatban', wartet: 'Letöltésre vár (Wi-Fi?)',
            fehler: 'A letöltés nem sikerült – újra', teil: '%2 epizódból %1 letöltve'
        },
        en: {
            laden: 'Download', staffel: 'Download season', serie: 'Download all episodes',
            fertig: 'Downloaded – watch in “Downloads”', laedt: 'Downloading', wartet: 'Waiting to download (Wi-Fi?)',
            fehler: 'Download failed – try again', teil: '%1 of %2 episodes downloaded'
        }
    };
    const VIDEO = ['Movie', 'Episode', 'Video', 'MusicVideo'];
    const SAMMLUNG = ['Season', 'Series'];
    const SYMBOL = { fertig: 'download_done', laedt: 'downloading', wartet: 'schedule', fehler: 'error_outline' };

    function t() {
        const l = (document.documentElement.lang || navigator.language || 'de').slice(0, 2).toLowerCase();
        return TEXTE[l] || TEXTE.de;
    }

    let erlaubt = 'recht';
    try { erlaubt = NI.kinolelDownloadsErlaubt(); } catch (e) { /* alte App */ }
    let recht = null;
    const titel = new Map();

    function aktuelleId() {
        if (!/^#\/details/.test(location.hash)) return null;
        const m = location.hash.match(/[?&]id=([0-9a-f-]{32,36})/i);
        return m ? m[1].replace(/-/g, '').toLowerCase() : null;
    }

    async function holeTitel(id) {
        if (titel.has(id)) return titel.get(id);
        const api = window.ApiClient;
        if (!api || !api.getCurrentUserId()) return null;
        const versprechen = api.getItem(api.getCurrentUserId(), id).catch(() => null);
        titel.set(id, versprechen);
        return versprechen;
    }

    async function hatRecht() {
        if (recht === null) {
            try {
                const u = await window.ApiClient.getCurrentUser();
                recht = !!(u && u.Policy && u.Policy.EnableContentDownloading);
            } catch (e) { return false; }
        }
        return recht;
    }

    function status(ids) {
        try { return JSON.parse(NI.kinolelDownloadStatus(JSON.stringify(ids))) || {}; } catch (e) { return {}; }
    }

    function starten(id) {
        NI.downloadFiles(JSON.stringify([{ itemId: id }]));
    }

    function symbol(element, name) {
        const s = element.querySelector('.kl-dl-symbol');
        if (s && s.textContent !== name) s.textContent = name;
    }

    function neuerKnopf(klasse) {
        const b = document.createElement('button');
        b.type = 'button';
        b.setAttribute('is', 'emby-button');
        b.className = klasse;
        b.innerHTML = '<div class="detailButton-content"><span class="material-icons detailButton-icon kl-dl-symbol" aria-hidden="true">download</span>' +
            '<span class="kl-dl-prozent"></span></div>';
        return b;
    }

    function darstellen(b, st, text) {
        const zustand = st ? st.s : null;
        symbol(b, SYMBOL[zustand] || 'download');
        const p = b.querySelector('.kl-dl-prozent');
        const prozent = zustand === 'laedt' && st.p >= 0 ? st.p + ' %' : '';
        if (p.textContent !== prozent) p.textContent = prozent;
        const titelText = zustand ? (t()[zustand] || text) : text;
        if (b.title !== titelText) { b.title = titelText; b.setAttribute('aria-label', titelText); }
        b.dataset.zustand = zustand || '';
    }

    function stil() {
        if (document.getElementById('kl-dl-stil')) return;
        const s = document.createElement('style');
        s.id = 'kl-dl-stil';
        s.textContent =
            '.kl-dl .detailButton-content{display:flex;flex-direction:column;align-items:center}' +
            '.kl-dl .kl-dl-prozent{font-size:.7em;line-height:1;margin-top:2px}.kl-dl .kl-dl-prozent:empty{display:none}' +
            '.kl-dl[data-zustand="fertig"] .kl-dl-symbol,.kl-dl-mini[data-zustand="fertig"] .kl-dl-symbol{color:#9aa4ff}' +
            '.kl-dl-mini{background:none;border:0;color:inherit;padding:.3em;margin:0 .2em;cursor:pointer;flex-shrink:0;display:inline-flex;align-items:center}' +
            '.kl-dl-mini .detailButton-content{display:flex;align-items:center;gap:.2em}.kl-dl-mini .kl-dl-prozent{font-size:.75em}' +
            '.kl-dl-mini .kl-dl-prozent:empty{display:none}';
        document.head.appendChild(s);
    }

    // Kleine Knöpfe an den Folgen einer Staffel/Serie
    function folgenKnoepfe(seite) {
        const zeilen = Array.from(seite.querySelectorAll('.listItem[data-id][data-type="Episode"], .listItem[data-id][data-isfolder="false"]'))
            .filter((z) => !z.closest('.kl-dl-mini'));
        if (!zeilen.length) return [];
        const ids = zeilen.map((z) => z.getAttribute('data-id').replace(/-/g, '').toLowerCase());
        const st = status(ids);
        zeilen.forEach((zeile, i) => {
            const id = ids[i];
            let b = zeile.querySelector(':scope .kl-dl-mini');
            if (!b) {
                b = neuerKnopf('kl-dl-mini');
                b.dataset.id = id;
                b.addEventListener('click', (ev) => {
                    ev.preventDefault();
                    ev.stopPropagation();
                    const z = b.dataset.zustand;
                    if (z === 'fertig' || z === 'laedt' || z === 'wartet') NI.openDownloadManager();
                    else starten(id);
                }, true);
                const ziel = zeile.querySelector('.listViewUserDataButtons') || zeile;
                ziel.insertBefore(b, ziel.firstChild);
            }
            darstellen(b, st[id], t().laden);
        });
        return ids.map((id) => st[id]);
    }

    let laeuft = false;
    async function aktualisieren() {
        if (laeuft) return;
        laeuft = true;
        try {
            const id = aktuelleId();
            const seite = document.querySelector('.itemDetailPage:not(.hide)');
            if (!id || !seite) return;
            const leiste = seite.querySelector('.mainDetailButtons');
            if (!leiste) return;
            const it = await holeTitel(id);
            const vorhanden = leiste.querySelector('.kl-dl');
            const istVideo = it && VIDEO.includes(it.Type);
            const istSammlung = it && SAMMLUNG.includes(it.Type);
            let darf = istVideo || istSammlung;
            if (darf && erlaubt === 'recht') darf = istVideo ? !!it.CanDownload : await hatRecht();
            if (!darf) {
                if (vorhanden) vorhanden.remove();
                return;
            }
            stil();
            let b = vorhanden;
            if (!b || b.dataset.id !== id) {
                if (b) b.remove();
                b = neuerKnopf('button-flat detailButton kl-dl');
                b.dataset.id = id;
                b.addEventListener('click', () => {
                    const z = b.dataset.zustand;
                    if (z === 'fertig' || (istVideo && (z === 'laedt' || z === 'wartet'))) NI.openDownloadManager();
                    else starten(id);
                });
                const play = Array.from(leiste.querySelectorAll('.btnPlay, .btnReplay, .btnResume')).pop();
                leiste.insertBefore(b, play ? play.nextSibling : leiste.firstChild);
            }
            if (istVideo) {
                darstellen(b, status([id])[id], t().laden);
            } else {
                const folgen = folgenKnoepfe(seite);
                const fertig = folgen.filter((s) => s && s.s === 'fertig').length;
                const laedt = folgen.find((s) => s && (s.s === 'laedt' || s.s === 'wartet'));
                const text = it.Type === 'Season' ? t().staffel : t().serie;
                darstellen(b, laedt ? { s: laedt.s, p: laedt.p } : (folgen.length && fertig === folgen.length ? { s: 'fertig', p: 100 } : null), text);
                if (fertig && fertig < folgen.length && !laedt) {
                    const hinweis = t().teil.replace('%1', fertig).replace('%2', folgen.length);
                    b.title = text + ' (' + hinweis + ')';
                }
            }
        } catch (e) {
            console.warn('KinoLel-Downloads:', e);
        } finally {
            laeuft = false;
        }
    }

    setInterval(aktualisieren, 1500);
    window.addEventListener('hashchange', () => setTimeout(aktualisieren, 300));
})();
