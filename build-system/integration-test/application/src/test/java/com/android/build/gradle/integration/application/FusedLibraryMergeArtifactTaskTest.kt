/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.JavaLibraryProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.PluginCallback
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.junit.Rule
import org.junit.Test

/** Tests for merging and packaging of non classes.jar or resources artifacts. */
internal class FusedLibraryMergeArtifactsTest {

    @get:Rule
    val rule = GradleRule.configure().from {
        // Library dependency at depth 1 with no dependencies.
        androidLibrary(":androidLib1") {
            android {
                namespace = "com.example.androidLib1"
                defaultConfig {
                    minSdk = 12
                    renderscriptTargetApi = 18
                    renderscriptSupportModeEnabled = true
                    aarMetadata {
                        minCompileSdk = 12
                        minAgpVersion = "3.0.0"
                        minCompileSdkExtension = 2
                    }
                }
                buildFeatures {
                    renderScript = true
                }
            }
            files {
                add("src/main/assets/android_lib_one_asset.txt", "androidLib1")
                add("src/main/assets/subdir/android_lib_one_asset_in_subdir.txt", "androidLib1")
                add("src/main/resources/my_java_resource.txt", "androidLib1")
                add(
                    "src/main/rs/com/example/androidLib1/ip.rsh",
                    """
                        #pragma version(1)
                        #pragma rs java_package_name(com.android.rs.image2)
                    """.trimIndent()
                )
                add(
                    "src/main/rs/com/example/androidLib1/copy.rs",
                    """
                        #include "ip.rsh"

                        uchar4 __attribute__((kernel)) root(uchar4 v_in) {
                            return v_in;
                        }
                    """.trimIndent()
                )
            }
            }
        // Library dependency at depth 0 with a dependency on androidLib1.
        androidLibrary(":androidLib2") {
            android {
                namespace = "com.example.androidLib2"
                defaultConfig.minSdk = 19
            }
            dependencies {
                implementation(project(":androidLib1"))
            }
            files.add("src/main/assets/android_lib_two_asset.txt", "androidLib2")
        }
        // Library dependency at depth 0 with no dependencies
        androidLibrary(":androidLib3") {
            android {
                namespace = "com.example.androidLib3"
                defaultConfig {
                    minSdk = 18
                    aarMetadata {
                        minCompileSdk = 18
                        minAgpVersion = "4.0.1"
                    }
                }
            }
            dependencies {
                implementation(project(":androidLib1"))
            }
        }
        androidLibrary(":libraryWithLint") {
            android {
                lint {
                    textReport = true
                    checkOnly += "UnitTestLintCheck"
                    absolutePaths = false
                }
            }
            dependencies {
                lintPublish(project(":lintPublish1"))
            }
        }
        javaLibrary(":lintPublish1") {
            setUpLint(AddMyIssueRegistryManifestAttribute::class.java as Class<GenericCallback>)
        }
        androidLibrary(":libraryWithLint2") {
            android {
                lint {
                    textReport = true
                    checkOnly += "UnitTestLintCheck"
                    absolutePaths = false
                }
            }
            dependencies {
                lintPublish(project(":lintPublish2"))
            }
        }
        javaLibrary(":lintPublish2") {
            setUpLint(AddMyIssueRegistryManifestAttribute::class.java as Class<GenericCallback>)
            files {
                add(
                    "src/main/java/com/example/google/lintpublish2/ClassFromLintPublish2.java",
                    //language=java
                    """
                    package com.example.google.lintpublish2;

                    public class ClassFromLintPublish2 {
                        public int foo() { return 0; }
                    }
                    """.trimIndent()
                )
            }
        }
        androidLibrary(":libraryWithLint3") {
            android {
                lint {
                    textReport = true
                    checkOnly += "UnitTestLintCheck"
                    absolutePaths = false
                }
            }
            dependencies {
                lintPublish(project(":lintPublish3"))
            }
        }
        javaLibrary(":lintPublish3") {
            setUpLint(AddMyIssueRegistryManifest2Attribute::class.java as Class<GenericCallback>)
        }
        fusedLibrary(":fusedLib1") {
            androidFusedLibrary {
                namespace = "com.example.fusedLib1"
                minSdk = 19
            }
            dependencies {
                include(project(":libraryWithLint"))
                include(project(":androidLib3"))
                include(project(":androidLib2"))
                include(project(":androidLib1"))
            }
        }
        gradleProperties {
            add(BooleanOption.FUSED_LIBRARY_SUPPORT, true)
        }
    }
    class AddMyIssueRegistryManifestAttribute: GenericCallback {
        override fun handleProject(project: Project) {
            val jar = project.tasks.named("jar", Jar::class.java)
            jar.configure {
                it.manifest {
                    it.attributes(
                        mutableMapOf<String, String>(
                            "Lint-Registry-v2" to "com.example.google.lint.MyIssueRegistry"
                        )
                    )
                }
            }
        }
    }
    class AddMyIssueRegistryManifest2Attribute: GenericCallback {
        override fun handleProject(project: Project) {
            val jar = project.tasks.named("jar", Jar::class.java)
            jar.configure {
                it.manifest {
                    it.attributes(
                        mutableMapOf<String, String>(
                            "Lint-Registry-v2" to "com.example.google.lint.MyIssueRegistry2"
                        )
                    )
                }
            }
        }
    }

