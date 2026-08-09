import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.ipsuman.mediadownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ipsuman.mediadownloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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

    // Embedded local HTTP server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Maintained FFmpegKit fork, LGPL FFmpeg 8.1 LTS
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
}
