import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ipsuman.mediadownloader"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

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

    packagingOptions {
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts.add("lib/arm64-v8a/libc++_shared.so")
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
            jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
        }
    }
}

val copyWebApp by tasks.registering(Copy::class) {
    from(rootProject.file("index.html"))
    into(layout.buildDirectory.dir("generated/assets"))
}

val copyLibcxxShared by tasks.registering(Copy::class) {
    val ndkRoot = android.ndkDirectory
    from(File(ndkRoot, "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"))
    into(layout.buildDirectory.dir("generated/jniLibs/arm64-v8a"))
}

android.applicationVariants.all {
    val variantName = name.replaceFirstChar { it.uppercase() }
    tasks.named("merge${variantName}Assets").configure {
        dependsOn(copyWebApp)
    }
    tasks.named("merge${variantName}JniLibFolders").configure {
        dependsOn(copyLibcxxShared)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
