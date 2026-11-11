plugins {
  kotlin("jvm") version libs.versions.kotlinVersion.get()
  `java-gradle-plugin`
}

// Integration tests run on jbr25 (where compileJava defaults to target 25), while KGP 2.2.10 on
// the MINIMUM layer only supports up to JVM_24. Pinning buildSrc to JVM 17 keeps Java and Kotlin
// targets aligned across all KGP test layers.
// Also skip metadata version checks when compiling buildSrc against gradleApi(), because Gradle
// 9.7+ bundles kotlin-stdlib 2.4.0 metadata which KGP 2.2.10 (MINIMUM) rejects by default.
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    freeCompilerArgs.add("-Xskip-metadata-version-check")
  }
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlinVersion.get()}")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-idea:${libs.versions.kotlinVersion.get()}")
  implementation("com.android.tools.build:gradle:${libs.versions.buildVersion.get()}")
  implementation("com.google.code.gson:gson:2.8.6")
  implementation("com.google.protobuf:protobuf-java-util:4.28.3")
  implementation(gradleApi())
}

gradlePlugin {
  plugins {
    create("androidTargetDumper") {
      id = "dumpAndroidTarget"
      implementationClass = "com.buildsrc.plugin.DumpAndroidTargetPlugin"
    }
  }
}
