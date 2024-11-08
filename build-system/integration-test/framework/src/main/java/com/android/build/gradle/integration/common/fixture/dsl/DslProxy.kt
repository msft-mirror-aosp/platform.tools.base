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

package com.android.build.gradle.integration.common.fixture.dsl

import org.gradle.internal.extensions.stdlib.toDefaultLowerCase
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * a Proxy class over all the Android DSL interfaces.
 *
 * The proxy records all calls to the class and stores them (in [DslContentHolder]) so that they
 * can be rewritten in build files, with a choice of format (groovy, kts, declarative).
 */
class DslProxy private constructor(
    private val theInterface: Class<*>,
    private val contentHolder: DslContentHolder,
    /** whether the proxy represents one of the root extension (CommonExtension and its extensions */
    private val rootExtensionProxy: Boolean = false,
): InvocationHandler {

    companion object {

        /**
         * Creates a Java proxy for type `theClass`
         */
        fun <T> createProxy(
            theClass: Class<T>,
            contentHolder: DslContentHolder,
            rootExtensionProxy: Boolean = false
        ): T {
            @Suppress("UNCHECKED_CAST")
            return Proxy.newProxyInstance(
                DslProxy::class.java.classLoader,
                arrayOf(theClass),
                DslProxy(theClass, contentHolder, rootExtensionProxy)
            ) as T
        }
    }

    /**
     * Special handling for the namespace property. Because we need the value to finish creating
     * projects (to set the value in the manifest and/or to generate java code in the right
     * folder), we need to not just record calls setting the values, but we need to keep
     * track of the current value, and allow doing a get (which we do not allow for other values).
     */
    private var namespace: String? = null

    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?
    ): Any? {
        if (args != null) {
            // is it a setter?
            if (checkSetter(method, args)) {
                return null
            }

            // or an action block to configure a nested object?
            if (checkNestedBlock(method, args)) {
                return null
            }

        } else {
            // this may be a getter
            val r =  checkGetter(method)
            if (r.validResult) return r.value
        }

        throw Error("$method not supported")
    }

    private fun checkSetter(method: Method, args: Array<out Any?>): Boolean {
        if (method.parameters.size != 1) return false

        val regex = Regex("^set([A-Za-z0-9]+)$")
        val matcher = regex.matchEntire(method.name) ?: return false
        val matchedName = matcher.groups[1]?.value ?: throw Error("Expected prop name but null")

        val param = method.parameters.first()
        val propName = (if (param.type == Boolean::class.java) {
            // we have to check whether the prop is
            //    foo: Boolean
            // or
            //    isFoo: Boolean
            // So we search for the getter
            theInterface.methods.firstOrNull { it.name == "is$matchedName" }?.name
        } else null) ?: matchedName.replaceFirstChar { c -> c.lowercaseChar() }

        val value = args.first()

        // nullable primitive types are showing up as java types, not Kotlin types, so need to check
        // for both
        when (param.type) {
            java.lang.Integer::class.java,
            Int::class.java,
            java.lang.Boolean::class.java,
            Boolean::class.java -> contentHolder.set(propName, value)
            String::class.java -> {
                contentHolder.set(propName, value)
                if (rootExtensionProxy && propName == "namespace") {
                    namespace = value as String?
                }
            }

            else -> throw IllegalArgumentException("Does not support type ${param.type} for method ${method.name}")
        }

        return true
    }

    data class GetterResult(
        val validResult: Boolean,
        val value: Any?
    )

    private val notAGetter = GetterResult(false, null)

    private fun checkGetter(method: Method): GetterResult {
        if (method.parameters.isNotEmpty()) return notAGetter

        val regex = Regex("^get([A-Za-z0-9]+)$")
        val matcher = regex.matchEntire(method.name) ?: return notAGetter
        val propName =
            matcher.groups[1]?.value?.replaceFirstChar { c -> c.lowercaseChar() }
                ?: throw Error("Expected prop name but null")

        val returnValue = when (method.returnType) {
            MutableList::class.java -> contentHolder.getList(propName)
            MutableSet::class.java -> contentHolder.getSet(propName)
            java.lang.String::class.java -> {
                if (rootExtensionProxy && propName == "namespace") {
                    return GetterResult(true, namespace)
                }
                throw Error("Unsupported getter type ${method.returnType} for method ${method.name}")
            }
            else -> {
                // we may get here if the type is a nested block.
                if (method.returnType.name.startsWith("com.android.build.api")) {
                    // FIXME should we check whether there is a matching action method?
                    try {
                        val theInterface = javaClass.classLoader.loadClass(method.returnType.name)
                        contentHolder.chainedProxy(propName, theInterface)
                    } catch (e: ClassNotFoundException) {
                        throw RuntimeException(
                            "Failed to load ${method.returnType.name} for ${theInterface.name}.$propName",
                            e
                        )
                    }
                } else {
                    throw Error("Unsupported getter type ${method.returnType} for method ${method.name}")
                }
            }
        }

        return GetterResult(true, returnValue)
    }

    private fun checkNestedBlock(method: Method, args: Array<out Any?>): Boolean {
        if (args.size != 1) return false
        val type = method.parameters[0]

        if (type.type != Function1::class.java) {
            return false
        }

        val parameterizedType = type.parameterizedType as ParameterizedType
        val typeArguments = parameterizedType.actualTypeArguments
        val blockType = typeArguments[0] as WildcardType
        val blockTypeValue = blockType.lowerBounds[0]

        // if this is of type TypeVariable that means that the type of the function is a type
        // param in the class that defines the method.
        // we need to look at the proxied class to know what type is used for that given type
        // parameter.
        val blockTypeClass = if (blockTypeValue is TypeVariable<*>) {
            // search in the class that defined the method for the index of the type param for
            // the type used in the function.
            val ownerClass = method.declaringClass
            val index = findTypeParameterIndex(ownerClass, blockTypeValue.name)

            // get the same info on the proxied interface to get the final type, and find the
            // type from the same index.
            val resolvedType = getTypeParameterByIndex(ownerClass.typeName, index)

            ownerClass.classLoader.loadClass(resolvedType.typeName)
        } else {
            method.declaringClass.classLoader.loadClass(blockTypeValue.typeName)
        }

        // run the nested block
        contentHolder.runNestedBlock(method.name, blockTypeClass) {
            @Suppress("UNCHECKED_CAST")
            (args[0] as Function1<Any,*>).invoke(this)
        }

        return true
    }

    private fun findTypeParameterIndex(theClass: Class<*>, typeName: String): Int {
        val ownerGenericTypeParams = theClass.typeParameters

        var i = 0
        ownerGenericTypeParams.forEach { variable ->
            if (variable.name == typeName) {
                return i
            }
            i++
        }

        throw Error("Could not find type parameters $typeName on class $theClass")
    }

    private fun getTypeParameterByIndex(originalClass: String, index: Int): Type {
        val genericInfo = theInterface.genericInterfaces
        for (type in genericInfo) {
            if (type is ParameterizedType) {
                val owner = type.rawType
                if (owner.typeName == originalClass) {
                    return type.actualTypeArguments[index]
                }
            }
        }

        throw Error("Unable to find Type info in ${theInterface.typeName} matching type parameter definition from $originalClass")
    }
}
