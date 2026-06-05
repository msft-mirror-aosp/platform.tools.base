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

package com.android.tools.render

/**
 * Kotlin/kotlinx packages whose user-supplied versions should be repackaged into an internal namespace, so they don't collide with the
 * layoutlib-side Kotlin runtime.
 *
 * See go/screenshot-classloading-design §6 for full design context.
 */
internal val PACKAGES_TO_RENAME = listOf("kotlin.", "kotlinx.")

internal const val INTERNAL_PACKAGE = "_layoutlib_._internal_."

/**
 * Packages allowed to be loaded from the parent classloader (the outer URLClassLoader). Notably excludes "kotlin." and "kotlinx." — those
 * must come from the user's classpath via the renamed namespace.
 */
internal val ALLOWED_PACKAGES_FROM_PARENT =
  listOf(
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "com.sun.",
    "org.w3c.",
    "org.xml.",
    "org.apache.",
    "org.xmlpull.",
    "org.json.",
    "android.",
    "dalvik.",
    "com.android.",
    "androidx.compose.animation.tooling.",
    "junit.",
  )

internal val STRING_REPLACEMENTS =
  mapOf(
    INTERNAL_PACKAGE + "kotlin.reflect.jvm.internal.impl.load.java.JvmAnnotationNames" to
      mapOf(
        "kotlin.Metadata" to "${INTERNAL_PACKAGE}kotlin.Metadata",
        "kotlin.annotations.jvm.ReadOnly" to "${INTERNAL_PACKAGE}kotlin.annotations.jvm.ReadOnly",
        "kotlin.annotations.jvm.Mutable" to "${INTERNAL_PACKAGE}kotlin.annotations.jvm.Mutable",
        "kotlin.jvm.internal" to "${INTERNAL_PACKAGE}kotlin.jvm.internal",
        "kotlin.jvm.internal.EnhancedNullability" to "${INTERNAL_PACKAGE}kotlin.jvm.internal.EnhancedNullability",
        "kotlin.jvm.internal.SerializedIr" to "${INTERNAL_PACKAGE}kotlin.jvm.internal.SerializedIr",
      )
  )

/**
 * A key used for identifying classes loaded by the [DefaultModuleClassLoader] in [ClassesTracker], both for writing with
 * [ClassesTracker.trackClass] and for retrieving with [ClassesTracker.getClasses].
 */
internal const val CLASSES_TRACKER_KEY = ""
