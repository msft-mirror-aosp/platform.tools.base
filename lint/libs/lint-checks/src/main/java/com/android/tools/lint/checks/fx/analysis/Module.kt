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
package com.android.tools.lint.checks.fx.analysis

import com.android.tools.lint.checks.fx.analysis.Module.AnnotationParser.Companion.nearestBaseAnns
import com.android.tools.lint.checks.fx.analysis.Module.AnnotationParser.Companion.parseMethodAnnotations
import com.android.tools.lint.checks.fx.result.ClassBody
import com.android.tools.lint.checks.fx.result.ClassId
import com.android.tools.lint.checks.fx.result.EffectAnnotation
import com.android.tools.lint.checks.fx.result.Env
import com.android.tools.lint.checks.fx.result.Env.Companion.withFun
import com.android.tools.lint.checks.fx.result.KtTypeReferenceAdapter
import com.android.tools.lint.checks.fx.result.MethodBody
import com.android.tools.lint.checks.fx.result.MethodId
import com.android.tools.lint.checks.fx.result.PsiClassAdapter
import com.android.tools.lint.checks.fx.result.PsiTypeAdapter
import com.android.tools.lint.checks.fx.result.TermEnv
import com.android.tools.lint.checks.fx.result.Type
import com.android.tools.lint.checks.fx.result.Type.Sym
import com.android.tools.lint.checks.fx.result.TypeAdapter
import com.android.tools.lint.checks.fx.result.TypeBounds
import com.android.tools.lint.checks.fx.result.isStatic
import com.android.tools.lint.checks.fx.result.returnType
import com.android.tools.lint.checks.fx.result.showBound
import com.android.tools.lint.checks.fx.utils.assoc
import com.android.tools.lint.checks.fx.utils.plus
import com.android.tools.lint.client.api.JavaEvaluator
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.nameFromSource
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiTypeParameterListOwner
import com.intellij.psi.impl.source.PsiClassReferenceType
import java.util.IdentityHashMap
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import org.jetbrains.kotlin.name.JvmStandardClassIds
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtTypeParameterListOwner
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getContainingUMethod
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.withContainingElements

/**
 * Internal representation of program/module.
 *
 * This is what the detector builds up during the first pass as a [SourceCodeScanner], instead of actually analyzing the program. The
 * [Module] indexes classes and methods for the analysis to proceed in its own order and iteration.
 */
internal class Module<FX : Any>(val classes: Map<ClassId, ClassBody<FX>>) {

  operator fun contains(cl: ClassId): Boolean = cl in classes

  operator fun get(impl: Type.MethodRef): MethodBody<FX>? {
    val methodRef = impl.method
    fun lookUp(cl: ClassId): MethodBody<FX>? =
        when (val classImpl = classes[cl]) {
          null -> null
          else -> classImpl.methods[methodRef] ?: classImpl.supers.firstNotNullOfOrNull(::lookUp)
        }

    return lookUp(impl.klass)
  }

  operator fun get(field: PsiField): Type<FX>? {
    val c = classes[ClassId.of(field.containingClass!!)] ?: return null
    return c.initEnvironment.vars[field.name]
  }

  fun getSAMStatus(intf: ClassId): MethodBody.Status<FX> = getSAM(intf)?.value?.status ?: MethodBody.Status.Abstract // TODO

  operator fun get(classId: ClassId): Map<MethodId, MethodBody<FX>>? = classes[classId]?.methods

  private fun getSAM(intf: ClassId): Map.Entry<MethodId, MethodBody<FX>>? {
    val header = classes[intf] ?: return null
    val abstractMethods =
        header.methods.filter { (_, header) ->
          when (val s = header.status) {
            is MethodBody.Status.Abstract -> true
            is MethodBody.Status.ForChecking -> s.body == null
            is MethodBody.Status.ForInference,
            is MethodBody.Status.BasicConstructor -> false
          }
        }
    return when (val n = abstractMethods.size) {
      0 -> header.supers.firstNotNullOfOrNull(::getSAM)
      1 -> abstractMethods.asSequence().first()
      else -> throw IllegalStateException("Expect SAM, but got $intf with $n abstract methods: [${abstractMethods.keys.joinToString()}]")
    }
  }

