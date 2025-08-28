/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.build.gradle.integration.application;

import static com.google.common.truth.Truth.assertThat;

import com.android.build.gradle.integration.common.fixture.GradleTestProject;
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp;
import com.android.build.gradle.integration.common.utils.TestFileUtils;

import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class PostprocessingTest {
    @Rule
    public GradleTestProject project =
            GradleTestProject.builder()
                    .fromTestApp(HelloWorldApp.forPlugin("com.android.application"))
                    .create();

    @Test
    public void features_oldDsl() throws Exception {
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "android.buildTypes.release {\n"
                        + "minifyEnabled true\n"
                        + "proguardFiles"
                        + " android.getDefaultProguardFile('proguard-android-optimize.txt'),"
                        + " 'proguard-rules.pro'\n"
                        + "}\n");

        Files.asCharSink(project.file("proguard-rules.pro"), StandardCharsets.UTF_8)
                .write("-printconfiguration build/proguard-config.txt");

        project.executor().run("assembleRelease");

        String proguardConfiguration =
                Files.toString(project.file("build/proguard-config.txt"), StandardCharsets.UTF_8);

        assertThat(proguardConfiguration).doesNotContain("-dontoptimize");
        assertThat(proguardConfiguration).doesNotContain("-dontshrink");
        assertThat(proguardConfiguration).doesNotContain("-dontobfuscate");
    }

}
