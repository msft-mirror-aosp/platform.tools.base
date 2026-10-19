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

package com.android.build.gradle.internal.ide.v2

import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.dsl.Lint
import com.android.build.gradle.internal.lint.LINT_BASELINE_ANDROID_FILE_NAME
import com.android.build.gradle.internal.lint.LINT_BASELINE_FILE_NAME
import com.android.build.gradle.internal.lint.LINT_BASELINE_JVM_FILE_NAME
import com.google.common.truth.Truth
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ConvertersTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `test Lint convert with baseline convention`() {
    val lint = mock<Lint>()
    whenever(lint.disable).thenReturn(mutableSetOf())
    whenever(lint.enable).thenReturn(mutableSetOf())
    whenever(lint.informational).thenReturn(mutableSetOf())
    whenever(lint.warning).thenReturn(mutableSetOf())
    whenever(lint.error).thenReturn(mutableSetOf())
    whenever(lint.fatal).thenReturn(mutableSetOf())
    whenever(lint.checkOnly).thenReturn(mutableSetOf())
    whenever(lint.baseline).thenReturn(null)

    val projectDir = File("/path/to/project")

    // Convention disabled
    val options1 = lint.convert(projectDir, useBaselineConvention = false)
    Truth.assertThat(options1.baseline).isNull()
    val optionsTargetDisabled =
      lint.convert(projectDir, useBaselineConvention = false, defaultBaselineFileName = LINT_BASELINE_ANDROID_FILE_NAME)
    Truth.assertThat(optionsTargetDisabled.baseline).isNull()

    // Convention enabled
    val options2 = lint.convert(projectDir, useBaselineConvention = true)
    Truth.assertThat(options2.baseline).isEqualTo(File(projectDir, LINT_BASELINE_FILE_NAME))

    // DSL overrides convention
    val explicitBaseline = File("/path/to/explicit-baseline.xml")
    whenever(lint.baseline).thenReturn(explicitBaseline)
    val options3 = lint.convert(projectDir, useBaselineConvention = true)
    Truth.assertThat(options3.baseline).isEqualTo(explicitBaseline)
  }

  @Test
  fun `test Lint convert with target baseline convention when target file exists`() {
    val lint = mock<Lint>()
    whenever(lint.disable).thenReturn(mutableSetOf())
    whenever(lint.enable).thenReturn(mutableSetOf())
    whenever(lint.informational).thenReturn(mutableSetOf())
    whenever(lint.warning).thenReturn(mutableSetOf())
    whenever(lint.error).thenReturn(mutableSetOf())
    whenever(lint.fatal).thenReturn(mutableSetOf())
    whenever(lint.checkOnly).thenReturn(mutableSetOf())
    whenever(lint.baseline).thenReturn(null)

    val projectDir = temporaryFolder.newFolder()
    val targetFile = File(projectDir, LINT_BASELINE_ANDROID_FILE_NAME)
    targetFile.createNewFile()

    val options = lint.convert(projectDir, useBaselineConvention = true, defaultBaselineFileName = LINT_BASELINE_ANDROID_FILE_NAME)
    Truth.assertThat(options.baseline).isEqualTo(targetFile)
  }

  @Test
  fun `test Lint convert with target baseline convention when target missing but legacy file exists`() {
    val lint = mock<Lint>()
    whenever(lint.disable).thenReturn(mutableSetOf())
    whenever(lint.enable).thenReturn(mutableSetOf())
    whenever(lint.informational).thenReturn(mutableSetOf())
    whenever(lint.warning).thenReturn(mutableSetOf())
    whenever(lint.error).thenReturn(mutableSetOf())
    whenever(lint.fatal).thenReturn(mutableSetOf())
    whenever(lint.checkOnly).thenReturn(mutableSetOf())
    whenever(lint.baseline).thenReturn(null)

    val projectDir = temporaryFolder.newFolder()
    val legacyFile = File(projectDir, LINT_BASELINE_FILE_NAME)
    legacyFile.createNewFile()

    val options = lint.convert(projectDir, useBaselineConvention = true, defaultBaselineFileName = LINT_BASELINE_ANDROID_FILE_NAME)
    Truth.assertThat(options.baseline).isEqualTo(legacyFile)
  }

  @Test
  fun `test Lint convert with target baseline convention when neither file exists`() {
    val lint = mock<Lint>()
    whenever(lint.disable).thenReturn(mutableSetOf())
    whenever(lint.enable).thenReturn(mutableSetOf())
    whenever(lint.informational).thenReturn(mutableSetOf())
    whenever(lint.warning).thenReturn(mutableSetOf())
    whenever(lint.error).thenReturn(mutableSetOf())
    whenever(lint.fatal).thenReturn(mutableSetOf())
    whenever(lint.checkOnly).thenReturn(mutableSetOf())
    whenever(lint.baseline).thenReturn(null)

    val projectDir = temporaryFolder.newFolder()
    val expectedTargetFile = File(projectDir, LINT_BASELINE_JVM_FILE_NAME)

    val options = lint.convert(projectDir, useBaselineConvention = true, defaultBaselineFileName = LINT_BASELINE_JVM_FILE_NAME)
    Truth.assertThat(options.baseline).isEqualTo(expectedTargetFile)
  }

  @Test
  fun `test Lint convert with target baseline convention when both files exist`() {
    val lint = mock<Lint>()
    whenever(lint.disable).thenReturn(mutableSetOf())
    whenever(lint.enable).thenReturn(mutableSetOf())
    whenever(lint.informational).thenReturn(mutableSetOf())
    whenever(lint.warning).thenReturn(mutableSetOf())
    whenever(lint.error).thenReturn(mutableSetOf())
    whenever(lint.fatal).thenReturn(mutableSetOf())
    whenever(lint.checkOnly).thenReturn(mutableSetOf())
    whenever(lint.baseline).thenReturn(null)

    val projectDir = temporaryFolder.newFolder()
    val legacyFile = File(projectDir, LINT_BASELINE_FILE_NAME)
    legacyFile.createNewFile()
    val targetFile = File(projectDir, LINT_BASELINE_JVM_FILE_NAME)
    targetFile.createNewFile()

    val options = lint.convert(projectDir, useBaselineConvention = true, defaultBaselineFileName = LINT_BASELINE_JVM_FILE_NAME)
    Truth.assertThat(options.baseline).isEqualTo(targetFile)
  }

  @Test
  fun `test Lint convert with target baseline convention when target missing but other target and legacy exist`() {
    val lint = mock<Lint>()
    whenever(lint.disable).thenReturn(mutableSetOf())
    whenever(lint.enable).thenReturn(mutableSetOf())
    whenever(lint.informational).thenReturn(mutableSetOf())
    whenever(lint.warning).thenReturn(mutableSetOf())
    whenever(lint.error).thenReturn(mutableSetOf())
    whenever(lint.fatal).thenReturn(mutableSetOf())
    whenever(lint.checkOnly).thenReturn(mutableSetOf())
    whenever(lint.baseline).thenReturn(null)

    val projectDir = temporaryFolder.newFolder()
    val legacyFile = File(projectDir, LINT_BASELINE_FILE_NAME)
    legacyFile.createNewFile()
    val otherTargetFile = File(projectDir, LINT_BASELINE_ANDROID_FILE_NAME)
    otherTargetFile.createNewFile()

    val options = lint.convert(projectDir, useBaselineConvention = true, defaultBaselineFileName = LINT_BASELINE_JVM_FILE_NAME)
    Truth.assertThat(options.baseline).isEqualTo(File(projectDir, LINT_BASELINE_JVM_FILE_NAME))
  }

  @Test
  fun `test Lint convert with target baseline convention when target missing but custom other target and legacy exist`() {
    val lint = mock<Lint>()
    whenever(lint.disable).thenReturn(mutableSetOf())
    whenever(lint.enable).thenReturn(mutableSetOf())
    whenever(lint.informational).thenReturn(mutableSetOf())
    whenever(lint.warning).thenReturn(mutableSetOf())
    whenever(lint.error).thenReturn(mutableSetOf())
    whenever(lint.fatal).thenReturn(mutableSetOf())
    whenever(lint.checkOnly).thenReturn(mutableSetOf())
    whenever(lint.baseline).thenReturn(null)

    val projectDir = temporaryFolder.newFolder()
    val legacyFile = File(projectDir, LINT_BASELINE_FILE_NAME)
    legacyFile.createNewFile()
    val otherTargetFile = File(projectDir, "lint-baseline-desktop.xml")
    otherTargetFile.createNewFile()

    val options = lint.convert(projectDir, useBaselineConvention = true, defaultBaselineFileName = LINT_BASELINE_ANDROID_FILE_NAME)
    Truth.assertThat(options.baseline).isEqualTo(File(projectDir, LINT_BASELINE_ANDROID_FILE_NAME))
  }

  @Test
  fun `test SigningConfig convert drops passwords and preserves isSigningReady`() {
    val dslSigningConfig = mock<ApkSigningConfig>()
    whenever(dslSigningConfig.name).thenReturn("release")
    whenever(dslSigningConfig.storeFile).thenReturn(File("/path/to/keystore"))
    whenever(dslSigningConfig.storePassword).thenReturn("secretStorePassword")
    whenever(dslSigningConfig.keyAlias).thenReturn("keyAlias")
    whenever(dslSigningConfig.keyPassword).thenReturn("secretKeyPassword")
    whenever(dslSigningConfig.enableV1Signing).thenReturn(true)
    whenever(dslSigningConfig.enableV2Signing).thenReturn(true)
    whenever(dslSigningConfig.enableV3Signing).thenReturn(true)
    whenever(dslSigningConfig.enableV4Signing).thenReturn(true)

    val converted = dslSigningConfig.convert()
    Truth.assertThat(converted.name).isEqualTo("release")
    Truth.assertThat(converted.storeFile).isEqualTo(File("/path/to/keystore"))
    Truth.assertThat(converted.storePassword).isNull()
    Truth.assertThat(converted.keyAlias).isEqualTo("keyAlias")
    Truth.assertThat(converted.keyPassword).isNull()
    Truth.assertThat(converted.isSigningReady).isTrue()
    Truth.assertThat(converted.toString()).doesNotContain("secretStorePassword")
    Truth.assertThat(converted.toString()).doesNotContain("secretKeyPassword")
  }
}
