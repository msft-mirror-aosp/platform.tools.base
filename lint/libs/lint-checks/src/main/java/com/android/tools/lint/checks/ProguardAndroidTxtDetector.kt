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
package com.android.tools.lint.checks

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.GradleContext
import com.android.tools.lint.detector.api.GradleScanner
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity

class ProguardAndroidTxtDetector : Detector(), GradleScanner {
  override fun checkMethodCall(
    context: GradleContext,
    statement: String,
    parent: String?,
    parentParent: String?,
    namedArguments: Map<String, String>,
    unnamedArguments: List<String>,
    cookie: Any,
  ) {
    // Only apply to application plugin, since we really care more about `-dontoptimize`
    // at level of app optimization
    if (context.project.isLibrary) return

    if (statement == "getDefaultProguardFile") {
      if (
        unnamedArguments.any { it.contains("proguard-android.txt") } || namedArguments.values.any { it.contains("proguard-android.txt") }
      ) {
        val incident =
          Incident(
            ISSUE,
            cookie,
            context.getLocation(cookie),
            "Avoid `getDefaultProguardFile('proguard-android.txt')`",
            fix().replace().pattern("proguard-android.txt").with("proguard-android-optimize.txt").build(),
          )
        context.client.report(context, incident)
      }
    }
  }

  companion object {
    val ISSUE =
      Issue.create(
        id = "ProguardAndroidTxtUsage",
        briefDescription = "Use proguard-android-optimize.txt to enable optimizations",
        explanation =
          "Support for `getDefaultProguardFile('proguard-android.txt')` will be removed in AGP 9.0" +
            " since it includes `-dontoptimize`, which prevents R8 from performing many" +
            " optimizations. Instead use" +
            " `getDefaultProguardFile('proguard-android-optimize.txt)`, and if needed," +
            " temporarily use `-dontoptimize` in a custom keep rule file while fixing breakages.",
        category = Category.PERFORMANCE,
        priority = 2,
        severity = Severity.WARNING,
        implementation = Implementation(ProguardAndroidTxtDetector::class.java, Scope.GRADLE_SCOPE),
        moreInfo = "https://developer.android.com/topic/performance/app-optimization/enable-app-optimization",
        androidSpecific = true,
      )
  }
}
