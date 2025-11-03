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
package com.android.tools.idea.wizard.template.impl.activities.aiGlassesActivity

import com.android.tools.idea.wizard.template.ModuleTemplateData
import com.android.tools.idea.wizard.template.RecipeExecutor
import com.android.tools.idea.wizard.template.impl.activities.aiGlassesActivity.res.values.stringsXml
import com.android.tools.idea.wizard.template.impl.activities.aiGlassesActivity.src.app_package.mainActivityKt
import com.android.tools.idea.wizard.template.impl.activities.common.addAllKotlinDependencies
import com.android.tools.idea.wizard.template.impl.activities.common.addComposeDependencies
import com.android.tools.idea.wizard.template.impl.activities.composeActivityMaterial3.res.values.themesXml

fun RecipeExecutor.aiGlassesActivityRecipe(
  moduleData: ModuleTemplateData,
  activityClass: String,
  packageName: String,
) {
  val (_, srcOut, resOut, manifestOut, _, _, _, rootDir) = moduleData
  addAllKotlinDependencies(moduleData)

  addDependency(mavenCoordinate = "androidx.activity:activity-compose:+")

  // Add Compose dependencies, using the BOM to set versions
  addComposeDependencies(moduleData, composeBomVersion = "2025.07.00")

  addDependency(mavenCoordinate = "androidx.compose.runtime:runtime")

  addDependency(mavenCoordinate = "androidx.xr.glimmer:glimmer:1.0.0-SNAPSHOT")
  addDependency(mavenCoordinate = "androidx.xr.projected:projected:1.0.0-SNAPSHOT")

  mergeXml(
    aiGlassesActivityManifestXml(activityClass = activityClass, packageName = packageName),
    manifestOut.resolve("AndroidManifest.xml"),
  )
  mergeXml(
    themesXml(themeName = moduleData.themesData.main.name),
    resOut.resolve("values/themes.xml"),
  )
  mergeXml(stringsXml(), resOut.resolve("values/strings.xml"))

  save(mainActivityKt(activityClass, packageName), srcOut.resolve("${activityClass}.kt"))

  setJavaKotlinCompileOptions(true)
  setBuildFeature("compose", true)

  open(srcOut.resolve("${activityClass}.kt"))
}
