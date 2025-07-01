import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension

plugins {
  id("dumpAndroidTarget")
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  id("com.android.lint")
}

kotlin {
  jvmToolchain(17)
  android {
    withHostTestBuilder {}.configure {
        isIncludeAndroidResources = true
        // Robolectric android instrumented jars which are in prebuilts are for API Level 28
        targetSdk { version = release(28) }
    }

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }

    localDependencySelection {
      productFlavorDimension("type") {
        selectFrom.set(listOf("typeone"))
      }
      productFlavorDimension("mode") {
        selectFrom.set(listOf("modetwo"))
      }
    }

    aarMetadata.minAgpVersion = "7.2.0"
  }

   sourceSets.getByName("commonTest") {
     dependencies {
       implementation(kotlin("test"))
       implementation("org.robolectric:robolectric:4.8.2")
     }
   }
}

androidComponents {
    finalizeDsl { extension ->
        extension.namespace = "com.example.kmpHostTestOnlyLib"
        extension.compileSdk = libs.versions.latestCompileSdk.get().toInt()
        extension.minSdk = 22
    }
    onVariants { variant ->
        if (variant.name.isEmpty()) {
            throw IllegalArgumentException("must have variant name")
        }
    }
}
