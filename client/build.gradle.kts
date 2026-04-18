plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlinPluginSerialization)
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
        browser()
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":utils"))
            implementation(libs.kotlinxCoroutines)
            implementation(libs.kotlinxSerialization)
        }
    }
}
