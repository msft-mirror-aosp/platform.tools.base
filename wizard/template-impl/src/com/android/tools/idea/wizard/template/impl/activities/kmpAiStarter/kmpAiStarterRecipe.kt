/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tools.idea.wizard.template.impl.activities.kmpAiStarter

import com.android.tools.idea.wizard.template.ModuleTemplateData
import com.android.tools.idea.wizard.template.RecipeExecutor
import com.android.tools.idea.wizard.template.impl.activities.kmpAiStarter.src.app_package.appKt
import com.android.tools.idea.wizard.template.impl.activities.kmpAiStarter.src.app_package.mainActivityKt
import com.android.tools.idea.wizard.template.impl.activities.kmpAiStarter.src.app_package.platformAndroidKt
import com.android.tools.idea.wizard.template.impl.activities.kmpAiStarter.src.app_package.platformIosKt
import com.android.tools.idea.wizard.template.impl.activities.kmpAiStarter.src.app_package.platformKt

fun RecipeExecutor.kmpAiStarterRecipe(moduleData: ModuleTemplateData, activityClass: String, packageName: String, isLauncher: Boolean) {
  val projectData = moduleData.projectTemplateData
  val projectRoot = projectData.rootDir
  val appRoot = moduleData.rootDir
  val kotlinVersion = projectData.kotlinVersion
  val agpVersion = projectData.agpVersion.toString()
  val (_, srcOut, _, manifestOut) = moduleData

  // 1. Setup :shared module
  val sharedRoot = projectRoot.resolve("shared")
  createDirectory(sharedRoot)
  addIncludeToSettings("shared")

  addPluginDeclaration("org.jetbrains.kotlin.multiplatform", "org.jetbrains.kotlin:kotlin-gradle-plugin", kotlinVersion)
  addPluginDeclaration("com.android.kotlin.multiplatform.library", "com.android.tools.build:gradle", agpVersion)

  addProjectGradleProperty("android.builtInKotlin", "false")
  addProjectGradleProperty("android.newDsl", "false")

  save(
    sharedBuildGradle(packageName, moduleData.apis.buildApi.apiLevel, moduleData.apis.minApi.apiLevel),
    sharedRoot.resolve("build.gradle.kts"),
  )

  // Write shared source files
  val sharedCommonMain = sharedRoot.resolve("src/commonMain/kotlin/${packageName.replace('.', '/')}/shared")
  createDirectory(sharedCommonMain)
  save(platformKt(packageName), sharedCommonMain.resolve("Platform.kt"))

  val sharedAndroidMain = sharedRoot.resolve("src/androidMain/kotlin/${packageName.replace('.', '/')}/shared")
  createDirectory(sharedAndroidMain)
  save(platformAndroidKt(packageName), sharedAndroidMain.resolve("Platform.android.kt"))

  val sharedIosMain = sharedRoot.resolve("src/iosMain/kotlin/${packageName.replace('.', '/')}/shared")
  createDirectory(sharedIosMain)
  save(platformIosKt(packageName), sharedIosMain.resolve("Platform.ios.kt"))

  // Configure shared dependencies using the engine
  addDependency("org.jetbrains.kotlin:kotlin-test:$kotlinVersion", "implementation", sourceSetName = "commonTest", moduleDir = sharedRoot)

  // Configure plugins and build features for the app module
  addPlugin("org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin:compose-compiler-gradle-plugin", kotlinVersion)
  setBuildFeature("compose", true)
  setJavaKotlinCompileOptions(true)

  // Configure app dependencies using the engine
  addPlatformDependency("androidx.compose:compose-bom:2026.02.01")
  addPlatformDependency("androidx.compose:compose-bom:2026.02.01", "androidTestImplementation")

  addDependency("androidx.activity:activity-compose:1.10.1")
  addDependency("androidx.compose.ui:ui")
  addDependency("androidx.compose.ui:ui-graphics")
  addDependency("androidx.compose.ui:ui-tooling", "debugImplementation")
  addDependency("androidx.compose.ui:ui-tooling-preview")
  addDependency("androidx.compose.ui:ui-test-manifest", "debugImplementation")
  addDependency("androidx.compose.ui:ui-test-junit4", "androidTestImplementation")
  addDependency("androidx.compose.material3:material3")
  addDependency("androidx.compose.foundation:foundation")

  addModuleDependency("implementation", "shared", appRoot)

  // Configure packaging options by appending to the build file
  append(
    """
    android {
        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }
        }
    }
    """
      .trimIndent(),
    appRoot.resolve("build.gradle.kts"),
  )

  // Create App.kt under app module
  save(appKt(packageName), srcOut.resolve("App.kt"))

  // Create MainActivity.kt under app module
  save(mainActivityKt(packageName, activityClass), srcOut.resolve("$activityClass.kt"))

  // Merge AndroidManifest.xml safely
  mergeXml(androidManifestXml(activityClass, isLauncher, moduleData.themesData.appName), manifestOut.resolve("AndroidManifest.xml"))
}
