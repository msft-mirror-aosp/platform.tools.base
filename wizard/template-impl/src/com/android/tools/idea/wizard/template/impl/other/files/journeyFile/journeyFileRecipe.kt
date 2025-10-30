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

fun RecipeExecutor.journeyFileRecipe(
  moduleData: ModuleTemplateData,
  name: String,
  description: String,
  fileName: String,
  testSuiteName: String,
  targetVariant: String?,
) {
  addJourneysTestSuite(testSuiteName, targetVariant)

  addJourneyFile(moduleData, testSuiteName, name, description, fileName)
}

private fun RecipeExecutor.addJourneyFile(
  moduleData: ModuleTemplateData,
  testSuiteName: String,
  name: String,
  description: String,
  fileName: String,
) {
  val directory = moduleData.rootDir.resolve("src").resolve(testSuiteName)
  createDirectory(directory)

  val file = directory.resolve("$fileName.journey.xml")

  save(journeyXml(name, description), file)
  open(file)
}
