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

import com.android.deploy.asm.Type;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.stream.Stream;

class LiveEditClass {
    // The context this class is defined in.
    private final LiveEditContext context;
    private final Type type;

    private Interpretable bytecode;

    // Whether Live Edit should create proxy objects when creating new instances of this class.
    private boolean isProxyClass;

    private Class<?> superclass;
    private final HashSet<Class<?>> interfaces;
    private final HashSet<Class<?>> supertypes;

    // Whether the static initializer has been interpreted and run for this class. This will only
    // occur for proxy classes.
    private boolean isInitialized;
    private final HashMap<String, Object> staticFields;

    public LiveEditClass(
            LiveEditContext context, Type type, Interpretable bytecode, boolean isProxyClass) {
        this.context = context;
        this.type = type;

        this.interfaces = new HashSet<>();
        this.staticFields = new HashMap<>();
        this.supertypes = new HashSet<>();

        updateBytecode(bytecode, isProxyClass);
    }

    public void updateBytecode(Interpretable bytecode, boolean isProxyClass) {
        if (isProxyClass) {
            try {
                computeProxyTypeInformation(bytecode);
            } catch (Exception e) {
                throw new LiveEditException("Error computing type information for proxy class", e);
            }

            isInitialized = false;
            staticFields.clear();
        }
        this.isProxyClass = isProxyClass;
        this.bytecode = bytecode;
    }

    /**
     * @return the latest bytecode for this class
     */
    public Interpretable getLatestBytecode() {
        return bytecode;
    }

    public boolean isProxyClass() {
        return isProxyClass;
    }

    // Invoke the specified method with the specified receiver and arguments. The method must be
    // declared in the bytecode for this class; methods of superclasses will not be resolved.
    public Object invokeDeclaredMethod(
            String methodName, String methodDesc, Object thisObject, Object[] arguments) {
        MethodBodyEvaluator evaluator =
                new MethodBodyEvaluator(context, bytecode, methodName, methodDesc);
        return evaluator.eval(thisObject, bytecode.getInternalName(), arguments);
    }

    /**
     * Creates an instance of a {@link Proxy} that implements the transitive closure of all
     * interfaces in the inheritance hierarchy of this class. VM-generated proxies cannot extend
     * superclasses, only implement interfaces, so the returned proxy will *not* have the exact same
     * public interface as the original class.
     *
     * <p>For example, if class A extends class B, class A implements interface C, and class B
     * implements interface D, the proxy for class A will implement interfaces C and D
     *
     * <p>For cases where the proxy *needs* to extend the superclass for correct functionality, such
     * as with kotlin lambdas, the proxy interface returned by this method must be replaced with a
     * pre-generated proxy class with the appropriate public interface/typing. This occurs during
     * super-constructor invocation in {@link ProxyClassEval#invokeSpecial}.
     */
    public ProxyClass createProxyInstance() {
        InterpreterLogger.v("New VM proxy interface created for: " + type);
        if (!isProxyClass) {
            throw new LiveEditException(
                    "Cannot create a proxy handler for a non-proxy LiveEdit class");
        }
        ProxyClassHandler handler =
                new ProxyClassHandler(
                        context,
                        type,
                        superclass,
                        interfaces,
                        supertypes,
                        bytecode.getDefaultFieldValues());
        Class<?>[] interfaces =
                Stream.concat(Stream.of(ProxyClass.class), supertypes.stream())
                        .filter(Class::isInterface)
                        .toArray(Class[]::new);
        return (ProxyClass) Proxy.newProxyInstance(context.getClassLoader(), interfaces, handler);
    }

    public synchronized Object getStaticField(String fieldName) {
        ensureClinit();
        return staticFields.get(fieldName);
    }

    public synchronized void setStaticField(String fieldName, Object value) {
        ensureClinit();
        staticFields.put(fieldName, value);
    }

    private synchronized void ensureClinit() {
        if (!isProxyClass) {
            throw new LiveEditException("Cannot invoke <clinit> for non-proxy LiveEdit class");
        }
        if (!isInitialized) {
            isInitialized = true; // Must set this first to prevent infinite loops.
            invokeDeclaredMethod("<clinit>", "()V", null, new Object[0]);
        }
    }

    private void computeProxyTypeInformation(Interpretable bytecode)
            throws ClassNotFoundException, SecurityException {
        interfaces.clear();
        supertypes.clear();

        superclass = classForName(bytecode.getSuperName());
        for (String inter : bytecode.getInterfaces()) {
            interfaces.add(classForName(inter));
        }

        LinkedList<Class<?>> queue = new LinkedList<>();
        queue.add(superclass);
        queue.addAll(interfaces);

        while (!queue.isEmpty()) {
            Class<?> clz = queue.remove();

            supertypes.add(clz);

            Class<?> superclass = clz.getSuperclass();
            if (superclass != null) {
                queue.add(superclass);
            }
            Class<?>[] interfaces = clz.getInterfaces();
            for (Class<?> inter : interfaces) {
                if (!supertypes.contains(inter)) {
                    queue.add(inter);
                }
            }
        }
    }

    private Class<?> classForName(String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'), true, context.getClassLoader());
    }
}
