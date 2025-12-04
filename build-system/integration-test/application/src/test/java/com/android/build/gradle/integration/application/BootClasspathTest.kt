package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.BaseExtension
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Test [BaseExtension.bootClasspath] can be used before afterEvaluate with
 * [BooleanOption.USE_NEW_DSL] set to `false`.
 *
 * Once [BooleanOption.USE_NEW_DSL] is no longer an option, the cleanup described at
 * [BaseExtension.bootClasspath] can happen (and this test can be deleted).
 */
class BootClasspathTest {

    @get:Rule
    var project = GradleTestProject.builder().fromTestApp(HelloWorldApp.noBuildFile()).create()

    @Before
    fun setUp() {
        TestFileUtils.appendToFile(
            project.buildFile,
            """|
               |apply plugin: 'com.android.application'
               |
               |android {
               |    namespace = "${HelloWorldApp.NAMESPACE}"
               |    compileSdkVersion ${GradleTestProject.DEFAULT_COMPILE_SDK_VERSION}
               |
               |    buildToolsVersion '${GradleTestProject.DEFAULT_BUILD_TOOL_VERSION}'
               |}
               |
               |task checkBootClasspath {
               |    assert android.getBootClasspath() != null
               |}""".trimMargin("|")
        )
    }

    @Test
    fun checkBootClasspathCanBeCalled() {
        project.executor().with(BooleanOption.USE_NEW_DSL, false).run("checkBootClasspath")
    }
}
