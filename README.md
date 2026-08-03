# imagesearcher

A self-hosted photo search server. It indexes the XMP metadata embedded in your photo library — tags, description and GPS coordinates — into an Apache Lucene index, and serves a web frontend with a search grid and a map view.

- Full-text search over photo tags, with a mild recency boost
- Map view showing where photos were taken, clustered by zoom level
- On-the-fly AVIF thumbnails via libvips, cached in memory
- Live re-indexing as files are added, changed or removed

Currently only `.avif` files are indexed.

## Prerequisites

**JDK** — none needed up front; Gradle downloads the required toolchains automatically (the server targets JDK 24).

**libvips** — must be installed natively for thumbnail generation.

- Linux: `apt install libvips` (or your distribution's equivalent)
- macOS: `brew install vips`
- Windows: install libvips (e.g. `winget install libvips.libvips`) and **add its `bin` directory to `PATH`**

On Windows the `PATH` entry is the part that matters: `libvips-42.dll` loads around 60 sibling DLLs from the same directory, and the load fails if they cannot be resolved. `VIPS_LIB_PATH` — which the test task forwards to vips-ffm as an explicit library location — is not a substitute for it. A winget install puts the `bin` directory at:

```
%LOCALAPPDATA%\Microsoft\WinGet\Packages\libvips.libvips_Microsoft.Winget.Source_8wekyb3d8bbwe\vips-dev-<version>\bin
```

**GitHub Packages credentials** — the `kim` metadata library is published to GitHub Packages rather than Maven Central, so dependency resolution needs a token. Add to `~/.gradle/gradle.properties`:

```properties
gpr.user=your-github-username
gpr.key=your-personal-access-token   # needs read:packages scope
```

## Building and running

This project uses the [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) (`./gradlew`), so no local Gradle install is required.

```shell
./gradlew :server:run           # build and run the server
./gradlew build                 # build only
./gradlew check                 # run all checks, including tests
./gradlew :server:shadowJar     # self-contained jar in server/build/libs/
./gradlew clean                 # clean all build outputs
```

The web client is compiled from Kotlin to JavaScript and bundled into the server's resources, so `:server:run` and `shadowJar` build and include the frontend automatically. Then open <http://localhost:8080>.

To run the fat jar directly:

```shell
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -jar server/build/libs/server-all.jar
```

## Configuration

All configuration is via environment variables:

| Variable             | Default              | Description                                                        |
|----------------------|----------------------|--------------------------------------------------------------------|
| `PHOTO_LIBRARY_PATH` | `/mnt/ramnas/Photos` | Root of the photo library                                          |
| `PHOTO_YEAR`         | *(unset)*            | Index only this subdirectory of the library, rather than all of it |
| `URL_BASE_PATH`      | library path         | Prefix applied to image URLs in search results — set this to `/images` |
| `PORT`               | `8080`               | HTTP port                                                          |
| `THUMBNAIL_CACHE_MB` | `256`                | Maximum in-memory thumbnail cache size                             |

The index is held in memory and rebuilt on every startup, so no database or index directory needs to be provisioned. Expect a delay proportional to library size before the server begins listening.

## Project structure

The build is a multi-module Gradle project with shared build logic extracted into a convention plugin in `buildSrc`, a version catalog in `gradle/libs.versions.toml`, and both the build cache and configuration cache enabled (see `gradle.properties`).

| Module    | Target             | Contents                                                          |
|-----------|--------------------|-------------------------------------------------------------------|
| `:server` | JVM                | Ktor server, Lucene index, file watcher, thumbnail cache          |
| `:client` | Kotlin/JS          | Browser frontend — search grid and MapLibre map view              |
| `:utils`  | Multiplatform      | Wire types shared by server and client, plus the XMP reader       |

## Tests

```shell
./gradlew :server:test
./gradlew :server:test --tests '*ThumbnailCacheTest*'
```

Thumbnail tests compare against byte-exact snapshots in `server/src/test/resources/snapshots/`. Set `UPDATE_SNAPSHOTS=true` to regenerate them — note that different libvips/aom versions produce different AVIF bytes for the same input, so a snapshot generated on one machine may not match another.

If libvips cannot be loaded, the test process aborts rather than skipping cleanly — see the note in the prerequisites above.
