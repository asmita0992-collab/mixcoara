import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// Cargar o inicializar la versión desde version.properties
val versionPropsFile = file("version.properties")
val versionProps = Properties()

if (versionPropsFile.exists()) {
    FileInputStream(versionPropsFile).use { versionProps.load(it) }
} else {
    versionProps["MAJOR"] = "2"
    versionProps["MINOR"] = "6"
    versionProps["PATCH"] = "0"
    versionProps["BUILD"] = "26"
    FileOutputStream(versionPropsFile).use { versionProps.store(it, "Mixcoara Version Properties") }
}

val verMajor = (versionProps["MAJOR"] as? String)?.toIntOrNull() ?: 2
val verMinor = (versionProps["MINOR"] as? String)?.toIntOrNull() ?: 1
val verPatch = (versionProps["PATCH"] as? String)?.toIntOrNull() ?: 0
val verBuild = (versionProps["BUILD"] as? String)?.toIntOrNull() ?: 21

val currentVersionCode = verBuild
val currentVersionName = "$verMajor.$verMinor.$verPatch"

// Cargar keystore.properties (solo existirá en CI)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

android {
    namespace = "com.tlahuis.mixcoara"
    compileSdk = 35

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.tlahuis.mixcoara"
        minSdk = 26
        targetSdk = 35
        versionCode = currentVersionCode
        versionName = currentVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    applicationVariants.all {
        outputs.all {
            val outputImpl = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (outputImpl != null) {
                outputImpl.outputFileName = "Mixcoara-$currentVersionName.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.matching { it.name.startsWith("bundle") && !it.name.contains("Classes") }.configureEach {
    doLast {
        val bundleDir = layout.buildDirectory.dir("outputs/bundle").get().asFile
        if (bundleDir.exists()) {
            bundleDir.walkTopDown().filter { it.extension == "aab" }.forEach { aabFile ->
                val newName = "Mixcoara-$currentVersionName.aab"
                val destFile = File(aabFile.parentFile, newName)
                if (aabFile.name != newName) {
                    aabFile.renameTo(destFile)
                }
            }
        }
    }
}

// Tareas para incremento de versión
tasks.register("incrementVersion") {
    group = "versioning"
    description = "Incrementa automáticamente el número de build (versionCode) en version.properties"
    doLast {
        val nextBuild = verBuild + 1
        versionProps["BUILD"] = nextBuild.toString()
        FileOutputStream(versionPropsFile).use { versionProps.store(it, "Mixcoara Version Properties") }
        println("Build incrementado: $currentVersionName (versionCode: $nextBuild)")
    }
}

tasks.register("bumpMinor") {
    group = "versioning"
    description = "Incrementa la versión MINOR (ej. 2.0.0 -> 2.1.0) en version.properties"
    doLast {
        val nextMinor = verMinor + 1
        val nextBuild = verBuild + 1
        versionProps["MINOR"] = nextMinor.toString()
        versionProps["PATCH"] = "0"
        versionProps["BUILD"] = nextBuild.toString()
        FileOutputStream(versionPropsFile).use { versionProps.store(it, "Mixcoara Version Properties") }
        println("Versión actualizada a $verMajor.$nextMinor.0 (versionCode: $nextBuild)")
    }
}

tasks.register("bumpPatch") {
    group = "versioning"
    description = "Incrementa la versión PATCH (ej. 2.1.0 -> 2.1.1) en version.properties"
    doLast {
        val nextPatch = verPatch + 1
        val nextBuild = verBuild + 1
        versionProps["PATCH"] = nextPatch.toString()
        versionProps["BUILD"] = nextBuild.toString()
        FileOutputStream(versionPropsFile).use { versionProps.store(it, "Mixcoara Version Properties") }
        println("Versión actualizada a $verMajor.$verMinor.$nextPatch (versionCode: $nextBuild)")
    }
}

tasks.register("bumpMajor") {
    group = "versioning"
    description = "Incrementa la versión MAJOR (ej. 2.1.0 -> 3.0.0) en version.properties"
    doLast {
        val nextMajor = verMajor + 1
        val nextBuild = verBuild + 1
        versionProps["MAJOR"] = nextMajor.toString()
        versionProps["MINOR"] = "0"
        versionProps["PATCH"] = "0"
        versionProps["BUILD"] = nextBuild.toString()
        FileOutputStream(versionPropsFile).use { versionProps.store(it, "Mixcoara Version Properties") }
        println("Versión actualizada a $nextMajor.0.0 (versionCode: $nextBuild)")
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("python")
        pip {
            install("pycryptodomex==3.21.0")
            install("requests==2.32.3")
            install("certifi==2025.1.31")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}