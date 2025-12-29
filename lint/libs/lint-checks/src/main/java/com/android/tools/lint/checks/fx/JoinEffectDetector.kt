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
package com.android.tools.lint.checks.fx

import com.android.tools.lint.checks.fx.analysis.Analysis
import com.android.tools.lint.checks.fx.analysis.Ans
import com.android.tools.lint.checks.fx.analysis.EffectResult
import com.android.tools.lint.checks.fx.analysis.LocalFun
import com.android.tools.lint.checks.fx.analysis.Module
import com.android.tools.lint.checks.fx.result.AssumptionTable
import com.android.tools.lint.checks.fx.result.ClassId
import com.android.tools.lint.checks.fx.result.Constraint
import com.android.tools.lint.checks.fx.result.ConstraintFailure
import com.android.tools.lint.checks.fx.result.Effect
import com.android.tools.lint.checks.fx.result.Error
import com.android.tools.lint.checks.fx.result.MethodBody
import com.android.tools.lint.checks.fx.result.MethodId
import com.android.tools.lint.checks.fx.result.Point
import com.android.tools.lint.checks.fx.result.Result
import com.android.tools.lint.checks.fx.result.ResultTable
import com.android.tools.lint.checks.fx.result.ResultTemplate
import com.android.tools.lint.checks.fx.result.Type
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.static
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.virtual
import com.android.tools.lint.checks.fx.result.get
import com.android.tools.lint.checks.fx.utils.Encoder
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.adapt
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.case
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.interned
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.orNull
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.subType
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.withDefault
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.zeroOrMore
import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.checks.fx.utils.UnboundedSet
import com.android.tools.lint.checks.fx.utils.decodeFromDir
import com.android.tools.lint.checks.fx.utils.encodeToDir
import com.android.tools.lint.checks.fx.utils.leastFixPoint
import com.android.tools.lint.checks.fx.utils.unionedWith
import com.android.tools.lint.client.api.LintClient
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.PartialResult
import com.android.tools.lint.detector.api.Project
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.openapi.application.runReadAction
import java.io.File
import java.lang.Iterable as JIterable
import java.nio.file.Paths
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Function as JFunction
import java.util.function.Predicate
import java.util.stream.Stream
import kotlin.collections.forEach as ktForEach
import kotlin.io.path.exists
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import kotlinx.collections.immutable.toPersistentSet
import org.jetbrains.kotlin.incremental.createDirectory
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UVariable

/**
 * Generic detector for effect with only one way of combining by joining up a [Lattice] regardless
 * of whether sequentially or from different branches. As a consequence of assuming that the effects
 * form a [Lattice], this detector is only applicable for effects where we don't care about order
 * and count. The analysis is also parameterizable by [initialAssumptions], which can be provided
 * either from the analysis result of a dependent module, or assumed for primitives.
 *
 * We probably won't let Lint developers directly implement this, but only define each of their [FX]
 * as a [Lattice] (along with other callbacks for introducing the concrete [FX]). Then all the [FX]s
 * will be fused together as one [FX] to run in the same passes (a la [UElementVisitor]).
 */
