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

package com.android.build.gradle.integration.publishing

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.testutils.truth.PathSubject
import com.google.common.io.Resources
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class SigningPublishedArtifactsTest {

    @get:Rule
    val rule = GradleRule.from {
        androidLibrary {
            applyPlugin(PluginType.MAVEN_PUBLISH)

            android {
                publishing {
                    multipleVariants {
                        allVariants()
                        withSourcesJar()
                        withJavadocJar()
                    }
                }
            }

            pluginCallbacks += SigningCallback::class.java
        }
        settings {
            addRepository("repo")
        }
        gradleProperties {
            add("signing.keyId", "70E99D38")
            add("signing.password", "Testing123")
            add("signing.secretKeyRingFile", "secring.gpg")
        }
    }

    class SigningCallback: GenericCallback {
        override fun handleProject(project: Project) {
            project.plugins.apply("signing")

            val publishing = project.extensions.findByType(PublishingExtension::class.java)
                ?: throw RuntimeException("Could not find extension of type PublishingExtension")

            publishing.apply {
                publications.create("myPublication", MavenPublication::class.java) { publication ->
                    publication.groupId = "com.android"
                    publication.artifactId = "lib"
                    publication.version = "1.0"

                    project.afterEvaluate {
                        publication.from(project.components.getByName("default"))
                    }
                }
                repositories {
                    it.maven {
                        it.url = project.uri(project.projectDir.parentFile.resolve("repo"))
                    }
                }
            }

            val signing = project.extensions.findByType(SigningExtension::class.java)
                ?: throw RuntimeException("Could not find extension of type SigningExtension")
            signing.apply {
                sign(publishing.publications)
            }
        }
    }

    @Before
    fun setUp() {
    }

    @Test
    fun testIntegrationWithGradleSigningPlugin() {
        val build = rule.build
        val lib = build.androidLibrary()

        val url = Resources.getResource(
            SigningPublishedArtifactsTest::class.java,
            "SigningPublishedArtifactsTest/secring.gpg"
        )
        lib.files.add("secring.gpg", Resources.toByteArray(url))

        build.executor.run("clean", "publish")

        val artifactsDir = lib.resolve("../repo/com/android/lib/1.0")
        val javadocDebugAsc = artifactsDir.resolve("lib-1.0-debug-javadoc.jar.asc")
        val sourcesDebugAsc = artifactsDir.resolve("lib-1.0-debug-sources.jar.asc")
        val javadocReleaseAsc = artifactsDir.resolve("lib-1.0-release-javadoc.jar.asc")
        PathSubject.assertThat(javadocDebugAsc).exists()
        PathSubject.assertThat(sourcesDebugAsc).exists()
        PathSubject.assertThat(javadocReleaseAsc).exists()
    }
}
