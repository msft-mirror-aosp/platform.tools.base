plugins {
  id("dumpAndroidTarget")
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  id("com.android.lint")
}

kotlin {
  android {
    withJava()
    withHostTestBuilder {}.configure {
        isIncludeAndroidResources = true
        targetSdk { version = release(libs.versions.latestCompileSdk.get().toInt()) }
    }

    androidResources.enable = true

    withDeviceTestBuilder {}.configure {
        targetSdk { version = release(libs.versions.latestCompileSdk.get().toInt()) }
    }

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }

    compilations.withType(com.android.build.api.dsl.KotlinMultiplatformAndroidDeviceTestCompilation::class.java) {
        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compilations.getByName("deviceTest") {
        compileTaskProvider.configure {
            compilerOptions.languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        }
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
     }
   }

   sourceSets.getByName("commonMain") {
     dependencies {
       implementation("com.google.guava:guava:19.0", {
         exclude(group="com.google.guava", module="listenablefuture")
       })
     }
    }

    sourceSets.getByName("androidMain") {
      dependencies {
        api(project(":androidLib"))
        implementation(project(":kmpSecondLib"))
        implementation(project(":kmpJvmOnly"))
      }
    }

    sourceSets.getByName("androidDeviceTest") {
      dependencies {
        implementation("androidx.test:runner:1.4.0-alpha06", {
          exclude(group="com.google.guava", module="listenablefuture")
        })
        implementation("androidx.test:core:1.4.0-alpha06", {
          exclude(group="com.google.guava", module="listenablefuture")
        })
        implementation("androidx.test.ext:junit:1.1.5", {
           exclude(group="com.google.guava", module="listenablefuture")
        })
      }
    }
}

androidComponents {
    finalizeDsl { extension ->
        extension.namespace = "com.example.kmpfirstlib"
        extension.compileSdk = libs.versions.latestCompileSdk.get().toInt()
        extension.minSdk = 22
    }
    onVariants { variant ->
        if (variant.name.isEmpty()) {
            throw IllegalArgumentException("must have variant name")
        }

        val generateAssetTask =
            project.tasks.register<GenerateAssetTask>("generate${variant.name}Assets")

        generateAssetTask.configure {
            outputDir.set(project.layout.buildDirectory.dir("generated/${variant.name}/assets"))
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
            generateAssetTask, GenerateAssetTask::outputDir
        )
    }
}

abstract class GenerateAssetTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun taskAction() {
        val d = outputDir.get().file("asset.txt").asFile
        d.parentFile.mkdirs()
        d.writeText("foo")
    }
}