abstract class JoinEffectDetector<FX : Any>(
  private val effects: Lattice<FX>,
  initialAssumptions: AssumptionTable<FX>,
) : Detector(), SourceCodeScanner, Module.AnnotationParser<FX> {
  private var knownResults: LazyLoadedResultTable =
    LazyLoadedResultTable(initialAssumptions, hashMapOf())
  private val programBuilder = Module.Builder(this)

  private var isSummariesCacheValid = false
  private lateinit var summariesCache: Map<Type.MethodRef, Result<Type<FX>, EffectResult.Eval<FX>>>

  protected abstract val mainIssue: Issue

  protected abstract val effectEncoder: Encoder<FX>

  final override fun beforeCheckEachProject(context: Context) {
    super.beforeCheckEachProject(context)
    maybeLoadPartialResults(context)
  }

  final override fun applicableSuperClasses() = listOf("java.lang.Object")

  final override fun visitClass(context: JavaContext, declaration: UClass) {
    isSummariesCacheValid = false
    try {
      programBuilder.addClass(context, declaration)
    } catch (e: Throwable) {
      if (LintClient.isUnitTest) {
        throw e
      } else {
        context.log(e, "Error while indexing class ${declaration.qualifiedName}")
      }
    }
  }

  final override fun getApplicableUastTypes() = listOf(UDeclaration::class.java)

  final override fun createUastHandler(context: JavaContext) =
    object : UElementHandler() {
      override fun visitDeclaration(node: UDeclaration) {
        val dec = node as? UVariable ?: return
        val fn = dec.sourcePsi as? KtNamedFunction ?: return
        val fnUast = dec.uastInitializer as? ULambdaExpression ?: return
        try {
          programBuilder.addLocalFunction(LocalFun(fnUast, fn))
        } catch (e: Throwable) {
          if (LintClient.isUnitTest) {
            throw e
          } else {
            context.log(
              e,
              "Error while indexing local function ${fn.name} in file ${fn.containingFile.name}",
            )
          }
        }
      }
    }

  final override fun afterCheckRootProject(context: Context) {
    super.afterCheckEachProject(context)

    if (!isSummariesCacheValid) {
      val program = programBuilder.build()
      // programBuilder.loTechDebug()
      summariesCache = runReadAction {
        val entries = buildList {
          for ((cId, cDefn) in program.classes) {
            for ((mId, _) in cDefn.methods) {
              add(Type.MethodRef(cId, mId))
            }
          }
        }

        val summaries = Analysis(program, effects, knownResults).leastFixPoint(entries)

        buildMap {
          for ((k, v) in summaries) {
            // debugEntry(program, k, v)
            if (k is Type.MethodRef)
              @Suppress("UNCHECKED_CAST") put(k, v as Result<Type<FX>, EffectResult.Eval<FX>>)
          }
        }
      }

      maybeSavePartialResults(context, program)
      isSummariesCacheValid = true
    }
    for ((_, sum) in summariesCache) {
      for (error in dedupErrors(sum.effect.errors!!)) report(context, error)
    }
  }

  /**
   * Report error discovered by the detector. Each implementation of this detector may have a
   * different interpretation of the [error], and may not even consider it an error. For example,
   * not all detectors consider [Error.IntroducingTop] a problem, where the inferred effect is
   * [Lattice.top].
   */
  protected abstract fun report(context: Context, error: Error<FX>)

  private fun maybeSavePartialResults(context: Context, program: Module<FX>) {
    if (context.isGlobalAnalysis()) return
    val dirPath =
      getPartialResultDir(context.project, createIfAbsent = true)?.absolutePath ?: return
    summaryEncoder.encodeToDir(
      resultList(program, summariesCache),
      dirPath,
      methodIdEncoder,
      Encoder.internedString,
    )
  }

  private fun maybeLoadPartialResults(context: Context) {
    if (context.isGlobalAnalysis()) return

    for (dependentProject in context.project.allLibraries) {
      val libDir =
        getPartialResultDir(dependentProject, createIfAbsent = false)?.absolutePath ?: continue
      val classIds =
        classIdListEncoder.decodeFromDir(libDir, methodIdEncoder, Encoder.internedString)
      for (c in classIds) knownResults.toBeLoaded[c] = libDir
    }
  }

  override fun checkPartialResults(context: Context, partialResults: PartialResult) {}

  private fun getPartialResultDir(project: Project, createIfAbsent: Boolean): File? {
    val path =
      when {
        // Workaround for unit tests not having `partialResultsDir` set
        LintClient.isUnitTest ->
          Paths.get(project.dir.absolutePath, "build", mainIssue.id, project.name)
        else -> Paths.get(project.partialResultsDir!!.absolutePath, mainIssue.id, project.name)
      }
    return when {
      path.exists() -> path.toFile()
      createIfAbsent -> path.toFile().apply { createDirectory() }
      else -> null
    }
  }

  /**
   * During the fix-point computation, we might report redundant errors, the latter subsuming the
   * former. While we could define a proper `widen` operation on the error set representation, we
   * can also just let the redundancy happen and eliminate it at the end.
   */
  private fun dedupErrors(errors: Set<Error<FX>>): Sequence<Error<FX>> = sequence {
    val introducingTop =
      DedupingAccumulator<Error.IntroducingTop<FX>, _, _>(
        ::joinFxPair,
        { (g, f, n) -> g to (f to n) },
        { g, (f, n) -> Error.IntroducingTop(g, f, n) },
      )
    val exceedingAnnotation =
      DedupingAccumulator<Error.ExceedingAnnotation<FX>, _, _>(
        effects::joinOf,
        { (er, ee, site) -> (site to er) to ee },
        { (site, er), ee -> Error.ExceedingAnnotation(er, ee, site) },
      )
    val failingConstraint =
      DedupingAccumulator<Error.FailingConstraint<FX>, _, _>(
        UnboundedSet<ConstraintFailure<FX>>::unionedWith,
        { (c, site) -> site to c },
        { site, c -> Error.FailingConstraint(c?.let(::dedupConstraints), site) },
      )
    val conflictingInference =
      DedupingAccumulator<Error.ConflictingInference<FX>, _, _>(
        effects::joinOf,
        { (site, inf, base) -> (site to base) to inf },
        { (site, base), inf -> Error.ConflictingInference(site, inf, base) },
      )

    for (error in errors) {
      when (error) {
        is Error.IntroducingTop -> introducingTop.accum(error)
        is Error.ExceedingAnnotation -> exceedingAnnotation.accum(error)
        is Error.FailingConstraint -> failingConstraint.accum(error)
        is Error.ConflictingInference -> conflictingInference.accum(error)
        is Error.CallingTop,
        is Error.ConflictingAnnotations -> yield(error)
      }
    }

    yieldAll(introducingTop.extract())
    yieldAll(exceedingAnnotation.extract())
    yieldAll(failingConstraint.extract())
    yieldAll(conflictingInference.extract())
  }

  private fun dedupConstraints(
    constraints: PersistentSet<ConstraintFailure<FX>>
  ): PersistentSet<ConstraintFailure<FX>> =
    with(
      DedupingAccumulator<ConstraintFailure<FX>, _, _>(
        ::joinFxPair,
        { (call, exp, inf) -> call to (exp to inf) },
        { call, (exp, inf) -> ConstraintFailure(call, exp, inf) },
      )
    ) {
      constraints.forEach(::accum)
      return extract().toPersistentSet()
    }

  private class DedupingAccumulator<T, K, V>(
    val join: (V, V) -> V,
    val encode: (T) -> Pair<K, V>,
    val decode: (K, V) -> T,
  ) {
    private val map = mutableMapOf<K, V>()

    fun accum(t: T) {
      val (k, v) = encode(t)
      map[k] =
        when (k) {
          in map -> join(map[k] as V, v)
          else -> v
        }
    }

    fun extract(): Sequence<T> = map.asSequence().map { (k, v) -> decode(k, v) }
  }

  private fun joinFxPair(fxPair0: Pair<FX, FX>, fxPair1: Pair<FX, FX>): Pair<FX, FX> =
    with(effects) { (fxPair0.first join fxPair1.first) to (fxPair0.second join fxPair1.second) }

  private val classSummaryEncoder: Encoder<PersistentMap<MethodId, ResultTemplate<FX>>> = Encoder {
    val resultEncoder =
      Encoder.product(
        ::ResultTemplate,
        Encoder.map(
          Encoder.internedString,
          Encoder.set(recNothingTypeEncoder) withDefault persistentSetOf(),
        ) withDefault persistentMapOf(),
        recNothingTypeEncoder.zeroOrMore() withDefault listOf(),
        recTypeEncoder withDefault Type.Unit,
        fxEncoder withDefault Effect(effects.bottom, persistentSetOf(), Constraint.MostPermissive),
      )
    Encoder.map(methodIdEncoder, resultEncoder)
  }

  private val classIdEncoder: Encoder<ClassId> = Encoder {
    ClassId.encoder(effectEncoder.adapt({ it as FX }, { it }), methodIdEncoder)
  }

  private val classIdListEncoder = classIdEncoder.zeroOrMore()

  private val summaryEncoder =
    Encoder.product(::Pair, classIdListEncoder, classSummaryEncoder.zeroOrMore())

  private val methodIdEncoder: Encoder<MethodId> =
    Encoder {
        Encoder.product(
          ::MethodId,
          Encoder.boolean withDefault true,
          Encoder.internedString withDefault "invoke",
          classIdEncoder.orNull().zeroOrMore() withDefault listOf(),
        )
      }
      .interned()

  private val methodRefEncoder: Encoder<Type.MethodRef> =
    Encoder.product(Type<FX>::MethodRef, classIdEncoder, methodIdEncoder)

  private val recTypeEncoder = Encoder { typeEncoder }
  private val typeListEncoder = recTypeEncoder.zeroOrMore()
  private val recTypeSymEncoder = recTypeEncoder.subType<_, Type.Sym<FX>>()
  private val invokeEncoder =
    Encoder.product(Type.Sym<FX>::Invoke, recTypeSymEncoder, methodIdEncoder, typeListEncoder)
  private val recNothingTypeEncoder = recTypeEncoder.subType<_, Type<Nothing>>()

  private val typeEncoder: Encoder<Type<FX>> = Encoder {
    Encoder.sum(
      case<_, Type.Application<FX>>(
        Encoder.product(Type<FX>::Application, classIdEncoder, typeListEncoder withDefault listOf())
      ),
      case<_, Type.Ellipsis<FX>>(
        recTypeEncoder.adapt(Type.Ellipsis<FX>::element, Type<FX>::Ellipsis)
      ),
      case<_, Type.WildCard>(Encoder.const(Type.WildCard)),
      case<_, Type.Union<FX>>(
        Encoder.set(recTypeEncoder).adapt(Type.Union<FX>::cases, { Type.Union(it) as Type.Union })
      ),
      case<_, Type.Lambda<FX>>(
        Encoder.product(
          Type<FX>::Lambda,
          typeListEncoder,
          Encoder.product(::Result, recTypeEncoder, fxEncoder),
          classIdEncoder.orNull(),
        )
      ),
      case<_, Type.MethodRef>(methodRefEncoder),
      case<_, Type.SpecializedMethodRef<FX>>(
        Encoder.product(Type<FX>::SpecializedMethodRef, recTypeEncoder, methodRefEncoder)
      ),
      case<_, Type.Sym.Rec>(Encoder.const(Type.Sym.Rec)),
      case<_, Type.Sym.Param>(
        Encoder.internedString.adapt(Type.Sym.Param::name, Type.Sym<Nothing>::Param)
      ),
      case<_, Type.Sym.This>(classIdEncoder.adapt(Type.Sym.This::site, Type.Sym<Nothing>::This)),
      case<_, Type.Sym.Invoke<FX>>(invokeEncoder),
      case<_, Type.Sym.Fix<FX>>(
        Encoder.product(Type.Sym<FX>::Fix, Encoder.set(typeEncoder), Encoder.set(typeEncoder))
      ),
    )
  }

  private val fxEncoder: Encoder<Effect<FX>> = Encoder {
    val constraintEncoder =
      Encoder.product(
        ::Constraint,
        Encoder.map(recTypeSymEncoder, effectEncoder).orNull() withDefault persistentMapOf(),
        Encoder.map(
            invokeEncoder,
            Encoder.set(recTypeSymEncoder).orNull() withDefault persistentSetOf(),
          )
          .orNull() withDefault persistentMapOf(),
      )

    Encoder.product(
      ::Effect,
      effectEncoder,
      Encoder.set(recTypeSymEncoder).orNull() withDefault persistentSetOf(),
      constraintEncoder withDefault Constraint.MostPermissive,
    )
  }

  private inner class LazyLoadedResultTable(
    var loaded: AssumptionTable<FX>,
    val toBeLoaded: MutableMap<ClassId, String>,
  ) : ResultTable<FX> {

    override fun get(ref: Type.MethodRef): ResultTemplate<FX>? {
      val cached = loaded[ref]
      if (cached != null) return cached
      loadFromDir(toBeLoaded[ref.klass] ?: return null)
      return loaded[ref]
    }

    private fun loadFromDir(dir: String) {
      val (classIds, classSummaries) =
        summaryEncoder.decodeFromDir(dir, methodIdEncoder, Encoder.internedString)
      require(classIds.size == classSummaries.size)
      for ((i, classId) in classIds.withIndex()) {
        loaded = loaded.put(classId, classSummaries[i])
        toBeLoaded.remove(classId)
      }
    }
  }

  companion object {
    /** Adds assumptions on common Java and Kotlin utils */
    fun <FX> AssumptionTableBuilder<FX>.assumeCommonJavaAndKotlinSignatures() {
      // Iterable
      run {
        virtual(JIterable<*>::forEach) assumedAs
          forAll { a ->
            forAll(Consumer::class(a)) { action ->
              given(Iterable::class(a), action) {
                symbolicInvocations += action[Consumer<*>::accept, a]
              }
            }
          }
        static(Iterable<*>::ktForEach) assumedAs
          forAll { a ->
            forAll(Function1::class(a, Type.Unit)) { action ->
              given(Iterable::class(a), action) {
                symbolicInvocations += action[MethodId.Invoke[1], a]
              }
            }
          }
        static(Iterable<*>::forEachIndexed) assumedAs
          forAll { a ->
            forAll(Function2::class(Type.Int, a, Type.Unit)) { action ->
              given(Iterable::class(a), action) {
                symbolicInvocations += action[MethodId.Invoke[2], Type.Int, a]
              }
            }
          }
        (static<_, (Any) -> Comparable<Any>>(Iterable<Nothing>::maxBy) +
          static<_, (Any) -> Comparable<Any>>(Iterable<Nothing>::maxByOrNull) +
          static<_, (Any) -> Comparable<Any>>(Iterable<Nothing>::minBy) +
          static<_, (Any) -> Comparable<Any>>(Iterable<Nothing>::minByOrNull)) assumedAs
          forAll { t ->
            forAll { r ->
              forAll(Function1::class(t, r)) { selector ->
                given(Iterable::class(t), selector) {
                  range = r
                  symbolicInvocations += selector[MethodId.Invoke[1], t]
                  symbolicInvocations += r[Comparable<*>::compareTo, r]
                }
              }
            }
          }
        (static<_, Comparator<Any>, (Any) -> Any>(Iterable<Nothing>::maxOfWith) +
          static<_, Comparator<Any>, (Any) -> Any>(Iterable<Nothing>::maxOfWithOrNull) +
          static<_, Comparator<Any>, (Any) -> Any>(Iterable<Nothing>::minOfWith) +
          static<_, Comparator<Any>, (Any) -> Any>(Iterable<Nothing>::minOfWithOrNull)) assumedAs
          forAll { t ->
            forAll { r ->
              forAll(Comparator::class(r)) { comparator ->
                forAll(Function1::class(t, r)) { selector ->
                  given(Iterable::class(t), comparator, selector) {
                    range = r
                    symbolicInvocations += comparator[Comparator<*>::compare, r, r]
                    symbolicInvocations += selector[MethodId.Invoke[1], t]
                  }
                }
              }
            }
          }
        (static(Iterable<*>::maxWith) +
          static(Iterable<*>::maxWithOrNull) +
          static(Iterable<*>::minWith) +
          static(Iterable<*>::minWithOrNull)) assumedAs
          forAll { t ->
            forAll(Comparator::class(t)) { comparator ->
              given(Iterable::class(t), comparator) {
                range = t
                symbolicInvocations += comparator[Comparator<*>::compare, t, t]
              }
            }
          }
        (static(Iterable<*>::all) +
          static<_, _>(Iterable<*>::any) +
          static<_, _>(Iterable<*>::none)) assumedAs
          forAll { t ->
            forAll(Function1::class(t, Type.Boolean)) { predicate ->
              given(Iterable::class(t), predicate) {
                range = Type.Boolean
                symbolicInvocations += predicate[MethodId.Invoke[1], t]
              }
            }
          }
        static(Iterable<*>::onEach) assumedAs
          forAll { t ->
            forAll(Iterable::class(t)) { self ->
              forAll(Function1::class(t, Type.Unit)) { action ->
                given(self, action) {
                  range = self
                  symbolicInvocations += action[MethodId.Invoke[1], t]
                }
              }
            }
          }
        static(Iterable<*>::onEachIndexed) assumedAs
          forAll { t ->
            forAll(Iterable::class(t)) { self ->
              forAll(Function2::class(Type.Int, t, Type.Unit)) { action ->
                given(Iterable::class(t), action) {
                  range = self
                  symbolicInvocations += action[MethodId.Invoke[2], Type.Int, t]
                }
              }
            }
          }
        (static(Iterable<*>::reduce) + static(Iterable<*>::reduceOrNull)) assumedAs
          forAll { s ->
            forAll(s) { t ->
              forAll(Function2::class(s, t, s)) { operation ->
                given(Iterable::class(t), operation) {
                  range = s
                  symbolicInvocations += operation[MethodId.Invoke[2], s, t]
                }
              }
            }
          }
        (static(Iterable<*>::reduceIndexed) + static(Iterable<*>::reduceIndexedOrNull)) assumedAs
          forAll { s ->
            forAll(s) { t ->
              forAll(Function3::class(Type.Int, s, t, s)) { operation ->
                given(Iterable::class(t), operation) {
                  range = s
                  symbolicInvocations += operation[MethodId.Invoke[3], Type.Int, s, t]
                }
              }
            }
          }

        static<_, (Any?) -> Any>(Iterable<*>::map) assumedAs
          forAll { x ->
            forAll { y ->
              forAll(Function1::class(x, y)) { transform ->
                given(Iterable::class(x), transform) {
                  range = List::class(y)
                  symbolicInvocations += transform[MethodId.Invoke[1], x]
                }
              }
            }
          }
        static(Iterable<*>::filter) assumedAs
          forAll { x ->
            forAll(Function1::class(x, Type.Boolean)) { predicate ->
              given(Iterable::class(x), predicate) {
                range = List::class(x)
                symbolicInvocations += predicate[MethodId.Invoke[1], x]
              }
            }
          }
        static<_, Nothing, _>(Iterable<*>::fold) assumedAs
          forAll { t ->
            forAll { r ->
              forAll(Function2::class(r, t, r)) { operation ->
                given(Iterable::class(t), r, operation) {
                  range = r
                  symbolicInvocations += operation[MethodId.Invoke[2], r, t]
                }
              }
            }
          }
      }

      // List
      run {
        (static(List<*>::reduceRight) + static(List<*>::reduceRightOrNull)) assumedAs
          forAll { s ->
            forAll(s) { t ->
              forAll(Function2::class(t, s, s)) { operation ->
                given(List::class(t), operation) {
                  range = s
                  symbolicInvocations += operation[MethodId.Invoke[2], t, s]
                }
              }
            }
          }
        (static(List<*>::reduceRightIndexed) + static(List<*>::reduceRightIndexedOrNull)) assumedAs
          forAll { s ->
            forAll(s) { t ->
              forAll(Function3::class(Type.Int, t, s, s)) { operation ->
                given(Iterable::class(t), operation) {
                  range = s
                  symbolicInvocations += operation[MethodId.Invoke[3], Type.Int, t, s]
                }
              }
            }
          }
      }

      // Map
      run {
        virtual(Map<*, *>::forEach) assumedAs
          forAll { k ->
            forAll { v ->
              forAll(BiConsumer::class(k, v)) { action ->
                given(Map::class(k, v), action) {
                  symbolicInvocations += action[BiConsumer<*, *>::accept, k, v]
                }
              }
            }
          }
        static(Map<*, *>::ktForEach) assumedAs
          forAll { k ->
            forAll { v ->
              forAll(Function1::class(Map.Entry::class(k, v), Type.Unit)) { action ->
                given(Map::class(k, v), action) {
                  symbolicInvocations += action[MethodId.Invoke[1], Map::class(k, v)]
                }
              }
            }
          }
        static<Map<*, *>, (Map.Entry<*, *>) -> Any>(Map<*, *>::map) assumedAs
          forAll { k ->
            forAll { v ->
              forAll { r ->
                forAll(Function1::class(Map.Entry::class(k, v), r)) { transform ->
                  given(Map::class(k, v), transform) {
                    range = List::class(r)
                    symbolicInvocations += transform[MethodId.Invoke[1], Map.Entry::class(k, v)]
                  }
                }
              }
            }
          }
      }

      // Array
      run {
        static(Array<*>::ktForEach) assumedAs
          forAll { a ->
            forAll(Function1::class(a, Type.Unit)) { action ->
              given(Array::class(a), action) {
                symbolicInvocations += action[MethodId.Invoke[1], a]
              }
            }
          }
        static<_, (Any?) -> Any>(Array<*>::map) assumedAs
          forAll { a ->
            forAll(Function1::class(a, Type.Unit)) { action ->
              given(Array::class(a), action) {
                symbolicInvocations += action[MethodId.Invoke[1], a]
              }
            }
          }
        static(Array<*>::filter) assumedAs
          forAll { a ->
            forAll(Function1::class(a, Type.Boolean)) { predicate ->
              given(Array::class(a), predicate) {
                range = List::class(a)
                symbolicInvocations += predicate[MethodId.Invoke[1], a]
              }
            }
          }
        static<_, Nothing, _>(Array<*>::fold) assumedAs
          forAll { t ->
            forAll { r ->
              forAll(Function2::class(r, t, r)) { operation ->
                given(Array::class(t), r, operation) {
                  range = r
                  symbolicInvocations += operation[MethodId.Invoke[2], r, t]
                }
              }
            }
          }
      }

      // Sequence
      // TODO below ain't accurate. Calls to `map`/`filter` only build up `Sequence`, and defer
      //  the actions to iteration time.
      run {
        static(Sequence<*>::forEach) assumedAs
          forAll { a ->
            forAll(Function1::class(a, Type.Unit)) { action ->
              given(Sequence::class(a), action) {
                symbolicInvocations += action[MethodId.Invoke[1], a]
              }
            }
          }
        static<_, (Any?) -> Any>(Sequence<*>::map) assumedAs
          forAll { x ->
            forAll { y ->
              forAll(Function1::class(x, y)) { transform ->
                given(Sequence::class(x), transform) {
                  range = Sequence::class(y)
                  symbolicInvocations += transform[MethodId.Invoke[1], x]
                }
              }
            }
          }
        static(Sequence<*>::filter) assumedAs
          forAll { x ->
            forAll(Function1::class(x, Type.Boolean)) { predicate ->
              given(Sequence::class(x), predicate) {
                range = Sequence::class(x)
                symbolicInvocations += predicate[MethodId.Invoke[1], x]
              }
            }
          }
        static<_, Nothing, _>(Sequence<*>::fold) assumedAs
          forAll { t ->
            forAll { r ->
              forAll(Function2::class(r, t, r)) { operation ->
                given(Sequence::class(t), r, operation) {
                  range = r
                  symbolicInvocations += operation[MethodId.Invoke[2], r, t]
                }
              }
            }
          }
      }

      // Stream
      // TODO(b/418276115)
      run {
        virtual(Stream<*>::forEach) assumedAs
          forAll { a ->
            forAll(Consumer::class(a)) { action ->
              given(Stream::class(a), action) {
                symbolicInvocations += action[Consumer<*>::accept, a]
              }
            }
          }
        virtual<JFunction<Any?, *>>(Stream<*>::map) assumedAs
          forAll { x ->
            forAll { y ->
              forAll(JFunction::class(x, y)) { mapper ->
                given(Stream::class(x), mapper) {
                  range = Stream::class(y)
                  symbolicInvocations += mapper[JFunction<*, *>::apply, x]
                }
              }
            }
          }
        virtual(Stream<*>::filter) assumedAs
          forAll { x ->
            forAll(Predicate::class(x)) { predicate ->
              given(Stream::class(x), predicate) {
                range = Stream::class(x)
                symbolicInvocations += predicate[Predicate<*>::test, x]
              }
            }
          }
      }

      // Common scoping functions
      run {
        (static(Any::apply) + static(Any::also)) assumedAs
          forAll { self ->
            forAll(Function1::class(self, Type.Unit)) { block ->
              given(self, block) {
                range = self
                symbolicInvocations += block[MethodId.Invoke[1], self]
              }
            }
          }
        (static<Any, Any.() -> Any>(::with) + static<Any, (Any) -> Any>(Any::let)) assumedAs
          forAll { receiver ->
            forAll { result ->
              forAll(Function1::class(receiver, result)) { block ->
                given(receiver, block) {
                  range = result
                  symbolicInvocations += block[MethodId.Invoke[1], receiver]
                }
              }
            }
          }
      }
    }
  }
}

