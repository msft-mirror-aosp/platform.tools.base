/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.ide.common.repository

/**
 * Enumeration of known artifacts used in Android Studio
 */
enum class GoogleMavenArtifactId(val mavenGroupId: String, val mavenArtifactId: String): WellKnownMavenArtifactId {

  // Platform support libraries
  SUPPORT_ANNOTATIONS("com.android.support", "support-annotations"),
  ANDROIDX_ANNOTATION("androidx.annotation", "annotation"),
  SUPPORT_V4("com.android.support", "support-v4"),
  ANDROIDX_LEGACY_SUPPORT_V4("androidx.legacy", "legacy-support-v4"),
  SUPPORT_V13("com.android.support", "support-v13"),
  ANDROIDX_LEGACY_SUPPORT_V13("androidx.legacy", "legacy-support-v13"),
  SUPPORT_APPCOMPAT_V7("com.android.support", "appcompat-v7"),
  ANDROIDX_APPCOMPAT("androidx.appcompat", "appcompat"),
  SUPPORT_VECTOR_DRAWABLE("com.android.support", "support-vector-drawable"),
  ANDROIDX_VECTORDRAWABLE("androidx.vectordrawable", "vectordrawable"),
  SUPPORT_DESIGN("com.android.support", "design"),
  MATERIAL("com.google.android.material", "material"),
  SUPPORT_GRIDLAYOUT_V7("com.android.support", "gridlayout-v7"),
  ANDROIDX_GRIDLAYOUT("androidx.gridlayout", "gridlayout"),
  SUPPORT_MEDIAROUTER_V7("com.android.support", "mediarouter-v7"),
  ANDROIDX_MEDIAROUTER("androidx.mediarouter", "mediarouter"),
  SUPPORT_CARDVIEW_V7("com.android.support", "cardview-v7"),
  ANDROIDX_CARDVIEW("androidx.cardview", "cardview"),
  SUPPORT_PALETTE_V7("com.android.support", "palette-v7"),
  ANDROIDX_PALETTE("androidx.palette", "palette"),
  SUPPORT_LEANBACK_V17("com.android.support", "leanback-v17"),
  ANDROIDX_LEANBACK("androidx.leanback", "leanback"),
  SUPPORT_RECYCLERVIEW_V7("com.android.support", "recyclerview-v7"),
  ANDROIDX_RECYCLERVIEW("androidx.recyclerview", "recyclerview"),
  SUPPORT_EXIFINTERFACE("com.android.support", "exifinterface"),
  ANDROIDX_EXIFINTERFACE("androidx.exifinterface", "exifinterface"),
  ANDROIDX_PREFERENCE("androidx.preference", "preference"),

  // Misc. layouts
  CONSTRAINT_LAYOUT("com.android.support.constraint", "constraint-layout"),
  ANDROIDX_CONSTRAINTLAYOUT("androidx.constraintlayout", "constraintlayout"),
  FLEXBOX_LAYOUT("com.google.android.flexbox", "flexbox"),
  ANDROIDX_COORDINATORLAYOUT("androidx.coordinatorlayout", "coordinatorlayout"),
  ANDROIDX_VIEWPAGER("androidx.viewpager", "viewpager"),
  ANDROIDX_VIEWPAGER2("androidx.viewpager2", "viewpager2"),
  ANDROIDX_FRAGMENT("androidx.fragment", "fragment"),

