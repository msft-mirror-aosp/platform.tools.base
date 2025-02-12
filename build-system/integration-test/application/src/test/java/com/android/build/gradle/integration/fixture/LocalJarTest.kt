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

package com.android.build.gradle.integration.fixture

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.output.ZipSubject
import org.junit.Rule
import org.junit.Test

class LocalJarTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies {
                implementation(localJar("libfoo.jar") {
                    addClasses(Foo::class.java)
                    addEmptyClasses("com/example/Bar")
                    addTextFile("com/example/foo.txt", "content")
                })
            }
        }
    }

    @Test
    fun testLocalJar() {
        val build = rule.build
        val app = build.androidApplication()

        ZipSubject.assertThat(app.resolve("libs/libfoo.jar")) {
            entries().containsExactly(
                "com/android/build/gradle/integration/fixture/Foo.class",
                "com/example/Bar.class",
                "com/example/foo.txt"
            )
            textFile("com/example/foo.txt").isEqualTo("content")
        }
    }
}

class Foo {
}
