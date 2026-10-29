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

package com.android.build.gradle.internal.dsl

import com.android.build.gradle.internal.SdkComponentsBuildService
import com.android.build.gradle.internal.dependency.SourceSetManager
import com.android.build.gradle.internal.dsl.decorator.androidPluginDslDecorator
import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.android.build.gradle.internal.plugins.DslContainerProvider
import com.android.build.gradle.internal.scope.DelayedActionsExecutor
import com.android.build.gradle.internal.services.AndroidLocationsBuildService
import com.android.build.gradle.internal.services.DslServices
import com.android.build.gradle.internal.services.createDslServices
import com.android.build.gradle.internal.variant.LegacyVariantInputManager
import com.android.builder.core.ComponentTypeImpl
import com.google.common.truth.Truth.assertThat
import groovy.util.Eval
import org.junit.Test
import org.mockito.kotlin.mock

class GroovyOneArgSettersTest {

  @Test
  fun testGroovyInvocationOnDecoratedExtensions() {
    val sdkComponents = mock<SdkComponentsBuildService>()
    val dslServices = createDslServices(sdkComponents = FakeGradleProvider(sdkComponents))
    AndroidLocationsBuildService.RegistrationAction(ProjectFactory.project).execute()

    val appVariantInputModel =
      LegacyVariantInputManager(
        dslServices,
        ComponentTypeImpl.BASE_APK,
        SourceSetManager(ProjectFactory.project, false, dslServices, DelayedActionsExecutor()),
      )
    val appExtension =
      androidPluginDslDecorator
        .decorate(ApplicationExtensionImpl::class)
        .getDeclaredConstructor(DslServices::class.java, DslContainerProvider::class.java)
        .newInstance(dslServices, appVariantInputModel)

    Eval.me("android", appExtension, "android.resourcePrefix 'app_'")
    assertThat(appExtension.resourcePrefix).isEqualTo("app_")

    val testVariantInputModel =
      LegacyVariantInputManager(
        dslServices,
        ComponentTypeImpl.TEST_APK,
        SourceSetManager(ProjectFactory.project, false, dslServices, DelayedActionsExecutor()),
      )
    val testExtension =
      androidPluginDslDecorator
        .decorate(TestExtensionImpl::class)
        .getDeclaredConstructor(DslServices::class.java, DslContainerProvider::class.java)
        .newInstance(dslServices, testVariantInputModel)

    Eval.me("android", testExtension, "android.resourcePrefix 'test_'")
    Eval.me("android", testExtension, "android.targetProjectPath ':app'")
    assertThat(testExtension.resourcePrefix).isEqualTo("test_")
    assertThat(testExtension.targetProjectPath).isEqualTo(":app")
  }
}
