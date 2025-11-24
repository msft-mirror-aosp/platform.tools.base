/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.android.builder.model.SyncIssue
import com.android.testutils.AssumeUtil
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.regex.Pattern

/**
 * A very simple test to compile a project with special characters in it
 */
@RunWith(Parameterized::class)
class SpecialCharactersBasicTest(projectName: String) {

    @get:Rule
    val rule = GradleRule.fromProject(BasicSpec(), folderName = projectName)

    @Test
    fun testProjectsWithSpecialCharacters() {
        // Windows won't work with the weird characters, and we throw an exception already
        AssumeUtil.assumeNotWindows()

        val build = rule.build

        build.executor.run("assemble")

        val container = build.modelBuilder.ignoreSyncIssues().fetchModels().container
        val issues = container.getProject().issues!!.syncIssues

        // basic project overwrites buildConfigField which emits a sync warning
        issues.forEach { issue ->
            assertThat(issue.severity).isEqualTo(SyncIssue.SEVERITY_WARNING)
            assertThat(issue.message)
                .containsMatch(Pattern.compile(".*value is being replaced.*"))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun projectNames(): Collection<String> {
            return listOf(
                "1b@s %i péà`e eã~e=.{}\$#!&^()¡²³¤€¼½¾‘’¥×βαосಮೂ基本どきコラપા기본आधមូលั้นਬੁਨਿਆძიমৌƏՀիመሠ",
                "בסיסיالأساسيةיקערדיק"

                /* TODO lint fails when ";" is part of the project name b/458128469
                "test;project" */

                /* Add these for individual language tests
                "βασικός",
                "основной",
                "ಮೂಲಭೂತ",
                "基本的な",
                "પાયાની",
                "الأساسية",
                "기본",
                "आधारभूत",
                "יקערדיק",
                "មូលដ្ឋាន",
                "ขั้นพื้นฐาน",
                "ਬੁਨਿਆਦੀ",
                "בסיסי",
                "ძირითადი",
                "মৌলিক",
                "Əsas",
                "Հիմնական",
                "መሠረታዊ"*/
            )
        }
    }
}
