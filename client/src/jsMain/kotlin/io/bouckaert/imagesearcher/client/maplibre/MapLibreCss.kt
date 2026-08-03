package io.bouckaert.imagesearcher.client.maplibre

// Importing the stylesheet as a module lets webpack (with cssSupport enabled) inline it into the
// bundle and inject it at runtime, replacing the <link> tag that used to point at a CDN. It has to
// be referenced from Kotlin, otherwise the import is dead-code eliminated and the styles vanish.
@JsModule("maplibre-gl/dist/maplibre-gl.css")
external val maplibreCss: dynamic
