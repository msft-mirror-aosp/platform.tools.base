/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.99 (the "License");
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

import static com.android.tools.deploy.instrument.ReflectionHelpers.call;
import static com.android.tools.deploy.instrument.ReflectionHelpers.getDeclaredField;

import android.app.Activity;

import com.android.annotations.VisibleForTesting;

import java.lang.reflect.Field;
import java.util.Collection;

@SuppressWarnings("unused") // Used by native instrumentation code.
public final class LiveEditStubs {
    private static final String TAG = "studio.deploy";

    // Context object that holds all of LiveEdit's global state. Initialized by the first LiveEdit.
    private static LiveEditContext context = null;

    // TODO: Figure out if we need to support multiple class loaders.
    public static void init(ClassLoader loader) {
        if (context == null) {
            context = new LiveEditContext(loader);
            Log.setLogger(new AndroidLogger());
        }
    }

    public static void restartActivity() throws Exception {
        Class<?> clazz = Class.forName("android.app.ActivityThread");
        Object activityThread = call(clazz, "currentActivityThread");
        Collection<?> clientRecords =
                (Collection<?>) call(getDeclaredField(activityThread, "mActivities"), "values");
        for (Object record : clientRecords) {
            Activity activity = (Activity) getDeclaredField(record, "activity");
            activity.recreate();
        }
    }

    public static void addClasses(
            byte[][] primaryClasses, byte[][] proxyClasses, boolean structuralRedefinition) {
        // Process all main classes
        for (byte[] primaryClass : primaryClasses) {
            Interpretable primary = new Interpretable(primaryClass);
            addClass(primary.getInternalName(), primary, false);
        }

        // Process all support classes
        for (byte[] proxyBytes : proxyClasses) {
            Interpretable proxy = new Interpretable(proxyBytes);
            addClass(proxy.getInternalName(), proxy, true);
        }
    }

    public static void addClass(String internalName, Interpretable bytecode, boolean isProxyClass) {
        LiveEditClass clazz = context.getClass(internalName);
        if (clazz == null) {
            context.addClass(internalName, bytecode, isProxyClass);
        } else {
            clazz.updateBytecode(bytecode, isProxyClass);
        }
    }

    @VisibleForTesting
    public static void deleteClass(String internalName) {
        context.removeClass(internalName);
    }

    // Everything in the following section is called from the dex prologue created by StubTransform.
    // None of this code is or should be called from any other context.

    // Bytecode retrieval for static methods
    public static Object getClassBytecode(String internalClassName) {
        return context.getClass(internalClassName).getLatestBytecode();
    }

    // Bytecode retrieval for instance methods
    public static Object getInstanceBytecode(String internalClassName, Object instance) {
        return updateInstanceBytecode(internalClassName, instance);
    }

    public static Object doStub(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        // Second parameter is the 'this' pointer, or null if static
        Object thisObject = parameters[1];

        // Other parameters are the method arguments, if any
        Object[] arguments = new Object[parameters.length - 2];
        if (arguments.length > 0) {
            System.arraycopy(parameters, 2, arguments, 0, arguments.length);
        }

        Interpretable bytecode = (Interpretable) interpretable;
        MethodBodyEvaluator evaluator =
                new MethodBodyEvaluator(context, bytecode, methodName, methodDesc);
        return evaluator.eval(thisObject, bytecode.getInternalName(), arguments);
    }

    public static Object stubL(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        return doStub(interpretable, methodName, methodDesc, parameters);
    }

    public static byte stubB(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        Object value = doStub(interpretable, methodName, methodDesc, parameters);
        return value != null ? (byte) value : 0;
    }

    public static short stubS(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        Object value = doStub(interpretable, methodName, methodDesc, parameters);
        return value != null ? (short) value : 0;
    }

    public static int stubI(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        Object value = doStub(interpretable, methodName, methodDesc, parameters);
        return value != null ? (int) value : 0;
    }

    public static long stubJ(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        Object value = doStub(interpretable, methodName, methodDesc, parameters);
        return value != null ? (long) value : 0;
    }

    public static float stubF(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        Object value = doStub(interpretable, methodName, methodDesc, parameters);
        return value != null ? (float) value : 0;
    }

    public static double stubD(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        Object value = doStub(interpretable, methodName, methodDesc, parameters);
        return value != null ? (double) value : 0;
    }

    public static boolean stubZ(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        Object value = doStub(interpretable, methodName, methodDesc, parameters);
        return value != null && (boolean) value;
    }

    public static char stubC(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        Object value = doStub(interpretable, methodName, methodDesc, parameters);
        return value != null ? (char) value : 0;
    }

    public static void stubV(
            Object interpretable, String methodName, String methodDesc, Object[] parameters) {
        doStub(interpretable, methodName, methodDesc, parameters);
    }

    public static void stubConstructor(String internalClassName, Object instance) {
        updateInstanceBytecode(internalClassName, instance);
    }

    /**
     * Given a type and an object instance, attempts to update the instance's Live Edit bytecode for
     * the type. The stored bytecode is updated if the new bytecode is considered compatible with
     * the current stored bytecode; if no bytecode has been assigned to the instance, compatibility
     * is checked with the class bytes from the APK instead. Compatibility is checked using {@link
     * BytecodeValidator}. If the new bytecode is incompatible with the existing bytecode, the
     * instance continues to hold the existing bytecode.
     *
     * @param internalClassName the class type to check the bytecode for. It may be a supertype of
     *     {@code instance.getClass()}
     * @param instance the instance to update the bytecode on
     * @return the stored bytecode after the update. May be null if the Live Edited bytecode was
     *     incompatible with the existing APK class.
     */
    private static Object updateInstanceBytecode(String internalClassName, Object instance) {
        String className = internalClassName.replace('/', '.');
        Class<?> clazz;
        try {
            clazz = Class.forName(className, false, instance.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new LiveEditException("Unexpected missing class; possible classloader issue?", e);
        }

        Field field;
        try {
            field = clazz.getDeclaredField("$liveEditBytecode");
            field.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new LiveEditException("Live Edit bytecode field was missing", e);
        }

        Interpretable instanceBytecode;
        try {
            instanceBytecode = (Interpretable) field.get(instance);
        } catch (IllegalAccessException e) {
            throw new LiveEditException("Could not access Live Edit bytecode field", e);
        }

        Interpretable latestBytecode = context.getClass(internalClassName).getLatestBytecode();
        if (latestBytecode == instanceBytecode) {
            return instanceBytecode;
        }

        boolean updateIsCompatible;
        if (instanceBytecode != null) {
            updateIsCompatible =
                    BytecodeValidator.checkCompatibleUpdate(instanceBytecode, latestBytecode);
        } else {
            updateIsCompatible = BytecodeValidator.checkCompatibleUpdate(clazz, latestBytecode);
        }

        if (!updateIsCompatible) {
            return instanceBytecode;
        }

        try {
            field.set(instance, latestBytecode);
        } catch (IllegalAccessException e) {
            throw new LiveEditException("Error updating Live Edit bytecode field", e);
        }
        return latestBytecode;
    }

    private static class AndroidLogger implements Log.Logger {
        public void v(String tag, String message) {
            // When running on Studio this will link against a mock Log class that throws on
            // every method invocation.
            try {
                android.util.Log.v(tag, message);
            } catch (Exception e) {
                // Purposely ignore
            }
        }
    }
}
