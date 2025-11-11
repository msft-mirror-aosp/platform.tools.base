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

import com.android.tools.lint.checks.fx.analysis.EffectResult.Checking
import com.android.tools.lint.checks.fx.analysis.EffectResult.Inapplicable
import com.android.tools.lint.checks.fx.analysis.EffectResult.Inference
import com.android.tools.lint.checks.fx.analysis.EffectResult.Instantiation
import com.android.tools.lint.checks.fx.result.ClassId
import com.android.tools.lint.checks.fx.result.Constraint
import com.android.tools.lint.checks.fx.result.ConstraintFailure
import com.android.tools.lint.checks.fx.result.Effect
import com.android.tools.lint.checks.fx.result.EffectAnnotation
import com.android.tools.lint.checks.fx.result.Env
import com.android.tools.lint.checks.fx.result.Env.Companion.withVar
import com.android.tools.lint.checks.fx.result.Env.Companion.withVars
import com.android.tools.lint.checks.fx.result.Error
import com.android.tools.lint.checks.fx.result.ErrorSite
import com.android.tools.lint.checks.fx.result.Instantiable
import com.android.tools.lint.checks.fx.result.KtTypeReferenceAdapter
import com.android.tools.lint.checks.fx.result.MethodBody
import com.android.tools.lint.checks.fx.result.MethodId
import com.android.tools.lint.checks.fx.result.Point
import com.android.tools.lint.checks.fx.result.PsiClassAdapter
import com.android.tools.lint.checks.fx.result.PsiTypeAdapter
import com.android.tools.lint.checks.fx.result.Result
import com.android.tools.lint.checks.fx.result.ResultTable
import com.android.tools.lint.checks.fx.result.Type
import com.android.tools.lint.checks.fx.result.TypeBounds
import com.android.tools.lint.checks.fx.result.TypeEffectConstraintLattice
import com.android.tools.lint.checks.fx.result.at
import com.android.tools.lint.checks.fx.result.errorSetLattice
import com.android.tools.lint.checks.fx.result.isExtension
import com.android.tools.lint.checks.fx.result.isFinal
import com.android.tools.lint.checks.fx.result.isStatic
import com.android.tools.lint.checks.fx.result.renderAbbrev
import com.android.tools.lint.checks.fx.result.widen
import com.android.tools.lint.checks.fx.utils.DependentMonotone
import com.android.tools.lint.checks.fx.utils.EffectfulComputation
import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.checks.fx.utils.UnboundedSet
import com.android.tools.lint.checks.fx.utils.flatMapToPersistentSet
import com.android.tools.lint.checks.fx.utils.foldM
import com.android.tools.lint.checks.fx.utils.forM
import com.android.tools.lint.checks.fx.utils.joinedOver
import com.android.tools.lint.checks.fx.utils.lastM
import com.android.tools.lint.checks.fx.utils.mapM
import com.android.tools.lint.checks.fx.utils.possibilityLattice
import com.android.tools.lint.checks.fx.utils.pure
import com.android.tools.lint.checks.fx.utils.unionedWith
import com.android.tools.lint.client.api.LintClient
import com.android.tools.lint.detector.api.UastLintUtils.Companion.tryResolveUDeclaration
import com.android.tools.lint.detector.api.asCall
import com.android.tools.lint.detector.api.isImmutable
import com.android.tools.lint.detector.api.nameFromSource
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiIntersectionType
import com.intellij.psi.PsiLambdaParameterType
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import java.util.IdentityHashMap
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.elements.isAccessor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UArrayAccessExpression
import org.jetbrains.uast.UBinaryExpressionWithType
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UBreakExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UContinueExpression
import org.jetbrains.uast.UDeclarationsExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UExpressionList
import org.jetbrains.uast.UForEachExpression
import org.jetbrains.uast.UForExpression
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UJumpExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UMultiResolvable
import org.jetbrains.uast.UObjectLiteralExpression
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.USuperExpression
import org.jetbrains.uast.USwitchClauseExpressionWithBody
import org.jetbrains.uast.USwitchExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.UThrowExpression
import org.jetbrains.uast.UTryExpression
import org.jetbrains.uast.UUnaryExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UWhileExpression
import org.jetbrains.uast.UYieldExpression
import org.jetbrains.uast.UastBinaryExpressionWithTypeKind
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.UastEmptyExpression
import org.jetbrains.uast.UastPostfixOperator
import org.jetbrains.uast.UastPrefixOperator
import org.jetbrains.uast.UastSpecialExpressionKind
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getParameterForArgument
import org.jetbrains.uast.java.isJava
import org.jetbrains.uast.resolveToUElement
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.tryResolve
import org.jetbrains.uast.tryResolveNamed
import org.jetbrains.uast.util.isConstructorCall

/**
 * An [Analysis] of a [module] is a [Monotone] on summaries, as in "given a hypothetical
 * bootstrapping summary of the [module], what else must be true before the summary is sound?". The
 * summary of interest is the [Analysis]'s least fix point. In addition, [assumptions] has the
 * assumed summaries of bindings that occur free in [module] (i.e. they are not defined in [module],
 * but [module] may use those).
 *
 * The analysis is parameterized by a [concreteEffect] lattice, and assumes that effects are to be
 * joined regardless of whether they are combined sequentially or from different branches. Some
 * examples of useful [concreteEffect] in practice are "thread requirements" (e.g. for detecting
 * conflicting thread requirements), "constructor calls" (e.g. for detecting subclass initialization
 * from superclass), "all function calls" (e.g. for reconstructing a modular, polymorphic call
 * graph).
 */
