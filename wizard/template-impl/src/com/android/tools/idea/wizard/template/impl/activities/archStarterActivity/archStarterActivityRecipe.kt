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
package com.android.tools.idea.wizard.template.impl.activities.archStarterActivity

import com.android.tools.idea.wizard.template.ModuleTemplateData
import com.android.tools.idea.wizard.template.RecipeExecutor
import com.android.tools.idea.wizard.template.escapeKotlinIdentifier
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.application
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.data.di.dataModule
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.data.local.database.appDatabase
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.data.local.database.dataModel
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.data.local.di.databaseModule
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.data.repository
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.ui.mainActivityKt
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.ui.mymodel.modelScreen
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.ui.mymodel.viewModel
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.ui.navigation
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.ui.theme.colorKt
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.ui.theme.themeKt
import com.android.tools.idea.wizard.template.impl.activities.archStarterActivity.src.app_package.ui.theme.typeKt
import com.android.tools.idea.wizard.template.impl.activities.common.addAllKotlinDependencies
import com.android.tools.idea.wizard.template.impl.activities.common.addComposeDependencies
import com.android.tools.idea.wizard.template.impl.activities.common.generateManifest
import com.android.tools.idea.wizard.template.impl.activities.common.generateThemeStyles
import java.io.File

fun RecipeExecutor.archStarterActivityRecipe(
  moduleData: ModuleTemplateData,
  activityClass: String,
  packageName: String,
  isLauncher: Boolean,
) {
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

  val hiltVersion = "2.57.2"
  addPlugin("com.google.dagger.hilt.android", "com.google.dagger:hilt-android-gradle-plugin", hiltVersion)

  // KSP is needed for Hilt and Room
  addPlugin(
    "com.google.devtools.ksp",
    "com.google.devtools.ksp:symbol-processing-gradle-plugin",
    // KSP versions are a composite of the Kotlin version and the KSP library version. We have to
    // take the Kotiln version we're given, so use the latest KSP that is compatible with it.
    "${moduleData.projectTemplateData.kotlinVersion}-+",
  )

  val hiltNavigationComposeVersion = "1.2.0"
  addDependency("androidx.hilt:hilt-navigation-compose:$hiltNavigationComposeVersion")

  val navigationVersion = "2.8.9"
  addDependency("androidx.navigation:navigation-compose:$navigationVersion")

  val roomVersion = "2.7.0"
  addDependency("androidx.room:room-runtime:$roomVersion")
  addDependency("androidx.room:room-ktx:$roomVersion")
  addDependency("androidx.room:room-compiler:$roomVersion", configuration = "ksp")

  addDependency("com.google.dagger:hilt-android:$hiltVersion")
  addDependency("com.google.dagger:hilt-android-compiler:$hiltVersion", configuration = "ksp")
  addDependency("com.google.dagger:hilt-compiler:$hiltVersion", configuration = "ksp")
  addDependency("com.google.dagger:hilt-android-testing:$hiltVersion", configuration = "androidTestImplementation")
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
  addPlatformDependency("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2")
  addDependency("org.jetbrains.kotlinx:kotlinx-coroutines-android")
  addDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core")

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
  val modelName = "${moduleData.themesData.appName}Model"

  generateThemeStyles(moduleData.themesData.main, true, resOut)

  val recipe =
    ArchStarterActivityTemplateVariables(
      basePackage = packageName,
      appName = moduleData.themesData.appName,
      activityName = activityClass,
      modelName = modelName,
      themeName = themeName,
    )

  with(recipe) {
    generateManifest(
      moduleData = moduleData,
      activityClass = activityClass,
      activityThemeName = moduleData.themesData.main.name,
      packageName = packageName("ui"),
      isLauncher = isLauncher,
      hasNoActionBar = true,
      generateActivityTitle = true,
    )
    mergeXml(
      """
          <manifest xmlns:android ="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.INTERNET" />
            <application android:name=".$appName"/>
          </manifest>
      """
        .trimIndent(),
      moduleData.manifestDir.resolve("AndroidManifest.xml"),
    )

    save(application(), srcOut.resolve("$appName.kt"))

    val uiFolder = srcOut.resolve("ui")
    save(mainActivityKt(), uiFolder.resolve("${activityClass}.kt"))
    save(navigation(), uiFolder.resolve("Navigation.kt"))

    val uiThemeFolder = srcOut.resolve("ui").resolve("theme")
    save(colorKt(), uiThemeFolder.resolve("Color.kt"))
    save(themeKt(), uiThemeFolder.resolve("Theme.kt"))
    save(typeKt(), uiThemeFolder.resolve("Type.kt"))

    val uiModelFolder = srcOut.resolve("ui").resolve(modelName)
    save(modelScreen(), uiModelFolder.resolve("$modelScreen.kt"))
    save(viewModel(), uiModelFolder.resolve("$viewModelName.kt"))

    val dataFolder = srcOut.resolve("data")
    save(dataModule(), dataFolder.resolve("di").resolve("DataModule.kt"))

    val databaseFolder = dataFolder.resolve("local").resolve("database")
    save(appDatabase(), databaseFolder.resolve("AppDatabase.kt"))
    save(dataModel(), databaseFolder.resolve("$modelName.kt"))

    save(databaseModule(), dataFolder.resolve("local").resolve("di").resolve("DatabaseModule.kt"))

    save(repository(), dataFolder.resolve("$repositoryName.kt"))
  }

  setJavaKotlinCompileOptions(true)
  setBuildFeature("compose", true)

  open(srcOut.resolve("${activityClass}.kt"))
}

class ArchStarterActivityTemplateVariables(
  val basePackage: String,
  val appName: String,
  val activityName: String,
  val modelName: String,
  val themeName: String,
) {
  fun packageName(vararg subpackages: String) = escapeKotlinIdentifier(listOf(basePackage, *subpackages).joinToString("."))

  fun packageDeclaration(vararg subpackages: String) = "package ${packageName(*subpackages)}"

  val dataPackage
    get() = packageName("data")

  val dataDiPackage
    get() = packageName("data", "di")

  val databasePackage
    get() = packageName("data", "local", "database")

  val dataLocalDiPackage
    get() = packageName("data", "local", "di")

  val repositoryName
    get() = "${modelName}Repository"

  val repositoryVarName
    get() = "${modelName.lowercaseFirst()}Repository"

  val repositoryNameQualified
    get() = "$dataPackage.$repositoryName"

  val themePackage
    get() = packageName("ui", "theme")

  val modelPackage
    get() = packageName("ui", modelName.lowercase())

  val viewModelName
    get() = "${modelName}ViewModel"

  val modelDao
    get() = "${modelName}Dao"

  val modelDaoVar
    get() = "${modelName.lowercaseFirst()}Dao"

  val dataModelQualified
    get() = "$databasePackage.$modelName"

  val modelDaoQualified
    get() = "$databasePackage.$modelDao"

  val modelScreen
    get() = "${modelName}Screen"

  val modelScreenQualified
    get() = "$modelPackage.$modelScreen"

  val modelUiState
    get() = "${modelName}UiState"

  val modelUiStateQualified
    get() = "$modelPackage.${modelName}UiState"

  val themeNameQualified
    get() = "${packageName("ui", "theme")}.$themeName"
}

private fun String.lowercaseFirst() = if (isEmpty()) "" else first().lowercase() + substring(1)