  // TODO (b/417750068)
  internal fun findMethodByName(
      name: String,
      receiver: ClassId,
      args: List<Type<FX>>,
  ): Type.MethodRef {
    val cl = classes[receiver] ?: throw MethodLookupException.NotFound(name) // TODO search superclasses?
    val candidates =
        cl.methods.filterKeys { id ->
          id.name == name &&
              when {
                // TODO may be ellipsis or not
                id.paramTags.lastOrNull() == ClassId.Array -> args.size >= id.paramTags.size - 1
                else -> id.paramTags.size == args.size
              }
        }
    return when (candidates.size) {
      0 -> throw MethodLookupException.NotFound(name)
      1 -> Type.MethodRef(receiver, candidates.asSequence().first().key)
      else -> throw MethodLookupException.Ambiguous(name, candidates.map { it.key })
    }
  }

  internal sealed class MethodLookupException(val name: String) : Exception() {
    class NotFound(name: String, val msg: String? = null) : MethodLookupException(name)

    class Ambiguous(name: String, val candidates: List<MethodId>) : MethodLookupException(name)
  }

  class Builder<FX : Any>(private val annotationParser: AnnotationParser<FX>) {
    private val classes = LinkedHashMap<ClassId, ClassBody<FX>>()
    private var elapsed: Duration = Duration.Companion.ZERO
    private val overloadingCache = IdentityHashMap<PsiElement, TermEnv<Nothing>>()

    fun build(): Module<FX> = Module(classes)

    fun addClass(context: JavaContext, klass: UClass) = apply {
      elapsed += measureTime {
        val id = ClassId.of(klass.javaPsi)
        if (id !in classes) {
          classes[id] = buildClass(context, klass)
        }
      }
    }

    fun addGuardedSubclass(
        context: JavaContext,
        source: UElement,
        guard: EffectAnnotation.Explicit<FX>,
        base: UClass,
        sam: PsiMethod,
    ): ClassId =
        ClassId.of(guard.annotated, base.javaPsi).also { id ->
          classes.computeIfAbsent(id) {
            val baseBody = buildClass(context, base)
            val baseMethodId = MethodId(sam)
            val baseMethod = baseBody.methods[baseMethodId]!!
            ClassBody(
                supers = listOf(ClassId.of(base.javaPsi)),
                methods =
                    persistentMapOf(
                        baseMethodId to
                            MethodBody(
                                domains = baseMethod.domains,
                                initEnvironment = baseMethod.initEnvironment,
                                returnTypeAnnotation = baseMethod.returnTypeAnnotation,
                                status = MethodBody.Status.ForChecking(guard, null),
                                source = source,
                            )
                    ),
                initEnvironment = baseBody.initEnvironment,
            )
          }
        }

    fun addLocalFunction(localFun: LocalFun) {
      val (classId, fnId) = containerChain(localFun.uast) ?: return
      val (containingClassId, containingMethodId) = classId.asContainingClassAndInnermostMethod()

      val containingClass = classes[containingClassId]!!
      val containingMethod = containingClass.methods[containingMethodId]!!
      val containingEnv = containingMethod.initEnvironment

      val fnDefn = buildLocalFunction(localFun, containingEnv)
      val fnRef = Type.MethodRef(classId, fnId)

      val extendedEnv = containingEnv.withFun(fnId.name, fnRef)
      val extendedMethod = containingMethod.copy(initEnvironment = extendedEnv)
      val extendedContainingClass = containingClass.copy(methods = containingClass.methods.put(containingMethodId, extendedMethod))

      classes.put(containingClassId, extendedContainingClass)

      val placeholderClass = classes[classId] ?: ClassBody.empty
      classes[classId] = placeholderClass.copy(methods = placeholderClass.methods.put(fnId, fnDefn))
    }

