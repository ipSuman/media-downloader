plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import org.gradle.api.tasks.Copy

android {
    namespace = "com.ipsuman.mediadownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ipsuman.mediadownloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
}

tasks.register<Copy>("copyWebApp") {
    from(rootProject.file("index.html"))
    into(layout.buildDirectory.dir("generated/assets/index"))
}

android.applicationVariants.all {
    val variantName = name
    tasks.named("pre${variantName.replaceFirstChar { it.uppercase() }}Build") {
        dependsOn("copyWebApp")
    }
}