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
package com.android.tools.lint.checks.fx.result

import com.android.tools.lint.checks.fx.utils.Encoder
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.adapt
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.case
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.zeroOrMore
import com.android.tools.lint.checks.fx.utils.InterningPool
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import java.io.File
import kotlin.reflect.KClass
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * A [ClassId] identifies a class.
 *
 * Most classes have fully qualified named. Anonymous classes also have an explicit internal
 * representation of their "names", which are the source file location. We also conflate Java and
 * Kotlin's counterparts for the same classes.
 *
 * Users of [ClassId] are expected to create instances through an appropriate overloading of
 * [ClassId.of].
 */
sealed interface ClassId {
  val fqn: String?
    get() = null

  private class Named(fqn: String) : ClassId {
    override val fqn = InterningPool.string(fqn)

    override fun equals(other: Any?) = other is Named && fqn === other.fqn

    override fun hashCode() = System.identityHashCode(fqn)

    override fun toString(): String {
      val untilBracket =
        when (val i = fqn.indexOf('<')) {
          -1 -> fqn
          else -> fqn.substring(0, i)
        }
      return when (val j = untilBracket.lastIndexOf('.')) {
        -1 -> fqn
        else -> fqn.substring(j + 1, fqn.length)
      }
    }

    companion object {
      fun of(name: String): Named {
        val bracketStart = name.indexOf('<')
        return Named(if (bracketStart == -1) name else name.substring(0, bracketStart))
      }
    }
  }

  private sealed class Anon : ClassId {
    abstract val path: String

    abstract fun offsetString(): String

    private val str by
      lazy(LazyThreadSafetyMode.NONE) { "⸨Anon@$${File(path).name}:${offsetString()}⸩" }

    final override fun toString() = str

    // TODO: This may not be worth it. The `Precise` class exists just so we have user-friendly
    //  information in the class's pretty printing. It's creation is heavyweight, and sometimes
    //  we don't have the information, so resort to `Imprecise`.
    data class Precise(override val path: String, val line: Int, val col1: Int, val col2: Int) :
      Anon() {
      override fun offsetString() = if (line == -1) "?" else "$line:$col1-$col2"
    }

    data class Imprecise(override val path: String, val offset: Int) : Anon() {
      override fun offsetString() = "~$offset"
    }

    companion object {
      operator fun invoke(elem: PsiClass): Anon {
        val file = elem.containingFile
        return when (val textRange = elem.textRange) {
          null -> Imprecise(file.virtualFile.path, elem.textOffset)
          else -> {
            val doc = PsiDocumentManager.getInstance(elem.project).getDocument(file)!!
            val startOffset = textRange.startOffset
            val l = if (startOffset == -1) -1 else doc.getLineNumber(startOffset) + 1
            val c1 = startOffset - doc.getLineStartOffset(doc.getLineNumber(startOffset)) + 1
            val c2 = c1 + textRange.endOffset - startOffset
            Precise(file.virtualFile.path, l, c1, c2)
          }
        }
      }
    }
  }

  /**
   * Imaginary class that's like [base], but is guarded by [guard]. The [guard] is treated opaquely,
   * but should have well-defined equality to be part of the identifier.
   */
  data class Guarded internal constructor(val guard: Any, val base: ClassId) : ClassId {
    override fun toString() = "$guard ◁ $base"
  }

  private enum class Common(val aliases: List<String>) : ClassId {
    Boolean("java.lang.Boolean", "kotlin.Boolean", "boolean"),
    Int("java.lang.Integer", "kotlin.Int", "int"),
    Char("java.lang.Char", "kotlin.Char", "char"),
    Byte("java.lang.Byte", "kotlin.Byte", "byte"),
    Short("java.lang.Short", "kotlin.Short", "short"),
    Long("java.lang.Long", "kotlin.Long", "long"),
    Float("java.lang.Float", "kotlin.Float", "float"),
    Double("java.lang.Double", "kotlin.Double", "double"),
    String("java.lang.String", "kotlin.String"),
    Unit("kotlin.Unit", "void"),
    Array("kotlin.Array");

    constructor(vararg aliases: String) : this(aliases.asList())
  }

  /** Imaginary class containing local functions */
  data class Local internal constructor(val root: ClassId, val path: List<MethodId>) : ClassId {
    init {
      require(path.isNotEmpty())
    }

    fun asContainingClassAndInnermostMethod(): Pair<ClassId, MethodId> {
      val parentClassId =
        when {
          path.size == 1 -> root
          else -> Local(root, path.subList(0, path.size - 1))
        }
      return parentClassId to path.last()
    }

    override fun toString() = "$root↝${path.joinToString("↝") { it.name }}"
  }

  companion object {

    fun of(ref: PsiClass): ClassId =
      when (val fqn = ref.qualifiedName) {
        null -> Anon(ref)
        else -> of(fqn)
      }

    fun of(fqn: String): ClassId = Common.entries.find { fqn in it.aliases } ?: Named.of(fqn)

    /**
     * Generate a [ClassId] specific to [base] and a [guard]. The [guard]'s type isn't tracked, but
     * it should be a "tag" with well-defined equality, reflecting the guard's value.
     */
    internal fun of(guard: Any, base: PsiClass): ClassId = Guarded(guard, of(base.qualifiedName!!))

    // TODO: Is `canonicalText` always the fully qualified name??
    fun of(type: PsiClassType): ClassId = of(type.canonicalText)

    fun of(c: KClass<*>): ClassId = of(c.java.canonicalName)

    inline fun <reified C> of(): ClassId = of(C::class)

    val Array: ClassId = Common.Array

    fun encoder(guardEncoder: Encoder<Any>, methodIdEncoder: Encoder<MethodId>): Encoder<ClassId> =
      Encoder.fix { self ->
        val int = Encoder.int
        val str = Encoder.internedString
        Encoder.sum(
          case<_, Named>(str.adapt(Named::fqn, ::Named)),
          case<_, Anon.Precise>(Encoder.product(Anon::Precise, str, int, int, int)),
          case<_, Anon.Imprecise>(Encoder.product(Anon::Imprecise, str, int)),
          case<_, Common>(Encoder.enum()),
          case<_, Guarded>(Encoder.product(ClassId::Guarded, guardEncoder, self)),
          case<_, Local>(Encoder.product(ClassId::Local, self, methodIdEncoder.zeroOrMore())),
        )
      }
  }
}

/**
 * A [ClassBody] represents a class's definition as far as the analysis is concerned.
 *
 * We take the "closure-converted" view of the program. If the class is nested within other classes
 * and methods, its [initEnvironment] accumulates all from that class's lexical scope.
 *
 * In the following example:
 *
 *  ```
 *  class A<X> {
 *    val a: X
 *    inner class B<Y> {
 *      val b: Int
 *      fun<Z> f(c: Z) {
 *        val d: Long
 *        class C<T> {
 *           val v: T
 *         }
 *      }
 *    }
 *  }
 *  ```
 *
 * class `C`'s [initEnvironment] is `[X, Y, Z, T; a: X, b: Int, c: Z, v: T]`. Notice that `d` is
 * missing even though it's captured. This is ok-ish, because it's in an "existential" position, and
 * the purpose of [initEnvironment] is to map the fields to more precise instantiated type
 * parameters instead of resorting to the underlying type system.
 */
internal data class ClassBody<out FX>(
  val supers: List<ClassId>,
  val methods: PersistentMap<MethodId, MethodBody<FX>>,
  val initEnvironment: Env<Nothing>,
) {
  companion object {
    val empty = ClassBody<Nothing>(listOf(), persistentMapOf(), Env.empty)
  }
}
