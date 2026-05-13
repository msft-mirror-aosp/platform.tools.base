import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
}

kotlin {
  jvm()
  android {
    namespace = "com.example.kmplibraryplugin"
    compileSdk = libs.versions.latestCompileSdk.get().toInt()

    minSdk = 20

  }
}

tasks.withType(KotlinCompile::class.java) {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
