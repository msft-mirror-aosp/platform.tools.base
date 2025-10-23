/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.build.gradle.integration.dependencies.lib

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.MavenRepoGenerator
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DependencyConstraintsIntegrationTest {
    val mavenRepoGenerator = MavenRepoGenerator(listOf(
        MavenRepoGenerator.Library("com.example:package:1.0-runtimeOnly"),
        MavenRepoGenerator.Library("com.example:package:2.0-compileOnly"),
        MavenRepoGenerator.Library("com.example:package:3.0-androidTestRuntimeOnly"),
        MavenRepoGenerator.Library("com.example:package:4.0-androidTestCompileOnly"),
        MavenRepoGenerator.Library("com.example:package:5.0-testRuntimeOnly"),
        MavenRepoGenerator.Library("com.example:package:6.0-testCompileOnly"),
    ))

    @JvmField
    @Rule
    val app: GradleTestProject = GradleTestProject.builder()
        .withName("app")
        .fromTestApp(MinimalSubProject.app())
        .disableBuiltInKotlin()
        .withAdditionalMavenRepo(mavenRepoGenerator).create()

    @JvmField
    @Rule
    val lib: GradleTestProject = GradleTestProject.builder()
        .withName("lib")
        .fromTestApp(MinimalSubProject.lib())
        .disableBuiltInKotlin()
        .withAdditionalMavenRepo(mavenRepoGenerator).create()

    @Before
    fun setUpDependencies() {
        // These dependencies are set up in a way that if a higher version is constrained by the
        // lower version, it would fail dependency resolution. We use that to make sure these
        // constraints are applied.
        listOf(lib, app).forEach { it.buildFile.appendText("""
            |dependencies {
            |    testCompileOnly 'com.example:package:6.0-testCompileOnly'
            |    testRuntimeOnly 'com.example:package:5.0-testRuntimeOnly'
            |    androidTestCompileOnly 'com.example:package:4.0-androidTestCompileOnly'
            |    androidTestRuntimeOnly 'com.example:package:3.0-androidTestRuntimeOnly'
            |    compileOnly 'com.example:package:2.0-compileOnly'
            |    runtimeOnly 'com.example:package:1.0-runtimeOnly'
            |}
            |""".trimMargin())
            }
    }

    @Test
    fun `default constraint behaviour`() {
        app.assertConstrained("debugAndroidTestRuntimeClasspath", """
            |debugAndroidTestRuntimeClasspath - Resolved configuration for runtime for variant: debugAndroidTest
            |+--- com.example:package:3.0-androidTestRuntimeOnly FAILED
            |\--- com.example:package:{strictly 1.0-runtimeOnly} FAILED
        """)

        // Nothing else should fail as nothing's aligned
        lib.assertNotConstrained("debugCompileClasspath")
        lib.assertNotConstrained("debugAndroidTestRuntimeClasspath")
        lib.assertNotConstrained("debugUnitTestRuntimeClasspath")
        app.assertNotConstrained("debugCompileClasspath")
        app.assertNotConstrained("debugUnitTestRuntimeClasspath")
    }


    @Test
    fun `all constraints applied`() {
        listOf(lib, app).forEach {
            it.gradlePropertiesFile.appendText(
                """
            ${BooleanOption.USE_DEPENDENCY_CONSTRAINTS.propertyName}=true
            ${BooleanOption.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS.propertyName}=false
        """.trimIndent()
            )
        }
        // Failures indicate runtime-compile constraint is applied for main artifact (failure to downgrade)
        listOf(lib, app).assertConstrained("debugCompileClasspath", """
            |debugCompileClasspath - Resolved configuration for compilation for variant: debug
            |+--- com.example:package:2.0-compileOnly FAILED
            |\--- com.example:package:{strictly 1.0-runtimeOnly} FAILED
        """)


        // This won't fail because the constraints we only ever apply this for app android test.
        lib.assertNotConstrained("debugAndroidTestRuntimeClasspath")
        // Failures indicate runtime-androidTestRuntime constraint is applied for app (failure to downgrade)
        app.assertConstrained("debugAndroidTestRuntimeClasspath", """
            |debugAndroidTestRuntimeClasspath - Resolved configuration for runtime for variant: debugAndroidTest
            |+--- com.example:package:3.0-androidTestRuntimeOnly FAILED
            |\--- com.example:package:{strictly 1.0-runtimeOnly} FAILED
        """)

        // Failures indicate runtime-compile constraint is applied for unit test (failure to downgrade)
        listOf(lib, app).assertConstrained("debugUnitTestCompileClasspath", """
            |+--- root project : (*)
            |+--- com.example:package:6.0-testCompileOnly FAILED
            |\--- com.example:package:{strictly 5.0-testRuntimeOnly} FAILED
        """)

    }

    @Test
    fun `all constraints applied excluding libraries`() {
        listOf(lib, app).forEach {
            it.gradlePropertiesFile.appendText(
                """
            ${BooleanOption.USE_DEPENDENCY_CONSTRAINTS.propertyName}=true
            ${BooleanOption.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS.propertyName}=true
        """.trimIndent()
            )
        }
        // This won't fail because no constraints are applied for libraries
        lib.assertNotConstrained("debugCompileClasspath")
        // Failures indicate runtime-compile constraint is applied for main artifact (failure to downgrade)
        app.assertConstrained("debugCompileClasspath", """
            |debugCompileClasspath - Resolved configuration for compilation for variant: debug
            |+--- com.example:package:2.0-compileOnly FAILED
            |\--- com.example:package:{strictly 1.0-runtimeOnly} FAILED
        """)


        // This won't fail because the constraints we only ever apply this for app android test.
        lib.assertNotConstrained("debugAndroidTestRuntimeClasspath")
        // Failures indicate runtime-androidTestRuntime constraint is applied for app (failure to downgrade)
        app.assertConstrained("debugAndroidTestRuntimeClasspath", """
            |debugAndroidTestRuntimeClasspath - Resolved configuration for runtime for variant: debugAndroidTest
            |+--- com.example:package:3.0-androidTestRuntimeOnly FAILED
            |\--- com.example:package:{strictly 1.0-runtimeOnly} FAILED
        """)

        // This won't fail because no constraints are applied for libraries
        lib.assertNotConstrained("debugUnitTestCompileClasspath")
        // We also do not constrain this case anymore to keep the already complicated logic simpler
        // (it's a corner case of a specific non-default flag configuration)
        app.assertNotConstrained("debugUnitTestCompileClasspath")
    }

    @Test
    fun `succeeds with constraints all disabled`() {
        listOf(lib, app).forEach {
            it.gradlePropertiesFile.appendText(
                "${BooleanOption.DISABLE_ALL_CONSTRAINTS.propertyName}=true"
            )
        }
        // Nothing should fail as nothing's aligned
        lib.assertNotConstrained("debugCompileClasspath")
        lib.assertNotConstrained("debugAndroidTestRuntimeClasspath")
        lib.assertNotConstrained("debugUnitTestRuntimeClasspath")
        app.assertNotConstrained("debugCompileClasspath")
        app.assertNotConstrained("debugAndroidTestRuntimeClasspath")
        app.assertNotConstrained("debugUnitTestRuntimeClasspath")
    }

    private fun List<GradleTestProject>.assertConstrained(configurationName: String, expectedOutput: String) {
        forEach { it.assertConstrained(configurationName, expectedOutput) }
    }


    private fun GradleTestProject.assertConstrained(configurationName: String, expectedOutput: String) {
        val result = executor().withArguments(listOf("dependencies","--configuration", configurationName)).run()
        result.stdout.use {
            ScannerSubject.assertThat(it).contains(expectedOutput.trimMargin())
        }
    }

    private fun GradleTestProject.assertNotConstrained(configurationName: String) {
        val result = executor().withArguments(listOf("dependencies","--configuration", configurationName)).run()
        result.stdout.use {
            ScannerSubject.assertThat(it).doesNotContain("FAILED")
        }
    }

}