    private fun JavaLibraryProjectDefinition.setUpLint(callback: Class<GenericCallback>) {
        pluginCallbacks += callback
        dependencies {
            compileOnly("com.android.tools:annotations:+")
            compileOnly("com.android.tools.lint:lint-api:+")
            compileOnly("com.android.tools.lint:lint-checks:+")
        }
        files {
            add(
                "src/main/java/com/example/google/lintpublish/MyIssueRegistry.java",
                """
                    package com.example.google.lintpublish;

                    import com.android.tools.lint.client.api.IssueRegistry;
                    import com.android.tools.lint.detector.api.Issue;
                    import com.android.tools.lint.detector.api.ApiKt;
                    import java.util.Collections;
                    import java.util.List;

                    public class MyIssueRegistry extends IssueRegistry {
                        @Override
                        public List<Issue> getIssues() {
                            return Collections.singletonList(MainActivityDetector.ISSUE);
                        }

                        @Override
                        public int getApi() {
                            return com.android.tools.lint.detector.api.ApiKt.CURRENT_API;
                        }
                    }
                    """.trimIndent()
            )
            add(
                "src/main/java/com/example/google/lintpublish/MainActivityDetector.java",
                """
                    package com.example.google.lintpublish;

                    import com.android.tools.lint.detector.api.Category;
                    import com.android.tools.lint.detector.api.Implementation;
                    import com.android.tools.lint.detector.api.Issue;
                    import com.android.tools.lint.detector.api.ResourceXmlDetector;
                    import com.android.tools.lint.detector.api.Scope;
                    import com.android.tools.lint.detector.api.Severity;
                    import com.android.tools.lint.detector.api.XmlContext;
                    import com.android.tools.lint.detector.api.XmlScanner;
                    import java.util.Collection;
                    import java.util.Collections;
                    import org.w3c.dom.Element;

                    public class MainActivityDetector extends ResourceXmlDetector implements XmlScanner {
                        public static final Issue ISSUE =
                                Issue.create(
                                                "UnitTestLintCheck",
                                                "Custom Lint Check",
                                                "This app should not have any activities.",
                                                Category.CORRECTNESS,
                                                8,
                                                Severity.ERROR,
                                                new Implementation(MainActivityDetector.class, Scope.MANIFEST_SCOPE))
                                        .
                                        // Make sure other integration tests don't pick this up.
                                        // The unit test will turn it on with android.lintOptions.check <id>
                                        setEnabledByDefault(false);

                        public MainActivityDetector() {}

                        @Override
                        public Collection<String> getApplicableElements() {
                            return Collections.singleton("activity");
                        }

                        @Override
                        public void visitElement(XmlContext context, Element activityElement) {
                            context.report(
                                    ISSUE,
                                    activityElement,
                                    context.getLocation(activityElement),
                                    "Should not specify <activity>.",
                                    null);
                        }
                    }
                            """.trimIndent()
            )
        }
    }

    @Test
    fun testAarMetadataMerging() {
        val build = rule.build
        build.executor.run(":fusedLib1:assemble")

        val fusedLib1 = build.fusedLibrary(":fusedLib1")
        fusedLib1.assertAar(AarSelector.NO_BUILD_TYPE) {
            aarMetadata {
                // Value constant from AGP
                formatVersion().isEqualTo("1.0")
                // Value constant from AGP
                metadataVersion().isEqualTo("1.0")
                // Value from androidLib3
                minAgpVersion().isEqualTo("4.0.1")
                // Value from androidLib3
                minCompileSdk().isEqualTo("18")
                // Value from androidLib1
                minCompileSdkExtension().isEqualTo("2")
            }
        }

        fusedLib1.reconfigure {
            androidFusedLibrary {
                aarMetadata.minAgpVersion = "8.4-alpha02"
                aarMetadata.minCompileSdk = 9
            }
        }
        build.executor.run(":fusedLib1:assemble")

        fusedLib1.assertAar(AarSelector.NO_BUILD_TYPE) {
            aarMetadata {
                // Value constant from AGP
                formatVersion().isEqualTo("1.0")
                // Value constant from AGP
                metadataVersion().isEqualTo("1.0")
                // Value from aarMetadata DSL
                minAgpVersion().isEqualTo("8.4-alpha02")
                // Value from aarMetadata DSL
                minCompileSdk().isEqualTo("9")
                // Default value
                minCompileSdkExtension().isEqualTo("0")
            }
        }
    }

