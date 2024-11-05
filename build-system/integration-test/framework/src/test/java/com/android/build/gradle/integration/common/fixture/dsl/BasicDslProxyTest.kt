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

import com.android.build.api.dsl.Address
import com.android.build.api.dsl.California
import com.android.build.api.dsl.Person
import com.android.build.gradle.integration.common.fixture.project.builder.GroovyBuildWriter
import com.google.common.truth.Truth
import org.junit.Test

// this should really test the event content of the holder instead of relying on
// the BuildWriter but it's so much more convenient...

class BasicDslProxyTest {

    @Test
    fun basicTest() {
        val contentHolder = DefaultDslContentHolder()
        contentHolder.runNestedBlock("address", Address::class.java) {
            street = "1600 Amphitheatre Parkway"
            city = "Mountain View"
            zipCode = 94043
        }

        val writer = GroovyBuildWriter()
        contentHolder.writeContent(writer)
        Truth.assertThat(writer.toString()).isEqualTo("""
            address {
              street = '1600 Amphitheatre Parkway'
              city = 'Mountain View'
              zipCode = 94043
            }

        """.trimIndent())
    }

    @Test
    fun nestedTest() {
        val contentHolder = DefaultDslContentHolder()
        contentHolder.runNestedBlock("person", Person::class.java) {
            name = "BugDroid"
            surname = null
            age = 17
            isRobot = true
            address {
                street = "1600 Amphitheatre Parkway"
                city = "Mountain View"
                zipCode = 94043
            }
        }

        val writer = GroovyBuildWriter()
        contentHolder.writeContent(writer)
        Truth.assertThat(writer.toString()).isEqualTo("""
            person {
              name = 'BugDroid'
              surname = null
              age = 17
              isRobot = true
              address {
                street = '1600 Amphitheatre Parkway'
                city = 'Mountain View'
                zipCode = 94043
              }
            }

        """.trimIndent())
    }

    @Test
    fun chainedBlockUsage() {
        val contentHolder = DefaultDslContentHolder()
        contentHolder.runNestedBlock("california", California::class.java) {
            mountainView.mayor {
                name = "bob"
                address.street = "1600 Amphitheatre Parkway"
            }
        }

        val groovy = GroovyBuildWriter()
        contentHolder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).isEqualTo("""
            california {
              mountainView.mayor {
                name = 'bob'
                address.street = '1600 Amphitheatre Parkway'
              }
            }

        """.trimIndent())
    }
}