    private fun buildClass(context: JavaContext, klass: UClass): ClassBody<FX> {
      val outerEnv: Env<Nothing> =
          when {
            klass.isStatic -> Env.empty
            else -> // Assume enclosing class/method has been indexed
            klass.getContainingUMethod()?.let { method ->
                  val (methodClass, methodDesc) = Type.MethodRef(method.javaPsi)
                  val impl = classes[methodClass] ?: return@let null
                  val body = impl.methods[methodDesc] ?: return@let null
                  body.initEnvironment
                }
                    ?: klass.getContainingUClass()?.let { cl ->
                      if (klass.isStatic) return@let null
                      classes[ClassId.of(cl.javaPsi)]?.initEnvironment
                    }
                    ?: Env.empty
          }

      val outerTypeParams = outerEnv.types + klass.javaPsi.typeParams()

      val klassId = ClassId.of(klass.javaPsi)
      val klassAsType = PsiClassAdapter.translate(outerTypeParams.keys, klass.javaPsi)

      val (typeParams, receivers) =
          when {
            klass.isFinal -> outerTypeParams to outerEnv.virtualReceivers.put(klassId, klassAsType)
            else -> {
              val recv = Sym.This(klassId)
              outerTypeParams.put(recv.uniqueName, persistentSetOf(klassAsType)) to outerEnv.virtualReceivers.put(klassId, recv)
            }
          }

      val typeParamNames = typeParams.keys

      val env =
          Env(
              types = typeParams,
              vars =
                  klass.fields.fold(outerEnv.vars) { env, f ->
                    val fParam = f.javaPsi as PsiField
                    when {
                      f.isStatic -> env
                      else -> env.put(fParam.name, PsiTypeAdapter.translate(typeParamNames, fParam.type))
                    }
                  },
              funs = outerEnv.funs,
              virtualReceivers = receivers,
          )

      val classAdapter: TypeAdapter<PsiClass> =
          when {
            // HACK: "instantiate" inner-class with the same type arguments
            typeParams.isNotEmpty() ->
                object : TypeAdapter<PsiClass> by PsiClassAdapter {
                  val location = ClassId.of(klass.javaPsi)
                  val type = Type.Application(location, typeParams.map { Sym.Param(it.key) })

                  override fun translate(env: Set<String>, repr: PsiClass) =
                      when {
                        repr is PsiAnonymousClass && ClassId.of(repr) == location -> type
                        else -> PsiClassAdapter.translate(env, repr)
                      }
                }
            else -> PsiClassAdapter
          }

      return ClassBody<FX>(
          supers = klass.javaPsi.supers.map(ClassId::of),
          methods =
              klass.methods.asList().assoc { method ->
                val env = if (method.javaPsi.isStatic()) Env.empty else env
                MethodId(method.javaPsi) to buildMethod(context, method, env, classAdapter)
              },
          initEnvironment = env,
      )
    }

    private fun buildMethod(
        context: JavaContext,
        method: UMethod,
        classEnv: Env<Nothing>,
        classAdapter: TypeAdapter<PsiClass>,
    ): MethodBody<FX> {
      val initTypeParams: TypeBounds<Nothing> = classEnv.types + method.javaPsi.typeParams()
      val (moreTypeParams, domains) = generateDomain(context, initTypeParams.keys, method, classAdapter)
      val typeParams = initTypeParams + moreTypeParams

      val params = method.javaPsi.parameters
      val (initEnv, paramDomains) =
          when (domains.size) {
            params.size -> classEnv.vars to domains
            params.size + 1 -> classEnv.vars to domains.subList(1, domains.size)
            else -> throw IllegalStateException("Got domains $domains for method ${method.name} with parameters $params")
          }
      val env =
          when (val status = method.getOverloadingStatus()) {
            is OverloadingStatus.None -> paramDomains.foldIndexed(initEnv) { i, env, dom -> env + (params[i].name!! to dom) }
            is OverloadingStatus.Primary ->
                paramDomains
                    .foldIndexed(initEnv) { i, env, dom -> env + (params[i].name!! to dom) }
                    .also { overloadingCache[status.source] = it }
            is OverloadingStatus.Secondary -> overloadingCache[status.source]!!
          }
      val body = method.uastBody
      var methodEnv = classEnv.copy(types = typeParams, vars = env)

      return MethodBody(
          domains = domains,
          initEnvironment = methodEnv,
          returnTypeAnnotation = returnType(typeParams.keys, method.javaPsi, classAdapter),
          status =
              when (val ann = annotationParser.parseMethodAnnotations(context, method)) {
                is EffectAnnotation.Implicit ->
                    when {
                      body != null -> MethodBody.Status.ForInference(body, ann)
                      method.isConstructor -> MethodBody.Status.BasicConstructor
                      else -> MethodBody.Status.Abstract
                    }
                is EffectAnnotation.Explicit -> MethodBody.Status.ForChecking(ann, body)
              },
          source = method,
      )
    }

