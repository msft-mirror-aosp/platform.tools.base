package com.android.build.gradle.integration.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import com.android.zipflinger.ZipArchive
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry

/** Tests for DSL AAPT options.  */
class AaptTest {
    @get:Rule  var temporaryFolder = TemporaryFolder()

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                defaultConfig.versionCode = 1
            }
            files {
                add("src/main/assets/ignored", "ignored")
                add("src/main/assets/kept", "kept")
            }
        }
    }

    @Test
    fun testAaptOptionsFlagsWithAapt2() {
        val tracesFolder = temporaryFolder.newFolder()

        val traceFolderPath = tracesFolder.absolutePath
        val windowsFriendlyFilePath = traceFolderPath.replace("\\", "\\\\")

        val build = rule.build {
            androidApplication {
                android {
                    androidResources {
                        additionalParameters += listOf("--trace-folder", windowsFriendlyFilePath)
                    }
                }
            }
        }

        build.executor.run("clean", "assembleDebug")

        // Check that ids file is generated
        assertThat(tracesFolder).exists()
        Truth.assertThat(tracesFolder.listFiles()!!.size).isEqualTo(1)
        FileUtils.deleteDirectoryContents(tracesFolder)

        build.androidApplication().reconfigure(buildFileOnly = true) {
            android.androidResources.additionalParameters.clear()
        }

        build.executor.run("assembleDebug")

        // Check that ids file is not generated
        Truth.assertThat(tracesFolder.listFiles()).isEmpty()
    }

    class TraceFolderCallback: ApplicationComponentCallback {
        override fun handleComponents(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            // we cannot pass values to the callback, so we're going to read it via a property
            val path = project.providers.gradleProperty("_aaptTest_").orNull ?: throw RuntimeException("cannot find _aaptTest_")

            androidComponents.onVariants { variant ->
                variant.androidResources.aaptAdditionalParameters.addAll(listOf("--trace-folder", path))
            }
        }
    }

    @Test
    fun testAaptOptionsFlagsWithAapt2FromVariantApi() {
        val tracesFolder = temporaryFolder.newFolder()
        val traceFolderPath = tracesFolder.absolutePath
        val windowsFriendlyFilePath = traceFolderPath.replace("\\", "\\\\")

        val build = rule.configure().withProperties {
            add("_aaptTest_", windowsFriendlyFilePath)
        }.build {
            androidApplication {
                componentCallback = TraceFolderCallback::class.java
            }
        }

        build.executor.run("assembleDebug")
        assertThat(tracesFolder).exists()
        Truth.assertThat(tracesFolder.listFiles()!!.size).isEqualTo(1)
    }

    @Test
    fun emptyNoCompressList() {
        val build = rule.build {
            androidApplication {
                android {
                    androidResources {
                        noCompress("")
                    }
                }
            }
        }

        build.executor.run("clean", "assembleDebug")

        // Check that APK entries are uncompressed
        build.androidApplication().withApk(ApkSelector.DEBUG) {
            // TODO (Issue 70118728) res/layout/main.xml should be uncompressed too.
            val entry = ZipArchive.listEntries(file)["classes.dex"]
            Truth.assertThat(entry?.compressionFlag).isEqualTo(ZipEntry.STORED)
        }
    }

    @Test
    fun testIgnoreAssetsPatterns_dsl() {
        val build = rule.build {
            androidApplication {
                android.androidResources.ignoreAssetsPattern = "ignored"
            }
        }

        build.executor.run("clean", "assembleDebug")

        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            containsFile("assets/kept")
            doesNotContain("assets/ignored")
        }
    }

    class IgnorePatternCallback: ApplicationComponentCallback {

        override fun handleComponents(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.onVariants { variant ->
                variant.androidResources.ignoreAssetsPatterns.add("ignored")
            }
        }

    }

    @Test
    fun testIgnoreAssetsPatterns_variantApi() {
        val build = rule.build {
            androidApplication {
                componentCallback = IgnorePatternCallback::class.java
            }
        }

        build.executor.run("clean", "assembleDebug")

        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            contains("assets/kept")
            doesNotContain("assets/ignored")
        }
    }

    @Test
    fun testTasksRunAfterAaptOptionsChanges_bundleDebug() {
        testTasksRunAfterAaptOptionsChanges(
            assembleTask = "bundleDebug",
            expectedTasksThatDidWorkOnANoCompressChange = listOf(
                ":app:bundleDebugResources",
                ":app:mergeDebugJavaResource",
                ":app:packageDebugBundle",
                ":app:processDebugResources"
            ),
            expectedTasksThatDidWorkOnANoIgnoreAssetsChange = listOf(
                ":app:mergeDebugAssets"
            )
        )
    }

    @Test
    fun testTasksRunAfterAaptOptionsChanges_assembleDebug() {
        testTasksRunAfterAaptOptionsChanges(
            assembleTask = "assembleDebug",
            expectedTasksThatDidWorkOnANoCompressChange = listOf(
                ":app:mergeDebugJavaResource",
                ":app:packageDebug",
                ":app:processDebugResources"
            ),
            expectedTasksThatDidWorkOnANoIgnoreAssetsChange = listOf(
                ":app:mergeDebugAssets"
            )
        )
    }

    class IgnorePatternCallback2 : ApplicationComponentCallback {

        override fun handleComponents(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            // we want to change the value we pass here, so we're going to read this
            // from a property again
            val value = project.providers.systemProperty("newIgnorePattern").orNull

            if (value != null) {
                androidComponents.onVariants { variant ->
                    variant.androidResources.ignoreAssetsPatterns.set(listOf(".ignoreAssetsPatternApi2"))
                }
            }
        }
    }

    private fun testTasksRunAfterAaptOptionsChanges(
        assembleTask: String,
        expectedTasksThatDidWorkOnANoCompressChange: List<String>,
        expectedTasksThatDidWorkOnANoIgnoreAssetsChange: List<String>
    ) {
        val build = rule.build {
            androidApplication {
                android {
                    androidResources {
                        noCompress += "noCompressDsl"
                        ignoreAssetsPattern = ".ignoreAssetsPatternDsl"
                    }
                }
                componentCallback = IgnorePatternCallback2::class.java
            }
        }

        build.executor.run("clean", assembleTask)

        val app = build.androidApplication()

        // test that tasks run when aapt options changed via the DSL
        app.reconfigure(buildFileOnly = true) {
            android.androidResources{
                noCompress.clear()
                noCompress += "noCompressDsl2"
            }
        }
        build.executor.run(assembleTask).let { result ->
            Truth.assertThat(result.didWorkTasks)
                .containsAtLeastElementsIn(expectedTasksThatDidWorkOnANoCompressChange)
        }

        app.reconfigure(buildFileOnly = true) {
            android.androidResources{
                ignoreAssetsPattern = ".ignoreAssetsPatternDsl2"
            }
        }

        build.executor.run(assembleTask).let { result ->
            Truth.assertThat(result.didWorkTasks)
                .containsAtLeastElementsIn(expectedTasksThatDidWorkOnANoIgnoreAssetsChange)
        }

        // test that tasks run when aapt options changed via the variant API
        build.executor
            .withArgument("-DnewIgnorePattern=true")
            .run(assembleTask).let { result ->
            Truth.assertThat(result.didWorkTasks)
                .containsAtLeastElementsIn(expectedTasksThatDidWorkOnANoIgnoreAssetsChange)
        }
    }
}
