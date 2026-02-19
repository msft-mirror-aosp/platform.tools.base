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

import com.android.tools.idea.wizard.template.Category
import com.android.tools.idea.wizard.template.Constraint.CLASS
import com.android.tools.idea.wizard.template.Constraint.NONEMPTY
import com.android.tools.idea.wizard.template.Constraint.UNIQUE
import com.android.tools.idea.wizard.template.FormFactor
import com.android.tools.idea.wizard.template.ModuleTemplateData
import com.android.tools.idea.wizard.template.PackageNameWidget
import com.android.tools.idea.wizard.template.TemplateConstraint
import com.android.tools.idea.wizard.template.TemplateData
import com.android.tools.idea.wizard.template.TextFieldWidget
import com.android.tools.idea.wizard.template.WizardUiContext
import com.android.tools.idea.wizard.template.impl.defaultPackageNameParameter
import com.android.tools.idea.wizard.template.stringParameter
import com.android.tools.idea.wizard.template.template
import java.io.File

val aiGlassesActivityTemplate
  get() = template {
    name = "Basic AI Glasses Activity"
    description = "Creates a new basic AI glasses activity"
    minApi = 30
    constraints = listOf(TemplateConstraint.AndroidX, TemplateConstraint.Kotlin, TemplateConstraint.Material3, TemplateConstraint.Compose)

    category = Category.Activity
    formFactor = FormFactor.AiGlasses
    screens = listOf(WizardUiContext.NewProject)

    val activityClass = stringParameter {
      name = "Activity Name"
      default = "MainActivity"
      visible = { false }
      help = "The name of the activity class to create"
      constraints = listOf(CLASS, UNIQUE, NONEMPTY)
      loggable = true
    }

    val packageName = defaultPackageNameParameter

    widgets(TextFieldWidget(activityClass), PackageNameWidget(packageName))

    thumb { File("ai-glasses-activity").resolve("template_ai_glasses_activity.png") }

    recipe = { data: TemplateData -> aiGlassesActivityRecipe(data as ModuleTemplateData, activityClass.value, packageName.value) }
  }
