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
import com.android.build.api.dsl.ExecutionProfile
import com.android.build.api.dsl.LibraryProductFlavor
import com.android.build.api.dsl.PrivacySandboxSdkExtension
import com.android.build.api.dsl.ProductFlavor
import com.android.build.api.dsl.TestProductFlavor
import org.gradle.api.JavaVersion
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.File
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
        val returnValue = handleMethodCall(method, args)
        if (returnValue.validResult) return returnValue.value

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
            Integer::class.java,
            Int::class.java,
            File::class.java,
            JavaVersion::class.java -> {
                contentHolder.set(propName, value)
            }
            java.lang.Boolean::class.java, Boolean::class.java -> {
                contentHolder.setBoolean(propName, value, isNotation)
            }
            // custom implementation for String in order to intercept set/get to namespace
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

    data class MethodReturn(
        val validResult: Boolean,
        val value: Any?
    )

    private val notAGetter = MethodReturn(false, null)

    private fun checkGetter(method: Method): MethodReturn {
        if (method.parameters.isNotEmpty()) return notAGetter

        val regex = Regex("^get([A-Za-z0-9]+)$")
        val matcher = regex.matchEntire(method.name) ?: return notAGetter
        val propName =
            matcher.groups[1]?.value?.replaceFirstChar { c -> c.lowercaseChar() }
                ?: throw Error("Expected prop name but null")

        val returnValue = when (method.returnType) {
            // some custom proxies in order to ensure they are not called in the wrong way
            // (like querying for the collection content, or calling get on providers)
            MutableList::class.java,
            MutableCollection::class.java -> contentHolder.getList(propName)
            MutableSet::class.java -> contentHolder.getSet(propName)
            MutableMap::class.java -> contentHolder.getMap(propName)
            Property::class.java -> contentHolder.getProperty(propName)
            ListProperty::class.java -> contentHolder.getListProperty(propName)
            // custom implementation for String in order to intercept set/get to namespace
            java.lang.String::class.java -> {
                if (rootExtensionProxy && propName == "namespace") {
                    return MethodReturn(true, namespace)
                }
                throw Error("Unsupported getter type ${method.returnType} for method ${method.name}")
            }
            // allow-listed Gradle, JetBrains and other types that we want to support.
            // Returned as chained proxies
            SourceDirectorySet::class.java,
            KotlinJvmCompilerOptions::class.java -> method.getChainedProxyForReturn(propName)
            // the rest
            else -> {
                // AGP API objects. These are generally objects that also have a matching configuration
                // method, so we return the objects as chained proxies
                if (method.returnType.name.startsWith("com.android.build.api")) {
                    // FIXME should we check whether there is a matching action method?
                    method.getChainedProxyForReturn(propName)
                } else {
                    // and the real rest throws.
                    throw Error("Unsupported getter type ${method.returnType} for method ${method.name} -- Add support as needed")
                }
            }
        }

        return MethodReturn(true, returnValue)
    }

    fun Method.getChainedProxyForReturn(propName: String): Any = try {
        val genericType = genericReturnType
        val actualReturnClass = if (genericType is TypeVariable<*>) {
            // search in the class that defined the method for the index of the type param for
            // the type used in the function.
            val ownerClass = declaringClass
            val index = findTypeParameterIndex(ownerClass, genericType.name)

            // get the same info on the proxied interface to get the final type, and find the
            // type from the same index.
            getTypeParameterByIndex(ownerClass.typeName, index)
        } else {
            returnType
        }

        contentHolder.chainedProxy(propName, actualReturnClass)
    } catch (e: ClassNotFoundException) {
        throw RuntimeException(
            "Failed to load ${returnType.name} for ${theInterface.name}.$propName",
            e
        )
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
                throw RuntimeException("Unsupported ParameterizedType in block function for '${method.name}' - Add support as needed.")
            }
        } else {
            method.declaringClass.classLoader.loadClass(blockTypeValue.typeName)
        }

        // Now that we have resolved all the types, we can call into the nested block.
        // Container blocks are handled on a case by case basis as the main action creates
        // objects based on the type parameter on the container.
        // Because of this we need custom support for each new container
        if (blockTypeValue.typeName.startsWith("org.gradle.api.NamedDomainObjectContainer<")) {
            when (blockTypeClass.name) {
                "com.android.build.api.dsl.ApplicationBuildType",
                "com.android.build.api.dsl.LibraryBuildType",
                "com.android.build.api.dsl.DynamicFeatureBuildType",
                "com.android.build.api.dsl.TestBuildType" -> {
                    // content holder has a special API for build type container.
                    // The provided type is not the type of the container but the type handled
                    // by the container.
                    @Suppress("UNCHECKED_CAST")
                    contentHolder.buildTypes(blockTypeClass as Class<BuildType>) {
                        // calls into the function configuring the container.
                        // `this` here is the nested block (container)
                        @Suppress("UNCHECKED_CAST")
                        (args[0] as Function1<Any, *>).invoke(this)
                    }
                }

                "com.android.build.api.dsl.ApplicationProductFlavor",
                "com.android.build.api.dsl.LibraryProductFlavor",
                "com.android.build.api.dsl.DynamicFeatureProductFlavor",
                "com.android.build.api.dsl.TestProductFlavor" -> {
                    // content holder has a special API for flavor container.
                    // The provided type is not the type of the container but the type handled
                    // by the container.
                    @Suppress("UNCHECKED_CAST")
                    contentHolder.productFlavors(blockTypeClass as Class<ProductFlavor>) {
                        // calls into the function configuring the container.
                        // `this` here is the nested block (container)
                        @Suppress("UNCHECKED_CAST")
                        (args[0] as Function1<Any, *>).invoke(this)
                    }
                }

                "com.android.build.api.dsl.ExecutionProfile" -> {
                    @Suppress("UNCHECKED_CAST")
                    contentHolder.executionProfiles(blockTypeClass as Class<ExecutionProfile>) {
                        // calls into the function configuring the container.
                        // `this` here is the nested block (container)
                        @Suppress("UNCHECKED_CAST")
                        (args[0] as Function1<Any, *>).invoke(this)
                    }
                }

                "org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet" -> {
                    @Suppress("UNCHECKED_CAST")
                    contentHolder.kotlinSourceSets(blockTypeClass as Class<KotlinSourceSet>) {
                        // calls into the function configuring the container.
                        // `this` here is the nested block (container)
                        @Suppress("UNCHECKED_CAST")
                        (args[0] as Function1<Any, *>).invoke(this)
                    }
                }

                else -> {
                    // this a container with different content. we need specific support for it.
                    throw RuntimeException("Unsupported container configuration: ${method.name}(${blockTypeClass.name})")
                }
            }
        } else {
            // Normal nested block. the provided type is the direct nested block type.
            contentHolder.runNestedBlock(method.name, listOf(), blockTypeClass) {
                // calls into the function configuring the nested block
                // `this` here is the nested block
                @Suppress("UNCHECKED_CAST")
                (args[0] as Function1<Any,*>).invoke(this)
            }
        }

        return true
    }

    private fun handleMethodCall(method: Method, args: Array<out Any?>?): MethodReturn {
        // there is one case where we want to be able to return a File. it is
        //   CommonExtension.getDefaultProguardFile(String)
        // In that case, we want to intercept this call return a fake FIle that contains
        // ths information, so that in BuildWriter, we can recognize it, and reconstruct
        // the original call.
        if (method.name == "getDefaultProguardFile" && method.declaringClass == CommonExtension::class.java) {
            return MethodReturn(true, MethodReturnedFile(
                methodName = "getDefaultProguardFile",
                parameter = args!!.first() as String
            ))
        }

        contentHolder.call(method.name, args?.toList() ?: listOf(), method.isVarArgs)
        return (MethodReturn(true, null))
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

    private fun getTypeParameterByIndex(originalClass: String, index: Int): Class<*> {
        val genericInfo = theInterface.genericInterfaces
        for (type in genericInfo) {
            if (type is ParameterizedType) {
                val owner = type.rawType
                if (owner.typeName == originalClass) {
                    return theInterface.classLoader.loadClass(type.actualTypeArguments[index].typeName)
                }
            }
        }

        throw Error("Unable to find Type info in ${theInterface.typeName} matching type parameter definition from $originalClass")
    }
}

/**
 * Override of [File] that encodes the single argument method that returned it.
 */
internal class MethodReturnedFile(
    val methodName: String,
    val parameter: String,
): File(parameter)
