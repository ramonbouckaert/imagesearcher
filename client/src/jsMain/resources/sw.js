// The version comes from the `?v=` on the registration URL — see SW_VERSION in Main.kt, which is
// the single place to bump it. It is only used for logging here; the client side is what decides
// when to tear down an old worker.
const SW_VERSION = new URL(self.location.href).searchParams.get('v') || 'dev';

// Without these two, a newly installed worker sits in "waiting" and the previously installed one
// keeps controlling every already-open tab until all of them are closed — which is how a client
// ends up running an old worker indefinitely. skipWaiting() activates this worker as soon as it is
// installed, and clients.claim() makes it take over pages that are already open.
self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', event => {
    console.log('Service worker ' + SW_VERSION + ' activated');
    event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);
    if (event.request.destination !== 'image') return;
    if (!url.pathname.startsWith('/thumbnails/')) return;

    const rest     = url.pathname.slice('/thumbnails/'.length);
    const thumbUrl = event.request.url;
    const fullUrl  = url.origin + '/images/' + rest + url.search;

    event.respondWith(raceImageFetch(thumbUrl, fullUrl));
});

async function raceImageFetch(thumbUrl, fullUrl) {
    const thumbAbort = new AbortController();
    const fullAbort  = new AbortController();

    return new Promise((resolve, reject) => {
        let won = false;

        function win(response, loser) {
            if (!won) {
                won = true;
                try { loser.abort(); } catch (_) {}
                resolve(response);
            }
        }

        fetch(thumbUrl, { signal: thumbAbort.signal })
            .then(r => { if (r.ok) win(r, fullAbort); })
            .catch(() => {}); // aborted or thumbnail generation failed — let full image win

        fetch(fullUrl, { signal: fullAbort.signal })
            .then(r => { if (r.ok) win(r, thumbAbort); else reject(new Error('image fetch failed')); })
            .catch(err => { if (err.name !== 'AbortError') reject(err); });
    });
}