internal open class Analysis<FX : Any>(
  private val module: Module<FX>,
  private val concreteEffect: Lattice<FX>,
  private val assumptions: ResultTable<FX> = ResultTable.of(persistentMapOf()),
  private val log: (String) -> Unit = { /* ignore */ },
) : DependentMonotone<Point<FX>, Ans<FX>> {

  /**
   * Default effect for constructs we don't understand.
   *
   * Analyses on safety properties that need to be a sound over-approximation resort to `⊤`, while
   * ones that aim to be helpful bug-finders without overwhelming users with technically correct but
   * practically pessimistic and tedious errors resort to `⊥`. Note that the latter means both false
   * positives and negatives are possible. This is also the default, because Lint tends to be more
   * of a useful bug-finder than a rigorous verifier.
   */
  protected open val <T> Lattice<T>.unsure: T
    get() = bottom

  protected open val <T> Lattice<T>.unsureResult: Result<Type<FX>, T>
    get() = emptyResult

  private val typeEffectConstraintLattice = TypeEffectConstraintLattice(concreteEffect)
  private val typeLattice = typeEffectConstraintLattice.typeLattice
  private val effectLattice = typeEffectConstraintLattice.effectLattice
  private val constraintLattice = typeEffectConstraintLattice.constraintLattice

  private val fxInstantiationLattice =
    Lattice.product(
      ::Instantiation,
      Instantiation<FX>::result,
      Instantiation<FX>::errors,
      effectLattice,
      possibilityLattice(),
    )

  private val fxInferenceLattice =
    Lattice.product(
      ::Inference,
      Inference<FX>::result,
      Inference<FX>::errors,
      effectLattice,
      errorSetLattice(),
    )

  private val fxCheckingLattice =
    Lattice.product(
      ::Checking,
      Checking<FX>::result,
      Checking<FX>::errors,
      constraintLattice,
      errorSetLattice(),
    )

  private val instantiationLattice = Result.domain(typeLattice, fxInstantiationLattice)
  private val inferenceLattice = Result.domain(typeLattice, fxInferenceLattice)
  private val checkingLattice = Result.domain(typeLattice, fxCheckingLattice)
  private val inapplicableLattice = Result.domain(typeLattice, Inapplicable)

  private val unboundedInferenceMode = // reused common value
    Mode.Inference(listOf(), concreteEffect, fxInferenceLattice, constraintLattice)

  private fun inferenceMode(ann: EffectAnnotation.Implicit<FX>) =
    when {
      ann.nearestBaseAnnotations.isEmpty() -> unboundedInferenceMode
      else ->
        Mode.Inference(
          ann.nearestBaseAnnotations,
          concreteEffect,
          fxInferenceLattice,
          constraintLattice,
        )
    }

  private fun checkingMode(ann: EffectAnnotation.Explicit<FX>) =
    Mode.Checking(ann.annotated, concreteEffect, fxCheckingLattice, constraintLattice)

  // Make sure this is in sync with `invoke` below
  override fun latticeAt(point: Point<FX>): Lattice<Ans<FX>> {
    return when (point) {
      is Type.MethodRef -> {
        val method = module[point] ?: return checkingLattice as Lattice<Ans<FX>>
        return when (method.status) {
          is MethodBody.Status.Abstract -> inapplicableLattice as Lattice<Ans<FX>>
          is MethodBody.Status.BasicConstructor -> inferenceLattice as Lattice<Ans<FX>>
          is MethodBody.Status.ForChecking -> checkingLattice as Lattice<Ans<FX>>
          is MethodBody.Status.ForInference -> inferenceLattice as Lattice<Ans<FX>>
        }
      }
      is Point.Instantiation -> instantiationLattice as Lattice<Ans<FX>>
    }
  }

  /**
   * We're encoding multiple mutually recursive functions into one [invoke]:
   * - inference: [Type.MethodRef] -> [Type] × [EffectResult.Inference]
   *     * Given unannotated method, infer polymorphic type+effect from its definition
   * - checking: [Type.MethodRef] -> [Type] × [EffectResult.Checking]
   *     * Given annotated method, ensure its definition behaves accordingly
   * - instantiation: [Point.Instantiation] -> [Type] × [EffectResult.Instantiation]
   *     * Given a method and arguments, instantiation the method's summary for context-sensitive
   *       type+effect
   */
  override fun invoke(rec: (Point<FX>) -> Ans<FX>, point: Point<FX>): Ans<FX> =
    when (point) {
      is Type.MethodRef -> {
        val method = module[point] ?: return Result(Type.None, fxCheckingLattice.unsure)

        fun <R : EffectResult.Eval<FX>> Mode<FX, R>.eval(body: UExpression): Result<Type<FX>, R> =
          eval(rec, method.initEnvironment, body, persistentListOf(ReturnRecord(body.uastParent!!)))

        when (val status = method.status) {
          is MethodBody.Status.Abstract -> Result(method.returnTypeAnnotation, Inapplicable)
          is MethodBody.Status.BasicConstructor ->
            unboundedInferenceMode.pure(method.returnTypeAnnotation)
          is MethodBody.Status.ForChecking ->
            with(checkingMode(status.upperBound)) {
              when (val body = status.body) {
                null -> pure(method.returnTypeAnnotation)
                else -> checkingLattice.catchError { eval(body) }
              }
            }
          is MethodBody.Status.ForInference ->
            inferenceLattice.catchError { inferenceMode(status.base).eval(status.body) }
        }
      }
      is Point.Instantiation ->
        instantiationLattice.catchError { apply(rec, point.method, point.args) }
    }

  /** Run action with errors suppressed in production, to avoid bringing down all of Lint */
  private fun <T> Lattice<T>.catchError(run: () -> T): T =
    try {
      run()
    } catch (e: Throwable) {
      if (LintClient.isUnitTest) {
        throw e
      } else {
        log("${e.javaClass.simpleName}: ${e.message}")
        unsure
      }
    }

  /**
   * Mode-polymorphic analysis of [body] (i.e. either checking or inference)
   *
   * Assuming an oracle [rec] that knows existing summaries and their instantiations, [eval] is
   * implemented as a structural recursion on the expression.
   *
   * Most complexities of this function are from manipulating UAST, which will eventually be cleaned
   * up.
   */
  private fun <R : EffectResult.Eval<FX>> Mode<FX, R>.eval(
    rec: (Point<FX>) -> Ans<FX>,
    initEnv: Env<FX>,
    body: UExpression,
    returns: PersistentList<ReturnRecord<FX>>,
  ): Result<Type<FX>, R> {
    val typeParams = initEnv.types.keys
    var env = initEnv

    val target = returns.last()

    val targetName =
      when (val target = target.target) {
        is UMethod -> target.name
        else -> target.toString()
      }

    fun translate(t: PsiType) = PsiTypeAdapter.translate(typeParams, t)
    fun translate(c: UClass) = PsiClassAdapter.translate(typeParams, c.javaPsi)

    fun getType(e: UExpression): Type<Nothing> =
      when (val t = e.getExpressionType()) {
        null -> Type.None.also { log("WARNING: Can't get type of ${e.renderAbbrev()}") }
        else -> translate(t)
      }

    /**
     * When there is an expression/feature we don't know, we return the type from
     * [UExpression.getExpressionType], and assume [unsure] effect.
     */
    fun giveUp(e: UExpression, msg: String): Result<Type<FX>, R> {
      log("WARNING: $msg")
      return Result(getType(e), unsure)
    }

    fun UThisExpression.type(): Type<FX> {
      val cl = tryResolve().toUElement() as? UClass
      return when {
        // opt: if the class is final, no need to make result parametric
        cl != null && cl.isFinal -> translate(cl)
        isJava(lang) -> {
          val cl = getContainingUClass()!!
          val t = translate(cl) as Type.Application
          when {
            cl.isFinal -> t
            else -> label?.let(env::receiver) ?: env.receiver(t.constructor) ?: t
          }
        }
        else ->
          tryResolveNamed()?.name?.let(env::varAt)
            ?: label?.let(env::receiver)
            ?: env.receiver(ClassId.of(getContainingUClass()!!.javaPsi))
            ?: getType(this).also { log("WARNING: Don't know what `this` is in `$targetName`") }
      }
    }

    fun <E : UExpression> E.asName(name: (E) -> String): Result<Type<FX>, R> {
      val x = name(this)
      val t = env.varAt(x)
      if (t != null) return pure(t)
      return giveUp(this, "Don't know what `$x` means in `${target.target.renderAbbrev()}`")
    }

    val cache = IdentityHashMap<UExpression, Result<Type<FX>, R>>()

    fun loop(e: UExpression): Result<Type<FX>, R> {

      /** Analyze local sub-expression that might be shared and reusable */
      fun loopCached(e: UExpression) = cache.getOrPut(e) { loop(e) }

      fun callMethod(
        receiver: UExpression?,
        method: PsiMethod,
        args: List<UExpression>,
      ): Result<Type<FX>, R> {
        fun implicitThis() =
          e.getContainingUClass()?.javaPsi?.let { PsiClassAdapter.translate(typeParams, it) }!!

        val virRecvAns: Result<Type<FX>, R>? =
          when {
            method.isStatic() -> null
            method.isConstructor -> null
            receiver != null -> loopCached(receiver) // TODO nope. See below
            else -> pure(env.receiver(ClassId.of(method.containingClass!!)) ?: implicitThis())
          }

        val extRecvAns: Result<Type<FX>, R>? =
          when {
            !method.isExtension() -> null
            receiver != null -> loopCached(receiver) // TODO nope. See above
            else -> pure(env.innermostExtensionReceiver() ?: implicitThis())
          }

        val (restTypes, restFx) = mapM(::loopCached, args)

        return when {
          // virtual extension
          // TODO this isn't general. Will run into problems with multiple implcit `this`s.
          virRecvAns != null && extRecvAns != null -> {
            val (virRecvType, virRecvFx) = virRecvAns
            val (extRecvType, extRecvFx) = extRecvAns
            val (appType, appFx) =
              when {
                method.isFinal() ->
                  rec[Type.MethodRef(method), listOf(virRecvType, extRecvType) + restTypes]
                else ->
                  invokeVirtual(rec, virRecvType, MethodId(method), listOf(extRecvType) + restTypes)
              }
            Result(appType, virRecvFx join extRecvFx join restFx join onInvocationEffect(e, appFx))
          }
          // plain virtual
          virRecvAns != null -> {
            val (virRecvType, virRecvFx) = virRecvAns
            val (appType, appFx) =
              when {
                method.isFinal() -> rec[Type.MethodRef(method), listOf(virRecvType) + restTypes]
                else -> invokeVirtual(rec, virRecvType, MethodId(method), restTypes)
              }
            Result(appType, virRecvFx join restFx join onInvocationEffect(e, appFx))
          }
          // static extension
          extRecvAns != null -> {
            val (extRecvType, extRecvFx) = extRecvAns
            val (appType, appFx) = rec[Type.MethodRef(method), listOf(extRecvType) + restTypes]
            Result(appType, extRecvFx join restFx join onInvocationEffect(e, appFx))
          }
          // plain static
          else -> {
            val (appType, appFx) = rec[Type.MethodRef(method), restTypes]
            Result(appType, restFx join onInvocationEffect(e, appFx))
          }
        }
      }

      fun callLambda(
        receiver: UExpression?,
        method: ULambdaExpression,
        args: List<UExpression>,
      ): Result<Type<FX>, R> {

        val methodRef =
          containerChain(method)?.let { (c, m) -> Type.MethodRef(c, m) }
            ?: method.nameFromSource?.let { env.funAt(it) { it.method.isCompatible(args) } }
            ?: return unsureResult.also {
              log("Warning: Don't know what `${method.nameFromSource}` is in `$targetName`")
            }

        fun KtNamedFunction.implicitThis(): Result<Type<FX>, R> {
          val t = KtTypeReferenceAdapter.translate(typeParams, typeReference) as Type.Application
          return pure(env.receiver(t.constructor)!!)
        }

        val receiver =
          when (val fn = method.sourcePsi) {
            is KtNamedFunction ->
              when {
                !fn.isExtensionDeclaration() -> null
                receiver != null -> {
                  val res = loop(receiver)
                  when (res.value) {
                    methodRef -> fn.implicitThis()
                    else -> res
                  }
                }
                else -> fn.implicitThis()
              }
            else -> null
          }

        val (argTypes, argsFx) = mapM(::loop, args)

        return when {
          receiver == null -> {
            val (appType, appFx) = rec[methodRef, argTypes]
            Result(appType, argsFx join onInvocationEffect(e, appFx))
          }
          else -> {
            val (recvType, recvFx) = receiver
            val (appType, appFx) = rec[methodRef, listOf(recvType) + argTypes]
            Result(appType, recvFx join argsFx join onInvocationEffect(e, appFx))
          }
        }
      }

      fun call(call: UCallExpression): Result<Type<FX>, R> {
        val method = call.resolveToUElement()
        return when {
          method is UMethod ->
            callMethod(
              call.callReceiver(),
              method.javaPsi,
              completeArguments(typeParams, call, method, UMethod::uastParameters),
            )
          method is ULambdaExpression ->
            callLambda(
              call.callReceiver(),
              method,
              completeArguments(typeParams, call, method, ULambdaExpression::valueParameters),
            )
          call.isConstructorCall() -> pure(getType(call))
          else -> {
            fun fail() =
              giveUp(
                e,
                "Handle ${e::class.java.simpleName} ${e.renderAbbrev()} with receiver `${call.receiver?.renderAbbrev()}`, resolved method `$method`",
              )

            val recv = call.receiver ?: call.callReceiver() ?: return fail()
            when (val recvDecl = recv.tryResolveUDeclaration()) {
              is UVariable -> {
                val rhs = recvDecl.uastInitializer as? ULambdaExpression ?: return fail()
                callLambda(
                  call.callReceiver(),
                  rhs,
                  completeArguments(typeParams, call, rhs, ULambdaExpression::valueParameters),
                )
              }
              // TODO shot in the dark below
              else -> {
                val methodName = call.methodName ?: call.methodIdentifier?.name
                val (recvType, recvFx) = loop(recv)
                val (restTypes, restFx) = mapM(::loop, call.valueArguments)
                when {
                  // Applying extension method whose code we don't have?
                  recvType is Type.Application -> {
                    val (t, _) =
                      giveUp(
                        e,
                        "Applying extension method whose code we don't have at ${e.uastParent?.renderAbbrev()}",
                      )
                    Result(t, recvFx join restFx)
                  }
                  else -> {
                    fun onNotFound(exc: Module.MethodLookupException? = null) =
                      Result(getType(e), fxInstantiationLattice.unsure).also {
                        log(
                          when (exc) {
                            null,
                            is Module.MethodLookupException.NotFound ->
                              "WARNING: Don't know what method `$methodName` is in `${e.uastParent?.renderAbbrev()}` in `$targetName`"
                            is Module.MethodLookupException.Ambiguous ->
                              "ERROR: ${exc.candidates.size} methods found for name ${exc.name}"
                          }
                        )
                      }

                    val (appType, appFx) =
                      when (methodName) {
                        null -> onNotFound()
                        else -> invokeWildGuess(rec, recvType, methodName, restTypes, ::onNotFound)
                      }

                    Result(appType, recvFx join restFx join onInvocationEffect(e, appFx))
                  }
                }
              }
            }
          }
        }
      }

      return when (e) {
        is OpaqueConstant -> pure(e.type)
        is ULiteralExpression -> pure(Type.ofLiteral(e.value))
        is UThisExpression -> pure(e.type())
        is USuperExpression -> pure(getType(e))

        // TODO: is this just the Java's `yield` in `switch`?
        is UYieldExpression -> e.expression?.let(::loop) ?: unsureResult
        is USimpleNameReferenceExpression ->
          when (val target = e.resolve()) {
            is KtLightMethod ->
              when {
                target.isAccessor(getter = true) -> callMethod(null, target, listOf())
                else -> e.asName(USimpleNameReferenceExpression::identifier)
              }
            else -> e.asName(USimpleNameReferenceExpression::identifier)
          }
        is UQualifiedReferenceExpression -> {
          val target = e.resolve()
          when {
            target is PsiField ->
              Result(module[target] ?: translate(target.type), loop(e.receiver).effect)
            target is KtLightMethod && target.isAccessor(getter = true) ->
              callMethod(e.receiver, target, listOf())
            e.selector is UCallExpression -> loop(e.selector)
            else -> giveUp(e, "Handle ${e.renderAbbrev()} of `${e::class.java.simpleName}`")
          }
        }
        is UCallableReferenceExpression -> {
          fun fail() =
            unsureResult.also {
              log("ERROR: Don't know how to parse method reference ${e.renderAbbrev()}")
            }
          when (val method = e.resolve()) {
            is PsiMethod -> {
              val ref = Type.MethodRef(method)
              val (refType, refFx) =
                when {
                  method.isConstructor -> pure(ref)
                  (e.qualifierExpression as? UReferenceExpression)?.resolve() is PsiClass &&
                    method.containingClass.toUElement()?.sourcePsi !is KtObjectDeclaration ->
                    pure(ref)
                  !method.isStatic() || method.isExtension() -> {
                    fun fromExplicit() =
                      e.qualifierExpression?.let { recvExpr ->
                        val (recvType, recvFx) = loop(recvExpr)
                        Result(Type.SpecializedMethodRef(recvType, ref), recvFx)
                      }

                    fun fromImplicit(): Result<Type.SpecializedMethodRef<FX>, R>? {
                      val t = e.qualifierType?.let(::translate) as? Type.Application ?: return null
                      val recvType = env.receiver(t.constructor) ?: return null
                      return pure(Type.SpecializedMethodRef(recvType, ref))
                    }

                    fun default() = pure(Type.SpecializedMethodRef(Type.None, ref)) // TODO

                    fromExplicit() ?: fromImplicit() ?: default()
                  }

                  // regular static
                  else -> pure(ref)
                }
              val errorsAgainstFunIntf: PersistentSet<Error<FX>> = run {
                val funIntf = resolvePossiblyAnnotatedSuperClass(e) ?: return@run persistentSetOf()
                val intfMethodStatus = module.getSAMStatus(funIntf)
                val implStatus = module[ref] ?: return@run persistentSetOf()
                guardImplAgainstStatus(rec, e, intfMethodStatus, ref, implStatus)
              }
              Result(refType, refFx + errorsAgainstFunIntf)
            }
            is PsiVariable -> env.funAt(method.name!!)?.let(::pure) ?: fail()
            else -> fail()
          }
        }
        is UReferenceExpression -> e.asName { it.resolvedName ?: throw UnresolvedNameException(e) }
        is UBlockExpression -> lastM(::loop, e.expressions) ?: unitResult
        is UExpressionList ->
          when (val r = lastM(::loop, e.expressions)) {
            null ->
              when (e.kind) {
                UastSpecialExpressionKind.VARARGS -> pure(Type.EmptyArray)
                else -> unitResult
              }
            else -> {
              val (t, fx) = r
              when (e.kind) {
                UastSpecialExpressionKind.VARARGS -> Result(Type.Ellipsis(t), fx)
                else -> r
              }
            }
          }
        is RestArg -> {
          val (t, fx) = lastM(::loop, e.elems) ?: return pure(Type.EmptyArray)
          Result(Type.Ellipsis(t), fx)
        }
        is UParenthesizedExpression -> loop(e.expression)
        is UBreakExpression,
        is UContinueExpression,
        is UastEmptyExpression -> emptyResult
        is UReturnExpression -> {
          // Compute the returned value
          val (t, fx) =
            when (val returnExpr = e.returnExpression) {
              null -> unitResult
              else -> loop(returnExpr)
            }
          // Accumulate the returned value at the target
          when (val jumpTarget = e.jumpTarget) {
            null -> target.accumulate(t)
            else ->
              returns.find { it.target == jumpTarget }?.accumulate(t)
                ?: throw UnresolvedReturnTargetException(e, returns)
          }
          // `return` clause, as an expression, computes no value
          Result(Type.None, fx)
        }
        is ULambdaExpression -> {
          // TODO best effort resolving to right super-interface
          val funIntf = resolvePossiblyAnnotatedSuperClass(e)
          val mode =
            when (funIntf) {
              null -> unboundedInferenceMode
              else ->
                when (val status = module.getSAMStatus(funIntf)) {
                  is MethodBody.Status.ForChecking ->
                    inferenceMode(EffectAnnotation.Implicit(listOf(status.upperBound)))
                  is MethodBody.Status.ForInference,
                  is MethodBody.Status.Abstract -> unboundedInferenceMode
                  is MethodBody.Status.BasicConstructor -> throw IllegalStateException()
                }
            }
          val lambdaParams: List<Pair<String, Type<FX>>> =
            e.parameters.map {
              val param = it.javaPsi as PsiParameter
              val paramType =
                when (val t = param.type) {
                  // TODO
                  is PsiLambdaParameterType,
                  is PsiIntersectionType -> typeLattice.top
                  else -> translate(t)
                }
              param.name to paramType
            }
          val (t, fx) =
            mode.eval(rec, env.withVars<FX>(lambdaParams), e.body, returns.add(ReturnRecord(e)))
          Result(
            Type.Lambda(lambdaParams.map { (_, t) -> t }, Result(t, fx.result), funIntf),
            bottom + fx.errors,
          )
        }
        is UDeclarationsExpression ->
          lastM(
            step@{ dec ->
              when (dec) {
                is ULocalVariable -> {
                  val decPsi = dec.javaPsi as PsiLocalVariable
                  val rhs = dec.uastInitializer?.let(::loop)
                  val rhsType =
                    // For immutable local bindings, we bypass even the user-declared type to use
                    // the inferred more precise type.
                    when {
                      rhs != null &&
                        rhs.value is Type.Lambda /* TODO generalize */ &&
                        dec.isImmutable() -> rhs.value
                      else -> translate(decPsi.type)
                    }
                  // We update the local environment imperatively instead of accumulating it
                  // functionally, because later declarations need to see updates by earlier
                  // declarations.
                  env = env.withVar(decPsi.name, rhsType)
                  rhs ?: emptyResult
                }
                is UVariable -> unitResult // already added to environment during indexing
                else -> {
                  log("TODO: Handle ${dec::class.java.simpleName}: ${dec.asSourceString()}")
                  unitResult
                }
              }
            },
            e.declarations,
          ) ?: unitResult
        is UUnaryExpression -> {
          fun default() = Result(getType(e), loop(e.operand).effect)
          when (e.operator) {
            UastPrefixOperator.INC,
            UastPrefixOperator.DEC,
            UastPostfixOperator.INC,
            UastPostfixOperator.DEC ->
              when (e) {
                is UMultiResolvable -> {
                  val operand = e.operand
                  val fx =
                    e.multiResolve().fold(bottom) { acc, res ->
                      val method = res.element as? PsiMethod ?: return@fold acc
                      val (receiver, indices) = operand.lhsReceiverAndIndices() ?: return default()
                      when {
                        // TODO
                        method.name.startsWith("get") ->
                          callMethod(receiver, method, indices).effect
                        // TODO
                        method.name.startsWith("set") ->
                          callMethod(receiver, method, indices + operand).effect
                        // TODO assuming inc/dec operator
                        method.parameterList.parametersCount == 1 ->
                          callMethod(operand, method, listOf()).effect
                        else ->
                          throw IllegalStateException(
                            "Got method `${method.name}` during ${e.renderAbbrev()}"
                          )
                      }
                    }
                  Result(e.getExpressionType()?.let(::translate) ?: Type.Int, fx)
                }
                else -> default()
              }
            else -> default()
          }
        }
        is UPolyadicExpression -> {
          fun defaultUnit() = Result(Type.Unit, forM(::loop, e.operands))
          when (e.operator) {
            // Special cases
            UastBinaryOperator.ASSIGN -> {
              val (lhs, rhs) = e.operands
              val target = lhs.tryResolve()
              when {
                target is KtLightMethod && target.name.startsWith("set") -> {
                  val (receiver, indices) = lhs.lhsReceiverAndIndices() ?: return defaultUnit()
                  callMethod(receiver, target, indices + rhs)
                }
                else -> defaultUnit()
              }
            }
            UastBinaryOperator.PLUS_ASSIGN,
            UastBinaryOperator.MINUS_ASSIGN,
            UastBinaryOperator.MULTIPLY_ASSIGN,
            UastBinaryOperator.DIVIDE_ASSIGN,
            UastBinaryOperator.REMAINDER_ASSIGN,
            UastBinaryOperator.AND_ASSIGN,
            UastBinaryOperator.OR_ASSIGN,
            UastBinaryOperator.XOR_ASSIGN,
            UastBinaryOperator.SHIFT_LEFT_ASSIGN,
            UastBinaryOperator.SHIFT_RIGHT_ASSIGN,
            UastBinaryOperator.UNSIGNED_SHIFT_RIGHT_ASSIGN -> {
              val (lhs, rhs) = e.operands
              when (e) {
                is UMultiResolvable -> {
                  val (receiver, indices) = lhs.lhsReceiverAndIndices() ?: return defaultUnit()
                  val fx =
                    e.multiResolve().fold(bottom) { acc, res ->
                      val method = res.element as? PsiMethod ?: return@fold acc
                      when {
                        // TODO
                        method.name.startsWith("get") ->
                          callMethod(receiver, method, indices).effect
                        // TODO
                        method.name.startsWith("set") ->
                          callMethod(receiver, method, indices + rhs).effect
                        // TODO assuming the arith op
                        method.parameterList.parametersCount == 2 ->
                          callMethod(lhs, method, listOf(rhs)).effect
                        method.parameterList.parametersCount == 1 ->
                          callMethod(lhs, method, listOf(rhs)).effect
                        else ->
                          throw IllegalStateException(
                            "Got method `${method.name}` during ${e.renderAbbrev()}"
                          )
                      }
                    }
                  Result(Type.Unit, fx)
                }
                else -> defaultUnit()
              }
            }
            // Call-by-value standard operators
            else -> {
              val fx = forM(::loop, e.operands)
              val t =
                when (e.operator) {
                  is UastBinaryOperator.LogicalOperator,
                  is UastBinaryOperator.ComparisonOperator,
                  is UastBinaryOperator.BitwiseOperator -> Type.Boolean
                  else -> getType(e)
                }
              Result(t, fx)
            }
          }
        }
        is UBinaryExpressionWithType ->
          when (val op = e.operationKind) {
            is UastBinaryExpressionWithTypeKind.InstanceCheck ->
              Result(Type.Boolean, loop(e.operand).effect)
            is UastBinaryExpressionWithTypeKind.TypeCast ->
              Result(translate(e.type), loop(e.operand).effect)
            else -> giveUp(e, "Handle ${e.renderAbbrev()} with operation $op")
          }
        is UIfExpression -> {
          val (_, condFx) = loop(e.condition)
          val (t, branchesFx) = joinM(::loop, listOfNotNull(e.thenExpression, e.elseExpression))
          Result(t, merge(e.condition, condFx, branchesFx))
        }
        is USwitchExpression -> {
          val condition = e.expression
          val (_, conditionFx) = condition?.let(::loop) ?: emptyResult
          val (t, bodyFx) = joinM(::loop, e.body.expressions)
          Result(
            t,
            if (condition != null) merge(condition, conditionFx, bodyFx)
            else conditionFx join bodyFx,
          )
        }
        is USwitchClauseExpressionWithBody -> lastM(::loop, e.body.expressions) ?: unitResult
        is UWhileExpression -> Result(Type.Unit, forM(::loop, listOf(e.condition, e.body)))
        is UForExpression ->
          Result(
            Type.Unit,
            forM(::loop, listOfNotNull(e.declaration, e.condition, e.update, e.body)),
          )
        is UForEachExpression -> {
          val (t1, fx1) = loop(e.iteratedValue)
          val bodyEnv =
            when (val x = e.parameter?.javaPsi as? PsiParameter) {
              null -> env
              else ->
                when {
                  t1 is Type.Application && t1.args.firstOrNull() != null ->
                    env.withVar(x.name, t1.args.first())
                  t1 is Type.Ellipsis -> env.withVar(x.name, t1.element)
                  else -> env // TODO
                }
            }
          val (_, fx2) = eval(rec, bodyEnv, e.body, returns)
          Result(Type.Unit, fx1 join fx2)
        }
        is UObjectLiteralExpression -> {
          val typeArgs = typeParams.map { Type.Sym.Param(it) }
          val typeRef = ClassId.of(e.declaration.javaPsi)
          val type = Type.Application(typeRef, typeArgs)
          val errorAgainstFunIntf: PersistentSet<Error<FX>> = run {
            val funIntf = resolvePossiblyAnnotatedSuperClass(e) ?: return@run persistentSetOf()
            guardImplAgainstIntf(rec, e, funIntf, typeRef)
          }
          Result(type, bottom + errorAgainstFunIntf)
        }
        is UThrowExpression -> Result(Type.None, loop(e.thrownExpression).effect)
        is UTryExpression -> {
          val (tryType, tryFx) = loop(e.tryClause)
          val (catchTypes, catchFx) = mapM({ loop(it.body) }, e.catchClauses)
          val finalFx = e.finallyClause?.let(::loop)?.effect ?: bottom
          val t = with(typeLattice) { tryType join catchTypes.joinedOver { it } }
          Result(t, tryFx join catchFx join finalFx)
        }
        is UArrayAccessExpression ->
          when (val call = e.asCall()) {
            null -> {
              val recFx = loop(e.receiver).effect
              val indFx = forM(::loop, e.indices)
              Result(getType(e), recFx join indFx)
            }
            else -> call(call)
          }
        is UCallExpression -> call(e)
        else -> giveUp(e, "Handle ${e::class.java.simpleName}: `${e.asSourceString()}`")
      }
    }

    return when (body) {
      is UBlockExpression -> {
        val fx = forM(::loop, body.expressions)
        val lastStm = body.expressions.lastOrNull()
        val res =
          when {
            (body.uastParent as? UMethod)?.isConstructor == true ->
              translate((body.uastParent as UMethod).getContainingUClass()!!)
            body.expressions.any { it is UReturnExpression } -> target.returns
            // TODO: extract `Nothing` type. Anything else is `Unit`.
            target.returns == Type.None && (lastStm == null || lastStm !is UThrowExpression) ->
              Type.Unit
            else -> target.returns
          }
        Result(res, fx)
      }
      else -> loop(body)
    }
  }

  private class ReturnRecord<FX> private constructor(val target: UElement, var returns: Type<FX>) {
    constructor(
      destination: UElement
    ) : this((destination as? UJumpExpression)?.jumpTarget ?: destination, Type.None)

    override fun toString() = "${target.renderAbbrev()} ↦ $returns"
  }

  /** Run [step] on each of [targets] for the joined type and effect [R] */
  private fun <X : ErrorSite, R : EffectResult.Eval<FX>> Mode<FX, R>.joinM(
    step: (X) -> Result<Type<FX>, R>,
    targets: List<X>,
  ) = foldM(typeLattice.bottom, typeLattice::joinOf, step, targets)

  private fun ReturnRecord<FX>.accumulate(t: Type<FX>) {
    returns = typeLattice.joinOf(returns, t)
  }

  private val placeholderInstantiation: InstAns<FX> =
    Result(typeLattice.top, /* TODO(b/390196415) */ fxInstantiationLattice.unsure)

  /**
   * Assuming an oracle [rec] that knows about existing summaries and their instantiations, either
   * instantiate given statically known receiver, or return a symbolic invocation.
   */
  private fun invokeVirtual(
    rec: (Point<FX>) -> Ans<FX>,
    receiver: Type<FX>,
    method: MethodId,
    args: List<Type<FX>>,
  ): InstAns<FX> =
    when (receiver) {
      // TODO pass class type arguments too
      is Type.Application ->
        rec[
          Type.MethodRef(receiver.constructor, method),
          listOf(receiver) + args,
        ]
      is Type.Lambda ->
        if (receiver.params.size == args.size) rec[receiver, listOf(receiver) + args]
        else instantiationLattice.bottom
      is Type.MethodRef -> rec[receiver, args]
      is Type.SpecializedMethodRef -> rec[receiver.ref, listOf(receiver.receiver) + args]
      is Type.Sym.Param,
      is Type.Sym.This,
      is Type.Sym.Invoke,
      is Type.Sym.Fix,
      is Type.Sym.Rec -> {
        val sym = Type.Sym.Invoke(receiver, method, args)
        Result(sym, Instantiation(Effect(concreteEffect.bottom, persistentSetOf(sym))))
      }
      is Type.Union ->
        receiver.cases.joinedOver(instantiationLattice) { invokeVirtual(rec, it, method, args) }
      is Type.WildCard -> placeholderInstantiation
      // TODO("Look at super methods to apply $receiver.$method(${args.joinToString()})")
      is Type.Ellipsis -> fxInstantiationLattice.pure(Type.None)
    }

  // TODO(b/417750068)
  private fun invokeWildGuess(
    rec: (Point<FX>) -> Ans<FX>,
    receiver: Type<FX>,
    methodName: String,
    args: List<Type<FX>>,
    notFound: (Module.MethodLookupException) -> InstAns<FX>,
  ): InstAns<FX> =
    when (receiver) {
      // TODO pass class type arguments too
      is Type.Application ->
        try {
          rec[
            module.findMethodByName(methodName, receiver.constructor, args),
            listOf(receiver) + args]
        } catch (e: Module.MethodLookupException) {
          notFound(e)
        }
      is Type.Lambda -> rec[receiver, listOf(receiver) + args]
      is Type.MethodRef -> rec[receiver, args]
      is Type.SpecializedMethodRef -> rec[receiver.ref, listOf(receiver.receiver) + args]
      is Type.Union ->
        receiver.cases.joinedOver(instantiationLattice) {
          invokeWildGuess(rec, it, methodName, args, notFound)
        }
      is Type.Sym,
      is Type.WildCard -> notFound(Module.MethodLookupException.NotFound(methodName))
      is Type.Ellipsis ->
        throw IllegalArgumentException("Unexpected invocation $receiver.$methodName($args)")
      is Type.Sym.Rec -> throw IllegalStateException("Unbound recursive variable $receiver")
    }

  /** Instantiate [method]'s summaries at [args] */
  private fun apply(
    rec: (Point<FX>) -> Ans<FX>,
    method: Instantiable<FX>,
    args: List<Type<FX>>,
  ): InstAns<FX> =
    when (method) {
      is Type.MethodRef ->
        when (val assumption = assumptions[method]) {
          null ->
            when (val methodDefn = module[method]) {
              null ->
                instantiationLattice
                  .bottom // If method not found, we ASSUME it's from a spurious call
              else -> {
                val (t, methodEffectResult) = rec[method]
                val methodFx: Effect<FX> =
                  when (methodEffectResult) {
                    is Checking<FX> ->
                      Effect(
                        concrete =
                          (methodDefn.status as MethodBody.Status.ForChecking).upperBound.annotated,
                        constraint = methodEffectResult.result,
                      )
                    is Inference<FX> -> methodEffectResult.result
                    is Inapplicable -> effectLattice.bottom
                  // throw IllegalStateException("Applying abstract method $method")
                  }
                apply(rec, methodDefn.initEnvironment.types, methodDefn.domains, t, methodFx, args)
              }
            }
          else ->
            apply(
              rec,
              assumption.typeBounds,
              assumption.domains,
              assumption.range,
              assumption.effect,
              args,
            )
        }
      is Type.Lambda -> {
        val (xs, body, intf) = method // TODO make sure applying the right message
        val (t, fx) = body
        // If the functional interface is something like `suspend () -> _`, `args` may have the
        // explicit one for the kontinuation
        // The first argument is the `lambda` itself, which we don't need
        val truncatedArgs = args.subList(1, 1 + xs.size)
        apply(rec, persistentMapOf(), xs, t, fx, truncatedArgs)
      }
    }

  /** Instantiate polymorphic [methodType] and [methodFx] at [args] */
  private fun apply(
    rec: (Point<FX>) -> Ans<FX>,
    typeBounds: TypeBounds<FX>,
    methodParams: List<Type<FX>>,
    methodType: Type<FX>,
    methodFx: Effect<FX>,
    args: List<Type<FX>>,
  ): InstAns<FX> {
    val (fxBound, fxSyms, constraints) = methodFx
    val env = Env.bindParams(typeLattice, typeBounds, methodParams, args)
    val appT = env.instType(rec, methodType)
    val (appConstraint, constraintFails) = env.instConstraint(rec, constraints)
    val appFx =
      with(fxInstantiationLattice) {
        Instantiation(Effect(fxBound, constraint = appConstraint), constraintFails) join
          (fxSyms?.joinedOver { env.instEffect(rec, it) } ?: fxInstantiationLattice.top)
      }
    return Result(appT, appFx)
  }

  /** Substitute and normalize type [type] under environment. */
  private fun Env<FX>.instType(rec: (Point<FX>) -> Ans<FX>, type: Type<FX>): Type<FX> =
    when (type) {
      is Type.Application -> Type.Application(type.constructor, type.args.map { instType(rec, it) })
      is Type.Lambda -> {
        val (xs, body, intf) = type
        val (bodyType, bodyFx) = body
        val (fxResBound, fxResInvks) = bodyFx
        val instantiatedBody =
          Result(
            value = instType(rec, bodyType),
            effect =
              with(effectLattice) {
                // TODO discarding constraint errors below. Will be problematic.
                Effect(fxResBound) join
                  (fxResInvks?.joinedOver { instEffect(rec, it).result } ?: top)
              },
          )
        Type.Lambda(xs, instantiatedBody, intf)
      }
      is Type.MethodRef -> type
      is Type.SpecializedMethodRef ->
        Type.SpecializedMethodRef(instType(rec, type.receiver), type.ref)
      is Type.Sym.Param -> varAt(type.name) ?: type
      is Type.Sym.This -> receiver(type.site) ?: type
      is Type.Sym.Invoke ->
        invokeVirtual(
            rec,
            typeLattice.widen(instType(rec, type.receiver)),
            type.method,
            type.args.map { typeLattice.widen(instType(rec, it)) },
          )
          .value
      is Type.Union -> type.cases.joinedOver(typeLattice) { instType(rec, it) }
      is Type.WildCard,
      is Type.Sym.Rec -> type
      is Type.Ellipsis -> Type.Ellipsis(instType(rec, type.element))
      is Type.Sym.Fix -> instFix(rec, type).value
    }

  /** Substitute and normalize effect [fx] under environment */
  private fun Env<FX>.instEffect(rec: (Point<FX>) -> Ans<FX>, fx: Type.Sym<FX>): Instantiation<FX> =
    when (fx) {
      is Type.Sym.Invoke -> {
        val receiver = typeLattice.widen(instType(rec, fx.receiver))
        val args = fx.args.map { typeLattice.widen(instType(rec, it)) }
        invokeVirtual(rec, receiver, fx.method, args).effect
      }
      is Type.Sym.Rec -> fxInstantiationLattice.bottom // TODO confirm OK??
      is Type.Sym.This,
      is Type.Sym.Param ->
        throw IllegalStateException("Unexpected symbolic effect representation: $fx")
      is Type.Sym.Fix -> instFix(rec, fx).effect
    }

  /** Instantiate symbolic effects in the constraint, possibly discovering violations */
  private fun Env<FX>.instConstraint(
    rec: (Point<FX>) -> Ans<FX>,
    constraint: Constraint<FX>,
  ): Pair<Constraint<FX>, PersistentSet<ConstraintFailure<FX>>> =
    with(constraintLattice) {
      val (concrete, symbolic) = constraint
      var newFails = persistentSetOf<ConstraintFailure<FX>>()
      val concreteInst: Constraint<FX> =
        concrete?.asSequence()?.joinedOver { (l, fx) ->
          val (instL, _ /* TODO ok? */) = instEffect(rec, l)
          val (lBound, lSyms, lConstraints) = instL
          if (!concreteEffect.precede(lBound, fx)) newFails += ConstraintFailure(l, fx, lBound)
          val fromSyms = Constraint(Constraint.concrete(fx, lSyms), persistentMapOf())
          fromSyms join lConstraints
        } ?: return top to newFails
      val symbolicInst: Constraint<FX> = bottom /* TODO */
      return (concreteInst join symbolicInst) to newFails
    }

  /** Instantiate inductive type [fixed] under environment */
  private fun Env<FX>.instFix(rec: (Point<FX>) -> Ans<FX>, fixed: Type.Sym.Fix<FX>): InstAns<FX> =
    with(fxInstantiationLattice) {
      fun Type<FX>.substAndInvoke(base: Type<FX>): InstAns<FX> =
        when (this) {
          is Type.Sym.Rec -> pure(base)
          is Type.Sym.Param,
          is Type.Sym.This -> pure(this)
          is Type.Sym.Invoke -> {
            val (substReceivers, recvFx) = receiver.substAndInvoke(base)
            val substArgs = args.map { it.substAndInvoke(base) }
            val args = substArgs.map { it.value }
            val argsFx = substArgs.fold(bottom) { fx, arg -> fx join arg.effect }
            val (res, invFx) =
              invokeVirtual(
                rec,
                typeLattice.widen(substReceivers),
                method,
                args.map(typeLattice::widen),
              )
            Result(res, recvFx join argsFx join invFx)
          }
          is Type.Application,
          is Type.Ellipsis,
          is Type.WildCard,
          is Type.MethodRef -> pure(this)
          is Type.Union -> cases.joinedOver(instantiationLattice) { it.substAndInvoke(base) }
          is Type.Lambda -> pure(this)
          is Type.SpecializedMethodRef -> {
            val (t, fx) = receiver.substAndInvoke(base)
            Result(copy(receiver = t), fx)
          }
          is Type.Sym.Fix ->
            with(instantiationLattice) {
              baseCases.joinedOver { it.substAndInvoke(base) } join
                inductiveCases.joinedOver { it.substAndInvoke(base) }
            }
        }

      val (baseCases, indCases) = fixed
      val instBase = baseCases.joinedOver(typeLattice) { instType(rec, it) }
      if (instBase == Type.None) return instantiationLattice.bottom

      val instIndCases =
        when (val t = indCases.joinedOver(typeLattice) { instType(rec, it) }) {
          is Type.Union -> t.cases
          else -> persistentSetOf(t)
        }

      return when {
        instIndCases.isEmpty() -> pure(instBase)
        // When the instantiation is just renaming symbols, there's no need for a general
        // fix-point computation
        isPureRenaming() -> {
          val instBaseCases =
            when (instBase) {
              is Type.Union -> instBase.cases as PersistentSet<Type.Sym<FX>>
              else -> persistentSetOf(instBase as Type.Sym<FX>)
            }
          pure(Type.Sym.Fix(instBaseCases, instIndCases as PersistentSet<Type.Sym.Invoke<FX>>))
        }
        else -> {
          fun fix(t0: Type<FX>, fx0: Instantiation<FX>): InstAns<FX> {
            val (t1, fx1) = instIndCases.joinedOver(instantiationLattice) { it.substAndInvoke(t0) }
            val tN = typeLattice.widen(t0, t1)
            val fxN = fxInstantiationLattice.widen(fx0, fx1)
            return when {
              t0 == tN && fx0 == fxN -> Result(tN, fxN)
              else -> fix(tN, fxN)
            }
          }
          fix(instBase, fxInstantiationLattice.bottom)
        }
      }
    }

  private operator fun ((Point<FX>) -> Ans<FX>).get(method: Type.MethodRef) =
    this(method) as SummAns<FX>

  private operator fun ((Point<FX>) -> Ans<FX>).get(
    method: Instantiable<FX>,
    args: List<Type<FX>>,
  ) = this(Point.Instantiation(method, args)) as InstAns<FX>

  private fun resolvePossiblyAnnotatedSuperClass(e: UExpression): ClassId? {
    fun typeOf(t: PsiType?) = (t as? PsiClassType)?.let(ClassId::of)
    fun default() =
      when {
        e is ULambdaExpression -> typeOf(e.functionalInterfaceType)
        else ->
          when (val parent = e.uastParent) {
            is UCallExpression -> typeOf(parent.getParameterForArgument(e)?.type)
            is ULocalVariable -> typeOf((parent.javaPsi as PsiVariable).type)
            else -> null
          }
      }

    val call = e.uastParent as? UCallExpression ?: return default()
    val param = call.getParameterForArgument(e) ?: return default()
    val callee = call.resolve() ?: return typeOf(param.type)
    val calleeRef = Type.MethodRef(callee)
    val calleeHeader = module[calleeRef] ?: return typeOf(param.type)
    val paramBound = calleeHeader.initEnvironment.types[param.name] ?: return typeOf(param.type)
    return when {
      paramBound.size == 1 -> {
        val bound = paramBound.first() as? Type.Application ?: return typeOf(param.type)
        bound.constructor as? ClassId.Guarded ?: typeOf(param.type)
      }
      else -> typeOf(param.type)
    }
  }

  /**
   * Given [method] and user-supplied arguments in [call], fill in default arguments from [method]
   */
  private fun <M : UElement> completeArguments(
    typeParams: Set<String>,
    call: UCallExpression,
    method: M,
    params: M.() -> List<UParameter>,
  ): List<UExpression> {
    val allParams = method.params()
    val firstParam = allParams.firstOrNull()
    return when {
      // discarding extension receiver
      firstParam != null && (firstParam.nameFromSource?.startsWith("$") != false) -> {
        val offset = if (call.isArrayAccess()) /* TODO hack against ArrayAccessAsCall */ 0 else 1
        val paramsSansReceiver = allParams.subList(1, allParams.size)
        completeArguments(typeParams, call, paramsSansReceiver)
      }
      else -> completeArguments(typeParams, call, method.params())
    }
  }

  private fun completeArguments(
    typeParams: Set<String>,
    call: UCallExpression,
    params: List<UParameter>,
  ) =
    when {
      // Common case: don't resort to `getArgumentForParameter` args already match!
      params.size == call.valueArguments.size &&
        params.none { it.isVararg() } &&
        !call.hasComplexArgList() -> call.valueArguments
      else ->
        params.mapIndexed { i, param ->
          val paramPsi = param.javaPsi as PsiParameter
          val paramName = paramPsi.name
          val arg =
            call.getArgumentForParameter(i)
              ?: param.uastInitializer // TODO wrong. Make it lexically, not dynamically scoped!
              ?: OpaqueConstant(PsiTypeAdapter.translate(typeParams, paramPsi.type)).also {
                log("WARNING: Can't retrieve default argument for $paramName, supplying $it")
              }
          // TODO (b/406877361)
          when {
            paramPsi.type !is PsiEllipsisType -> arg
            arg is UExpressionList || arg is OpaqueConstant -> arg
            i >= call.valueArguments.size -> OpaqueConstant(Type.EmptyArray)
            else -> {
              // TODO hack against `ArrayAccessAsCallExpression`
              val max = call.valueArgumentCount - (if (call.isArrayAccess()) 1 else 0)
              RestArg(call.valueArguments.subList(i, max))
            }
          }
        }
    }

  private fun UCallExpression.hasComplexArgList(): Boolean =
    (sourcePsi as? KtCallExpression)?.valueArguments?.any { it.children.size > 1 } == true

  private fun UParameter.isVararg(): Boolean =
    (sourcePsi as? KtParameter)?.isVarArg == true || (javaPsi as? PsiParameter)?.isVarArgs == true

  private fun UCallExpression.isArrayAccess() =
    javaClass.canonicalName == "com.android.tools.lint.detector.api.ArrayAccessAsCallExpression"

  private fun <R : EffectResult.Eval<FX>> Mode<FX, R>.guardImplAgainstIntf(
    rec: (Point<FX>) -> Ans<FX>,
    arg: UExpression,
    intfId: ClassId,
    implId: ClassId,
  ): PersistentSet<Error<FX>> {
    if (intfId == implId) return persistentSetOf()
    val intfs = module[intfId] ?: return persistentSetOf()
    val impls = module[implId] ?: return persistentSetOf()
    return intfs.entries.flatMapToPersistentSet { (methodIntf, methodIntfHeader) ->
      val methodImplHeader = impls[methodIntf] ?: return@flatMapToPersistentSet persistentSetOf()
      guardImplAgainstStatus(
        rec,
        arg,
        methodIntfHeader.status,
        Type.MethodRef(implId, methodIntf),
        methodImplHeader,
      )
    }
  }

  private fun <R : EffectResult.Eval<FX>> Mode<FX, R>.guardImplAgainstStatus(
    rec: (Point<FX>) -> Ans<FX>,
    arg: UExpression,
    intfMethodStatus: MethodBody.Status<FX>,
    implMethodRef: Type.MethodRef,
    methodImplHeader: MethodBody<FX>,
  ): PersistentSet<Error<FX>> {
    var result = persistentSetOf<Error<FX>>()

    when (intfMethodStatus) {
      is MethodBody.Status.ForChecking -> {
        val baseAnn = intfMethodStatus.upperBound
        val annValue = baseAnn.annotated
        when (val assumption = assumptions[implMethodRef]) {
          null ->
            when (val implFx = rec[implMethodRef].effect) {
              is Inference -> {
                val (concrete, /* TODO ignored, unsound */ _, _) = implFx.result
                if (!concreteEffects.precede(concrete, annValue)) {
                  result += Error.ConflictingInference(arg, concrete, baseAnn)
                }
              }
              is Checking -> {
                val implCheckedFx =
                  (methodImplHeader.status as MethodBody.Status.ForChecking).upperBound.annotated
                if (!concreteEffects.precede(implCheckedFx, annValue)) {
                  result += Error.ConflictingInference(arg, implCheckedFx, baseAnn)
                }
              }
              is Inapplicable -> throw IllegalStateException()
            }
          else -> {
            val (concrete, /* TODO ignored, unsound*/ _, _) = assumption.effect
            if (!concreteEffects.precede(concrete, annValue)) {
              result += Error.ConflictingInference(arg, concrete, baseAnn)
            }
          }
        }
      }
      is MethodBody.Status.ForInference,
      is MethodBody.Status.Abstract -> {}
      is MethodBody.Status.BasicConstructor -> throw IllegalStateException()
    }

    return result
  }

  private sealed class Mode<FX, R : EffectResult.Eval<FX>>(
    val concreteEffects: Lattice<FX>,
    val effects: Lattice<R>,
    val constraintLattice: Lattice<Constraint<FX>>,
  ) : Lattice<R> by effects, EffectfulComputation<R> {

    class Inference<FX>(
      val bases: List<EffectAnnotation.Explicit<FX>>,
      concreteEffects: Lattice<FX>,
      effects: Lattice<EffectResult.Inference<FX>>,
      constraintLattice: Lattice<Constraint<FX>>,
    ) : Mode<FX, EffectResult.Inference<FX>>(concreteEffects, effects, constraintLattice) {
      private val upperBound =
        bases.fold(concreteEffects.top) { acc, base -> concreteEffects.meetOf(acc, base.annotated) }

      override fun onInvocationEffect(
        source: UExpression,
        fxInst: Instantiation<FX>,
      ): EffectResult.Inference<FX> {
        val (fx, failures) = fxInst

        // Accumulate errors
        var errors = persistentSetOf<Error<FX>>()
        when (fx.concrete) {
          concreteEffects.top -> errors += Error.CallingTop(source)
          else ->
            for (base in bases) {
              if (!concreteEffects.precede(fx.concrete, base.annotated)) {
                errors += Error.ConflictingInference(source, fx.concrete, base)
                break
              }
            }
        }
        errors += failures.at(source)

        // Accumulate constraints
        val constraintsFromBase =
          bases.joinedOver(constraintLattice) { (baseAnn, _) ->
            Constraint(Constraint.concrete(baseAnn, fx.invocations), persistentMapOf())
          }

        return Inference(
          fx.copy(
            concrete = concreteEffects.meetOf(upperBound, fx.concrete),
            constraint = constraintLattice.joinOf(fx.constraint, constraintsFromBase),
          ),
          errors,
        )
      }

      override fun merge(
        context: ErrorSite,
        left: EffectResult.Inference<FX>,
        right: EffectResult.Inference<FX>,
      ): EffectResult.Inference<FX> {
        val joined = left join right
        return with(concreteEffects) {
          when {
            left.errors == errorSetLattice<FX>().bottom &&
              right.errors == errorSetLattice<FX>().bottom &&
              left.result.concrete != top &&
              right.result.concrete != top &&
              joined.result.concrete == top ->
              joined.copy(
                errors =
                  persistentSetOf(
                    Error.IntroducingTop(context, left.result.concrete, right.result.concrete)
                  )
              )
            else -> joined
          }
        }
      }
    }

    class Checking<FX>(
      val annotation: FX,
      concreteEffects: Lattice<FX>,
      effects: Lattice<EffectResult.Checking<FX>>,
      constraintLattice: Lattice<Constraint<FX>>,
    ) : Mode<FX, EffectResult.Checking<FX>>(concreteEffects, effects, constraintLattice) {
      override fun onInvocationEffect(source: UExpression, fxInst: Instantiation<FX>) =
        with(concreteEffects) {
          val (fx, failures) = fxInst
          var errors = persistentSetOf<Error<FX>>()
          if (!(fx.concrete precedes annotation))
            errors += Error.ExceedingAnnotation(annotation, fx.concrete, source)
          errors += failures.at(source)
          val generatedConstraints = Constraint.concrete(annotation, fx.invocations)
          Checking(
            constraintLattice.joinOf(
              fx.constraint,
              Constraint(generatedConstraints, persistentMapOf()),
            ),
            errors,
          )
        }
    }

    abstract fun onInvocationEffect(source: UExpression, fxInst: Instantiation<FX>): R
  }

  private class UnresolvedNameException(val element: UElement) : Exception()

  private class UnresolvedReturnTargetException(
    val statement: UElement,
    val targets: List<ReturnRecord<*>>,
  ) : Exception()
}