private fun <FX : Any> debugEntry(program: Module<FX>, k: Point<FX>, v: Ans<FX>) {
  when (k) {
    is Type.MethodRef ->
      when (program[k]?.status) {
        is MethodBody.Status.ForChecking,
        is MethodBody.Status.ForInference -> println("  - $k : (…) -> $v")
        null,
        is MethodBody.Status.BasicConstructor,
        is MethodBody.Status.Abstract -> {}
      }
    is Point.Instantiation -> println("  - $k : $v")
  }
}

private fun <FX : Any> resultList(
  module: Module<FX>,
  results: Map<Type.MethodRef, Result<Type<FX>, EffectResult.Eval<FX>>>,
) =
  module.classes
    .map { (classId, classBody) ->
      val classSummary: PersistentMap<MethodId, ResultTemplate<FX>> =
        classBody.methods.asSequence().fold(persistentMapOf()) { m, (methodId, methodBody) ->
          val methodSummary = results[Type.MethodRef(classId, methodId)] ?: return@fold m
          val effect =
            when (val effectResult = methodSummary.effect) {
              is EffectResult.Checking ->
                when (val status = methodBody.status) {
                  is MethodBody.Status.ForChecking ->
                    Effect(status.upperBound.annotated, persistentSetOf(), effectResult.result)
                  is MethodBody.Status.ForInference -> throw IllegalStateException()
                  is MethodBody.Status.Abstract,
                  is MethodBody.Status.BasicConstructor -> return@fold m
                }
              is EffectResult.Inference -> effectResult.result
              is EffectResult.Inapplicable -> return@fold m
            }
          m.put(
            methodId,
            ResultTemplate(
              methodBody.initEnvironment.types,
              methodBody.domains,
              methodSummary.value,
              effect,
            ),
          )
        }
      classId to classSummary
    }
    .unzip()
