import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.ipsuman.mediadownloader"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.ipsuman.mediadownloader"
        minSdk = 26
        targetSdk = 35
        val ciIteration = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = ciIteration ?: 2
        versionName = "0.2.0"
        ndk {
          abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/assets"))
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"

        pip {
            install("-r", "src/main/python/requirements.txt")
        }
    }
}

val copyWebApp by tasks.registering(Copy::class) {
    from(rootProject.file("index.html"))
    into(layout.buildDirectory.dir("generated/assets"))
}

android.applicationVariants.all {
    val variantName = name.replaceFirstChar { it.uppercase() }
    tasks.named("merge${variantName}Assets").configure {
        dependsOn(copyWebApp)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Embedded local HTTP server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Seal-compatible Android yt-dlp engine + bundled FFmpeg.
    // Keep 0.17.3 as the known-good Android baseline.
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.3")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.3")
}