private val <T> Lattice<T>.emptyResult: Result<Type<Nothing>, T>
  get() = pure(Type.None)

private val <T> Lattice<T>.unitResult: Result<Type<Nothing>, T>
  get() = pure(Type.Unit)

/**
 * The "effect" information returned is a little different depending on whether we're inferring,
 * checking, or instantiating.
 * - [Inference] represents inferred [Effect] as well as [errors] during the process.
 * - [Checking] represents implied [Constraint] during checking, without inferring anything, as well
 *   as errors during the process.
 * - [Instantiation] represents internally instantiated [Effect]. Instantiation is agnostic of call
 *   sites, so results can be shared. In particular, the [ConstraintFailure] doesn't have any call
 *   site attached to it yet, compared to [Error.FailingConstraint].
 */
sealed class EffectResult<out FX> {
  abstract val errors: UnboundedSet<*>
  abstract val result: Any?

  sealed class Eval<out FX> : EffectResult<FX>() {
    abstract override val errors: UnboundedSet<Error<FX>>
  }

  data class Inference<out FX>(
    override val result: Effect<FX>,
    override val errors: UnboundedSet<Error<FX>>,
  ) : Eval<FX>()

  // TODO: Also generate constraints on higher-order arguments
  data class Checking<out FX>(
    override val result: Constraint<FX>,
    override val errors: UnboundedSet<Error<FX>>,
  ) : Eval<FX>()