    @Test
    fun testAssetsMergingWithDuplicateAssets() {
        val build = rule.build
        val fusedLib1 = build.fusedLibrary(":fusedLib1")
        val androidLib3 = build.androidLibrary(":androidLib3")

        // Adds duplicate asset file in androidLib3, which should override the asset in androidLib1,
        // as androidLib3 is declared as a dependency in fusedLib1 before androidLib1 transitively
        // through androidLib2.
        androidLib3.files.add("src/main/assets/android_lib_one_asset.txt", "androidLib3")

        build.executor.run(":fusedLib1:assemble")

        fusedLib1.assertAar(AarSelector.NO_BUILD_TYPE) {
            assets {
                resourceAsText("android_lib_one_asset.txt").isEqualTo("androidLib3")
                containsExactly(
                    "android_lib_one_asset.txt",
                    "android_lib_two_asset.txt",
                    "subdir/android_lib_one_asset_in_subdir.txt"
                )

            }
        }
    }

    @Test
    fun testRenderscriptCreatedJniCopiesToFusedLibrary() {
       val build = rule.build
        val fusedLib1 = build.fusedLibrary(":fusedLib1")
        build.executor.run(":fusedLib1:assemble")

        fusedLib1.assertAar(AarSelector.NO_BUILD_TYPE) {
            jniLibs().containsExactly(
                "armeabi-v7a/librsjni_androidx.so",
                "armeabi-v7a/libRSSupport.so",
                "armeabi-v7a/librsjni.so",
                "armeabi-v7a/librs.copy.so",
                "x86_64/librsjni_androidx.so",
                "x86_64/libRSSupport.so",
                "x86_64/librsjni.so",
                "arm64-v8a/librsjni_androidx.so",
                "arm64-v8a/libRSSupport.so",
                "arm64-v8a/librsjni.so",
                "x86/librsjni_androidx.so",
                "x86/libRSSupport.so",
                "x86/librsjni.so",
                "x86/librs.copy.so"
            )
        }
    }

    @Test
    fun testJavaResourcesMerge() {
        val build = rule.build
        val fusedLib1 = build.fusedLibrary(":fusedLib1")
        build.executor.run(":fusedLib1:assemble")

        fusedLib1.assertAar(AarSelector.NO_BUILD_TYPE) {
            javaResources().containsExactly("my_java_resource.txt")
        }

        val androidLib2 = build.androidLibrary(":androidLib2")
        androidLib2.files.add("src/main/resources/my_java_resource.txt", "content")

        build.executor.expectFailure().run(":fusedLib1:assemble").assertErrorContains(
            "2 files found with path 'my_java_resource.txt'"
        )
    }

    @Test
    fun testLintJarIsPackaged() {
        val build = rule.build
        build.executor.run(":fusedLib1:assemble")

        val fusedLib = build.fusedLibrary(":fusedLib1")
        fusedLib.assertAar(AarSelector.NO_BUILD_TYPE) {
            // Fused AARs should contain the lint jars from their dependencies.
            contains("lint.jar")
            lintJar().classes {
                // Classes are from :libraryWithLint1
                contains("com/example/google/lintpublish/MainActivityDetector.class")
                contains("com/example/google/lintpublish/MyIssueRegistry.class")
                contains("META-INF/MANIFEST.MF")
            }
        }

        // Check merging ignores duplicate lint classes from other lint.jar files.
        build.fusedLibrary(":fusedLib1").reconfigure {
            dependencies {
                include(project(":libraryWithLint2"))
            }
        }
        build.executor.run(":fusedLib1:assemble")
        fusedLib.assertAar(AarSelector.NO_BUILD_TYPE) {
            // Fused AARs should contain the lint jars from their dependencies.
            contains("lint.jar")
            lintJar().classes {
                // Classes from :lintPublish2
                contains("src/main/java/com/example/google/lintpublish2/ClassFromLintPublish2.class")
                // Class from :lintPublish1 and :lintPublish2 (identical in both)
                contains("com/example/google/lintpublish/MainActivityDetector.class")
                contains("com/example/google/lintpublish/MyIssueRegistry.class")
                contains("META-INF/MANIFEST.MF")
            }
        }
        // Check a lint check with different contents triggers a conflict when fusing.
        build.javaLibrary(":lintPublish2").reconfigure {
            files.update("src/main/java/com/example/google/lintpublish/MyIssueRegistry.java") {
                appendMethod("public int newMethod() { return 0; }")
            }
        }
        build.executor.expectFailure().run(":fusedLib1:assemble")
            .assertErrorContains(
            "com/example/google/lintpublish/MyIssueRegistry.class is present in multiple jars with different contents"
        )

        // Build fails on a conflicting lint.jar MANIFEST.MF file.
        build.fusedLibrary(":fusedLib1").reconfigure {
            dependencies {
                remove("include", project(":libraryWithLint2"))
                include(project(":libraryWithLint3"))
            }
        }
        build.executor.expectFailure().run(":fusedLib1:assemble")
            .assertErrorContains("META-INF/MANIFEST.MF is present in multiple jars")
    }
}
