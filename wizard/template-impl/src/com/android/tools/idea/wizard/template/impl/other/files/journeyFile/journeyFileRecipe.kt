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
package com.android.tools.idea.wizard.template.impl.other.files.journeyFile

import com.android.tools.idea.wizard.template.ModuleTemplateData
import com.android.tools.idea.wizard.template.RecipeExecutor
import com.android.tools.idea.wizard.template.impl.other.files.journeyFile.res.journeyXml
import java.io.File

fun RecipeExecutor.journeyFileRecipe(
  moduleData: ModuleTemplateData,
  name: String,
  description: String,
  fileName: String,
) {
  addJourneyFile(moduleData, name, description, fileName)
}

private fun RecipeExecutor.addJourneyFile(
  moduleData: ModuleTemplateData,
  name: String,
  description: String,
  fileName: String,
) {
  val directory = getJourneyDirectoryForModuleRoot(moduleData.rootDir)
  createDirectory(directory)

  val file = directory.resolve("$fileName.xml")

  save(journeyXml(name, description), file)
  open(file)
}

private fun getJourneyDirectoryForModuleRoot(moduleRootDir: File): File {
  return moduleRootDir.resolve("src").resolve("journeysTest")
}