  data object Inapplicable : Eval<Nothing>(), Lattice<Inapplicable> {
    override val errors = persistentSetOf<Nothing>()
    override val result = null
    override val bottom
      get() = this

    override val top
      get() = this

    override fun joinOf(first: Inapplicable, second: Inapplicable) = this

    override fun meetOf(first: Inapplicable, second: Inapplicable) = this

    override fun precede(first: Inapplicable, second: Inapplicable) = true
  }

  data class Instantiation<out FX>(
    override val result: Effect<FX>,
    override val errors: UnboundedSet<ConstraintFailure<FX>> = persistentSetOf(),
  ) : EffectResult<FX>()

  final override fun toString() =
    when (this) {
      is Inapplicable -> "\uD83D\uDD35"
      else -> "$result ${errors.format()}"
    }
}

private operator fun <FX, R : EffectResult.Eval<FX>> R.plus(
  moreErrors: UnboundedSet<Error<FX>>
): R =
  when (this) {
    is Inference<*> -> copy(errors = errors unionedWith moreErrors) as R
    is Checking<*> -> copy(errors = errors unionedWith moreErrors) as R
    else -> throw IllegalStateException("Unexpected: ${this::class.java.simpleName}")
  }

private typealias InstAns<FX> = Result<Type<FX>, Instantiation<FX>>

