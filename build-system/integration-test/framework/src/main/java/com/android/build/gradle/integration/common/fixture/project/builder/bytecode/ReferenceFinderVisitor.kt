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

package com.android.build.gradle.integration.common.fixture.project.builder.bytecode

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ASM9

/**
 * Class visitor to gather references to other types.
 */
class ReferenceFinderVisitor: ClassVisitor(ASM9) {

    val methods = mutableListOf<ReferenceMethodVisitor>()
    internal val references = mutableSetOf<String>()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String,
        interfaces: Array<out String>
    ) {
        references.addAll(interfaces)
        references += superName

        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        super.visitMethod(access, name, descriptor, signature, exceptions)

        descriptor?.fromSignatureToTypes()?.let {
            references += it
        }

        return ReferenceMethodVisitor().also { methods.add(it) }
    }


    override fun visitEnd() {
        // read the results from all the methods.
        for (method in methods) {
            references += method.references
        }

        // then filter the stuff we need. It's easier to accept specifically types
        // in  com.android.build.gradle.integration.* than rejecting other types.
        references.removeIf {
            !it.startsWith("com/android/build/gradle/integration/")
        }

        super.visitEnd()
    }
}
