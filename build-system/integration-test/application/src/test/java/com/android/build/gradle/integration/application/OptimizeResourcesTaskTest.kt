package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.tasks.OptimizeResourcesTask
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.readText

/** Integration tests for [OptimizeResourcesTask]. */
class OptimizeResourcesTaskTest {

    @Rule
    @JvmField
    val rule = GradleRule.from {
        androidApplication {
            HelloWorldAndroid.setupKotlin(files)
        }
    }

    @Test
    fun `test OptimizeResourcesTask works with resource shrinker`() {
        val build = rule.build {
            androidApplication {
                android {
                    buildTypes {
                        named("release") {
                            it.isShrinkResources = true
                            it.isMinifyEnabled = true
                        }
                    }
                }
            }
        }

        build.executor.run(":app:assembleRelease")

        with(build.androidApplication()) {
            assertApk(ApkSelector.RELEASE) {
                androidResources().contains("01.xml")
            }

            buildDir
                .resolve("reports/${InternalArtifactType.RESOURCES_CONFIG_MAP_FILE.getFolderName()}/release/resources.cfg")
                .apply {
                    assertThat(readText()).isEqualTo("res/layout/main.xml -> res/01.xml\n")
                }
        }
    }
}
