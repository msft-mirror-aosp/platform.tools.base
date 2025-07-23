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
package com.android.tools.deploy.liveedit;

import com.android.deploy.asm.Opcodes;
import com.android.deploy.asm.Type;
import com.android.deploy.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

public class BytecodeValidator {

    private static final Set<String> lambdaSuperclasses = new HashSet<>();

    // Currently, we only want to check for compatibility for generated lambda classes; we are
    // guarding against changes from Function<N> to Function<K> and changes between lambda
    // superclasses. Keeping the scope of this small to start with is intended to let us ensure
    // versioning works as intended.
    static {
        lambdaSuperclasses.add("kotlin/jvm/internal/Lambda");
        lambdaSuperclasses.add("kotlin/coroutines/jvm/internal/SuspendLambda");
        lambdaSuperclasses.add("kotlin/coroutines/jvm/internal/RestrictedSuspendLambda");
    }

    static boolean checkCompatibleUpdate(Interpretable current, Interpretable next) {
        if (!lambdaSuperclasses.contains(current.getSuperName())
                && !lambdaSuperclasses.contains(next.getSuperName())) {
            return true;
        }

        // Validating that every public non-static method exists in both bytecodes ensures that
        // the new bytecode has an implementation for any method that may be called on the bytecode
        // owner (in this case, a proxy object).
        for (MethodNode method : current.getMethods()) {
            boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
            boolean isPublic = (method.access & Opcodes.ACC_PUBLIC) != 0;
            if (!isStatic && isPublic && next.getMethod(method.name, method.desc) == null) {
                return false;
            }
        }

        return true;
    }

    static boolean checkCompatibleUpdate(Class<?> current, Interpretable next) {
        if (!lambdaSuperclasses.contains(Type.getInternalName(current.getSuperclass()))
                && !lambdaSuperclasses.contains(next.getSuperName())) {
            return true;
        }

        // Validating that every public non-static method exists in both bytecodes ensures that
        // the new bytecode has an implementation for any method that may be called on the bytecode
        // owner (in this case, an instrumented object instance).
        for (Method method : current.getDeclaredMethods()) {
            boolean isStatic = (method.getModifiers() & Modifier.STATIC) != 0;
            boolean isPublic = (method.getModifiers() & Modifier.PUBLIC) != 0;
            if (!isStatic
                    && isPublic
                    && next.getMethod(method.getName(), Type.getMethodDescriptor(method)) == null) {
                return false;
            }
        }

        return true;
    }
}