private typealias SummAns<FX> = Result<Type<FX>, EffectResult.Eval<FX>>

typealias Ans<FX> = Result<Type<FX>, EffectResult<FX>>

private fun <R> UnboundedSet<R>.format() =
  when {
    this == null -> "✗"
    this.isEmpty() -> "✓"
    else -> "(errors: ${joinToString()})"
  }

private data class OpaqueConstant(val type: Type<Nothing>) : UExpression {
  override val uastParent = null
  override val psi = null

  override fun asLogString() = "⦉$type⦊"

  override fun asRenderString() = asLogString()

  override fun asSourceString() = asLogString()

  override fun toString() = asLogString()

  override val uAnnotations
    get() = listOf<UAnnotation>()
}

// TODO (b/406877361)
private data class RestArg(val elems: List<UExpression>) : UExpression {
  override val uastParent = null
  override val psi = null

  override fun asLogString() = joinString(UExpression::asLogString)

  override fun asRenderString() = joinString(UExpression::asRenderString)

  override fun asSourceString() = joinString(UExpression::asSourceString)

  override val uAnnotations = listOf<Nothing>()

  private fun joinString(onElem: (UExpression) -> String) =
    "[|${elems.joinToString(transform = onElem)}|]"
}

private fun UExpression.lhsReceiverAndIndices(): Pair<UExpression?, List<UExpression>>? =
  when (this) {
    is UQualifiedReferenceExpression -> receiver to listOf()
    is UArrayAccessExpression -> receiver to indices
    is USimpleNameReferenceExpression -> null to listOf()
    else -> null
  }

// TODO(b/405135846)
private fun UCallExpression.callReceiver(): UExpression? =
  receiver
    ?: when (val src = sourcePsi) {
      is KtCallExpression ->
        (src.calleeExpression.toUElement() as? UExpression)?.takeIf {
          it.getExpressionType() != null
        }
      else -> null
    }

private fun MethodId.isCompatible(args: List<UExpression>): Boolean =
  // TODO must check the types too!!
  paramTags.size == args.size
