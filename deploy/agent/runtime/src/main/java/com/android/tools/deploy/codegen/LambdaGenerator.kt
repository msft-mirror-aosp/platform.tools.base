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
package com.android.tools.deploy.codegen

import com.android.deploy.asm.Opcodes
import com.android.deploy.asm.Type
import com.android.tools.deploy.liveedit.ProxyClass
import com.android.tools.deploy.liveedit.ProxyClassHandler
import com.android.tools.deploy.liveedit.SourceLocationAware
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.WildcardTypeName
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Year
import javax.lang.model.element.Modifier
import kotlin.collections.map

const val pkg = "com.android.tools.deploy.liveedit"

val copyright =
  """
/*
 * Copyright (C) ${Year.now()} The Android Open Source Project
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
"""
    .trimIndent()

// Tool to generate proxy class implementations for kotlin lambda base classes.
//
// We generate a proxy interface for each possible lambda superclass + functional interface so that
// we can create a lambda object that can be cast to both the superclass AND functional interface.
// Regular proxy objects can only implement interfaces, not extend a superclass.
//
// We generated source code and compile it rather than generating bytecode directly as it is less
// verbose to implement, easier to visually inspect/debug (run the code generation in isolation),
// and requires no understanding of JVM bytecode/intricacies to work with.
//
// The generated source code resembles the following (methods omitted for brevity):
//
// public static class Lambda1 extends Lambda<Object> implements Function1<Object, Object>, ProxyClass, SourceLocationAware {
//    private ProxyClassHandler handler;
//
//    public Lambda1(int arg0) {
//        super(arg0);
//    }
//
//    /**
//     * Inherited from Object
//     */
//    public boolean equals(Object arg0) {
//        if (handler.implementsMethod("equals", "(Ljava/lang/Object;)Z")) {
//            return (boolean) handler.invokeMethod(this, "equals", "(Ljava/lang/Object;)Z", new Object[] { arg0 });
//        }
//        return super.equals(arg0);
//    }
//
//    /**
//     * Inherited from Function1
//     */
//    public Object invoke(Object arg0) {
//        return (Object) handler.invokeMethod(this, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", new Object[] { arg0 });
//    }
//
//    public ProxyClassHandler getHandler() {
//        return handler;
//    }
//
//    public void setHandler(ProxyClassHandler handler) {
//        this.handler = handler;
//    }
//
//    public Map getSourceLocationInfo() {
//        return handler.getSourceLocationInfo();
//    }
// }

/**
 * @param name the name that will be used for the generated proxy type
 * @param superclass the superclass the proxy type will inherit from
 * @param interfaces the set of interfaces the proxy type will implement
 * @param rawTypes whether to generate parameterized java generics (Type<Param> vs Type) in the class inheritance. Some classes
 *   (FunctionReference) already have raw types in their definition; java complains if raw and parameterized types are mixed in this
 *   context.
 */
private data class ProxySpec(val name: String, val superclass: Class<*>, val interfaces: List<Class<*>>, val rawTypes: Boolean = false)

fun main(args: Array<String>) {
  val functionInterfaces = ((0..22) + listOf("N")).map { Class.forName("kotlin.jvm.functions.Function$it") }

  val lambdas =
    listOf(
        Class.forName("kotlin.jvm.internal.Lambda"),
        Class.forName("kotlin.coroutines.jvm.internal.SuspendLambda"),
        Class.forName("kotlin.coroutines.jvm.internal.RestrictedSuspendLambda"),
      )
      .flatMap { l -> functionInterfaces.map { ProxySpec(l.simpleName + it.simpleName.substringAfter("Function"), l, listOf(it)) } }

  val refs =
    listOf(
        Class.forName("kotlin.jvm.internal.FunctionReference"),
        Class.forName("kotlin.jvm.internal.FunctionReferenceImpl"),
        Class.forName("kotlin.jvm.internal.AdaptedFunctionReference"),
      )
      .flatMap { r ->
        functionInterfaces.map { ProxySpec(r.simpleName + it.simpleName.substringAfter("Function"), r, listOf(it), /* raw types */ true) }
      }

  val continuations =
    listOf(
        Class.forName("kotlin.coroutines.jvm.internal.BaseContinuationImpl"),
        Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl"),
        Class.forName("kotlin.coroutines.jvm.internal.RestrictedContinuationImpl"),
      )
      .map { ProxySpec(it.simpleName, it, emptyList(), false) }

  generateProxies(lambdas + refs + continuations).let {
    val path = Paths.get(args[0]).toAbsolutePath()
    Files.write(path, "$copyright\n$it".toByteArray(Charsets.UTF_8))
  }
}

