/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.idea.wizard.template.impl.activities.xrActivity

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

val xrActivityTemplate
  get() = template {
    name = "Basic Headset Activity"
    description = "Creates a new basic XR headset activity"
    minApi = 30
    constraints = listOf(TemplateConstraint.AndroidX, TemplateConstraint.Kotlin, TemplateConstraint.Material3, TemplateConstraint.Compose)

    category = Category.Activity
    formFactor = FormFactor.XR
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

    thumb { File("xr-activity").resolve("template_xr_activity.png") }

    recipe = { data: TemplateData -> xrActivityRecipe(data as ModuleTemplateData, activityClass.value, packageName.value) }
  }
