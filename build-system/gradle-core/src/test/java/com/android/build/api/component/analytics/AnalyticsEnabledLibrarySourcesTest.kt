/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.build.api.variant.LibrarySources
import com.android.build.api.variant.SourceDirectories
import com.android.build.gradle.internal.fixtures.FakeObjectFactory
import com.android.tools.build.gradle.internal.profile.VariantPropertiesMethodType
import com.google.common.truth.Truth
import com.google.wireless.android.sdk.stats.GradleBuildVariant
import org.junit.Rule
import org.junit.Test
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

class AnalyticsEnabledLibrarySourcesTest {
  @get:Rule val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

  private val delegate: LibrarySources = mock()

  private val stats = GradleBuildVariant.newBuilder()
  private val proxy: AnalyticsEnabledLibrarySources by lazy {
    object : AnalyticsEnabledLibrarySources(delegate, stats, FakeObjectFactory.factory) {}
  }

  @Test
  fun getAarKeepRules() {
    testAnalytics<SourceDirectories.Flat>(LibrarySources::aarKeepRules, VariantPropertiesMethodType.SOURCES_AAR_KEEP_RULES_ACCESS_VALUE)
  }

  @Test
  fun testNullableApis() {
    Truth.assertThat(proxy.aarKeepRules).isNull()
  }

  private inline fun <reified T : SourceDirectories> testAnalytics(accessor: (sources: LibrarySources) -> T?, analyticsEnumValue: Int) {
    val mockedType: T = mock()
    whenever(accessor(delegate)).thenReturn(mockedType)

    val sourcesProxy = accessor(proxy)
    Truth.assertThat(sourcesProxy is AnalyticsEnabledSourceDirectories).isTrue()
    Truth.assertThat((sourcesProxy as AnalyticsEnabledSourceDirectories).delegate).isEqualTo(mockedType)

    Truth.assertThat(stats.variantApiAccess.variantPropertiesAccessList.first().type).isEqualTo(analyticsEnumValue)
    accessor(verify(delegate, times(1)))
    verifyNoMoreInteractions(delegate)
  }
}
