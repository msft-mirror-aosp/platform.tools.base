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
package com.android.flags

import java.util.Objects
import kotlin.test.assertFailsWith
import org.junit.Test

class CustomFlagTest {

    @Test
    fun checkAllowList() {
        val flags = Flags()
        val group = FlagGroup(flags, "test", "Test Group")

        class InvalidFlag: CustomTypeFlag<Boolean>(
            Boolean::class.java,
            group,
            "name",
            "displayName",
            "description",
            false,
            object : ValueConverter<Boolean> {
                override fun serialize(value: Boolean): String = value.toString()
                override fun deserialize(strValue: String): Boolean = strValue.toBoolean()
            },
            listOf(false, true))
        assertFailsWith<Exception> {
            InvalidFlag()
        }
    }

    class NonDataClassCustomValue(val value: String) {
        override fun toString() =
            "NonDataClassCustomValue(value=$value)"
    }

    @Test
    fun checkValidation() {
        val flags = Flags()
        val group = FlagGroup(flags, "test", "Test Group")

        class CustomValueFlag: CustomTypeFlag<NonDataClassCustomValue>(
            NonDataClassCustomValue::class.java,
            group,
            "name",
            "displayName",
            "description",
            NonDataClassCustomValue("1"),
            object : ValueConverter<NonDataClassCustomValue> {
                override fun serialize(value: NonDataClassCustomValue): String = value.value
                override fun deserialize(strValue: String): NonDataClassCustomValue = NonDataClassCustomValue(strValue)
            },
            listOf(NonDataClassCustomValue("1"), NonDataClassCustomValue("2")))
        val flag = CustomValueFlag()
        val failure = assertFailsWith<Exception> {
            flag.validate()
        }
    }
}