    private fun buildLocalFunction(localFun: LocalFun, classEnv: Env<Nothing>): MethodBody<FX> {
      val (fnUast, fn) = localFun
      val initTypeParams: TypeBounds<Nothing> = classEnv.types + fn.typeParams()
      val (moreTypeParams, domains) = generateDomain(initTypeParams.keys, fn)
      val typeParams = initTypeParams + moreTypeParams

      val params = fnUast.valueParameters
      val (initEnv, paramDomains) =
          when (domains.size) {
            params.size -> classEnv.vars to domains
            params.size + 1 -> classEnv.vars to domains.subList(1, domains.size)
            else -> throw IllegalStateException("Got domains $domains for method ${fnUast.nameFromSource} with parameters $params")
          }
      val env =
          paramDomains.foldIndexed(initEnv) { i, env, dom ->
            val param = params[i].javaPsi as PsiParameter
            env + (param.name to dom)
          }
      val body = fnUast.body
      var methodEnv = classEnv.copy(types = typeParams, vars = env)

      val ann = annotationParser.parseAnnotations(fnUast.uAnnotations)

      return MethodBody(
          domains = domains,
          initEnvironment = methodEnv,
          returnTypeAnnotation = KtTypeReferenceAdapter.translate(typeParams.keys, fn.typeReference),
          status =
              when (ann) {
                null -> MethodBody.Status.ForInference(body, EffectAnnotation.None)
                else -> MethodBody.Status.ForChecking(EffectAnnotation.Explicit(ann, fnUast), body)
              },
          source = fnUast,
      )
    }

    private fun generateDomain(
        context: JavaContext,
        classTypeParams: Set<String>,
        method: UMethod,
        classAdapter: TypeAdapter<PsiClass>,
    ): Pair<TypeBounds<Nothing>, List<Type<Nothing>>> {
      val params = method.uastParameters
      var typeBounds: TypeBounds<Nothing> = persistentMapOf()
      val domains = ArrayList<Type<Nothing>>(params.size + 1)

      fun <T> addDomain(bound: T, adapter: TypeAdapter<T>, genParam: () -> Sym<Nothing>) {
        val translatedBound = adapter.translate(classTypeParams, bound)
        domains.add(
            when {
              translatedBound is Type.Ellipsis && !adapter.isFinal(bound) ->
                  Type.Ellipsis(genParam()).also {
                    val x = it.element
                    if (x is Sym.Param) typeBounds += x.name to persistentSetOf(translatedBound.element)
                  }
              translatedBound is Type.Application &&
                  translatedBound.constructor == ClassId.Array &&
                  translatedBound.args.isNotEmpty() &&
                  !adapter.isFinal(bound) -> {
                val (elemBound, reconstruct) = translatedBound.arrayDeepComponentTypeAndContext()
                val x = genParam()
                if (x is Sym.Param) typeBounds += x.name to persistentSetOf(elemBound)
                reconstruct(x)
              }
              translatedBound is Sym.Param -> translatedBound
              translatedBound is Sym.This -> translatedBound
              adapter.isFinal(bound) -> translatedBound
              else -> genParam().also { if (it is Sym.Param) typeBounds += it.name to persistentSetOf(translatedBound) }
            }
        )
      }

      if (!method.isStatic /* TODO ok? */ && !method.isConstructor) {
        val cl = method.javaPsi.containingClass!!
        addDomain(cl, classAdapter, { Sym.This(ClassId.of(cl)) })
      }

      for (param in params) {
        val paramPsi = param.javaPsi as PsiParameter
        when (val ann = annotationParser.parseAnnotations(context.evaluator.getAnnotations(paramPsi))) {
          null -> addDomain(paramPsi.type, PsiTypeAdapter, { Type.Companion.genParam(paramPsi.name) })
          else -> {
            val baseType = paramPsi.type as PsiClassType
            fun todo(): Nothing = throw NotImplementedError("Annotated base type $baseType of ${baseType::class.java.simpleName}")
            when (val baseClass = baseType.resolve()) {
              // TODO hack
              null ->
                  when (baseType) {
                    is PsiClassReferenceType -> {
                      val fqn = baseType.reference.qualifiedName
                      val functionPrefix = "kotlin.jvm.functions.Function"
                      when {
                        fqn.startsWith(functionPrefix) -> {
                          // FIXME proper type parameters from arity
                          val bound = Type.Application<Nothing>(ClassId.of(fqn), listOf())
                          val typeParam = Type.Companion.genParam(paramPsi.name)
                          domains.add(typeParam)
                          typeBounds += typeParam.name to persistentSetOf(bound)
                        }
                        else -> todo()
                      }
                    }
                    else -> todo()
                  }
              else -> {
                val baseMethods = baseClass.methods.filter { it.body == null }
                require(baseMethods.size == 1) { "TODO: report annotation on non-SAM interface `${baseClass.name}`" }
                val baseMethod = baseMethods.first()
                val paramAnn =
                    with(annotationParser) {
                      val bases = nearestBaseAnns(context.evaluator, arrayOf(baseMethod))
                      resolveAnnotations(context, EffectAnnotation.Explicit(ann, param), bases)
                    }
                val paramModifiedClassId =
                    addGuardedSubclass(
                        context,
                        param,
                        paramAnn,
                        baseClass.toUElement() as UClass,
                        baseMethod,
                    )
                addDomain(
                    paramModifiedClassId,
                    object : TypeAdapter<ClassId> {
                      override fun translate(env: Set<String>, repr: ClassId): Type<Nothing> {
                        val base = PsiTypeAdapter.translate(env, baseType) as Type.Application
                        return Type.Application(paramModifiedClassId, base.args)
                      }

                      override fun isFinal(repr: ClassId) = false
                    },
                    { Type.Companion.genParam(paramPsi.name) },
                )
              }
            }
          }
        }
      }
      return typeBounds to domains
    }

