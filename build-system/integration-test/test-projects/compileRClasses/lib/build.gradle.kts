import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.library")
  id("com.android.built-in-kotlin")
}

android {
    namespace = "com.example.lib"

    compileSdk = libs.versions.latestCompileSdk.get().toInt()

    defaultConfig {
        minSdk = 21
    }

    lint {
        targetSdk = libs.versions.latestCompileSdk.get().toInt()
    }

    testOptions {
        targetSdk = libs.versions.latestCompileSdk.get().toInt()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(project(":dependencyLib"))
}
