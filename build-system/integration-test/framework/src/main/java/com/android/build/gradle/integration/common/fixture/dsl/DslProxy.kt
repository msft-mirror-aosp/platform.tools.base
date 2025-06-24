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
import com.android.build.api.dsl.CompileSdkReleaseSpec
import com.android.build.api.dsl.CompileSdkSpec
import com.android.build.api.dsl.CompileSdkVersion
import com.android.build.api.dsl.DynamicFeatureProductFlavor
import com.android.build.api.dsl.ExecutionProfile
import com.android.build.api.dsl.LibraryProductFlavor
import com.android.build.api.dsl.MaxSdkSpec
import com.android.build.api.dsl.MaxSdkVersion
import com.android.build.api.dsl.MinSdkSpec
import com.android.build.api.dsl.MinSdkVersion
import com.android.build.api.dsl.PrivacySandboxSdkExtension
import com.android.build.api.dsl.ProductFlavor
import com.android.build.api.dsl.TargetSdkSpec
import com.android.build.api.dsl.TargetSdkVersion
import com.android.build.api.dsl.TestProductFlavor
import com.android.build.gradle.integration.common.fixture.project.builder.CustomObjectInstance
import com.android.build.gradle.integration.common.fixture.project.builder.StringHandler
import org.gradle.api.DomainObjectSet
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer
import org.gradle.api.JavaVersion
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.artifacts.dsl.DependencyCollector
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * a Proxy class over any DSL interfaces.
 *
 * The proxy records all calls to the class and stores them (in [DslRecorder]) so that they
 * can be rewritten in build files, with a choice of format (groovy, kts, declarative).
 *
 * Note that some classes are proxied manually.
 *
 * - *ProductFlavor is proxy manually due to [ProductFlavor.setDimension] colliding with [ProductFlavor.dimension]
 * - List, and Map. This allows us to not implement some methods (like getters) that aren't safe.
 * - (List)Property. This also allows us to not implement the getters.
 */
