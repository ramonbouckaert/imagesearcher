@file:JsModule("maplibre-gl")

package io.bouckaert.imagesearcher.client.maplibre

// Bindings for the bundled `maplibre-gl` module. Only the constructors are declared: the instances
// are held as `dynamic` by the caller, so their methods resolve at runtime and need no declarations
// here. MapLibre 6 is ESM-only and has no default export, so these map to its named exports.

external class Map(options: dynamic)

external class Popup(options: dynamic)

// MapLibre derives its worker URL from `import.meta.url` and gives up (returning an empty string)
// unless that is an http(s) URL — which it is not once webpack has bundled the module, leaving it
// to call `new Worker("")`. The worker files are therefore served from our own origin and pointed
// at explicitly. See the `mapLibreWorkerFiles` copy task in client/build.gradle.kts.
external fun setWorkerUrl(value: String)
