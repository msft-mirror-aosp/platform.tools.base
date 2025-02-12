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

package com.android.build.gradle.integration.common.output

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import org.objectweb.asm.tree.ClassNode

/**
 * Information about a class to test with [ClassDefinitionSubject]
 *
 * This is not meant to be generated manually, but instead the subject is directly created
 * by [ClassesSubject.classDefinition]
 */
sealed interface ClassDefinition {
    val interfaces: List<String>
    val innerClasses: List<String>
    val fields: List<String>
    val methods: List<String>
}

/**
 * Implementation of [ClassDefinition] over ASM's [ClassNode]
 */
internal class ClassDefinitionFromAsm(private val classNode: ClassNode): ClassDefinition {
    override val interfaces: List<String>
        get() = classNode.interfaces
    override val innerClasses: List<String>
        get() = classNode.innerClasses.map { it.name }
    override val fields: List<String>
        get() = classNode.fields.map { it.name }
    override val methods: List<String>
        get() = classNode.methods.map { it.name }
}

/**
 * Implementation of [ClassDefinition] over smali's [DexBackedClassDef]
 */
internal class ClassDefinitionFromDex(private val dex: DexBackedClassDef): ClassDefinition {
    override val interfaces: List<String>
        get() = throw RuntimeException("Not yet implemented")
    override val innerClasses: List<String>
        get() = throw RuntimeException("Not yet implemented")
    override val fields: List<String>
        get() = dex.fields.map { it.name }
    override val methods: List<String>
        get() = dex.methods.map { it.name }
}
