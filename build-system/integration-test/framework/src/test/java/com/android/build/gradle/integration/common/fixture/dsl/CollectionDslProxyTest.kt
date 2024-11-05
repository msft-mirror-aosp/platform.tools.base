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

package com.android.build.gradle.integration.common.fixture.dsl

import com.android.build.api.dsl.California
import com.android.build.api.dsl.Town
import com.android.build.gradle.integration.common.fixture.project.builder.GroovyBuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.KtsBuildWriter
import com.google.common.truth.Truth
import org.junit.Test

class CollectionDslProxyTest {

    @Test
    fun listAdd() {
        val contentHolder = DefaultDslContentHolder()
        contentHolder.runNestedBlock("town", Town::class.java) {
            places += "Post Office"
        }

        val writer = GroovyBuildWriter()
        contentHolder.writeContent(writer)
        Truth.assertThat(writer.toString()).isEqualTo("""
            town {
              places += 'Post Office'
            }

        """.trimIndent())
    }

    @Test
    fun listAddAll() {
        val contentHolder = DefaultDslContentHolder()
        contentHolder.runNestedBlock("town", Town::class.java) {
            places += listOf("Post Office", "City Hall")
        }

        val groovy = GroovyBuildWriter()
        contentHolder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).isEqualTo("""
            town {
              places += ['Post Office', 'City Hall']
            }

        """.trimIndent())

        val kts = KtsBuildWriter()
        contentHolder.writeContent(kts)
        Truth.assertThat(kts.toString()).isEqualTo("""
            town {
              places += listOf("Post Office", "City Hall")
            }

        """.trimIndent())

    }

    @Test
    fun chainedListUsage() {
        val contentHolder = DefaultDslContentHolder()
        contentHolder.runNestedBlock("california", California::class.java) {
            mountainView.places += listOf("Post Office", "City Hall")
        }

        val groovy = GroovyBuildWriter()
        contentHolder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).isEqualTo("""
            california {
              mountainView.places += ['Post Office', 'City Hall']
            }

        """.trimIndent())
    }
}
