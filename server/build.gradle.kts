import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.jar.JarFile

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
    application
}

kotlin {
    jvmToolchain(24)
}

dependencies {
    implementation(project(":utils"))
    implementation(libs.bundles.ktorServer)
    implementation(libs.bundles.lucene)
    implementation(libs.kfswatch)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.kotlinLogging)
    implementation(libs.mvt)
    implementation(libs.vipsffm)
    runtimeOnly(libs.logback)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation(libs.kotlinxCoroutinesTest)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // On Windows, libvips installs as libvips-42.dll; set VIPS_LIB_PATH to its full path.
    val vipsLibPath = System.getenv("VIPS_LIB_PATH")
    if (vipsLibPath != null) {
        systemProperty("vipsffm.libpath.vips.override", vipsLibPath)
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    mergeServiceFiles()
    exclude("module-info.class")

    // Shadow 9 bug: when the same META-INF/services file exists in multiple dependency JARs,
    // mergeServiceFiles() silently uses "last write wins" instead of appending all entries.
    // For Lucene this breaks SPI codec discovery (e.g. Lucene104PostingsFormat is missing).
    // Fix: after the JAR is assembled, scan every dependency JAR for service entries and
    // add back anything that was dropped.
    val runtimeJarFiles = project.configurations.getByName("runtimeClasspath").filter { f: File -> f.extension == "jar" }
    doLast {
        val outputJar = archiveFile.get().asFile
        val expected = mutableMapOf<String, LinkedHashSet<String>>()
        runtimeJarFiles.forEach { depJar: File ->
            JarFile(depJar).use { jf: JarFile ->
                val jarEntries = jf.entries()
                while (jarEntries.hasMoreElements()) {
                    val e = jarEntries.nextElement()
                    if (!e.isDirectory && e.name.startsWith("META-INF/services/")) {
                        jf.getInputStream(e).bufferedReader().readLines()
                            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                            .forEach { line: String -> expected.getOrPut(e.name) { linkedSetOf() }.add(line) }
                    }
                }
            }
        }
        FileSystems.newFileSystem(URI("jar:" + outputJar.toURI()), emptyMap<String, String>()).use { fs ->
            expected.forEach { (path, allEntries) ->
                val p = fs.getPath(path)
                if (Files.exists(p)) {
                    val current = Files.readString(p)
                    val present = current.lineSequence().map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
                    val missing = allEntries - present
                    if (missing.isNotEmpty()) {
                        Files.writeString(p, current.trimEnd() + "\n" + missing.joinToString("\n") + "\n")
                        logger.lifecycle("Patched $path: added ${missing.joinToString()}")
                    }
                }
            }
        }
    }
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
    applicationDefaultJvmArgs = listOf(
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED"
    )
}