private fun generateProxies(proxies: List<ProxySpec>): JavaFile {
  val factory = TypeSpec.classBuilder("Proxies").addModifiers(Modifier.PUBLIC)

  // Class<?>
  val classType = ParameterizedTypeName.get(ClassName.get(Class::class.java), WildcardTypeName.subtypeOf(Object::class.java))

  // Map<Set<Class<?>>, Class<?>>
  val mapType =
    ParameterizedTypeName.get(
      ClassName.get(java.util.Map::class.java),
      ParameterizedTypeName.get(ClassName.get(java.util.Set::class.java), classType),
      classType,
    )

  // Field holding the mapping of a set of types to the generated proxy that extends/implements those types
  val field =
    FieldSpec.builder(mapType, "proxies", Modifier.PUBLIC, Modifier.STATIC)
      .initializer(CodeBlock.of("new \$T<>()", java.util.HashMap::class.java))
      .build()
  factory.addField(field)

  val init = CodeBlock.builder()
  for (proxy in proxies) {
    val proxyType = generateProxy(proxy)
    factory.addType(proxyType)

    // Create the mapping of implemented types --> proxy type
    val types = listOf(proxy.superclass) + proxy.interfaces
    val args = CodeBlock.join(types.map { CodeBlock.of("\$T.class", it) }, ",")
    val key = CodeBlock.of("new \$T<>(\$T.asList(\$L))", java.util.HashSet::class.java, java.util.Arrays::class.java, args)
    init.addStatement(CodeBlock.of("\$L.put(\$L, \$L.class)", field.name, key, proxyType.name))
  }

  factory.addStaticBlock(init.build())

  val paramType = ParameterizedTypeName.get(ClassName.get(java.util.Set::class.java), classType)
  val getter =
    MethodSpec.methodBuilder("getProxyInterface")
      .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
      .addParameter(paramType, "supertypes")
      .returns(classType)
      .addStatement(CodeBlock.of("return \$N.get(\$N)", "proxies", "supertypes"))
  factory.addMethod(getter.build())

  val factoryType = factory.build()
  val file = JavaFile.builder(pkg, factoryType).build()
  val path = Paths.get("${factoryType.name}.java").toAbsolutePath()
  Files.write(path, "$copyright\n$file".toByteArray(Charsets.UTF_8))
  return file
}

private fun generateProxy(proxy: ProxySpec): TypeSpec {
  // Set of Pair(name, descriptor) of methods we have already implemented. Tracking this prevents
  // us from generating multiple instances of the exact same method if we encounter it multiple
  // times in the inheritance hierarchy.
  val implementedMethods = mutableSetOf<Pair<String, String>>()
  val builder = TypeSpec.classBuilder(proxy.name).addConstructorsFrom(proxy.superclass).addModifiers(Modifier.PUBLIC, Modifier.STATIC)

  if (!proxy.rawTypes && proxy.superclass.typeParameters.isNotEmpty()) {
    val superTypeArgs = List(proxy.superclass.typeParameters.size) { Object::class.java }.toTypedArray()
    builder.superclass(ParameterizedTypeName.get(proxy.superclass, *superTypeArgs))
  } else {
    builder.superclass(proxy.superclass)
  }

  // Traverse the inheritance hierarchy by recursively visiting each class, then its
  // superclasses/interfaces. Generate proxy stubs for all non-final public or protected methods.
  // These are the methods a derived class could override, so a proxy stub must be generated to
  // allow the proxy to 'implement' that method.
  val queue = ArrayDeque(listOf(proxy.superclass))
  while (queue.isNotEmpty()) {
    val cur = queue.removeFirst()
    builder.addMethodsFrom(cur, implementedMethods)
    cur.superclass?.let { queue.add(it) }
    cur.interfaces.forEach { queue.add(it) }
  }

  proxy.interfaces.forEach {
    if (!proxy.rawTypes) {
      val typeArgs = List(it.typeParameters.size) { Object::class.java }.toTypedArray()
      builder.addSuperinterface(ParameterizedTypeName.get(it, *typeArgs))
    } else {
      builder.addSuperinterface(it)
    }
    builder.addMethodsFrom(it, implementedMethods)
  }

  builder.addSuperinterface(ProxyClass::class.java)
  builder.addField(ProxyClassHandler::class.java, "handler", Modifier.PRIVATE)
  builder.addMethod(
    MethodSpec.methodBuilder("getHandler")
      .addModifiers(Modifier.PUBLIC)
      .returns(ProxyClassHandler::class.java)
      .addStatement("return \$N", "handler")
      .build()
  )
  builder.addMethod(
    MethodSpec.methodBuilder("setHandler")
      .addModifiers(Modifier.PUBLIC)
      .addParameter(ProxyClassHandler::class.java, "handler")
      .addStatement("this.\$N = \$N", "handler", "handler")
      .build()
  )

  builder.addSuperinterface(SourceLocationAware::class.java)
  builder.addMethod(
    MethodSpec.methodBuilder("getSourceLocationInfo")
      .addModifiers(Modifier.PUBLIC)
      .returns(Map::class.java)
      .addStatement("return \$N", "handler.getSourceLocationInfo()")
      .build()
  )

  return builder.build()
}

