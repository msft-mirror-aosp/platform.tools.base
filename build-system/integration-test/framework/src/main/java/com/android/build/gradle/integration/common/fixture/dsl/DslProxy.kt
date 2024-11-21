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

import com.android.build.api.dsl.ApplicationProductFlavor
import com.android.build.api.dsl.BuildType
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.DynamicFeatureProductFlavor
import com.android.build.api.dsl.LibraryProductFlavor
import com.android.build.api.dsl.PrivacySandboxSdkExtension
import com.android.build.api.dsl.ProductFlavor
import com.android.build.api.dsl.TestProductFlavor
import org.gradle.api.provider.Property
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
): InvocationHandler {

    private val rootExtensionProxy =
        CommonExtension::class.java.isAssignableFrom(theInterface) ||
                PrivacySandboxSdkExtension::class.java.isAssignableFrom(theInterface)

    companion object {

        /**
         * Creates a Java proxy for type `theClass`
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> createProxy(
            theClass: Class<T>,
            contentHolder: DslContentHolder,
        ): T {
            return when (theClass) {
                ApplicationProductFlavor::class.java -> ApplicationProductFlavorProxy(contentHolder) as T
                LibraryProductFlavor::class.java -> LibraryProductFlavorProxy(contentHolder) as T
                DynamicFeatureProductFlavor::class.java -> DynamicFeatureProductFlavorProxy(contentHolder) as T
                TestProductFlavor::class.java -> TestProductFlavorProxy(contentHolder) as T
                else -> Proxy.newProxyInstance(
                    DslProxy::class.java.classLoader,
                    arrayOf(theClass),
                    DslProxy(theClass, contentHolder)
                ) as T
            }
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

        // if we land here, it means it's just a method call. let's record it
        if (handleMethodCall(method, args)) return null

        throw Error("$method not supported")
    }

    private fun checkSetter(method: Method, args: Array<out Any?>): Boolean {
        if (method.parameters.size != 1) return false

        val regex = Regex("^set([A-Za-z0-9]+)$")
        val matcher = regex.matchEntire(method.name) ?: return false
        val matchedName = matcher.groups[1]?.value ?: throw Error("Expected prop name but null")

        val propName = matchedName.replaceFirstChar { c -> c.lowercaseChar() }

        val param = method.parameters.first()
        val isNotation = if (param.type == Boolean::class.java) {
            // we have to check whether the prop is
            //    foo: Boolean
            // or
            //    isFoo: Boolean
            // So we search for the getter
            theInterface.methods.any { it.name == "is$matchedName" }
        } else false

        val value = args.first()

        // nullable primitive types are showing up as java types, not Kotlin types, so need to check
        // for both
        when (param.type) {
            java.lang.Integer::class.java, Int::class.java -> {
                contentHolder.set(propName, value)
            }
            java.lang.Boolean::class.java, Boolean::class.java -> {
                contentHolder.setBoolean(propName, value, isNotation)
            }
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
            MutableMap::class.java -> contentHolder.getMap(propName)
            Property::class.java -> contentHolder.getProperty(propName)
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
                        val returnType = method.genericReturnType
                        val actualReturnType = if (returnType is TypeVariable<*>) {
                            // search in the class that defined the method for the index of the type param for
                            // the type used in the function.
                            val ownerClass = method.declaringClass
                            val index = findTypeParameterIndex(ownerClass, returnType.name)

                            // get the same info on the proxied interface to get the final type, and find the
                            // type from the same index.
                            val resolvedType = getTypeParameterByIndex(ownerClass.typeName, index)

                            resolvedType.typeName
                        } else {
                            method.returnType.name
                        }

                        contentHolder.chainedProxy(
                            propName,
                            javaClass.classLoader.loadClass(actualReturnType)
                        )
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
        } else if (blockTypeValue is ParameterizedType) {
            // handle the container case. In this case, we need not the type of the container
            // but the type that the container handles.
            if (blockTypeValue.typeName.startsWith("org.gradle.api.NamedDomainObjectContainer<")) {
                // there should be a single type param for a container.
                val containerTypeParam = blockTypeValue.actualTypeArguments.first()

                // right now our type params in container are type variables. This
                // may change in the future.
                if (containerTypeParam is TypeVariable<*>) {
                    // search in the class that defined the method for the index of the type param for
                    // the type used in the function.
                    val ownerClass = method.declaringClass
                    val index = findTypeParameterIndex(ownerClass, containerTypeParam.name)

                    // get the same info on the proxied interface to get the final type, and find the
                    // type from the same index.
                    val resolvedType = getTypeParameterByIndex(ownerClass.typeName, index)

                    ownerClass.classLoader.loadClass(resolvedType.typeName)
                } else {
                    method.declaringClass.classLoader.loadClass(containerTypeParam.typeName)
                }
            } else {
                throw RuntimeException("Unsupported ParameterizedType in block function")
            }
        } else {
            method.declaringClass.classLoader.loadClass(blockTypeValue.typeName)
        }

        // Now that we have resolved all the types, we can call into the nested block.
        // Build Type and Product Flavor handle their block differently as the
        // inner type is a specific container that has to create the build type or flavor
        // themselves, so there's 2 layers inside this method really.
        when (method.name) {
            "buildTypes" -> {
                // content holder has a special API for build type container.
                // The provided type is not the type of the container but the type handled
                // by the container.
                @Suppress("UNCHECKED_CAST")
                contentHolder.buildTypes(blockTypeClass as Class<BuildType>) {
                    // calls into the function configuring the container.
                    // `this` here is the nested block (container)
                    @Suppress("UNCHECKED_CAST")
                    (args[0] as Function1<Any,*>).invoke(this)
                }
            }
            "productFlavors" -> {
                // content holder has a special API for flavor container.
                // The provided type is not the type of the container but the type handled
                // by the container.
                @Suppress("UNCHECKED_CAST")
                contentHolder.productFlavors(blockTypeClass as Class<ProductFlavor>) {
                    // calls into the function configuring the container.
                    // `this` here is the nested block (container)
                    @Suppress("UNCHECKED_CAST")
                    (args[0] as Function1<Any,*>).invoke(this)
                }
            }
            else -> {
                // Normal nested block. the provided type is the direct nested block type.
                contentHolder.runNestedBlock(method.name, listOf(), blockTypeClass) {
                    // calls into the function configuring the nested block
                    // `this` here is the nested block
                    @Suppress("UNCHECKED_CAST")
                    (args[0] as Function1<Any,*>).invoke(this)
                }
            }
        }


        return true
    }

    private fun handleMethodCall(method: Method, args: Array<out Any?>?): Boolean {
        contentHolder.call(method.name, args?.toList() ?: listOf(), method.isVarArgs)
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
