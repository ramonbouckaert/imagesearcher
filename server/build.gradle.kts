plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
    application
}

dependencies {
    implementation(project(":utils"))
    implementation(libs.bundles.ktorServer)
    implementation(libs.bundles.lucene)
    implementation(libs.kfswatch)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.kotlinLogging)
    runtimeOnly(libs.logback)
}

val clientDist = project(":client").tasks.named("jsBrowserDistribution")

tasks.named<ProcessResources>("processResources") {
    dependsOn(clientDist)
    into("static") {
        from(clientDist.map { it.outputs.files })
    }
}

application {
    // (Note that Kotlin compiles `App.kt` to a class with FQN `io.bouckaert.imagesearcher.server.AppKt`.)
    mainClass = "io.bouckaert.imagesearcher.server.AppKt"
}