private fun TypeSpec.Builder.addConstructorsFrom(clazz: Class<*>) =
  this.addMethods(
    clazz.constructors.mapNotNull {
      val params = it.parameters.map { param -> ParameterSpec.builder(param.type, param.name).build() }

      val constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameters(params)

      val paramString = params.joinToString(", ") { "\$N" }
      constructor.addStatement("super($paramString)", *params.toTypedArray())
      constructor.build()
    }
  )

private fun TypeSpec.Builder.addMethodsFrom(clazz: Class<*>, implemented: MutableSet<Pair<String, String>>) =
  this.addMethods(
    clazz.declaredMethods.mapNotNull {
      // Ignore bridge methods
      if (it.isBridge) {
        return@mapNotNull null
      }

      val descriptor = Type.getMethodDescriptor(it)

      // Ignore methods that only differ by return type; we can't handle that in source code. This happens
      // when a superclass defines a method that a subclass overrides to return a derived type.
      // For example:
      // class Type {}
      // class A {
      //      fun foo() : Type
      // }
      // class Derived : Type {}
      // class B : A {
      //      fun foo() : Derived
      // }
      // When we walk the inheritance hierarchy for class B, we will encounter 'foo() : Derived', then
      // 'foo() : Type' ; we only want to generate a stub method for 'foo() : Derived' - and, in fact, we
      // can't generate a stub for both, as java source code doesn't support it.
      val namedDescriptor = Pair(it.name, descriptor.substringBeforeLast(')'))
      if (namedDescriptor in implemented) {
        return@mapNotNull null
      }

      // Add this here to ensure we treat final methods we encounter as implemented; a generated
      // lambda cannot override them, so we should not generate them even if we encounter a
      // non-final version of the method somewhere in the inheritance hierarchy.
      implemented.add(namedDescriptor)

      // Don't proxy private or final methods of superclasses, as neither type of method can be
      // overridden in the base class.
      if (it.modifiers and Opcodes.ACC_PRIVATE != 0 || it.modifiers and Opcodes.ACC_FINAL != 0) {
        return@mapNotNull null
      }

      val method =
        MethodSpec.methodBuilder(it.name)
          .returns(it.returnType)
          .addJavadoc("Inherited from \$T\n", clazz)
          .addExceptions(it.exceptionTypes.map { ex -> TypeName.get(ex) })
          .addParameters(it.parameters.map { param -> ParameterSpec.builder(param.type, param.name).build() })

      // Ensure the method visibility is the same as the overridden method. Do not just copy the
      // modifiers directly, since we don't want to generate ACC_INTERFACE or ACC_ABSTRACT.
      if (it.modifiers and Opcodes.ACC_PUBLIC != 0) {
        method.addModifiers(Modifier.PUBLIC)
      } else if (it.modifiers and Opcodes.ACC_PROTECTED != 0) {
        method.addModifiers(Modifier.PROTECTED)
      }

      val args = CodeBlock.of(it.parameters.joinToString(", ") { "\$N" }, *it.parameters.map { param -> param.name }.toTypedArray())

      val invoke =
        CodeBlock.of("\$N.invokeMethod(\$N, \$S, \$S, new \$T[] { \$L })", "handler", "this", it.name, descriptor, Object::class.java, args)

      // If we are overriding a superclass implementation, ensure we have the ability to fall back
      // to that implementation in the event that the new lambda class does not provide an
      // implementation. Note that ACC_ABSTRACT is also set for interface methods.
      if (it.modifiers and Opcodes.ACC_ABSTRACT == 0) {
        val controlFlow = "if (\$N.implementsMethod(\$S, \$S))"
        val superInvoke = CodeBlock.of("super.\$N(\$L)", it.name, args)

        method.beginControlFlow(controlFlow, "handler", it.name, descriptor)
        if (it.returnType != Void.TYPE) {
          method.addStatement("return (\$T) \$L", it.returnType, invoke)
          method.endControlFlow()
          method.addStatement("return \$L", superInvoke)
        } else {
          method.addStatement(invoke)
          method.endControlFlow()
          method.addStatement(superInvoke)
        }
      } else {
        if (it.returnType != Void.TYPE) {
          method.addStatement("return (\$T) \$L", it.returnType, invoke)
        } else {
          method.addStatement(invoke)
        }
      }

      method.build()
    }
  )
