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

package com.android.build.gradle.internal.core.dsl.impl.features

import com.android.build.gradle.internal.dsl.BuildType
import com.android.build.gradle.internal.dsl.DefaultConfig
import com.android.build.gradle.internal.services.VariantServices
import com.android.build.gradle.internal.services.createDslServices
import com.android.builder.core.BuilderConstants
import com.android.builder.core.ComponentTypeImpl
import com.google.common.truth.Truth.assertThat
import org.gradle.api.file.DirectoryProperty
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class OptimizationDslInfoImplTest {

  private val dslServices = createDslServices()
  private val services: VariantServices = mock()
  private val buildDirectory: DirectoryProperty = mock()

  private lateinit var defaultConfig: DefaultConfig
  private lateinit var buildType: BuildType

  @Before
  fun setUp() {
    defaultConfig =
      dslServices.newDecoratedInstance(
        DefaultConfig::class.java,
        BuilderConstants.MAIN,
        dslServices,
      )
    buildType =
      dslServices.newDecoratedInstance(
        BuildType::class.java,
        BuilderConstants.RELEASE,
        dslServices,
        ComponentTypeImpl.BASE_APK,
      )
  }

  @Test
  fun testCodeShrinkerEnabled_withLegacyMinifyEnabled() {
    buildType.isMinifyEnabled = true

    val dslInfo =
      OptimizationDslInfoImpl(
        componentType = ComponentTypeImpl.BASE_APK,
        defaultConfig = defaultConfig,
        buildTypeObj = buildType,
        productFlavorList = emptyList(),
        services = services,
        buildDirectory = buildDirectory,
      )

    assertThat(dslInfo.postProcessingOptions.codeShrinkerEnabled()).isTrue()
    assertThat(dslInfo.postProcessingOptions.resourcesShrinkingEnabled()).isFalse()
  }

  @Test
  fun testCodeShrinkerEnabled_withOptimizationEnable() {
    buildType.optimization.enable = true

    val dslInfo =
      OptimizationDslInfoImpl(
        componentType = ComponentTypeImpl.BASE_APK,
        defaultConfig = defaultConfig,
        buildTypeObj = buildType,
        productFlavorList = emptyList(),
        services = services,
        buildDirectory = buildDirectory,
      )

    assertThat(dslInfo.applicationOptimizationEnabled).isTrue()
    assertThat(dslInfo.postProcessingOptions.codeShrinkerEnabled()).isTrue()
    assertThat(dslInfo.postProcessingOptions.resourcesShrinkingEnabled()).isTrue()
  }

  @Test
  fun testCodeShrinkerDisabled_byDefault() {
    val dslInfo =
      OptimizationDslInfoImpl(
        componentType = ComponentTypeImpl.BASE_APK,
        defaultConfig = defaultConfig,
        buildTypeObj = buildType,
        productFlavorList = emptyList(),
        services = services,
        buildDirectory = buildDirectory,
      )

    assertThat(dslInfo.applicationOptimizationEnabled).isFalse()
    assertThat(dslInfo.postProcessingOptions.codeShrinkerEnabled()).isFalse()
    assertThat(dslInfo.postProcessingOptions.resourcesShrinkingEnabled()).isFalse()
  }
}
