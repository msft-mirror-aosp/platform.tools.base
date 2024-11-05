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

package com.android.build.api.dsl

// Some basic interface with nesting to test DslProxy and associated classes.

interface Person {
    var name: String
    var surname: String?
    var age: Int?
    var isRobot: Boolean

    val address: Address
    fun address(action: Address.() -> Unit)
}

interface Address {
    var street: String
    var city: String
    var zipCode: Int
}

interface Town {
    val places: MutableList<String>
    val mayor: Person
    fun mayor(action: Person.() -> Unit)
}

interface California {
    val mountainView: Town
}