    private fun generateDomain(
        classTypeParams: Set<String>,
        fn: KtNamedFunction,
    ): Pair<TypeBounds<Nothing>, List<Type<Nothing>>> {
      val params = fn.valueParameters
      var typeBounds: TypeBounds<Nothing> = persistentMapOf()
      val domains = ArrayList<Type<Nothing>>(params.size + 1)

      fun <T> addDomain(bound: T, adapter: TypeAdapter<T>, genParam: () -> Sym<Nothing>) {
        val translatedBound = adapter.translate(classTypeParams, bound)
        domains.add(
            when {
              translatedBound is Sym.Param -> translatedBound
              translatedBound is Sym.This -> translatedBound
              adapter.isFinal(bound) -> translatedBound
              else -> genParam().also { if (it is Sym.Param) typeBounds += it.name to persistentSetOf(translatedBound) }
            }
        )
      }

      fn.receiverTypeReference?.let { recvType ->
        addDomain(
            recvType,
            KtTypeReferenceAdapter,
            {
              val t = KtTypeReferenceAdapter.translate(classTypeParams, recvType) as Type.Application
              Sym.This(t.constructor)
            },
        )
      }

      for (param in params) {
        when (val ann = annotationParser.parseAnnotations(/* TODO */ param.annotations.mapNotNull { it.toUElement() as? UAnnotation })) {
          null ->
              addDomain(
                  param.typeReference,
                  KtTypeReferenceAdapter,
                  { Type.Companion.genParam(param.name!!) },
              )
          else -> {
            println("TODO: support annotation on parameter `${param.name!!}` of local function `${fn.name}`: $ann")
          }
        }
      }
      return typeBounds to domains
    }

    private fun PsiTypeParameterListOwner.typeParams(): TypeBounds<Nothing> =
        typeParameters.fold(persistentMapOf()) { m: TypeBounds<Nothing>, param ->
          // TODO: store the bounds, useful as finitization hints and possibly other things
          m.put(param.name!!, persistentSetOf())
        }

    private fun KtTypeParameterListOwner.typeParams(): TypeBounds<Nothing> =
        typeParameters.fold(persistentMapOf()) { m: TypeBounds<Nothing>, param ->
          // TODO: store the bounds, useful as finitization hints and possibly other things
          m.put(param.name!!, persistentSetOf())
        }

    private fun UMethod.getOverloadingStatus(): OverloadingStatus {
      val methodSource = sourcePsi as? KtFunction ?: return OverloadingStatus.None
      return if (
          methodSource.annotationEntries.any {
            it.shortName?.asString() == JvmStandardClassIds.JVM_OVERLOADS_FQ_NAME.shortName().asString()
          }
      ) {
        val firstMethod = (uastParent as? UClass)?.uastDeclarations?.find { it.sourcePsi === methodSource } as? UMethod
        when {
          firstMethod == null -> OverloadingStatus.None
          firstMethod.uastParameters.size == javaPsi.parameters.size -> OverloadingStatus.Primary(methodSource)
          else -> OverloadingStatus.Secondary(methodSource)
        }
      } else {
        OverloadingStatus.None
      }
    }

