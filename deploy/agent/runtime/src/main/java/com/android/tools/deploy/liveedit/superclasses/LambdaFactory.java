/*
 * Copyright (C) 2022 The Android Open Source Project
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

import kotlin.coroutines.Continuation;
import kotlin.reflect.KDeclarationContainer;

public final class LambdaFactory {

    public static Object create(String superInternalName, Object[] args, Object proxy) {
        switch (superInternalName) {
            case "kotlin/jvm/internal/Lambda":
                return makeLambda(args);
            case "kotlin/jvm/internal/FunctionReferenceImpl":
                // Base class for function references (MyClass::method)
                return makeFunctionReferenceImpl(args);
            case "kotlin/jvm/internal/AdaptedFunctionReference":
                // Base class for function references where function type doesn't match the expected
                // signature; i.e., passing a () -> String as a () -> Unit
                return makeAdaptedFunctionReference(args);
            case "kotlin/coroutines/jvm/internal/SuspendLambda":
                return makeSuspendLambda(args, proxy);
            case "kotlin/coroutines/jvm/internal/RestrictedSuspendLambda":
                return makeRestrictedSuspendLambda(args, proxy);
            case "java/lang/Object":
                return null;
            default:
                throw new IllegalArgumentException("Unhandled superclass: " + superInternalName);
        }
    }

    private static Object makeLambda(Object[] args) {
        return new LiveEditLambda((int) args[0]);
    }

    private static Object makeFunctionReferenceImpl(Object[] args) {
        if (args.length == 4) {
            return new LiveEditFunctionReferenceImpl(
                    (int) args[0],
                    (KDeclarationContainer) args[1],
                    (String) args[2],
                    (String) args[3]);
        }

        if (args.length == 5) {
            return new LiveEditFunctionReferenceImpl(
                    (int) args[0],
                    (Class) args[1],
                    (String) args[2],
                    (String) args[3],
                    (int) args[4]);
        }

        if (args.length == 6) {
            return new LiveEditFunctionReferenceImpl(
                    (int) args[0],
                    args[1],
                    (Class) args[2],
                    (String) args[3],
                    (String) args[4],
                    (int) args[5]);
        }

        throw new IllegalArgumentException("Unhandled FunctionReferenceImpl constructor");
    }

    private static Object makeAdaptedFunctionReference(Object[] args) {
        if (args.length == 5) {
            return new LiveEditAdaptedFunctionReference(
                    (int) args[0],
                    (Class) args[1],
                    (String) args[2],
                    (String) args[3],
                    (int) args[4]);
        }

        if (args.length == 6) {
            return new LiveEditAdaptedFunctionReference(
                    (int) args[0],
                    args[1],
                    (Class) args[2],
                    (String) args[3],
                    (String) args[4],
                    (int) args[5]);
        }

        throw new IllegalArgumentException("Unhandled AdaptedFunctionReference constructor");
    }

    private static Object makeSuspendLambda(Object[] args, Object proxy) {
        return new LiveEditSuspendLambda((int) args[0], (Continuation) args[1], proxy);
    }

    private static Object makeRestrictedSuspendLambda(Object[] args, Object proxy) {
        return new LiveEditRestrictedSuspendLambda((int) args[0], (Continuation) args[1], proxy);
    }
}
