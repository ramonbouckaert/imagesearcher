plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
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

application {
    // (Note that Kotlin compiles `App.kt` to a class with FQN `io.bouckaert.imagesearch.server.AppKt`.)
    mainClass = "io.bouckaert.imagesearch.server.AppKt"
}