    internal fun loTechDebug() {
      println("Took $elapsed to index ${classes.size} classes and ${classes.asSequence().sumOf { (_, c) -> c.methods.size }} methods:")

      for ((id, c) in classes) {

        val zs = c.initEnvironment.types
        val methods = c.methods
        val paramBounds =
            when {
              zs.isEmpty() -> ""
              else ->
                  zs.asSequence()
                      .joinToString(
                          prefix = "<",
                          postfix = ">",
                          transform = { (x, b) -> showBound(x, b) },
                      )
            }
        println("  - $id$paramBounds (${methods.size}):")
        for ((mId, mSummary) in methods) {
          println("    + $mId: $mSummary")
        }
      }
    }

    private sealed interface OverloadingStatus {
      class Primary(val source: KtFunction) : OverloadingStatus

      class Secondary(val source: KtFunction) : OverloadingStatus

      object None : OverloadingStatus
    }
  }

  interface AnnotationParser<FX : Any> {
    /** Parse a list of [org.jetbrains.uast.UAnnotation] into internal effect representation */
    fun parseAnnotations(annotations: List<UAnnotation>): FX?

    fun parseMethodImmediateAnnotations(evaluator: JavaEvaluator, method: UMethod): FX?

    /** When both the method's annotation and its supers' are present */
    fun resolveAnnotations(
        context: JavaContext,
        targetAnn: EffectAnnotation.Explicit<FX>,
        bases: List<EffectAnnotation.Explicit<FX>>,
    ): EffectAnnotation.Explicit<FX>

    /** When the method doesn't have annotation, but its supers do */
    fun inheritAnnotations(
        evaluator: JavaEvaluator,
        bases: List<EffectAnnotation.Explicit<FX>>,
    ): EffectAnnotation.Implicit<FX>

    companion object {
      fun <FX : Any> AnnotationParser<FX>.parseMethodAnnotations(
          context: JavaContext,
          method: UMethod,
      ): EffectAnnotation<FX> {
        val evaluator = context.evaluator
        val bases = nearestBaseAnns(evaluator, method.javaPsi.findSuperMethods())
        return when (val targetAnn = parseMethodImmediateAnnotations(evaluator, method)) {
          null -> inheritAnnotations(evaluator, bases)
          else -> resolveAnnotations(context, EffectAnnotation.Explicit(targetAnn, method), bases)
        }
      }

      internal fun <FX : Any> AnnotationParser<FX>.nearestBaseAnns(
          evaluator: JavaEvaluator,
          baseMethods: Array<PsiMethod>,
      ): List<EffectAnnotation.Explicit<FX>> =
          baseMethods.flatMap { base ->
            val baseMethod = base.toUElement() as UMethod
            when (val baseAnn = parseMethodImmediateAnnotations(evaluator, baseMethod)) {
              null -> nearestBaseAnns(evaluator, base.findSuperMethods())
              else -> listOf(EffectAnnotation.Explicit(baseAnn, baseMethod))
            }
          }
    }
  }
}

internal fun UMethod.isKtProperty() =
    when (sourcePsi) {
      is KtProperty,
      is KtPropertyAccessor -> true
      else -> false
    }

internal data class LocalFun(val uast: ULambdaExpression, val sourcePsi: KtNamedFunction)

internal fun containerChain(fnUast: ULambdaExpression): Pair<ClassId.Local, MethodId>? {
  val methods =
      fnUast.withContainingElements
          .filter { it is UMethod || it is ULambdaExpression && (it.sourcePsi as? KtNamedFunction)?.name != null || it is UClass }
          .takeWhile { it !is UClass }
          .mapTo(mutableListOf()) {
            when (it) {
              is UMethod -> MethodId(it.javaPsi)
              is ULambdaExpression -> MethodId(it.sourcePsi as KtNamedFunction)
              else -> throw IllegalStateException()
            }
          }
          .reversed()
  if (methods.size < 2) {
    return null // TODO(b/438815669)
  }
  val selfId = methods.last()
  val classId =
      ClassId.Local(
          ClassId.of(fnUast.getContainingUClass()!!.javaPsi),
          methods.subList(0, methods.size - 1),
      )
  return classId to selfId
}

private fun <FX> Type<FX>.arrayDeepComponentTypeAndContext(): Pair<Type<FX>, (Type<FX>) -> Type<FX>> =
    when {
      this is Type.Application && this.constructor == ClassId.Array && this.args.isNotEmpty() -> {
        val (elem, ctx) = this.args.first().arrayDeepComponentTypeAndContext()
        elem to { Type.Application(ClassId.Array, listOf(ctx(it))) }
      }
      else -> this to { it }
    }
