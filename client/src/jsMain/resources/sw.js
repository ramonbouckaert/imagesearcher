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
