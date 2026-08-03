plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlinPluginSerialization)
}

// MapLibre's worker cannot be bundled: it resolves its own URL from `import.meta.url` and refuses
// to run unless that is an http(s) URL, which webpack output is not. Both files are copied out of
// the npm package so the server can serve them from its own origin; they must land in the same
// directory, because the worker imports `./maplibre-gl-shared.mjs` relative to itself.
val mapLibreWorkerFiles by tasks.registering(Copy::class) {
    dependsOn(rootProject.tasks.named("kotlinNpmInstall"))
    from(rootProject.layout.buildDirectory.dir("js/node_modules/maplibre-gl/dist")) {
        include("maplibre-gl-worker.mjs", "maplibre-gl-shared.mjs")
    }
    into(layout.buildDirectory.dir("maplibreWorker"))
}

rootProject.tasks.matching { it.name == "rootPackageJson" }.configureEach {
    doLast {
        val file = outputs.files.singleFile
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parseText(file.readText()) as MutableMap<String, Any>
        @Suppress("UNCHECKED_CAST")
        (json.getOrPut("overrides") { mutableMapOf<String, String>() } as MutableMap<String, Any>).apply {
            put("glob", "^13.0.0")
            put("rimraf", "^6.0.0")
            put("inflight", "^2.0.0")
        }
        file.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json)))
    }
}

kotlin {
    js {
        // Default UMD output would require every @JsModule to also declare @JsNonModule (a global
        // fallback), which bundled npm packages do not have. ES modules are also what maplibre-gl 6
        // requires: it is ESM-only, so its `exports` map has no `require` condition for CommonJS.
        useEsModules()

        browser {
            // Lets `maplibre-gl/dist/maplibre-gl.css` be imported from Kotlin and injected at runtime,
            // rather than pulled from a CDN with a <link> tag.
            commonWebpackConfig {
                cssSupport { enabled.set(true) }
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":utils"))
            implementation(libs.kotlinxCoroutines)
            implementation(libs.kotlinxSerialization)
            // Bundled rather than loaded from a CDN: this app is self-hosted, so the map must keep
            // working without internet access to unpkg (and MapLibre 6 is ESM-only anyway).
            implementation(npm("maplibre-gl", libs.versions.maplibre.get()))
        }
    }
}
