/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.options

import com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION
import com.android.ide.common.repository.AgpVersion
import org.junit.Test

/** Tests the validity of the Android Gradle plugin versions associated with the [Option]s. */
class OptionVersionTest {

    companion object {

        /**
         * The AGP stable version that is going to be published (ignoring dot releases for the
         * purpose of this test).
         */
        private val AGP_STABLE_VERSION: AgpVersion = run {
            val agpVersion = AgpVersion.parse(ANDROID_GRADLE_PLUGIN_VERSION)
            AgpVersion(agpVersion.major, agpVersion.minor, 0)
        }

        /**
         * [Option]s that have invalid associated versions.
         *
         * @ RELEASE TEAM: If you update this list when upgrading AGP, be sure to file a new bug
         * assigned to the AGP team and blocking beta release.
         *   - [Insert new bug below]
         *   - Tracking bug for AGP 8.3: b/295183580
         *   - Tracking bug for AGP 8.2: b/277803353
         *   - Tracking bug for AGP 8.0: b/243560711
         */
        private val INVALID_OPTIONS: List<Option<*>> = listOf(
        )

    }

    @Test
    fun `check deprecated options have deprecation versions in the future`() {
        val violatingOptions = getAllOptions()
            .filter { it.status is Option.Status.Deprecated }
            .filter {
                (it.status as Option.Status.Deprecated).deprecationTarget.removalTarget.agpVersion <= AGP_STABLE_VERSION
            }

        checkViolatingProjectOptions(
                violatingOptions = violatingOptions,
                ignoreList = INVALID_OPTIONS,
                requirement = "Deprecated options must have target removal versions in the future. (@ RELEASE TEAM: To handle this error, please read the full error message.) ",
                suggestion = "@ RELEASE TEAM: This error usually happens when we upgrade AGP version.\n" +
                        "We don't have to fix this issue immediately, but we should fix it before the beta release.\n" +
                        "To do that:\n" +
                        "  - Please copy the invalid options shown above to `OptionVersionTest.INVALID_OPTIONS`.\n" +
                        "  - Please file a bug for the AGP team and mark it as blocking the beta release (example bug: b/277803353)."
        )
    }

    @Test
    fun `check removed options do not have removed versions in the future`() {
        val violatingOptions = getAllOptions()
            .filter { it.status is Option.Status.Removed }
            .filter {
                (it.status as Option.Status.Removed).removedVersion.agpVersion > AGP_STABLE_VERSION
            }

        checkViolatingProjectOptions(
                violatingOptions = violatingOptions,
                requirement = "Removed options must not have removed versions in the future."
        )
    }

    @Test
    fun `check BooleanOptions have FutureStage in the future`() {
        val violatingOptions = BooleanOption.entries.filter {
            it.futureStage != null && it.futureStage.version.agpVersion <= AGP_STABLE_VERSION
        }

        checkViolatingProjectOptions(
            violatingOptions = violatingOptions,
            ignoreList = INVALID_OPTIONS,
            requirement = "`BooleanOption`s must have FutureStage in the future. (@ RELEASE TEAM: To handle this error, please read the full error message.) ",
            suggestion = "@ RELEASE TEAM: This error usually happens when we upgrade AGP version.\n" +
                    "We don't have to fix this issue immediately, but we should fix it before the beta release.\n" +
                    "To do that:\n" +
                    "  - Please copy the invalid options shown above to `OptionVersionTest.INVALID_OPTIONS`.\n" +
                    "  - Please file a bug for the AGP team and mark it as blocking the beta release (example bug: b/277803353)."
        )
    }

    private fun getAllOptions(): List<Option<Any>> =
        BooleanOption.entries +
                OptionalBooleanOption.entries +
                StringOption.entries +
                IntegerOption.entries
}

internal fun checkViolatingProjectOptions(
        violatingOptions: List<Option<*>>,
        ignoreList: List<Option<*>> = emptyList(),
        requirement: String,
        suggestion: String? = null) {
    val newViolations = violatingOptions - ignoreList.toSet()
    check(newViolations.isEmpty()) {
        "$requirement\n" +
                "The following options do not meet that requirement:\n" +
                "```\n" +
                newViolations.joinToString(",\n") { "${it.javaClass.simpleName}.$it" } + "\n" +
                "```\n" +
                (suggestion
                    ?: "If this is intended, copy the above code snippet to the ignore list of this test.")
    }

    val fixedViolations = ignoreList - violatingOptions.toSet()
    check(fixedViolations.isEmpty()) {
        "$requirement\n" +
                "The following options have met that requirement:\n" +
                "```\n" +
                fixedViolations.joinToString(",\n") { "${it.javaClass.simpleName}.$it" } + "\n" +
                "```\n" +
                "Remove them from the ignore list of this test."
    }
}
