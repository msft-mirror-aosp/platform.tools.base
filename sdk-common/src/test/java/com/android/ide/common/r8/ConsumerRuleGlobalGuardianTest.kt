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

package com.android.ide.common.r8

import com.google.common.truth.Truth.assertThat
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Test exceptions and filtering behavior from ConsumerRuleGlobalGuardian */
internal class ConsumerRuleGlobalGuardianTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `no exception`() {
    assertThat(getExceptionsFromConsumerContent("")).isEmpty()
  }

  @Test
  fun `no exception when commented`() {
    assertThat(getExceptionsFromConsumerContent("# -dontoptimize")).isEmpty()
    assertThat(getExceptionsFromConsumerContent("# -dontshrink")).isEmpty()
  }

  @Test
  fun `exception with missing package arguments`() {
    assertThat(getExceptionsFromConsumerContent("-repackageclasses").single()).contains("without specifying a package")
    assertThat(getExceptionsFromConsumerContent("-repackageclasses # comment but no arg").single()).contains("without specifying a package")
    assertThat(getExceptionsFromConsumerContent("-flattenpackagehierarchy").single()).contains("without specifying a package")
  }

  @Test
  fun `no exception with package arguments`() {
    assertThat(getExceptionsFromConsumerContent("-repackageclasses com.foo")).isEmpty()
    assertThat(getExceptionsFromConsumerContent("-flattenpackagehierarchy com.foo")).isEmpty()
  }

  @Test
  fun `banned option exception message unique for dynamic feature`() {
    assertThat(getExceptionsFromConsumerContent("-dontoptimize", isDynamicFeature = true).single())
      .contains("should not be specified in this module.")

    assertThat(getExceptionsFromConsumerContent("-dontoptimize", isDynamicFeature = false).single())
      .contains("should not be used in a consumer configuration file.")
  }

  @Test
  fun `banned option without package exception message unique for dynamic feature`() {
    assertThat(getExceptionsFromConsumerContent("-repackageclasses", isDynamicFeature = true).single())
      .contains("should not be specified in this module without specifying a package.")

    assertThat(getExceptionsFromConsumerContent("-repackageclasses", isDynamicFeature = false).single())
      .contains("should not be used in a consumer configuration file without specifying a package.")
  }

  @Test
  fun `exceptions for new global options in AGP 9`() {
    assertThat(getExceptionsFromConsumerContent("-dontrepackage").single()).contains("Global keep option -dontrepackage was specified")
    assertThat(getExceptionsFromConsumerContent("-printblastradius").single())
      .contains("Global keep option -printblastradius was specified")
    assertThat(getExceptionsFromConsumerContent("-processkotlinnullchecks").single())
      .contains("Global keep option -processkotlinnullchecks was specified")
  }

  private fun getExceptionsFromConsumerContent(content: String, isDynamicFeature: Boolean = false): List<String> {
    val consumerFile = temporaryFolder.newFile().also { it.writeText(content) }
    val errors = mutableListOf<String>()
    ConsumerRuleGlobalGuardian.validateConsumerRulesHasNoBannedGlobals(consumerFile, isDynamicFeature = isDynamicFeature) { discoveredIssue
      ->
      errors.add(discoveredIssue.errorMessage)
    }

    content.byteInputStream().use {
      assertEquals(
        errors.size,
        ConsumerRuleGlobalGuardian.readConsumerKeepRulesRemovingBannedGlobals(it, shouldRemoveBannedGlobals = true)
          .split("# REMOVED CONSUMER RULE: ")
          .size - 1,
      )
    }

    return errors
  }
}
