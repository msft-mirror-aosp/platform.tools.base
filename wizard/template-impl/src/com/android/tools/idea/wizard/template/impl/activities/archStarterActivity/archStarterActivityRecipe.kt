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

fun RecipeExecutor.archStarterActivityRecipe(
  moduleData: ModuleTemplateData,
  activityClass: String,
  packageName: String,
  isLauncher: Boolean,
) {
  val (_, srcOut, resOut, _) = moduleData
  addAllKotlinDependencies(moduleData)

  addDependency(mavenCoordinate = "androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  addDependency(mavenCoordinate = "androidx.activity:activity-compose:1.10.1")

  // Add Compose dependencies, using the BOM to set versions
  addComposeDependencies(moduleData)

  val hiltVersion = "2.56.1"
  addPlugin(
    "com.google.dagger.hilt.android",
    "com.google.dagger:hilt-android-gradle-plugin",
    "$hiltVersion",
  )
  // kapt is obsolete, but needed for Hilt
  addPlugin(
    "org.jetbrains.kotlin.kapt",
    "org.jetbrains.kotlin:kotlin-kapt-gradle-plugin",
    moduleData.projectTemplateData.kotlinVersion,
  )

  // KSP is needed for Room
  addPlugin(
    "com.google.devtools.ksp",
    "com.google.devtools.ksp:symbol-processing-gradle-plugin",
    // KSP versions are a composite of the Kotlin version and the KSP library version. We have to
    // take the Kotiln version we're given, so use the latest KSP that is compatible with it.
    "${moduleData.projectTemplateData.kotlinVersion}-+",
  )

  addDependency("androidx.hilt:hilt-navigation-compose:1.2.0")
  addDependency("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
  addDependency("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
  addDependency("androidx.navigation:navigation-compose:2.8.9")
  addDependency("androidx.room:room-runtime:2.7.0")
  addDependency("androidx.room:room-ktx:2.7.0")
  addDependency("androidx.room:room-compiler:2.7.0", configuration = "ksp")
  addDependency("com.google.dagger:hilt-android:$hiltVersion")
  addDependency("com.google.dagger:hilt-android-compiler:$hiltVersion", configuration = "kapt")
  addDependency("com.google.dagger:hilt-compiler:$hiltVersion", configuration = "kapt")
  addDependency(
    "com.google.dagger:hilt-android-testing:$hiltVersion",
    configuration = "androidTestImplementation",
  )
  addDependency("junit:junit:4.13.2", configuration = "testImplementation")
  addDependency(
    "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2",
    configuration = "testImplementation",
  )
  addDependency("androidx.test:core:1.6.1", configuration = "testImplementation")
  addDependency("androidx.test.ext:junit:1.2.1", configuration = "testImplementation")
  addDependency("androidx.test:runner:1.6.2", configuration = "androidTestImplementation")

  addDependency(mavenCoordinate = "androidx.compose.material3:material3")

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
  fun packageName(vararg subpackages: String) =
    escapeKotlinIdentifier(listOf(basePackage, *subpackages).joinToString("."))

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
