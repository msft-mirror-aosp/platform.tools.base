/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.fusedlibrary

import com.android.build.gradle.integration.common.fixture.DEFAULT_MIN_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.output.JarSubject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.integration.fusedlibrary.FusedLibraryTestConstants.FUSED_LIBRARY_ARTIFACT_NAME
import com.android.build.gradle.integration.fusedlibrary.FusedLibraryTestConstants.FUSED_LIBRARY_GROUP
import com.android.build.gradle.integration.fusedlibrary.FusedLibraryTestConstants.FUSED_LIBRARY_REPO_NAME
import com.android.build.gradle.integration.fusedlibrary.FusedLibraryTestConstants.FUSED_LIBRARY_VERSION
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import java.util.zip.ZipEntry
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.junit.Rule
import org.junit.Test
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile

class FusedLibraryTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            aar("com.remotedep.remoteaar.a", "remoteaar-a", "1.0")
                .addResource(
                    "values/strings.xml",
                    // language=XML
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="remote_b_string">Remote String from remoteaar a</string>
                        </resources>
                    """.trimIndent()
                )
                .withDependencies(listOf("com.remotedep.remoteaar.b:remoteaar-b:1.0"))

            aar("com.remotedep.remoteaar.b", "remoteaar-b", "1.0")
                .addResource(
                    "values/strings.xml",
                    // language=XML
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="remote_b_string">Remote String from remoteaar b</string>
                        </resources>
                    """.trimIndent()
                )
        }.from {
            androidLibrary(":androidLib1") {
                android {
                    namespace = "com.example.androidLib1"
                    publishing {
                        multipleVariants {
                            withSourcesJar()
                            allVariants()
                        }
                    }
                    enableKotlin = false
                }
                dependencies {
                    implementation("junit:junit:4.12")
                    implementation(project(":androidLib3"))
                }
                files {
                    add(
                        "src/main/res/values/strings.xml",
                        //language=xml
                        """
                        <resources>
                            <string name="string_from_android_lib_1">androidLib2</string>
                        </resources>
                    """.trimIndent()
                    )
                    add(
                        "src/main/java/com/example/androidLib1/ClassFromAndroidLib1.java",
                        //language=java
                        """
                            package com.example.androidLib2;

                            public class ClassFromAndroidLib1 {
                                int bar() {
                                    return 1;
                                }
                            }
                        """.trimIndent()
                    )
                }
            }
            androidLibrary(":androidLib2") {
                android {
                    namespace = "com.example.androidLib2"
                    publishing {
                        multipleVariants {
                            withSourcesJar()
                            allVariants()
                        }
                    }
                    enableKotlin = false
                }
                files {
                    add(
                        "src/main/java/com/example/androidLib2/ClassFromAndroidLib2.java",
                        //language=java
                        """
                            package com.example.androidLib2;

                            public class ClassFromAndroidLib2 {
                                int foo() {
                                    return 1;
                                }
                            }
                        """.trimIndent()
                    )
                }
            }
            androidLibrary(":androidLib3") {
                android {
                    namespace = "com.example.androidLib3"
                    enableKotlin = false
                }
                group = "fusedlib"
                version = "1.0.0"
            }
            fusedLibrary(":fusedLib1") {
                androidFusedLibrary {
                    minSdk {
                        version = release(DEFAULT_MIN_SDK_VERSION)
                    }
                }
                pluginCallbacks += FusedLibPublicationCallback::class.java
                dependencies {
                    include(project(":androidLib1"))
                    include(project(":androidLib2"))
                    include("com.remotedep.remoteaar.a:remoteaar-a:1.0")
                }
            }
            gradleProperties {
                add(BooleanOption.FUSED_LIBRARY_SUPPORT, true)
            }
        }

    @Test
    fun checkAarNoPublishing() {
        val build = rule.build
        build.executor.run(":fusedLib1:assemble")

        build.fusedLibrary(":fusedLib1").assertAar(AarSelector.NO_BUILD_TYPE) {
            exists()
        }
    }

    @Test
    fun checkAarPublishing() {
        val build = rule.build
        val fusedLibrary = build.fusedLibrary(":fusedLib1")

        build.executor.run(
            "generatePomFileForMavenPublication",
            "generateMetadataFileForMavenPublication",
            "publishReleasePublicationToMyrepoRepository"
        )

        fusedLibrary.buildDir.resolve("publications/maven")
            .also { publicationDir ->
                val pom = publicationDir.resolve("pom-default.xml")
                assertExpectedPomDependencies(
                    pom, listOf(
                        "junit:junit:4.12 scope:runtime",
                        "org.hamcrest:hamcrest-core:1.3 scope:runtime",
                        "fusedlib:androidLib3:1.0.0 scope:runtime",
                        "com.remotedep.remoteaar.b:remoteaar-b:1.0 scope:runtime"
                    )
                )
                Truth.assertThat(publicationDir.resolve("module.json").isRegularFile()).isTrue()
            }

        fusedLibrary.buildDir.resolve(FUSED_LIBRARY_REPO_NAME).also { repoPath ->
            val publishedLibRepoDir = repoPath.resolve(
                "$FUSED_LIBRARY_GROUP/${FUSED_LIBRARY_ARTIFACT_NAME}/$FUSED_LIBRARY_VERSION"
            )
            assertThat(
                publishedLibRepoDir.resolve(
                    "$FUSED_LIBRARY_ARTIFACT_NAME-${FUSED_LIBRARY_VERSION}.aar"
                )
                    .isRegularFile()
            ).isTrue()

            assertExpectedPomDependencies(
                publishedLibRepoDir.resolve(
                    "$FUSED_LIBRARY_ARTIFACT_NAME-${FUSED_LIBRARY_VERSION}.pom"
                ),
                listOf(
                    "junit:junit:4.12 scope:runtime",
                    "org.hamcrest:hamcrest-core:1.3 scope:runtime",
                    "fusedlib:androidLib3:1.0.0 scope:runtime",
                    "com.remotedep.remoteaar.b:remoteaar-b:1.0 scope:runtime"
                )
            )

            val publishedSourcesJar =
                publishedLibRepoDir.resolve("$FUSED_LIBRARY_ARTIFACT_NAME-${FUSED_LIBRARY_VERSION}-sources.jar")
            JarSubject.assertThat(publishedSourcesJar) {
                resources().containsExactly(
                    "com/example/androidLib1/ClassFromAndroidLib1.java",
                    "com/example/androidLib2/ClassFromAndroidLib2.java"
                )
                classes().isEmpty()
            }
        }
    }

    @Test
    fun checkSourcesCauseError() {
        val build = rule.build {
            fusedLibrary(":fusedLib1") {
                files {
                    // No sources are permitted in the Fused Library
                    add(
                        "src/main/java/com/fused/library/NotAllowed.java",
                        ""
                    )
                }
            }
        }

        val failure = build.executor.expectFailure().run(":fusedLib1:assemble")
        failure.assertErrorContains(
            "Fused Library modules do not allow sources. Only dependencies are allowed.\n" +
                    "   Recommended Action: Ensure any sources added to `:fusedLib1` are moved to an " +
                    "Android Library that is a dependency of `:fusedLib1`"
        )
    }

    @Test
    fun checkContentsOfEmptyFusedLibrary() {
        val build = rule.build {
            fusedLibrary(":empty-fused-library") {
                androidFusedLibrary {
                    namespace = "com.example.emptyFusedLibrary"
                    minSdk {
                        version = release(DEFAULT_MIN_SDK_VERSION)
                    }
                }
            }
        }
        build.executor.run(":empty-fused-library:assemble")
        val buildDir = build.fusedLibrary(":empty-fused-library").buildDir
            .resolve("outputs/aar/empty-fused-library.aar")
        ZipFile(buildDir.toFile()).use {
            Truth.assertThat(it.entries().asSequence().map(ZipEntry::getName).toList()).containsExactly(
                "META-INF/",
                "META-INF/MANIFEST.MF",
                "classes.jar", // Included as Java Resources merging always packages a 'base.jar'.
                "AndroidManifest.xml",
                "META-INF/com/android/build/gradle/aar-metadata.properties"
            )
        }
    }
}

object FusedLibraryTestConstants {
    const val FUSED_LIBRARY_GROUP = "my-company"
    const val FUSED_LIBRARY_ARTIFACT_NAME = "my-fused-library"
    const val FUSED_LIBRARY_VERSION = "1.0"
    const val FUSED_LIBRARY_REPO_NAME = "repo"
}
class FusedLibPublicationCallback: GenericCallback {
    override fun handleProject(project: Project) {
        project.plugins.apply("maven-publish")

        val publishing = project.extensions.findByType(PublishingExtension::class.java)
            ?: throw RuntimeException("Could not find extension of type PublishingExtension")
        publishing.apply {
            publications.create("release", MavenPublication::class.java) {
                it.groupId = FUSED_LIBRARY_GROUP
                it.artifactId = FUSED_LIBRARY_ARTIFACT_NAME
                it.version = FUSED_LIBRARY_VERSION
                it.from(project.components.getByName("fusedLibraryComponent"))
            }
            repositories {
                it.maven {
                    it.name = "myrepo"
                    it.url = project.uri(project.layout.buildDirectory.dir(FUSED_LIBRARY_REPO_NAME))
                }
            }
        }
    }
}
