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

package com.android.tools.ui.inspector.client

import com.android.sdklib.AndroidApiLevel
import com.android.sdklib.AndroidVersion

/** Android Studio's threshold for Android 17: API level 36.1 with the codename CinnamonBun (`CINNAMON_BUN_VERSION`). */
private val CINNAMON_BUN_VERSION = AndroidVersion(AndroidApiLevel(36, 1), "CinnamonBun", null, true)

/** Whether the Compose inspector of the app's Compose version can be loaded on the device. See [getComposeVersionSupport]. */
internal sealed interface ComposeVersionSupport {
  /** The inspector can be loaded. */
  data object Supported : ComposeVersionSupport

  /** The inspector must not be loaded: it has bugs that crash the app or make the inspection fail. */
  sealed interface Unsupported : ComposeVersionSupport {
    /** The androidx.compose.ui:ui version of the app. */
    val composeVersion: String

    /** The first Compose version whose inspector doesn't have the problem. */
    val fixedVersion: String

    /** Explains the problem to the user. */
    val message: String
  }

  /** The inspector fails to load on Android 17 and later. */
  data class UnsupportedOnAndroid17(override val composeVersion: String) : Unsupported {
    override val fixedVersion: String
      get() = "1.11.0"

    override val message: String
      get() = unsupportedMessage(composeVersion, "whose Compose inspector is not supported on Android 17 and later", fixedVersion)
  }

  /** The inspector can crash the app, on any device. */
  data class CanCrashTheApp(override val composeVersion: String, override val fixedVersion: String) : Unsupported {
    override val message: String
      get() = unsupportedMessage(composeVersion, "whose Compose inspector can crash the app", fixedVersion)
  }
}

private fun unsupportedMessage(composeVersion: String, problem: String, fixedVersion: String): String =
  "Compose version not supported: the app uses androidx.compose.ui:ui:$composeVersion, $problem. Update Compose to $fixedVersion or later."

/**
 * The first Compose version whose inspector loads on Android 17.
 *
 * b/482914242 (in Android Studio: b/487130700): the inspector's ReflectionScope sets a static final field of the Kotlin standard library,
 * in kotlin.jvm.internal.Reflection. Android 17 forbids it in apps that target Android 17, so the inspector fails to load. Like Android
 * Studio, the app's target SDK is not checked. Fixed in 1.11.0-beta01 by androidx 313a81dc691: 1.11.0-alpha06 still has ReflectionScope.
 */
private val FIRST_VERSION_SUPPORTED_ON_ANDROID_17 = ComposeVersion.of("1.11.0-beta01")

/**
 * The Compose versions whose inspector can crash the app on any device, from the first release of the minor version to the release that
 * fixed it. These are the versions Android Studio refuses, restricted to 1.x.
 *
 * b/237987764: a native crash while reading lambda parameters (fixed by androidx 7a4beaf4aa4). b/235526153: an uncaught ClassCastException
 * on the main thread while reading the tree (fixed by androidx e9398cf86e0).
 */
private val VERSIONS_THAT_CAN_CRASH_THE_APP =
  listOf(
    ComposeVersion.of("1.1.0-alpha01")..<ComposeVersion.of("1.2.1"),
    ComposeVersion.of("1.3.0-alpha01")..<ComposeVersion.of("1.3.0"),
  )

/**
 * Returns whether the Compose inspector of [composeVersion], the androidx.compose.ui:ui version of the app, can be loaded on a device
 * running [deviceVersion].
 *
 * These are the rules of Android Studio's Layout Inspector (`checkComposeVersion` in `ComposeLayoutInspectorClient`), restricted to 1.x.
 * Versions that are not Compose releases, including locally built ones (-SNAPSHOT, -dev, which Android Studio doesn't check either), are
 * [ComposeVersionSupport.Supported]: they are not checked, since no inspector can be downloaded for them anyway.
 */
internal fun getComposeVersionSupport(composeVersion: String, deviceVersion: AndroidVersion): ComposeVersionSupport {
  val version = ComposeVersion.parse(composeVersion) ?: return ComposeVersionSupport.Supported
  if (deviceVersion.isAndroid17OrLater() && version < FIRST_VERSION_SUPPORTED_ON_ANDROID_17) {
    return ComposeVersionSupport.UnsupportedOnAndroid17(composeVersion)
  }
  val crashingVersions = VERSIONS_THAT_CAN_CRASH_THE_APP.firstOrNull { version in it } ?: return ComposeVersionSupport.Supported
  return ComposeVersionSupport.CanCrashTheApp(composeVersion, fixedVersion = crashingVersions.endExclusive.toString())
}

/**
 * Whether the device runs Android 17 or later, compared like Android Studio does: with the API level and the codename only, so a beta or
 * canary build counts as its preview. A release of API level 36.1 is Android 16 QPR2, and at API level 36.1 the codenames are compared as
 * text: a codename before "CinnamonBun", like "CANARY", is not Android 17.
 */
private fun AndroidVersion.isAndroid17OrLater(): Boolean = AndroidVersion(androidApiLevel, codename, null, true) >= CINNAMON_BUN_VERSION
