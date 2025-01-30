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
import com.android.build.api.dsl.Person
import com.android.build.gradle.integration.common.fixture.project.builder.GroovyBuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.KtsBuildWriter
import com.google.common.truth.Truth
import org.junit.Test

/**
 * this tests the dsl recorder only, by providing a manual instance of the object
 *
 * See [BasicDslProxyTest] for usage integrated with the [DslProxy]
 */
class DslRecorderTest {
    private val dslRecorder = DefaultDslRecorder()

    @Test
    fun simple() {
        dslRecorder.runNestedBlock(
            name = "address",
            parameters = listOf(),
            instanceProvider = { AddressImpl(it) }
        ) {
            street = "foo"
            city = "bar"
            zipCode = 42
        }

        val writer = GroovyBuildWriter()
        dslRecorder.writeContent(writer)
        Truth.assertThat(writer.toString()).isEqualTo("""
            address {
              street = 'foo'
              city = 'bar'
              zipCode = 42
            }

        """.trimIndent())
    }

    @Test
    fun nested() {
        dslRecorder.runNestedBlock(
            "person",
            parameters = listOf(),
            instanceProvider = { PersonImpl(it) }
        ) {
            name = "bob"
            surname = null
            age = 42
            address {
                street = "foo"
                city = "bar"
                zipCode = 42
            }
        }

        val writer = GroovyBuildWriter()
        dslRecorder.writeContent(writer)
        Truth.assertThat(writer.toString()).isEqualTo("""
            person {
              name = 'bob'
              surname = null
              age = 42
              address {
                street = 'foo'
                city = 'bar'
                zipCode = 42
              }
            }

        """.trimIndent())
    }

    @Test
    fun skipNullAndBlock() {
        dslRecorder.runNestedBlock(
            "person",
            parameters = listOf(),
            instanceProvider = { PersonImpl(it) }
        ) {
            name = "bob"
        }

        val writer = GroovyBuildWriter()
        dslRecorder.writeContent(writer)
        Truth.assertThat(writer.toString()).isEqualTo("""
            person {
              name = 'bob'
            }

        """.trimIndent())
    }

    @Test
    fun testOrder() {
        dslRecorder.runNestedBlock(
            "person",
            parameters = listOf(),
            instanceProvider = { PersonImpl(it) }
        ) {
            name = "bo"
            name = "bob"
            address {
                street = "foo"
            }
            address {
                city = "bar"
            }
            address {
                zipCode = 42
            }
        }

        val writer = GroovyBuildWriter()
        dslRecorder.writeContent(writer)
        Truth.assertThat(writer.toString()).isEqualTo("""
            person {
              name = 'bo'
              name = 'bob'
              address {
                street = 'foo'
              }
              address {
                city = 'bar'
              }
              address {
                zipCode = 42
              }
            }

        """.trimIndent())
    }

    @Test
    fun getterChain() {
        dslRecorder.runNestedBlock(
            "person",
            parameters = listOf(),
            instanceProvider = { PersonImpl(it) }
        ) {
            name = "bob"
            address.street = "foo"
            address.city = "bar"
            address.zipCode = 42
        }

        val writer = GroovyBuildWriter()
        dslRecorder.writeContent(writer)
        Truth.assertThat(writer.toString()).isEqualTo("""
            person {
              name = 'bob'
              address.street = 'foo'
              address.city = 'bar'
              address.zipCode = 42
            }

        """.trimIndent())
    }

    @Test
    fun methodCall() {
        dslRecorder.runNestedBlock(
            "person",
            parameters = listOf(),
            instanceProvider = { PersonImpl(it) }
        ) {
            name = "bob"
            sendMessage("Hello!")
            something("one", "two")
        }

        val writer = GroovyBuildWriter()
        dslRecorder.writeContent(writer)
        Truth.assertThat(writer.toString()).isEqualTo("""
            person {
              name = 'bob'
              sendMessage('Hello!')
              something('one', 'two')
            }

        """.trimIndent())
    }

    @Test
    fun mapPut() {
        dslRecorder.runNestedBlock(
            "address",
            parameters = listOf(),
            instanceProvider = { AddressImpl(it) }
        ) {
            properties["foo"] = "bar"
            properties += mapOf("1" to "2", "3" to "4")
        }

        val groovy = GroovyBuildWriter()
        dslRecorder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).isEqualTo("""
            address {
              properties['foo'] = 'bar'
              properties += ['1': '2', '3': '4']
            }

        """.trimIndent())

        val kts = KtsBuildWriter()
        dslRecorder.writeContent(kts)
        Truth.assertThat(kts.toString()).isEqualTo("""
            address {
              properties["foo"] = "bar"
              properties += mapOf("1" to "2", "3" to "4")
            }

        """.trimIndent())
    }
}

/**
 * Manual implementation of the [Person] interface using the [DslRecorder].
 * This simulates the code generated by [DslProxy]
 */
class PersonImpl(private val dslRecorder: DslRecorder): Person {

    override var name: String
        get() = throw RuntimeException("get not supported")
        set(value) {
            dslRecorder.set("name", value)
        }

    override var surname: String?
        get() = throw RuntimeException("get not supported")
        set(value) {
            dslRecorder.set("surname", value)
        }

    override var age: Int?
        get() = throw RuntimeException("get not supported")
        set(value) {
            dslRecorder.set("age", value)
        }

    override var isRobot: Boolean
        get() = throw RuntimeException("get not supported")
        set(value) {
            dslRecorder.setBoolean("robot", value, usingIsNotation = false)
        }

    override val address: Address
        get() = AddressImpl(dslRecorder.createChainedRecorder("address"))

    override fun address(action: Address.() -> Unit) {
        (dslRecorder as DefaultDslRecorder).runNestedBlock(
            "address",
            parameters = listOf(),
            { AddressImpl(it) }
        ) {
            action()
        }
    }

    override fun sendMessage(message: String?) {
        dslRecorder.call("sendMessage", listOf(message),false)
    }

    override fun something(vararg value: String) {
        dslRecorder.call("something", listOf(value), true)
    }

    override fun something(someInt: Int, vararg value: String) {
        dslRecorder.call("something", listOf(someInt, value), true)
    }

    override fun voteFor(candidate: Person) {
        dslRecorder.call("voteFor", listOf(candidate), false)
    }
}

class AddressImpl(private val dslRecorder: DslRecorder): Address {

    @Suppress("UNCHECKED_CAST")
    override val properties: MutableMap<String, String>
        get() = MapProxy<String, String>(dslRecorder.createChainedRecorder("properties"))

    override var street: String
        get() = throw RuntimeException("get not supported")
        set(value) {
            dslRecorder.set("street", value)
        }

    override var city: String
        get() = throw RuntimeException("get not supported")
        set(value) {
            dslRecorder.set("city", value)
        }

    override var zipCode: Int
        get() = throw RuntimeException("get not supported")
        set(value) {
            dslRecorder.set("zipCode", value)
        }
}
