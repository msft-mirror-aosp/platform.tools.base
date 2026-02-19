/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.google.common.truth.ComparableSubject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

internal class SettingsExtensionImplTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @get:Rule val mockitoJUnitRule: MockitoRule = MockitoJUnit.rule()

  private val project: Project by lazy { ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build() }

  private lateinit var settings: SettingsExtensionImpl

  @Before
  fun setup() {
    settings = SettingsExtensionImpl(project.objects)
  }

  @Test
  fun testDefaults() {
    testCompileValues()
    testMinSdkValues()
  }

  @Test
  fun compileSdk() {
    settings.compileSdk = 12
    testCompileValues(compileSdk = 12)

    settings.compileSdkExtension = 2
    testCompileValues(compileSdk = 12, compileSdkExtension = 2)

    // test reset to null from other values
    settings.compileSdkPreview = "Foo"
    testCompileValues(compileSdkPreview = "Foo")

    settings.compileSdk = 12
    settings.compileSdkAddon("foo", "bar", 42)
    testCompileValues(addOnVendor = "foo", addOnName = "bar", addOnVersion = 42)

    settings.compileSdk { version = release(33) }
    testCompileValues(compileSdk = 33)

    settings.compileSdk {
      version =
        release(33) {
          sdkExtension = 18
          minorApiLevel = 0
        }
      assertThat(version?.minorApiLevel).isEqualTo(0)
    }
    testCompileValues(compileSdk = 33, compileSdkExtension = 18)
  }

  @Test
  fun compileSdkPreview() {
    settings.compileSdkPreview = "S"
    testCompileValues(compileSdkPreview = "S")

    // test reset to null from other values
    settings.compileSdk = 12
    testCompileValues(compileSdk = 12)

    settings.compileSdkPreview = "S"
    settings.compileSdkAddon("foo", "bar", 42)
    testCompileValues(addOnVendor = "foo", addOnName = "bar", addOnVersion = 42)

    settings.compileSdk { version = preview("S") }
    settings.compileSdk {}

    testCompileValues(compileSdkPreview = "S")
  }

  @Test
  fun compileSdkAddon() {
    settings.compileSdkAddon("foo", "bar", 42)
    testCompileValues(addOnVendor = "foo", addOnName = "bar", addOnVersion = 42)

    // test reset to null from other values
    settings.compileSdk = 12
    testCompileValues(compileSdk = 12)

    settings.compileSdkAddon("foo", "bar", 42)
    settings.compileSdkPreview = "S"
    testCompileValues(compileSdkPreview = "S")

    settings.compileSdk { version = addon("foo", "bar", 41) }
    testCompileValues(addOnVendor = "foo", addOnName = "bar", addOnVersion = 41)
  }

  @Test
  fun minSdk() {
    settings.minSdk = 12
    testMinSdkValues(minSdk = 12)

    // test reset to null from other values
    settings.minSdkPreview = "S"
    testMinSdkValues(minSdkPreview = "S")

    settings.minSdk { version = release(33) }
    testMinSdkValues(minSdk = 33)
  }

  @Test
  fun minSdkPreview() {
    settings.minSdkPreview = "S"
    testMinSdkValues(minSdkPreview = "S")

    // test reset to null from other values
    settings.minSdk = 12
    testMinSdkValues(minSdk = 12)

    settings.minSdk { version = preview("S") }
    testMinSdkValues(minSdkPreview = "S")
  }

  @Test
  fun targetSdk() {
    settings.targetSdk { version = release(33) }
    testTargetSdkValues(targetSdk = 33)

    settings.targetSdk { version = preview("S") }
    testTargetSdkValues(targetSdkPreview = "S")
  }

  private fun testCompileValues(
    compileSdk: Int? = null,
    compileSdkExtension: Int? = null,
    compileSdkPreview: String? = null,
    addOnVendor: String? = null,
    addOnName: String? = null,
    addOnVersion: Int? = null,
  ) {
    assertWithMessage("compileSdk").that(settings.compileSdk).compareTo(compileSdk)

    assertWithMessage("compileSdkExtension").that(settings.compileSdkExtension).compareTo(compileSdkExtension)

    assertWithMessage("compileSdkPreview").that(settings.compileSdkPreview).compareTo(compileSdkPreview)

    assertWithMessage("addOnVendor").that(settings.addOnVendor).compareTo(addOnVendor)
    assertWithMessage("addOnName").that(settings.addOnName).compareTo(addOnName)
    assertWithMessage("addOnVersion").that(settings.addOnVersion).compareTo(addOnVersion)
  }

  private fun testMinSdkValues(minSdk: Int? = null, minSdkPreview: String? = null) {
    assertWithMessage("minSdk").that(settings.minSdk).compareTo(minSdk)

    assertWithMessage("minSdkPreview").that(settings.minSdkPreview).compareTo(minSdkPreview)
  }

  private fun testTargetSdkValues(targetSdk: Int? = null, targetSdkPreview: String? = null) {
    assertWithMessage("targetSdk").that(settings.targetSdk).compareTo(targetSdk)

    assertWithMessage("targetSdkPreview").that(settings.targetSdkPreview).compareTo(targetSdkPreview)
  }

  private fun <TypeT, SubjectT : ComparableSubject<SubjectT, TypeT>> SubjectT.compareTo(value: TypeT?) =
    if (value == null) {
      this.isNull()
    } else {
      this.isEqualTo(value)
    }
}
