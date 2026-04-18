plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    jvm()
    js { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinxSerialization)
        }
        jvmMain.dependencies {
            implementation(libs.kim)
        }
    }
}
