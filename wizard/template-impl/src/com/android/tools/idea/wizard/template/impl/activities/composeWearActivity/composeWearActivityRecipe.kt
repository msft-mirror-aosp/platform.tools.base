/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tools.idea.wizard.template.impl.activities.composeWearActivity

import com.android.tools.idea.wizard.template.ModuleTemplateData
import com.android.tools.idea.wizard.template.RecipeExecutor
import com.android.tools.idea.wizard.template.impl.activities.common.COMPOSE_BOM_VERSION
import com.android.tools.idea.wizard.template.impl.activities.common.addAllKotlinDependencies
import com.android.tools.idea.wizard.template.impl.activities.common.addComposeDependencies
import com.android.tools.idea.wizard.template.impl.activities.common.generateManifest
import com.android.tools.idea.wizard.template.impl.activities.composeWearActivity.complication.complicationServiceKt
import com.android.tools.idea.wizard.template.impl.activities.composeWearActivity.res.values.complicationStringsXml
import com.android.tools.idea.wizard.template.impl.activities.composeWearActivity.res.values.stringsXml
import com.android.tools.idea.wizard.template.impl.activities.composeWearActivity.res.values.stylesXml
import com.android.tools.idea.wizard.template.impl.activities.composeWearActivity.res.values.tileStringsXml
import com.android.tools.idea.wizard.template.impl.activities.composeWearActivity.src.app_package.mainActivityKt
import com.android.tools.idea.wizard.template.impl.activities.composeWearActivity.src.app_package.theme.themeKt
import com.android.tools.idea.wizard.template.impl.activities.composeWearActivity.src.app_package.tile.tileServiceKt
import java.io.File

private fun RecipeExecutor.commonComposeRecipe(
  moduleData: ModuleTemplateData,
  activityClass: String,
  packageName: String,
  isLauncher: Boolean,
  wearAppName: String,
  defaultPreview: String,
  composeBomVersion: String = COMPOSE_BOM_VERSION,
) {
  addAllKotlinDependencies(moduleData)

  // Add Compose dependencies, using the BOM to set versions
  addComposeDependencies(moduleData, composeBomVersion)

  // Add Compose Wear dependencies; the Compose BOM doesn't include Wear.
  val wearComposeVersionVarName = getDependencyVarName("androidx.wear.compose:compose-material3", "wear_compose_version")
  val wearComposeVersion = getExtVar(wearComposeVersionVarName, "1.5.6")
  addDependency(mavenCoordinate = "androidx.wear.compose:compose-material3:$wearComposeVersion")
  addDependency(mavenCoordinate = "androidx.wear.compose:compose-foundation:$wearComposeVersion")
  addDependency(mavenCoordinate = "androidx.wear.compose:compose-ui-tooling:$wearComposeVersion")
  // Add Wear Tooling Preview dependency, which is not included in the wear BOM
  addDependency(mavenCoordinate = "androidx.wear:wear-tooling-preview:1.0.0")

  addDependency(mavenCoordinate = "androidx.activity:activity-compose:+")

  addDependency(mavenCoordinate = "androidx.core:core-splashscreen:1.2.0")

  val splashScreenTheme = "${activityClass}Theme.Starting"
  generateManifest(
    moduleData = moduleData,
    activityClass = "presentation.${activityClass}",
    activityThemeName = splashScreenTheme,
    packageName = packageName,
    isLauncher = isLauncher,
    hasNoActionBar = true,
    generateActivityTitle = false,
    taskAffinity = "",
  )

  val (_, srcOut, resOut, manifestOut) = moduleData
  val useWearSdkLibrary = moduleData.apis.buildApi.androidApiLevel.majorVersion >= 36
  mergeXml(androidManifestWearOsAdditions(useWearSdkLibrary), manifestOut.resolve("AndroidManifest.xml"))
  if (useWearSdkLibrary) {
    useLibrary("wear-sdk")
  }
  mergeXml(stringsXml(activityClass, moduleData.isNewModule), resOut.resolve("values/strings.xml"))

  val themeName = "${moduleData.themesData.appName}Theme"
  save(
    mainActivityKt(
      // when a new project is being created, there will not be an applicationPackage
      moduleData.projectTemplateData.applicationPackage ?: packageName,
      activityClass,
      defaultPreview,
      wearAppName,
      packageName,
      themeName,
    ),
    srcOut.resolve("presentation/${activityClass}.kt"),
  )
  val uiThemeFolder = "presentation/theme"
  save(themeKt(packageName, themeName), srcOut.resolve("$uiThemeFolder/Theme.kt"))

  mergeXml(stylesXml(splashScreenTheme), resOut.resolve("values/styles.xml"))

  mergeXml(lintXml(), moduleData.rootDir.resolve("lint.xml"))

  setJavaKotlinCompileOptions(true)
  setBuildFeature("compose", true)
}

