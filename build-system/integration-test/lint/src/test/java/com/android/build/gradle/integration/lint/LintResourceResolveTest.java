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

package com.android.build.gradle.integration.lint;

import static com.android.testutils.truth.PathSubject.assertThat;

import com.android.build.gradle.integration.common.fixture.GradleTestProject;
import com.android.build.gradle.options.BooleanOption;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;

/**
 * Test making sure that the SupportAnnotationUsage does not report errors referencing R.type.name
 * resource fields. (Regression test for bug 133326990.)
 */
@RunWith(Parameterized.class)
public class LintResourceResolveTest {

    @Parameterized.Parameters(name = "aggregationEnabled={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{true}, {false}});
    }

    private final boolean aggregationEnabled;

    public LintResourceResolveTest(boolean aggregationEnabled) {
        this.aggregationEnabled = aggregationEnabled;
    }

    @Rule
    public final GradleTestProject project =
            GradleTestProject.builder()
                    .fromTestProject("lintResourceResolve")
                    .create();

    @Test
    public void checkClean() throws Exception {
        // Run twice to catch issues with configuration caching
        runLint();
        runLint();
        project.getBuildResult().assertConfigurationCacheHit();

        if (aggregationEnabled) {
            assertThat(
                            new File(
                                    project.getSubproject("app").getBuildDir(),
                                    "reports/local-lint-results-debug.txt"))
                    .contentWithUnixLineSeparatorsIsExactly("No issues found.");
            assertThat(
                            new File(
                                    project.getSubproject("app").getBuildDir(),
                                    "reports/aggregated-lint-results-debug.txt"))
                    .contentWithUnixLineSeparatorsIsExactly("No issues found.");
            assertThat(
                            new File(
                                    project.getSubproject("app").getBuildDir(),
                                    "reports/local-lint-results-debug.sarif"))
                    .contains("\"$schema\" : \"https://docs.oasis-open.org/sarif/sarif/");
            assertThat(
                            new File(
                                    project.getSubproject("app").getBuildDir(),
                                    "reports/aggregated-lint-results-debug.sarif"))
                    .contains("\"$schema\" : \"https://docs.oasis-open.org/sarif/sarif/");
        } else {
            assertThat(new File(project.getSubproject("app").getProjectDir(), "lint-report.txt"))
                    .contentWithUnixLineSeparatorsIsExactly("No issues found.");
            assertThat(
                            new File(
                                    project.getSubproject("app").getBuildDir(),
                                    "reports/lint-results-debug.sarif"))
                    .contains("\"$schema\" : \"https://docs.oasis-open.org/sarif/sarif/");
        }
    }

    private void runLint() throws Exception {
        project.executor()
                .with(BooleanOption.LINT_REPORT_AGGREGATION, aggregationEnabled)
                .run(":app:clean", ":app:lintDebug");
        if (aggregationEnabled) {
            project.executor()
                    .with(BooleanOption.LINT_REPORT_AGGREGATION, aggregationEnabled)
                    .run(":app:lintAggregatedDebug");
        }
    }
}