class DslProxy private constructor(
    private val theInterface: Class<*>,
    internal val dslRecorder: DslRecorder,
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
            dslRecorder: DslRecorder,
        ): T {
            return when (theClass) {
                ApplicationProductFlavor::class.java -> ApplicationProductFlavorProxy(dslRecorder) as T
                LibraryProductFlavor::class.java -> LibraryProductFlavorProxy(dslRecorder) as T
                DynamicFeatureProductFlavor::class.java -> DynamicFeatureProductFlavorProxy(dslRecorder) as T
                TestProductFlavor::class.java -> TestProductFlavorProxy(dslRecorder) as T
                else -> Proxy.newProxyInstance(
                    DslProxy::class.java.classLoader,
                    arrayOf(theClass),
                    DslProxy(theClass, dslRecorder)
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

            // check if it's a call to a *SdkSpec.release/preview method
            // this should be done before the nested block because one version has a
            // nested block
            val result = checkSdkSpecCall(method, args)
            if (result.validResult) return result.value

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
            Float::class.java,
            File::class.java,
            JavaVersion::class.java -> {
                dslRecorder.set(propName, value)
            }
            java.lang.Boolean::class.java, Boolean::class.java -> {
                dslRecorder.setBoolean(propName, value, isNotation)
            }
            // custom implementation for String in order to intercept set/get to namespace
            String::class.java -> {
                dslRecorder.set(propName, value)
                if (rootExtensionProxy && propName == "namespace") {
                    namespace = value as String?
                }
            }

            // these four SdkVersion interfaces are very basic to handle since
            // they are just nullable objects, so we can handle then in the BuildWriter
            CompileSdkVersion::class.java,
            TargetSdkVersion::class.java,
            MinSdkVersion::class.java,
            MaxSdkVersion::class.java -> {
                // TODO: verify they are assigned to the right thing too.
                dslRecorder.set(propName, value)
            }

            else -> throw IllegalArgumentException("Does not support type ${param.type} for method ${method.name} -- Add support as needed")
        }

        return true
    }

    data class MethodReturn(
        val validResult: Boolean,
        val value: Any?
    ) {
        constructor(value: Any?): this(true, value)
    }

    private val notAMatch = MethodReturn(false, null)

    /**
     * Intercepts calls to the DSL for compile/min/max/target Sdk as the pattern
     * is to call a method that returns an item to pass to a setter.
     *
     * This is unusual in our DSL and requires custom handling.
     *
     * This intercepts:
     * - [CompileSdkSpec.release]
     * - [CompileSdkSpec.preview]
     * - [TargetSdkSpec.release]
     * - [TargetSdkSpec.preview]
     * - [MinSdkSpec.release]
     * - [MaxSdkSpec.release]
     */
    private fun checkSdkSpecCall(method: Method, args: Array<out Any?>?): MethodReturn {
        if (args == null) return notAMatch

        // If the method call is a call to a *SdkSpec object that returns a *SdkVersion object,
        // the main goal is to return such object, in a way that allows us to recreate the DSL
        // calls.
        // However since the main difference between release and preview is already in the
        // value of the *SdkVersion, we don't really need to record whether it was created
        // by release or preview, we can just put the values in the object.

        // we have to handle [CompileSdkSpec.release] with a lambda in a special way (to record
        // the content of the lambda.)
        return when (method.declaringClass) {
            CompileSdkSpec::class.java -> {
                when (method.name) {
                    "release" -> {
                        when (method.parameterCount) {
                            1 -> {
                                MethodReturn(CompileSdkVersionImpl(apiLevel = args.first() as Int))
                            }
                            2 -> {
                                val lambda = method.parameters[1]

                                if (lambda.type != Function1::class.java) {
                                    throw RuntimeException("Unexpected lambda for 2nd arg for ${method.name} -- Add support as needed")
                                }

                                // we are going to take a shortcut here. We don't want to record
                                // the lambda calls because it's not attached to a DSL object
                                // (it's just constructing the CompileSdkVersion object).
                                // So we're just going to run the lambda, and then later,
                                // when we write it, we'll reconstruct the calls (since they
                                // are simple setters
                                val spec = CompileSdkReleaseSpecImpl()

                                @Suppress("UNCHECKED_CAST")
                                (args[1] as Function1<CompileSdkReleaseSpec, *>).invoke(spec)

                                MethodReturn(
                                    CompileSdkVersionImpl(
                                        apiLevel = args[0] as Int,
                                        minorApiLevel = spec.minorApiLevel,
                                        sdkExtension = spec.sdkExtension,
                                    )
                                )
                            }
                            else -> {
                                throw RuntimeException("Unexpected arg count for ${method.name} -- Add support as needed")
                            }
                        }
                    }
                    "preview" -> {
                        if (method.parameterCount != 1) {
                            throw RuntimeException("Unexpected arg count for ${method.name} -- Add support as needed")
                        }

                        MethodReturn(CompileSdkVersionImpl(codeName = args.first() as String))
                    }
                    "addon" -> {
                        if (method.parameterCount != 3) {
                            throw RuntimeException("Unexpected arg count for ${method.name} -- Add support as needed")
                        }

                        MethodReturn(
                            CompileSdkVersionImpl(
                                vendorName = args.first() as String,
                                addonName = args[1] as String,
                                apiLevel = args[2] as Int
                            )
                        )
                    }
                    else -> throw RuntimeException("Unexpected method call ${method.name} -- Add support as needed")
                }
            }
            TargetSdkSpec::class.java -> {
                if (method.parameterCount != 1) {
                    throw RuntimeException("Unexpected arg count for ${method.name} -- Add support as needed")
                }

                when (method.name) {
                    "release" -> {
                        MethodReturn(TargetSdkVersionImpl(apiLevel = args.first() as Int))
                    }
                    "preview" -> {
                        MethodReturn(TargetSdkVersionImpl(codeName = args.first() as String))
                    }
                    else -> throw RuntimeException("Unexpected method call ${method.name} -- Add support as needed")
                }
            }
            MinSdkSpec::class.java -> {
                if (method.parameterCount != 1) {
                    throw RuntimeException("Unexpected arg count for ${method.name} -- Add support as needed")
                }

                when (method.name) {
                    "release" -> {
                        MethodReturn(MinSdkVersionImpl(apiLevel = args.first() as Int))
                    }
                    "preview" -> {
                        MethodReturn(MinSdkVersionImpl(codeName = args.first() as String))
                    }
                    else -> throw RuntimeException("Unexpected method call ${method.name} -- Add support as needed")
                }
            }
            MaxSdkSpec::class.java -> {
                if (method.parameterCount != 1) {
                    throw RuntimeException("Unexpected arg count for ${method.name} -- Add support as needed")
                }

                when (method.name) {
                    "release" -> {
                        MethodReturn(MaxSdkVersionImpl(apiLevel = args.first() as Int))
                    }
                    else -> throw RuntimeException("Unexpected method call ${method.name} -- Add support as needed")
                }
            }
            else -> notAMatch
        }
    }

    private fun checkGetter(method: Method): MethodReturn {
        if (method.parameters.isNotEmpty()) return notAMatch

        val regex = Regex("^get([A-Za-z0-9]+)$")
        val matcher = regex.matchEntire(method.name) ?: return notAMatch
        val propName =
            matcher.groups[1]?.value?.replaceFirstChar { c -> c.lowercaseChar() }
                ?: throw Error("Expected prop name but null")

        val returnValue = when (method.returnType) {
            // some custom proxies in order to ensure they are not called in the wrong way
            // (like querying for the collection content, or calling get on providers)
            MutableList::class.java,
            MutableCollection::class.java -> ListProxy<Any>(dslRecorder.createChainedRecorder(propName))
            MutableSet::class.java -> SetProxy<Any>(dslRecorder.createChainedRecorder(propName))
            MutableMap::class.java -> MapProxy<Any, Any>(dslRecorder.createChainedRecorder(propName))
            Property::class.java -> PropertyProxy<Any>(dslRecorder.createChainedRecorder(propName))
            RegularFileProperty::class.java -> RegularFilePropertyProxy(dslRecorder.createChainedRecorder(propName))
            MapProperty::class.java -> MapPropertyProxy<Any, Any>(dslRecorder.createChainedRecorder(propName))
            DomainObjectSet::class.java -> DomainObjectSetProxy<Any>(dslRecorder.createChainedRecorder(propName))
            ListProperty::class.java -> ListPropertyProxy<Any>(dslRecorder.createChainedRecorder(propName))
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
            NamedDomainObjectContainer::class.java -> NamedDomainObjectContainerProxy(
                extractResolvedTypeParamFromReturn(method),
                dslRecorder.createChainedRecorder(propName)
            )
            ExtensiblePolymorphicDomainObjectContainer::class.java -> ExtensiblePolymorphicDomainObjectContainerProxy(
                extractResolvedTypeParamFromReturn(method),
                dslRecorder.createChainedRecorder(propName)
            )
            DependencyCollector::class.java -> DependencyCollectorProxy(
                dslRecorder.createChainedRecorder(propName)
            )
            // the rest
            else -> {
                // AGP API objects. These are generally objects that also have a matching configuration
                // method, so we return the objects as chained proxies
                if (method.returnType.name.startsWith("com.android.build.api") ||
                    method.returnType.name.startsWith("com.android.build.gradle.integration")) {
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

    private fun extractResolvedTypeParamFromReturn(method: Method): Class<out Any> {
        // the return type should be a Parameterized Type
        val returnType = method.genericReturnType
        // because it's a container it should be a parameterized type
        returnType as ParameterizedType

        // there should be a single type param for a container.
        val containerTypeParam = returnType.actualTypeArguments.first()

        // right now our type params in container are type variables. This
        // may change in the future.
        return when (containerTypeParam) {
            is TypeVariable<*> -> {
                // search in the class that defined the method for the index of the type param for
                // the type used in the function.
                val ownerClass = method.declaringClass
                val index = findTypeParameterIndex(ownerClass, containerTypeParam.name)

                // get the same info on the proxied interface to get the final type, and find the
                // type from the same index.
                val resolvedType = getTypeParameterByIndex(ownerClass.typeName, index)

                ownerClass.classLoader.loadClass(resolvedType.typeName)
            }

            is WildcardType -> {
                containerTypeParam.upperBounds[0] as Class<*>
            }

            else -> {
                method.declaringClass.classLoader.loadClass(containerTypeParam.typeName)
            }
        }
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

        val chainedRecorder = dslRecorder.createChainedRecorder(propName)
        createProxy(actualReturnClass, chainedRecorder)
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
                    @Suppress("UNCHECKED_CAST")
                    dslRecorder.buildTypes(blockTypeClass as Class<BuildType>) {
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
                    @Suppress("UNCHECKED_CAST")
                    dslRecorder.productFlavors(blockTypeClass as Class<ProductFlavor>) {
                        // calls into the function configuring the container.
                        // `this` here is the nested block (container)
                        @Suppress("UNCHECKED_CAST")
                        (args[0] as Function1<Any, *>).invoke(this)
                    }
                }

                "com.android.build.api.dsl.ExecutionProfile" -> {
                    @Suppress("UNCHECKED_CAST")
                    dslRecorder.executionProfiles(blockTypeClass as Class<ExecutionProfile>) {
                        // calls into the function configuring the container.
                        // `this` here is the nested block (container)
                        @Suppress("UNCHECKED_CAST")
                        (args[0] as Function1<Any, *>).invoke(this)
                    }
                }

                "org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet" -> {
                    @Suppress("UNCHECKED_CAST")
                    dslRecorder.kotlinSourceSets(blockTypeClass as Class<KotlinSourceSet>) {
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
            dslRecorder.runNestedBlock(
                name = method.name,
                parameters = listOf(),
                instanceProvider = { createProxy(blockTypeClass, it) }
            ) {
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

        dslRecorder.call(method.name, args?.toList() ?: listOf(), method.isVarArgs)
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

    private fun <T : BuildType> DslRecorder.buildTypes(
        theInterface: Class<T>,
        action: NamedDomainObjectContainerProxy<T>.() -> Unit
    ) {
        runNestedBlock(
            name = "buildTypes",
            parameters = listOf(),
            instanceProvider = {
                NamedDomainObjectContainerProxy(theInterface, it)
            },
            action = action,
        )
    }

    private fun <T : ProductFlavor> DslRecorder.productFlavors(
        theInterface: Class<T>,
        action: NamedDomainObjectContainerProxy<T>.() -> Unit
    ) {
        runNestedBlock(
            name = "productFlavors",
            parameters = listOf(),
            instanceProvider = {
                NamedDomainObjectContainerProxy(theInterface, it)
            },
            action = action,
        )
    }

    private fun DslRecorder.executionProfiles(
        theInterface: Class<ExecutionProfile>,
        action: NamedDomainObjectContainerProxy<ExecutionProfile>.() -> Unit
    ) {
        runNestedBlock(
            name = "profiles",
            parameters = listOf(),
            instanceProvider = {
                NamedDomainObjectContainerProxy(theInterface, it)
            },
            action = action,
        )
    }

    private fun DslRecorder.kotlinSourceSets(
        theInterface: Class<KotlinSourceSet>,
        action: NamedDomainObjectContainerProxy<KotlinSourceSet>.() -> Unit
    ) {
        runNestedBlock(
            name = "sourceSets",
            parameters = listOf(),
            instanceProvider = {
                NamedDomainObjectContainerProxy(theInterface, it)
            },
            action = action,
        )
    }
}

/**
 * Override of [File] that encodes the single argument method that returned it.
 */
internal class MethodReturnedFile(
    val methodName: String,
    val parameter: String,
): File(parameter), CustomObjectInstance {
    override fun toString(handler: StringHandler): String =
        "${methodName}(${handler.quoteString(parameter)})"
}

/**
 * Simple impl of [CompileSdkReleaseSpec] to run the action provided to
 * [CompileSdkSpec.release].
 */
private class CompileSdkReleaseSpecImpl : CompileSdkReleaseSpec {
    override var minorApiLevel: Int? = null
    override var sdkExtension: Int? = null
}

/**
 * Implementation of [CompileSdkVersion] so that we have an object that
 * is returned by the call to [CompileSdkSpec.release] and other similar methods
 */
private data class CompileSdkVersionImpl(
    override val apiLevel: Int? = null,
    override val minorApiLevel: Int? = null,
    override val sdkExtension: Int? = null,
    override val codeName: String? = null,
    override val addonName: String? = null,
    override val vendorName: String? = null,
): CompileSdkVersion, CustomObjectInstance {
    override fun toString(handler: StringHandler): String = if (vendorName != null) {
        """addon(${handler.quoteString(vendorName)}, "$addonName", $apiLevel)"""
    } else if (apiLevel != null) {
        if (minorApiLevel != null || sdkExtension != null) {
            buildString {
                append("release($apiLevel) {\n")
                val internalIndent = handler.getIndentStringFragment(1)
                minorApiLevel?.let { append("${internalIndent}minorApiLevel = $it\n") }
                sdkExtension?.let { append("${internalIndent}sdkExtension = $it\n") }
                append("${handler.getIndentStringFragment()}}")
            }
        } else {
            """release($apiLevel)"""
        }
    } else if (codeName != null) {
        """preview(${handler.quoteString(codeName)})"""
    } else {
        throw RuntimeException("Unexpected value, cannot write CompileSdkVersion: ${toString()}")
    }
}

/**
 * Implementation of [TargetSdkVersion] so that we have an object that
 * is returned by the call to [TargetSdkSpec.release] and other similar methods
 */
private data class TargetSdkVersionImpl(
    override val apiLevel: Int? = null,
    override val codeName: String? = null
) : TargetSdkVersion, CustomObjectInstance {
    override fun toString(handler: StringHandler): String = if (apiLevel != null) {
        """release($apiLevel)"""
    } else if (codeName != null) {
        """preview(${handler.quoteString(codeName)})"""
    } else {
        throw RuntimeException("Unexpected value, cannot write TargetSdkVersion: ${toString()}")
    }
}

/**
 * Implementation of [MinSdkVersion] so that we have an object that
 * is returned by the call to [MinSdkSpec.release] and other similar methods
 */
private data class MinSdkVersionImpl(
    override val apiLevel: Int? = null,
    override val codeName: String? = null
): MinSdkVersion, CustomObjectInstance {
    override fun toString(handler: StringHandler): String = if (apiLevel != null) {
        """release($apiLevel)"""
    } else if (codeName != null) {
        """preview(${handler.quoteString(codeName)})"""
    } else {
        throw RuntimeException("Unexpected value, cannot write MinSdkVersion: ${toString()}")
    }
}

/**
 * Implementation of [MaxSdkVersion] so that we have an object that
 * is returned by the call to [MaxSdkSpec.release] and other similar methods
 */
private data class MaxSdkVersionImpl(
    override val apiLevel: Int
) : MaxSdkVersion, CustomObjectInstance {
    override fun toString(handler: StringHandler): String =
        """release($apiLevel)"""
}