fun RecipeExecutor.composeWearActivityRecipe(
  moduleData: ModuleTemplateData,
  activityClass: String,
  packageName: String,
  isLauncher: Boolean,
  wearAppName: String,
  defaultPreview: String,
) {
  commonComposeRecipe(moduleData, activityClass, packageName, isLauncher, wearAppName, defaultPreview)

  val (_, srcOut, resOut, _) = moduleData
  open(srcOut.resolve("${activityClass}.kt"))

  copy(File("wear-app").resolve("drawable/splash_icon.xml"), resOut.resolve("drawable/splash_icon.xml"))
}

fun RecipeExecutor.composeWearActivityWithTileAndComplicationRecipe(
  moduleData: ModuleTemplateData,
  activityClass: String,
  tileServiceClass: String,
  tilePreviewName: String,
  complicationServiceClass: String,
  packageName: String,
  isLauncher: Boolean,
  wearAppName: String,
  defaultPreview: String,
) {
  commonComposeRecipe(moduleData, activityClass, packageName, isLauncher, wearAppName, defaultPreview, composeBomVersion = "2025.12.00")

  val wearTilesVersionVarName = getDependencyVarName("androidx.wear.tiles:tiles", "wear_tiles_version")
  val wearTilesVersion = getExtVar(wearTilesVersionVarName, "1.5.0")
  addDependency(mavenCoordinate = "androidx.wear.tiles:tiles:$wearTilesVersion")
  addDependency(mavenCoordinate = "androidx.wear.tiles:tiles-renderer:$wearTilesVersion", configuration = "debugImplementation")

  val protolayoutVersionVarName = getDependencyVarName("androidx.wear.protolayout:protolayout", "protolayout_version")
  val protolayoutVersion = getExtVar(protolayoutVersionVarName, "1.3.0")
  addDependency(mavenCoordinate = "androidx.wear.protolayout:protolayout:$protolayoutVersion")
  addDependency(mavenCoordinate = "androidx.wear.protolayout:protolayout-material3:$protolayoutVersion")

  val guavaVersionVarName = getDependencyVarName("com.google.guava:guava", "guava_version")
  val guavaVersion = getExtVar(guavaVersionVarName, "33.2.1-android")
  addDependency(mavenCoordinate = "com.google.guava:guava:$guavaVersion")

  val wearTilesPreviewVersionVarName = getDependencyVarName("androidx.wear.tiles:tiles", "wear_tiles_preview_version")
  val wearTilesPreviewVersion = getExtVar(wearTilesPreviewVersionVarName, "1.5.0")
  addDependency(mavenCoordinate = "androidx.wear.tiles:tiles-tooling:$wearTilesPreviewVersion", configuration = "debugImplementation")
  addDependency(mavenCoordinate = "androidx.wear.tiles:tiles-tooling-preview:$wearTilesPreviewVersion")

  addDependency(mavenCoordinate = "androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

  val (_, srcOut, resOut, manifestOut) = moduleData
  save(tileServiceKt(tileServiceClass, tilePreviewName, packageName), srcOut.resolve("tile/${tileServiceClass}.kt"))
  mergeXml(tileStringsXml(), resOut.resolve("values/strings.xml"))
  mergeXml(tileServiceManifestXml(tileServiceClass, packageName), manifestOut.resolve("AndroidManifest.xml"))
  copy(File("wear-app").resolve("drawable"), resOut.resolve("drawable"))

  save(complicationServiceKt(complicationServiceClass, packageName), srcOut.resolve("complication/${complicationServiceClass}.kt"))
  mergeXml(complicationStringsXml(), resOut.resolve("values/strings.xml"))
  mergeXml(complicationServiceManifestXml(complicationServiceClass, packageName), manifestOut.resolve("AndroidManifest.xml"))
}
