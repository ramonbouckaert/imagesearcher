# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A self-hosted photo search server: it indexes XMP metadata (tags, description, GPS) from an on-disk photo library into Lucene, and serves a Kotlin/JS single-page frontend with a search grid and a MapLibre map view.

## Build & run

```bash
./gradlew build                 # build everything
./gradlew :server:run           # run the server (builds the JS client first)
./gradlew :server:shadowJar     # fat jar at server/build/libs/
./gradlew check                 # all checks incl. tests
./gradlew :server:test --tests '*ThumbnailCacheTest*'                  # one test class
./gradlew :server:test --tests '*ThumbnailCacheTest.LRU eviction*'     # one test
```

Prerequisites:

- **GitHub Packages credentials.** The `kim` dependency (`de.stefan-oltmann:kim`) resolves from `maven.pkg.github.com/StefanOltmann/*`. Set `gpr.user` and `gpr.key` in `~/.gradle/gradle.properties` or the build cannot resolve dependencies.
- **libvips** must be installed natively for thumbnail generation (`vips-ffm`), and on Windows **its `bin` directory must be on `PATH`** — `libvips-42.dll` resolves ~60 sibling DLLs from that directory. Setting `VIPS_LIB_PATH` alone is *not* enough (the test task forwards it to `vipsffm.libpath.vips.override`, which only names the library, it does not help resolve its dependencies); conversely `PATH` alone is sufficient. A winget install lands in `%LOCALAPPDATA%\Microsoft\WinGet\Packages\libvips.libvips_*\vips-dev-<version>\bin`.
- Toolchains are auto-downloaded (foojay). `buildSrc` convention targets JDK 21; `:server` overrides to **JDK 24** (needs the FFM API and `jdk.incubator.vector`).

Runtime configuration is entirely environment variables (see `App.kt`): `PHOTO_LIBRARY_PATH` (default `/mnt/ramnas/Photos`), `PHOTO_YEAR` (index only one subdirectory), `URL_BASE_PATH`, `PORT` (8080), `THUMBNAIL_CACHE_MB` (256).

`URL_BASE_PATH` should be `/images` in practice: the server prefixes every returned result path with it, and the client's `thumbnailUrl()` rewrites `/images/...` → `/thumbnails/...`. Any other value breaks thumbnails in the browser.

### Test gotchas

**A failed libvips load kills the test JVM.** `ThumbnailCacheTest.vipsAvailable()` probes with `Vips.run { }`; when the native library cannot be loaded, the process aborts with `NTSTATUS 0xC0000409` (`STATUS_STACK_BUFFER_OVERRUN`) at teardown. The misleading part: the snapshot test still reports `SKIPPED` and the earlier tests report `PASSED`, so the output looks fine — but the run dies and `:server:test` fails with "Test process encountered an unexpected problem". The `assumeTrue` guard cannot prevent this; it's a native crash, not a catchable `Throwable`. If you see that NTSTATUS, fix `PATH` (above) rather than debugging the test.

**Snapshots are byte-exact and version-brittle.** `UPDATE_SNAPSHOTS=true` regenerates `server/src/test/resources/snapshots/`. The committed `thumbnail.avif` is 772 bytes; libvips 8.18 produces 965 bytes for the same input, differing in `ftyp` brand ordering and EXIF resolution fields as well as the compressed payload. So a snapshot mismatch usually means "different libvips/aom build", not a regression in `ThumbnailCache`. Confirm which before regenerating — the committed snapshot is the reference from the maintainer's environment.

## Module layout

- **`:utils`** — Kotlin Multiplatform (jvm + js). `commonMain` holds the wire types shared by server and client (`SearchResponse`, `SearchResult`); `jvmMain` holds `XmpReader` (kim + DOM XML parsing of the embedded XMP packet).
- **`:server`** — Kotlin/JVM, Ktor CIO. Lucene index, file watcher, thumbnail cache, routes.
- **`:client`** — Kotlin/JS browser target, no framework — direct DOM manipulation and `js("…")` interop for MapLibre GL (loaded from unpkg at runtime, not bundled).
- **`buildSrc`** — the `buildsrc.convention.kotlin-jvm` plugin (JVM toolchain + JUnit Platform + test logging).

`:server`'s `processResources` depends on `:client:jsBrowserDistribution` and copies the JS bundle into `static/`, which `Routes.kt` serves via `staticResources("/", "static")`. So the client ships inside the server jar; there is no separate frontend deploy, and editing client code requires rebuilding the server resources.

## Architecture notes

**The Lucene index is in-memory and ephemeral.** `App.kt` uses `ByteBuffersDirectory`, so the whole library is re-scanned and re-indexed on every startup; `PhotoIndexer.indexAll()` parallelises the walk across `availableProcessors()` coroutines. `getAllIndexedPaths()` de-duplication exists for the incremental path, not for persistence.

**Only `.avif` files are indexed.** `imageExtensions` is hardcoded in both `PhotoIndexer` and `PhotoWatcher`; changing it requires editing both.

**Live updates**: `PhotoWatcher` uses kfswatch on the scan root plus its immediate subdirectories (one level deep only). Events are coalesced through a `LinkedHashMap` keyed by absolute path and drained by a separate `drain()` coroutine, so rapid repeat events for the same file collapse to one re-index. Each event commits the index.

**Search ranking** (`LuceneIndex.kt`): a blank query returns everything sorted by `lastModified` descending; a non-blank query goes through `QueryParser` on the `tags` field wrapped in a `FunctionScoreQuery` that applies up to a 20% recency boost over a 10-year window. Pagination is `search(offset + limit)` then `.drop(offset)` — fine for the intended library sizes, but O(offset).

**Map view is served as Mapbox Vector Tiles**, not GeoJSON. `searchByTile()` converts the z/x/y tile to a lat/lon box query, projects hits into tile pixel space, then does greedy score-ordered spatial clustering against a 480px radius (9-cell grid neighbourhood lookup); the winner carries a `count` property for the cluster. The client renders circles + count labels, and separately creates MapLibre popups showing thumbnails, filtering the circle layer to hide points that already have a visible popup.

**Thumbnail delivery races two requests.** `sw.js` intercepts image requests to `/thumbnails/*` and fetches both the thumbnail and the corresponding `/images/*` original, resolving with whichever responds first and aborting the loser. This matters when tuning cache headers: `/thumbnails` sets an ETag and `max-age=3600, stale-while-revalidate=86400`, `/images` sets `no-cache`.

`ThumbnailCache` is an LRU (`LinkedHashMap` with `accessOrder=true`) guarded by a coroutine `Mutex`, generating 512×512 AVIF via vips. Generation happens *outside* the lock, so concurrent requests for the same path may both generate — the loser's result is discarded.

Both `/thumbnails` and `/images` normalise the resolved path and reject anything escaping `libraryRoot` with 403; preserve that check when touching those routes.

**Shadow jar workaround**: `server/build.gradle.kts` has a `doLast` block that re-patches `META-INF/services/` entries after `shadowJar`. Shadow 9's `mergeServiceFiles()` drops entries when the same service file appears in multiple jars, which breaks Lucene's SPI codec discovery. Don't remove it without verifying the fat jar can still open an index.
