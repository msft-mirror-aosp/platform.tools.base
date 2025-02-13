/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.library

import com.android.build.gradle.integration.common.dependencies.AarBuilder
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.internal.dsl.ModulePropertyKey.BooleanWithDefault
import com.android.build.gradle.internal.fusedlibrary.FusedLibraryInternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.tasks.FusedLibraryReport
import com.android.testutils.truth.PathSubject
import org.gradle.api.JavaVersion
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests to verify the classes that are packaged within the AAR are correct or cause an expected
 * build time failure when invalid.
 */
class FusedLibraryClassesVerificationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            aar(groupId ="com.externaldep.externalaar", version = "1.0").generateExternalAarContent()

            aar(groupId ="com.externaldep.externalaar", version = "2.0").generateExternalAarContent()

            jar("com.externaldep:depwithdep:1.0")
                .withDependencies("com.externaldep.externalaar:externalaar:1.0")

            jar("this.dependency:has-a-dependency-that-does-not-exist:1.0")
                .withDependencies("this.dependency:doesnotexist:1.0")
        }.from {
            androidLibrary(":androidLib1") {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
                android {
                    namespace = "com.example.androidLib1"
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_1_8
                        targetCompatibility = JavaVersion.VERSION_1_8
                    }
                }
                files.add(
                    "src/main/java/com/example/androidLib1/ClassFromAndroidLib1.kt",
                    // language=kotlin
                    """
                        package com.example.androidLib1
                        class ClassFromAndroidLib1 {
                            fun foo(): String {
                                return "foo"
                            }
                        }
                    """.trimIndent()
                )
            }
            /*
                    androidLib1
                        ▲
                        │
                    androidLib2
             */
            androidLibrary(":androidLib2") {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
                android {
                    namespace = "com.example.androidLib2"
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_1_8
                        targetCompatibility = JavaVersion.VERSION_1_8
                    }
                }
                files.add(
                    "src/main/java/com/example/androidLib2/ClassFromAndroidLib2.kt",
                    // language=kotlin
                    """
                        package com.example.androidLib2
                        class ClassFromAndroidLib2 {
                            fun foo(): String {
                                return "foo"
                            }
                        }
                    """.trimIndent()
                )
                dependencies {
                    implementation(project(":androidLib1"))
                }
            }
            /*
                    androidLib1
                         ▲
                         │
                     androidLib2
                         ▲
                         │
             androidLibWithManyTransitiveDeps
             */
            androidLibrary(":$ANDROID_LIB_MANY_TRANSITIVE_DEPS") {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
                android {
                    namespace = "com.example.$ANDROID_LIB_MANY_TRANSITIVE_DEPS"
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_1_8
                        targetCompatibility = JavaVersion.VERSION_1_8
                    }
                }
                files.add(
                    "src/main/java/com/example/$ANDROID_LIB_MANY_TRANSITIVE_DEPS/ClassFrom$ANDROID_LIB_MANY_TRANSITIVE_DEPS.kt",
                    //language=kotlin
                    """
                        package com.example.$ANDROID_LIB_MANY_TRANSITIVE_DEPS
                        class ClassFrom$ANDROID_LIB_MANY_TRANSITIVE_DEPS {
                            fun baz(): String {
                                return "baz"
                            }
                        }
                    """.trimIndent()
                )
                dependencies {
                    implementation(project(":androidLib2"))
                }
            }
            /*
              com.externaldep:externalaar:1
                           ▲
                           │
             androidLibWithExternalLibDependency
             */
            androidLibrary(":$ANDROID_LIB_WITH_EXTERNAL_LIB_DEPENDENCY") {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
                android {
                    namespace = "com.example.$ANDROID_LIB_WITH_EXTERNAL_LIB_DEPENDENCY"
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_1_8
                        targetCompatibility = JavaVersion.VERSION_1_8
                    }
                }
                files.add(
                    "src/main/java/com/example/$ANDROID_LIB_WITH_EXTERNAL_LIB_DEPENDENCY/ClassFrom$ANDROID_LIB_WITH_EXTERNAL_LIB_DEPENDENCY.kt",
                    //language=kotlin
                    """
                        package com.example.$ANDROID_LIB_WITH_EXTERNAL_LIB_DEPENDENCY
                        class ClassFrom$ANDROID_LIB_WITH_EXTERNAL_LIB_DEPENDENCY {
                            fun foo(): String {
                                return "foo"
                            }
                        }
                    """.trimIndent()
                )
                dependencies {
                    implementation("com.externaldep.externalaar:externalaar:1.0")
                }
            }
            /*
                    com.externaldep:externalaar:1
                                 ▲
                                 │
                    com.externaldep:depwithdep:1
                                 ▲
                                 │
               androidLibWithExternalLibWithCircularDep
             */
            androidLibrary(":$ANDROID_LIB_WITH_EXTERNAL_LIB_WITH_CIRCULAR_DEP") {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
                android {
                    namespace = "com.example.$ANDROID_LIB_WITH_EXTERNAL_LIB_WITH_CIRCULAR_DEP"
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_1_8
                        targetCompatibility = JavaVersion.VERSION_1_8
                    }
                }
                files.add(
                    "src/main/java/com/example/$ANDROID_LIB_WITH_EXTERNAL_LIB_WITH_CIRCULAR_DEP/ClassFrom$ANDROID_LIB_WITH_EXTERNAL_LIB_WITH_CIRCULAR_DEP.kt",
                    //language=kotlin
                    """
                        package com.example.$ANDROID_LIB_WITH_EXTERNAL_LIB_WITH_CIRCULAR_DEP
                        class ClassFrom$ANDROID_LIB_WITH_EXTERNAL_LIB_WITH_CIRCULAR_DEP {
                            fun fob(): String {
                                return "fob"
                            }
                        }
                    """.trimIndent()
                )
                dependencies {
                    implementation("com.externaldep:depwithdep:1.0")
                }
            }
            androidLibrary(":$ANDROID_LIB_WITH_DATABINDING") {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
                android {
                    namespace = "com.example.$ANDROID_LIB_WITH_DATABINDING"
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_1_8
                        targetCompatibility = JavaVersion.VERSION_1_8
                    }
                    buildFeatures {
                        dataBinding = true
                        viewBinding = true
                    }
                    dataBinding {
                        enable = true
                    }
                }
            }
            fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME") {
                applyPlugin(PluginType.MAVEN_PUBLISH)
                androidFusedLibrary {
                    namespace = "com.example.fusedLib1"
                    minSdk = 34
                    experimentalProperties[BooleanWithDefault.FUSED_LIBRARY_VALIDATE_DEPENDENCIES.key] = true
                }
                // Use addDependenciesToFusedLibProject() for setting dependencies.
                dependencies {}
            }
            /*
              fusedLib1
                 ▲
                 │
                app
             */
            androidApplication {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
                android {
                    namespace = "com.example.myapp"
                    defaultConfig.minSdk = 34
                }
                dependencies {
                    implementation(project(":$FUSED_LIBRARY_PROJECT_NAME"))
                }
            }
            gradleProperties {
                add(BooleanOption.FUSED_LIBRARY_SUPPORT, true)
                add(BooleanOption.USE_ANDROID_X, true)
            }
        }

    @Test
    fun testClassesFromDirectDependenciesAreIncludedInAar() {
        val build = rule.build {
            fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME") {
                dependencies {
                    include(project(":androidLib1"))
                    include(project(":androidLib2"))
                }
            }
        }
        val classesFromDirectDependencies = listOf(
            "com/example/androidLib2/ClassFromAndroidLib2",
            "com/example/androidLib1/ClassFromAndroidLib1",
        )

        build.assertFusedLibAarContainsExpectedClasses(classesFromDirectDependencies)
        build.checkFusedLibReportContents(
            listOf("project :androidLib1", "project :androidLib2"),
            listOf(
                "org.jetbrains.kotlin:kotlin-stdlib:<version>",
                "org.jetbrains:annotations:<version>"
            ),
        )
    }

    @Test
    fun checkTransitivesAreNotIncludedInAarImplicitly() {
        val build = rule.build {
            fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME") {
                dependencies {
                    include(project(":androidLib1"))
                    include(project(":androidLib2"))
                    include(project(":$ANDROID_LIB_WITH_EXTERNAL_LIB_DEPENDENCY"))
                }
            }
        }

        val classesFromDirectDependencies = listOf(
            // From :androidLib2
            "com/example/androidLib2/ClassFromAndroidLib2",
            // From :androidLib1
            "com/example/androidLib1/ClassFromAndroidLib1",
            // From :androidLibWithExternalLibDependency
            "com/example/androidLibWithExternalLibDependency/ClassFromandroidLibWithExternalLibDependency"
        )

        build.assertFusedLibAarContainsExpectedClasses(classesFromDirectDependencies)
        build.checkFusedLibReportContents(
            listOf(
                "project :androidLib1",
                "project :androidLib2",
                "project :androidLibWithExternalLibDependency"
            ),
            listOf(
                "org.jetbrains.kotlin:kotlin-stdlib:<version>",
                "org.jetbrains:annotations:<version>",
                "com.externaldep.externalaar:externalaar:1.0"
            )
        )
    }

    @Test
    fun checkNotIncludedProjectDependenciesAddedAsDependencies() {
        val build = rule.build {
            fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME") {
                dependencies {
                    include(project(":androidLib2"))
                }
            }
        }

        val classesFromDirectDependencies = listOf(
            "com/example/androidLib2/ClassFromAndroidLib2", // From :androidLib2
        )
        build.assertFusedLibAarContainsExpectedClasses(classesFromDirectDependencies)
        build.checkFusedLibReportContents(
            listOf("project :androidLib2"),
            listOf(
                "org.jetbrains.kotlin:kotlin-stdlib:<version>",
                "org.jetbrains:annotations:<version>",
                "project.:androidLib1:unspecified"
            )
        )
    }

    @Test
    fun checkExternalLibraryClassesIncludedInFusedAar() {
        val build = rule.build {
            fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME") {
                dependencies {
                    include("com.externaldep.externalaar:externalaar:1.0")
                    include(project(":androidLib1"))
                }
            }
        }

        val classesFromDirectDependencies = listOf(
            "com/example/androidLib1/ClassFromAndroidLib1", // From :androidLib1
            "com/externaldep/externaljar/ExternalClass" // From com.externaldep:externalaar:1
        )

        build.assertFusedLibAarContainsExpectedClasses(classesFromDirectDependencies)

        build.checkFusedLibReportContents(
            listOf("com.externaldep.externalaar:externalaar:1.0", "project :androidLib1"),
            listOf(
                "org.jetbrains.kotlin:kotlin-stdlib:<version>",
                "org.jetbrains:annotations:<version>"
            )
        )
    }

    @Test
    fun checkFusedLibraryAarForClassesFromLocalJarDependencies() {
        val build = rule.build {
            fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME") {
                dependencies {
                    include(localJar("testClass.jar") {
                        addClasses(TestClass::class.java)
                    })
                }
            }
        }
        val fusedLibrary = build.fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME")
        val appProject = build.androidApplication()

        build.executor.run(":$FUSED_LIBRARY_PROJECT_NAME:assemble")

        fusedLibrary.assertAar(AarSelector.NO_BUILD_TYPE) {
            contains("libs/testClass.jar")
        }

        fusedLibrary.files.add("src/main/java/com/example/myapp/AppClass.kt",
            //language=kotlin
            """
            package com.example.myapp
            import com.android.build.gradle.integration.library.TestClass
            class AppClass {
                abstract fun aFunctionThatReturnsATypeFromFusedLibraryLibsJars(): TestClass
            }
            """.trimIndent()
        )

        build.executor.run(":app:assembleDebug")

        appProject.assertApk(ApkSelector.DEBUG) {
            hasClass("Lcom/android/build/gradle/integration/library/TestClass;") }
    }

    @Test
    fun checkPublishingFailsForLibrariesWithDatabinding() {
        val build = rule.build {
            fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME") {
                dependencies {
                    include(project(":androidLib1"))
                    include(project(":$ANDROID_LIB_WITH_DATABINDING"))
                }
            }
        }

        for (enableAndroidx in listOf(true, false)) {
            val failureExecutor = build.executor
                    .with(BooleanOption.USE_ANDROID_X, enableAndroidx)

            val expectedFailure = "Validation failed due to 1 issue(s) with :fusedLib1 dependencies:\n" +
                    "   [Databinding is not supported by Fused Library modules]:\n" +
                    "    * androidx.databinding:databinding-common is not a permitted dependency."

            listOf(
                "generatePomFileForMavenPublication",
                "publish",
                "publishToMavenLocal"
            ).forEach {
                val publicationFailure =
                    failureExecutor.expectFailure().run(":$FUSED_LIBRARY_PROJECT_NAME:$it")
                publicationFailure.assertErrorContains(expectedFailure)
            }

            val buildFailure = failureExecutor.expectFailure().run(":$FUSED_LIBRARY_PROJECT_NAME:bundle")
            buildFailure.assertErrorContains(expectedFailure)
        }
    }

    @Test
    fun `validationFailsForDependencyIncludedButParentNotIncluded-ProjectDependency`() {
        val build = rule.build {
            fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME") {
                dependencies {
                    include(project(":$ANDROID_LIB_MANY_TRANSITIVE_DEPS"))

                    // :androidLib1 is also a transitive dependency from $ANDROID_LIB_MANY_TRANSITIVE_DEPS via :androidLib2
                    include(project(":androidLib1"))
                }
            }
        }

        val failure = build.executor.expectFailure()
            .run(":$FUSED_LIBRARY_PROJECT_NAME:assemble")
        failure.assertErrorContains(
            "Validation failed due to 1 issue(s) with :fusedLib1 dependencies:\n" +
                    "   [Require transitive dependency inclusion]:\n" +
                    "    * project :androidLib1 is included in the fused library .aar, " +
                    "however its parent dependency project :androidLib2 was not.")
    }

    @Test
    fun `validationFailsForDependencyIncludedButParentNotIncluded-ExternalDependency`() {
        val build = rule.build {
            fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME") {
                dependencies {
                    include(project(":$ANDROID_LIB_WITH_EXTERNAL_LIB_DEPENDENCY"))
                    include(project(":$ANDROID_LIB_WITH_EXTERNAL_LIB_WITH_CIRCULAR_DEP"))
                    include("com.externaldep.externalaar:externalaar:1.0")
                }
            }
        }

        val failure = build.executor.expectFailure()
            .run(":$FUSED_LIBRARY_PROJECT_NAME:assemble")
        failure.assertErrorContains(
            "Validation failed due to 1 issue(s) with :fusedLib1 dependencies:\n" +
                    "   [Require transitive dependency inclusion]:\n" +
                    "    * com.externaldep.externalaar:externalaar:1.0 is included in the fused library .aar, " +
                    "however its parent dependency com.externaldep:depwithdep:1.0 was not.")

        // Check validation can be disabled.
        build.fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME").reconfigure {
            androidFusedLibrary {
                experimentalProperties[BooleanWithDefault.FUSED_LIBRARY_VALIDATE_DEPENDENCIES.key] = false
            }
        }
        build.executor.run(":$FUSED_LIBRARY_PROJECT_NAME:assemble")
    }

    //Regression test for b/383184394
    @Test
    fun checkUnresolvedDependencyFailures() {
        val build = rule.build {
            fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME") {
                dependencies {
                    include("this.dependency:has-a-dependency-that-does-not-exist:1.0")
                }
            }
        }

        val failure = build.executor.expectFailure().run(":$FUSED_LIBRARY_PROJECT_NAME:assemble")
        failure.assertErrorContains(
            "> Validation failed due to 1 issue(s) with :fusedLib1 dependencies:\n" +
                    "   [Unresolved Dependencies]:\n" +
                    "    * Could not find this.dependency:doesnotexist:1.0.\n" +
                    "  Searched in the following locations:")
        failure.assertErrorContains(
            "  The following checks did not finish:\n" +
                    "   [Databinding is not supported by Fused Library modules]:\n" +
                    "    * class org.gradle.api.internal.artifacts.result.DefaultUnresolvedDependencyResult is not supported by this check.\n" +
                    "   [Require transitive dependency inclusion]:\n" +
                    "    * class org.gradle.api.internal.artifacts.result.DefaultUnresolvedDependencyResult is not supported by this check."
        )
    }

    private fun GradleBuild.checkFusedLibReportContents(
        included: List<String>,
        dependencies: List<String>
    ) {
        executor.run(":$FUSED_LIBRARY_PROJECT_NAME:report")

        val reportFile = fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME").buildDir.resolve(
            "reports/${FusedLibraryInternalArtifactType.FUSED_LIBRARY_REPORT.getFolderName()}/single/report.json"
        )
        PathSubject.assertThat(reportFile).isFile()

        val fusedLibReport = FusedLibraryReport.readFromFile(reportFile.toFile())

        assertThat(fusedLibReport.included).containsExactlyElementsIn(included)
        val idWithoutVersionPlaceholder =
            { str: String -> str.substringBeforeLast(":<version>") }
        assertThat(dependencies.count()).isEqualTo(fusedLibReport.dependencies.count())
        dependencies.map(idWithoutVersionPlaceholder).zip(fusedLibReport.dependencies).forEach() {
            assertThat(it.first).isEqualTo(it.second.substring(0, it.first.length))
        }
    }

    private fun GradleBuild.assertFusedLibAarContainsExpectedClasses(classesFromDirectDependencies: List<String>) {
        executor.run(":$FUSED_LIBRARY_PROJECT_NAME:assemble")

        val fusedLib1Project = fusedLibrary(":$FUSED_LIBRARY_PROJECT_NAME")

        fusedLib1Project.assertAar(AarSelector.NO_BUILD_TYPE) {
            mainJar().classes().apply {
                classesFromDirectDependencies.forEach {
                    contains(it)
                }
            }
        }
    }

    private fun AarBuilder.generateExternalAarContent() {
        withManifest(
            //language=xml
            """
                <manifest package="com.externaldep.externalaar" xmlns:android="http://schemas.android.com/apk/res/android">
                    <uses-sdk android:targetSdkVersion="34" android:minSdkVersion="21" />
                    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
                </manifest>
            """.trimIndent())
        withMainJar {
            addEmptyClasses("com/externaldep/externaljar/ExternalClass")
        }
    }

    companion object {
        const val FUSED_LIBRARY_PROJECT_NAME = "fusedLib1"
        const val ANDROID_LIB_WITH_EXTERNAL_LIB_DEPENDENCY = "androidLibWithExternalLibDependency"
        const val ANDROID_LIB_MANY_TRANSITIVE_DEPS = "androidLibManyTransitiveDeps"
        const val ANDROID_LIB_WITH_EXTERNAL_LIB_WITH_CIRCULAR_DEP = "androidLibWithExternalLibWithCircularDep"
        const val ANDROID_LIB_WITH_DATABINDING = "androidLibWithDatabinding"
        const val FUSED_LIBRARY_R_CLASS = "com/example/fusedLib1/R.class"
    }
}

private class TestClass
