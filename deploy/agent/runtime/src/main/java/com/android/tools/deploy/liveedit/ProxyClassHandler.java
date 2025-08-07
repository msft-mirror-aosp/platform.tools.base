/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.deploy.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Handler class bound to an instance of a proxy class, responsible for resolving field access and
// method invocations. Holds the instance fields of the proxy object.
//
// A proxy is an object's interface; the bound handler is the implementation.
//
// Must be public; accessed cross-classloader from LiveEditSuspendLambda and
// LiveEditRestrictedSuspendLambda.
public final class ProxyClassHandler implements InvocationHandler {
    private final LiveEditContext context;
    private final VersionedBytecode bytecode;

    private final Type type;
    private final Class<?> superclass;
    private final Set<Class<?>> interfaces;
    private final Set<Type> supertypes;
    private final HashMap<String, Object> fields;

    // To handle access to methods and fields of proxy superclasses, an instance of the superclass
    // is instantiated when the super-constructor is called.
    private Object superInstance;

    ProxyClassHandler(
            LiveEditContext context,
            Type type,
            Class<?> superclass,
            Set<Class<?>> interfaces,
            Set<Class<?>> supertypes,
            Map<String, Object> defaultFieldValues) {
        this.context = context;
        this.type = type;
        this.superclass = superclass;

        this.interfaces = new HashSet<>();
        this.interfaces.addAll(interfaces);

        this.supertypes = supertypes.stream().map(Type::getType).collect(Collectors.toSet());

        this.fields = new HashMap<>(defaultFieldValues);
        this.fields.putAll(defaultFieldValues);

        this.bytecode = new VersionedBytecode(context.getClass(type.getInternalName()));
    }

    Map<String, Object> getSourceLocationInfo() {
        ProxySourceLocation location = ProxySourceLocation.findSourceLocation(bytecode.get());
        if (location != null) {
            return location.asMap();
        }
        return new HashMap<>();
    }

    Object getSuperInstance() {
        return superInstance;
    }

    void superInit(Class<?>[] types, Object[] args) {
        try {
            Constructor<?> constructor;
            Class<?> proxyType = context.getProxyType(superclass, interfaces);

            // Create a specialized proxy supertype, if one exists for this combination of parent
            // classes and interfaces.
            if (proxyType != null) {
                constructor = proxyType.getConstructor(types);
            } else {
                constructor = superclass.getConstructor(types);
            }
            constructor.setAccessible(true);
            superInstance = constructor.newInstance(args);
        } catch (Exception e) {
            throw new LiveEditException("Error instantiating superclass: " + type, e);
        }
    }

    void setField(String name, Object value) {
        if (fields.containsKey(name)) {
            fields.put(name, value);
            return;
        }
        try {
            Field superField = superInstance.getClass().getField(name);
            superField.set(superInstance, value);
        } catch (Exception e) {
            throw new LiveEditException("Could not access field", e);
        }
    }

    Object getField(String name) {
        if (fields.containsKey(name)) {
            return fields.get(name);
        }
        try {
            Field superField = superInstance.getClass().getField(name);
            return superField.get(superInstance);
        } catch (Exception e) {
            throw new LiveEditException("Could not access field", e);
        }
    }

    /**
     * This method links generated proxy interfaces to the backing bytecode in LiveEdit.
     *
     * <p>It is called to determine if a given method has an implementation defined in the class
     * bytes in Live Edit, or if the execution should fall back to the superclass implementation.
     *
     * <p>This method is only called by a proxy if this handler is bound to a Live Edit generated
     * proxy interface (see Proxies.java)
     */
    public boolean implementsMethod(String name, String desc) {
        return bytecode.get().getMethod(name, desc) != null;
    }

    /**
     * This method links generated proxy interfaces to the class bytecode in LiveEdit.
     *
     * <p>It is called to execute the bytecode for the given method when implementsMethod() returns
     * true for a given method name and descriptor.
     *
     * <p>This method is only called by a proxy if this handler is bound to a Live Edit generated
     * proxy interface (see Proxies.java)
     */
    public Object invokeMethod(Object instance, String name, String desc, Object[] args) {
        Interpretable bytecode = this.bytecode.get();
        MethodBodyEvaluator evaluator = new MethodBodyEvaluator(context, bytecode, name, desc);
        return evaluator.eval(instance, bytecode.getInternalName(), args);
    }

    boolean isInstanceOf(Type type) {
        if (type == null) {
            return false;
        }
        return type.equals(this.type) || supertypes.contains(type);
    }

    /**
     * This method links VM proxies (those generated by {@link java.lang.reflect.Proxy}) to the
     * class bytecode in LiveEdit.
     *
     * <p>This method is only called by the proxy if this handler is bound to a VM generated proxy.
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Use this handler as the source for Object based methods, since using the proxy object
        // itself results in an infinite loop of this method being called.
        if (method.getDeclaringClass().equals(Object.class)) {
            return method.invoke(this, args);
        }

        if (method.getDeclaringClass() == SourceLocationAware.class
                && method.getName().equals("getSourceLocationInfo")) {
            return getSourceLocationInfo();
        }

        if (method.getDeclaringClass() == ProxyClass.class) {
            return this;
        }

        String name = method.getName();
        String desc = Type.getMethodDescriptor(method);
        if (implementsMethod(name, desc)) {
            // It's safe to pass the proxy object here, since it's serving as a 'this' pointer, not
            // having the specified method reflectively invoked on it.
            return invokeMethod(proxy, name, desc, args);
        }

        // Try to invoke the method on the super instance
        try {
            return method.invoke(superInstance, args);
        } catch (Exception e) {
            throw new LiveEditException("Could not access method", e);
        }
    }

    // Wrapper class to ensure that the class bytecode always is checked for updates before use.
    private static final class VersionedBytecode {
        private final LiveEditClass clazz;
        private Interpretable bytecode;

        VersionedBytecode(LiveEditClass clazz) {
            this.clazz = clazz;
            this.bytecode = clazz.getLatestBytecode();
        }

        Interpretable get() {
            Interpretable latest = clazz.getLatestBytecode();
            if (latest != bytecode && BytecodeValidator.checkCompatibleUpdate(bytecode, latest)) {
                bytecode = latest;
            }
            return bytecode;
        }
    }
}
