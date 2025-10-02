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

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.wizard.template.Category
import com.android.tools.idea.wizard.template.Constraint.JOURNEY
import com.android.tools.idea.wizard.template.Constraint.NONEMPTY
import com.android.tools.idea.wizard.template.Constraint.UNIQUE
import com.android.tools.idea.wizard.template.FormFactor
import com.android.tools.idea.wizard.template.LabelWidget
import com.android.tools.idea.wizard.template.ModuleTemplateData
import com.android.tools.idea.wizard.template.TemplateConstraint
import com.android.tools.idea.wizard.template.TemplateData
import com.android.tools.idea.wizard.template.TestSuiteWidget
import com.android.tools.idea.wizard.template.TextFieldWidget
import com.android.tools.idea.wizard.template.WizardUiContext
import com.android.tools.idea.wizard.template.impl.activities.common.MIN_API
import com.android.tools.idea.wizard.template.stringParameter
import com.android.tools.idea.wizard.template.template
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.io.FileUtil
import com.intellij.webSymbols.utils.NameCaseUtils
import java.io.File

val journeyFileTemplate
  get() = template {
    name = "Journey File"
    description =
      "Journeys with Gemini allows you to define and run end-to-end tests for your app using natural language prompts."
    minApi = MIN_API
    category = Category.Test
    formFactor = FormFactor.Mobile
    screens = listOf(WizardUiContext.MenuEntry)
    constraints = listOf(TemplateConstraint.TestSuite)

    val showTestSuiteOptions =
      StudioFlags.AGP_TEST_SUITES_ENABLED.get() && StudioFlags.JOURNEYS_WITH_GEMINI_TEST_SUITE.get()

    val testSuiteName = stringParameter {
      name = "Test Suite Name"
      default = "journeysTest"
      help =
        "The name of the Test Suite configured in the Android Gradle Plugin to run the Journey test"
      constraints = listOf(NONEMPTY)
      loggable = true
    }

    val journeyName = stringParameter {
      name = "Journey Name"
      default = "My first Journey"
      help = "Name describing the Journey"
      constraints = listOf(NONEMPTY)
      loggable = true
    }

    val journeyDescription = stringParameter {
      name = "Journey Description"
      default = "A Journey that tests ..."
      help = "A high-level description of the Journey"
      constraints = emptyList()
      loggable = true
    }

    val journeyFileName = stringParameter {
      name = "Journey File Name"
      default = "my_first_journey"
      help = "The Journey file name"
      constraints = listOf(NONEMPTY, JOURNEY, UNIQUE)
      loggable = true
      suggest = { journeyNameToFileName(journeyName.value) }
    }

    widgets(
      *listOfNotNull(
          if (showTestSuiteOptions)
            LabelWidget(
              "Journeys requires the module to be configured with a Journeys Test Suite. Test Suite support in the Android Gradle Plugin (AGP) is in preview, and could change in the future. This could impact your ability to upgrade to later AGP versions.",
              AllIcons.General.Warning,
            )
          else null,
          if (showTestSuiteOptions) TestSuiteWidget(testSuiteName) else null,
          TextFieldWidget(journeyName),
          TextFieldWidget(journeyDescription),
          TextFieldWidget(journeyFileName),
        )
        .toTypedArray()
    )

    thumb {
      // TODO(b/147126989)
      File("no_activity.png")
    }

    recipe = { data: TemplateData ->
      journeyFileRecipe(
        data as ModuleTemplateData,
        journeyName.value,
        journeyDescription.value,
        journeyFileName.value,
        testSuiteName.value,
        data.currentVariant,
      )
    }
  }

@Suppress("UnstableApiUsage")
private fun journeyNameToFileName(journeyName: String): String {
  return FileUtil.sanitizeFileName(NameCaseUtils.toSnakeCase(journeyName))
}
