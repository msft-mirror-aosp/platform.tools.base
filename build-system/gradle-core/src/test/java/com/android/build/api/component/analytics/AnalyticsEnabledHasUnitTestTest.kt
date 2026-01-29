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

package com.android.build.api.component.analytics

import com.android.build.api.variant.HasUnitTest
import com.android.build.api.variant.Variant
import com.android.build.gradle.internal.fixtures.FakeObjectFactory
import com.android.tools.build.gradle.internal.profile.VariantPropertiesMethodType
import com.google.common.truth.Truth
import com.google.wireless.android.sdk.stats.GradleBuildVariant
import org.gradle.api.provider.MapProperty
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

class AnalyticsEnabledHasUnitTestTest {

  @get:Rule val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

  private val delegate: HasUnitTest = Mockito.mock(HasUnitTest::class.java, Mockito.withSettings().extraInterfaces(Variant::class.java))

  private val stats = GradleBuildVariant.newBuilder()
  private val proxy: AnalyticsEnabledVariant by lazy {
    object : AnalyticsEnabledVariant(delegate as Variant, stats, FakeObjectFactory.factory) {}
  }

  @Test
  fun testUnitTest() {
    val mockedUnitTest = mock<com.android.build.api.component.UnitTest>()
    @Suppress("UNCHECKED_CAST") val map: MapProperty<String, String> = mock<MapProperty<String, String>>()

    whenever(mockedUnitTest.manifestPlaceholders).thenReturn(map)
    whenever(delegate.unitTest).thenReturn(mockedUnitTest)

    Truth.assertThat(proxy.unitTest!!.manifestPlaceholders).isEqualTo(map)

    Truth.assertThat(stats.variantApiAccess.variantPropertiesAccessCount).isEqualTo(1)
    Truth.assertThat(stats.variantApiAccess.variantPropertiesAccessList.first().type)
      .isEqualTo(VariantPropertiesMethodType.MANIFEST_PLACEHOLDERS_VALUE)
    verify(delegate, times(1)).unitTest
  }
}
