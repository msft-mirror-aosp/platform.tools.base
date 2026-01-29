/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.lint.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LintGradleUtilsTest {
  @Test
  fun testGetIncludedPath() {
    assertEquals(
        ":app",
        findFirstIncludedModulePath(
            """
            pluginManagement {
                repositories {
                    maven(url="/Users/tnorbye/dev/studio/dev/out/repo")
                    google {
                        content {
                            includeGroupByRegex("com\\.android.*")
                            includeGroupByRegex("com\\.google.*")
                            includeGroupByRegex("androidx.*")
                        }
                    }
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    maven(url="/Users/tnorbye/dev/studio/dev/out/repo")
                    google()
                    mavenCentral()
                }
            }

            rootProject.name = "My Application"
            include(":app")
            """
                .trimIndent()
        ),
    )

    assertEquals(
        ":mobile",
        findFirstIncludedModulePath(
            """
            include(
                ":mobile",
                ":core:data",
                ":core:data-testing",
                ":core:domain",
                ":core:domain-testing",
                ":core:designsystem",
                ":tv",
                ":wear",
                ":glancewidget"
            )
            """
                .trimIndent()
        ),
    )

    assertEquals(
        ":app",
        findFirstIncludedModulePath(
            """
            rootProject.name = "nowinandroid"
            include ':app'
            include ':benchmark'
            include ':core-common'
            include ':core-domain'
            include ':core-domain-test'
            include ':core-database'
            """
                .trimIndent()
        ),
    )

    assertEquals(
        ":Corona-Warn-App",
        findFirstIncludedModulePath(
            """
            include ':Corona-Warn-App', ':Server-Protocol-Buffer'
            """
                .trimIndent()
        ),
    )

    assertEquals(
        ":servoview-local",
        findFirstIncludedModulePath(
            """
            if (gradle.hasProperty('something')) {
                include ':servoview-local'
            } else {
              include ':servoview'
            }
            """
                .trimIndent()
        ),
    )

    assertEquals(
        ":generativeai",
        findFirstIncludedModulePath(
            """
            rootProject.name = "generativeai"
            includeBuild("./plugins")
            include(":generativeai")
            include(":common")
            """
                .trimIndent()
        ),
    )

    assertEquals(
        ":slack-lint-checks",
        findFirstIncludedModulePath(
            """
            include(":slack-lint-checks", ":slack-lint-annotations")
            """
                .trimIndent()
        ),
    )

    assertEquals(
        ":modules:features:account",
        findFirstIncludedModulePath(
            """
            include(":modules:features:account")
            include(":modules:features:cartheme")
            include(":modules:features:discover")
            """
                .trimIndent()
        ),
    )

    assertEquals(
        ":app-feature-preview",
        findFirstIncludedModulePath(
            """
            include(
                ":app-feature-preview",
                ":app-ui-catalog",
            )
            """
                .trimIndent()
        ),
    )
  }

  @Test
  fun getNonPaths() {
    assertNull(
        findFirstIncludedModulePath(
            """
            pluginManagement {
                includeBuild("build-logic")
            }
            """
                .trimIndent()
        )
    )

    assertNull(
        findFirstIncludedModulePath(
            """
            include modulePrefix + 'demo'
            """
                .trimIndent()
        )
    )

    assertNull(
        findFirstIncludedModulePath(
            """
            includeBuild("${"$"}{settings.ext.flutterSdkPath}/packages/flutter_tools/gradle")
            """
                .trimIndent()
        )
    )

    assertNull(
        findFirstIncludedModulePath(
            """
            //include ':benchmark'
            """
                .trimIndent()
        )
    )

    assertNull(
        findFirstIncludedModulePath(
            """
            plugins.each { name, path ->
                def pluginDirectory = flutterProjectRoot.resolve(path).resolve('android').toFile()
                include ":${"$"}name"
                project(":${"$"}name").projectDir = pluginDirectory
            }
            """
                .trimIndent()
        )
    )

    assertNull(
        findFirstIncludedModulePath(
            """
            include(":${"$"}path")
            """
                .trimIndent()
        )
    )
  }
}
