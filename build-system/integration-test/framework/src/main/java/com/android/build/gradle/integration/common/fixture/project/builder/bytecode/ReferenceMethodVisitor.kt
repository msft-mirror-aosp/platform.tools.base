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

import com.android.build.gradle.internal.instrumentation.ASM_API_VERSION
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor

/**
 * Method visitor to gather references to other types.
 */
class ReferenceMethodVisitor: MethodVisitor(ASM_API_VERSION) {

    internal val references = mutableSetOf<String>()

    override fun visitTypeInsn(opcode: Int, type: String?) {
        type?.let { references += it}
        super.visitTypeInsn(opcode, type)
    }

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
        descriptor?.fromDescriptorToType()?.let {
            references += it
        }
        owner?.let {
            references += it
        }
        super.visitFieldInsn(opcode, owner, name, descriptor)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        owner?.let {
            references += it
        }
        descriptor?.fromSignatureToTypes()?.let {
            references += it
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String?,
        bootstrapMethodHandle: Handle?,
        vararg bootstrapMethodArguments: Any?
    ) {
        descriptor?.fromSignatureToTypes()?.let {
            references += it
        }

        super.visitInvokeDynamicInsn(
            name,
            descriptor,
            bootstrapMethodHandle,
            *bootstrapMethodArguments
        )
    }
}