  // Navigation
  NAVIGATION_FRAGMENT("android.arch.navigation", "navigation-fragment"),
  ANDROIDX_NAVIGATION_FRAGMENT("androidx.navigation", "navigation-fragment"),
  NAVIGATION_UI("android.arch.navigation", "navigation-ui"),
  ANDROIDX_NAVIGATION_UI("androidx.navigation", "navigation-ui"),
  NAVIGATION_FRAGMENT_KTX("android.arch.navigation", "navigation-fragment-ktx"),
  ANDROIDX_NAVIGATION_FRAGMENT_KTX("androidx.navigation", "navigation-fragment-ktx"),
  NAVIGATION_UI_KTX("android.arch.navigation", "navigation-ui-ktx"),
  ANDROIDX_NAVIGATION_UI_KTX("androidx.navigation", "navigation-ui-ktx"),
  // This is currently only used in tests
  ANDROIDX_NAVIGATION_RUNTIME("androidx.navigation", "navigation-runtime"),
  ANDROIDX_NAVIGATION_DYNAMIC_FEATURES_FRAGMENT(
      "androidx.navigation",
      "navigation-dynamic-features-fragment"
  ),
  ANDROIDX_NAVIGATION_COMMON("androidx.navigation", "navigation-common"),

  // Testing
  ESPRESSO_CORE("com.android.support.test.espresso", "espresso-core"),
  ANDROIDX_ESPRESSO_CORE("androidx.test.espresso", "espresso-core"),
  ESPRESSO_CONTRIB("com.android.support.test.espresso", "espresso-contrib"),
  ANDROIDX_ESPRESSO_CONTRIB("androidx.test.espresso", "espresso-contrib"),
  TEST_RULES("com.android.support.test", "rules"),
  ANDROIDX_TEST_RULES("androidx.test", "rules"),
  ANDROIDX_JUNIT("androidx.test.ext", "junit"),

  // Data binding
  DATABINDING_LIBRARY("com.android.databinding", "library"),
  ANDROIDX_DATABINDING_RUNTIME("androidx.databinding", "databinding-runtime"),
  DATABINDING_BASE_LIBRARY("com.android.databinding", "baseLibrary"),
  ANDROIDX_DATABINDING_COMMON("androidx.databinding", "databinding-common"),
  DATABINDING_COMPILER("com.android.databinding", "compiler"),
  ANDROIDX_DATABINDING_COMPILER("androidx.databinding", "databinding-compiler"),
  DATABINDING_ADAPTERS("com.android.databinding", "adapters"),
  ANDROIDX_DATABINDING_ADAPTERS("androidx.databinding", "databinding-adapters"),

  // App Inspection supported libraries
  ANDROIDX_WORK_RUNTIME("androidx.work", "work-runtime"),

  // Google repo
  PLAY_SERVICES("com.google.android.gms", "play-services"),
  PLAY_SERVICES_ADS("com.google.android.gms", "play-services-ads"),
  PLAY_SERVICES_WEARABLE("com.google.android.gms", "play-services-wearable"),
  PLAY_SERVICES_MAPS("com.google.android.gms", "play-services-maps"),
  SUPPORT_WEARABLE("com.google.android.support", "wearable"),

  // Compose
  COMPOSE_RUNTIME("androidx.compose.runtime", "runtime"),
  COMPOSE_TOOLING("androidx.compose.ui", "ui-tooling"),
  COMPOSE_TOOLING_PREVIEW("androidx.compose.ui", "ui-tooling-preview"),
  COMPOSE_UI("androidx.compose.ui", "ui"),

  // Wear Tiles
  WEAR_TILES_TOOLING("androidx.wear.tiles", "tiles-tooling"),

  // Lifecycle
  ANDROIDX_LIFECYCLE_EXTENSIONS("androidx.lifecycle", "lifecycle-extensions"),
  ANDROIDX_LIFECYCLE_VIEWMODEL_KTX("androidx.lifecycle", "lifecycle-viewmodel-ktx"),

  // Core-Ktx
  ANDROIDX_CORE_KTX("androidx.core", "core-ktx"),
  ;

  override val groupId = mavenGroupId
  override val artifactId = mavenArtifactId

  override fun toString(): String = displayName

  companion object {
    @JvmStatic fun find(moduleId: String): GoogleMavenArtifactId? =
        values().asSequence().find { it.toString() == moduleId }

    @JvmStatic fun find(groupId: String, artifactId: String): GoogleMavenArtifactId? =
        values().asSequence().find { it.mavenGroupId == groupId && it.mavenArtifactId == artifactId }
  }
}
