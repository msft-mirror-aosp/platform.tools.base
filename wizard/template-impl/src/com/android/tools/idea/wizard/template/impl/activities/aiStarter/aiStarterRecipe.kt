/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.wizard.template.impl.activities.aiStarter

import com.android.tools.idea.wizard.template.ModuleTemplateData
import com.android.tools.idea.wizard.template.RecipeExecutor
import com.android.tools.idea.wizard.template.impl.activities.aiStarter.src.app_package.mainActivityKt
import com.android.tools.idea.wizard.template.impl.activities.common.addAllKotlinDependencies
import com.android.tools.idea.wizard.template.impl.activities.common.addComposeDependencies
import com.android.tools.idea.wizard.template.impl.activities.common.generateManifest
import com.android.tools.idea.wizard.template.impl.activities.common.generateThemeStyles
import com.android.tools.idea.wizard.template.impl.activities.composeActivityMaterial3.src.app_package.ui.colorKt
import com.android.tools.idea.wizard.template.impl.activities.composeActivityMaterial3.src.app_package.ui.themeKt
import com.android.tools.idea.wizard.template.impl.activities.composeActivityMaterial3.src.app_package.ui.typeKt
import java.io.File

fun RecipeExecutor.aiStarterRecipe(moduleData: ModuleTemplateData, activityClass: String, packageName: String, isLauncher: Boolean) {
  val (_, srcOut, resOut, _) = moduleData
  addAllKotlinDependencies(moduleData)

  val lifecycleVersion = "2.8.7"
  addDependency("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
  addDependency("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
  addDependency("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")

  val activityVersion = "1.10.1"
  addDependency("androidx.activity:activity-compose:$activityVersion")

  // Add Compose dependencies, using the BOM to set versions
  addComposeDependencies(moduleData)

  // KSP is needed for Room
  addPlugin("com.google.devtools.ksp", "com.google.devtools.ksp:symbol-processing-gradle-plugin", "2.3.5")

  val navigationVersion = "2.8.9"
  addDependency("androidx.navigation:navigation-compose:$navigationVersion")

  val roomVersion = "2.7.0"
  addDependency("androidx.room:room-runtime:$roomVersion")
  addDependency("androidx.room:room-ktx:$roomVersion")
  addDependency("androidx.room:room-compiler:$roomVersion", configuration = "ksp")

  addDependency("junit:junit:4.13.2", configuration = "testImplementation")
  addDependency("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2", configuration = "testImplementation")
  addDependency("androidx.test:core:1.6.1", configuration = "testImplementation")
  addDependency("androidx.test.ext:junit:1.3.0", configuration = "testImplementation")
  addDependency("androidx.test:runner:1.6.2", configuration = "androidTestImplementation")

  addDependency(mavenCoordinate = "androidx.compose.material3:material3")
  addDependency(mavenCoordinate = "androidx.compose.material:material-icons-core")
  addDependency(mavenCoordinate = "androidx.compose.material:material-icons-extended")

  // Coil
  val coilVersion = "2.7.0"
  addDependency("io.coil-kt:coil-compose:$coilVersion")

  // Retrofit
  val retroFitVersion = "2.12.0"
  addDependency("com.squareup.retrofit2:retrofit:$retroFitVersion")
  addDependency("com.squareup.retrofit2:converter-moshi:$retroFitVersion")

  // Coroutines
  val coroutinesVersion = "1.10.2"
  addDependency("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
  addDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

  // Accompanist
  val accompanistVersion = "0.37.3"
  addDependency("com.google.accompanist:accompanist-permissions:$accompanistVersion")

  // Play Services
  val playServicesLocationVersion = "21.3.0"
  addDependency("com.google.android.gms:play-services-location:$playServicesLocationVersion")

  // CameraX
  val cameraVersion = "1.5.0"
  addDependency("androidx.camera:camera-camera2:$cameraVersion")
  addDependency("androidx.camera:camera-lifecycle:$cameraVersion")
  addDependency("androidx.camera:camera-view:$cameraVersion")
  addDependency("androidx.camera:camera-core:$cameraVersion")

  // OkHttp
  val okHttpVersion = "4.10.0"
  addDependency("com.squareup.okhttp3:logging-interceptor:$okHttpVersion")
  addDependency("com.squareup.okhttp3:okhttp:$okHttpVersion")

  // Moshi
  val moshiVersion = "1.15.2"
  addDependency("com.squareup.moshi:moshi-kotlin:$moshiVersion")
  addDependency("com.squareup.moshi:moshi-kotlin-codegen:$moshiVersion", configuration = "ksp")

  // DataStore
  val dataStoreVersion = "1.1.7"
  addDependency("androidx.datastore:datastore-preferences:$dataStoreVersion")

  copy(File("arch-sample-activity").resolve("drawable"), resOut.resolve("drawable"))

  val themeName = "${moduleData.themesData.appName}Theme"

  generateThemeStyles(moduleData.themesData.main, true, resOut)

  generateManifest(
    moduleData = moduleData,
    activityClass = activityClass,
    activityThemeName = moduleData.themesData.main.name,
    packageName = packageName,
    isLauncher = isLauncher,
    hasNoActionBar = true,
    generateActivityTitle = true,
  )
  mergeXml(
    """
    <manifest xmlns:android ="http://schemas.android.com/apk/res/android">
      <uses-permission android:name="android.permission.INTERNET" />
    </manifest>
    """
      .trimIndent(),
    moduleData.manifestDir.resolve("AndroidManifest.xml"),
  )

  save(mainActivityKt(activityClass, "GreetingPreview", "Greeting", packageName, themeName), srcOut.resolve("${activityClass}.kt"))
  val uiThemeFolder = "ui/theme"
  save(colorKt(packageName), srcOut.resolve("$uiThemeFolder/Color.kt"))
  save(themeKt(packageName, themeName), srcOut.resolve("$uiThemeFolder/Theme.kt"))
  save(typeKt(packageName), srcOut.resolve("$uiThemeFolder/Type.kt"))

  setJavaKotlinCompileOptions(true)
  setBuildFeature("compose", true)

  open(srcOut.resolve("${activityClass}.kt"))
}
