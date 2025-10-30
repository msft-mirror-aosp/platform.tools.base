package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.tasks.OptimizeResourcesTask
import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.pathString
import org.junit.Rule
import org.junit.Test

/** Integration tests for [OptimizeResourcesTask]. */
class OptimizeResourcesTaskTest {

    @Rule
    @JvmField
    val project = GradleTestProject.builder()
            .fromTestApp(HelloWorldApp.forPlugin("com.android.application"))
            .create()

    @Test
    fun `test OptimizeResourcesTask works with resource shrinker`() {
        project.buildFile.appendText(
            """android {
                buildTypes {
                    release {
                        shrinkResources = true
                        minifyEnabled true
                    }
                }
            }"""
        )
        project.execute("assembleRelease")
        project.getApk(GradleTestProject.ApkType.RELEASE).apply {
            assertThat(entries.map(Path::pathString)).contains("/res/01.xml")
        }

        project.getReportsFile(
            InternalArtifactType.RESOURCES_CONFIG_MAP_FILE.getFolderName(),
            "release",
            "resources.cfg"
        ).apply {
            assertThat(readText()).isEqualTo("res/layout/main.xml -> res/01.xml\n")
        }
    }
}
