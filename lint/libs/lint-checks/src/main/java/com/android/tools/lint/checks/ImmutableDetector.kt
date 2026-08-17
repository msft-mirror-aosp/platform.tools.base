/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tools.lint.checks

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement

/** Check to enforce Error Prone's `@Immutable` check. */
class ImmutableDetector :
  AbstractSafetyDetector(
    typeAnnos = listOf(IMMUTABLE_ANNO),
    typeAnnosWithContainParams = listOf(IMMUTABLE_ANNO),
    strictTypeParamAnnos = listOf(IMMUTABLE_TYPE_PARAMETER_ANNO),
    containerTypeParamAnnos = emptyList(),
    strictTypeParamAnnosToCheck = listOf(IMMUTABLE_TYPE_PARAMETER_ANNO),
    variableSuppressionAnnoNames = emptyList(),
    knownSafeTypeMap = KNOWN_IMMUTABLE,
    unsafeAdj = "mutable",
    safeAdj = "immutable",
  ) {

  override val issue: Issue
    get() = ISSUE

  @Suppress("UElementAsPsi")
  override fun shouldAnalyzeClass(node: UClass, context: JavaContext): Boolean {
    // Enums are checked by ImmutableEnum
    if (node.isEnum) return false

    if (!node.hasDirectOrInheritedAnno(IMMUTABLE_ANNO)) {
      val immutableSuper =
        node.supers.firstOrNull {
          // Don't check KNOWN_IMMUTABLE here: subtypes of trusted types are
          // also trusted. Only check for explicitly annotated supertypes.
          it.hasAnnotation(IMMUTABLE_ANNO)
        } ?: return false
      // Anonymous classes have null names. They cannot be annotated immutable, so we treat
      // them like they are annotated if they have an immutable supertype.
      if (!node.name.isNullOrBlank()) {
        context.report(
          ISSUE,
          node,
          context.getLocation(node as UElement),
          "Class extends @Immutable type ${immutableSuper.qualifiedName}, " + "but is not annotated as immutable",
        )
        return false
      }
    }

    return true
  }

  companion object {
    const val IMMUTABLE_ANNO = "com.google.errorprone.annotations.Immutable"
    const val IMMUTABLE_TYPE_PARAMETER_ANNO = "com.google.errorprone.annotations.ImmutableTypeParameter"

    private val IMPLEMENTATION = Implementation(ImmutableDetector::class.java, Scope.JAVA_FILE_SCOPE)

    @JvmField
    val ISSUE: Issue =
      Issue.create(
        id = "Immutable",
        briefDescription = "Type declaration annotated with @Immutable is not immutable",
        explanation =
          """
          This check validates that all classes annotated with Error Prone's @Immutable annotation are \
          deeply immutable. It also checks that any class extending an @Immutable-annotated class or \
          implementing an @Immutable-annotated interface are also immutable.
          """,
        category = Category.CORRECTNESS,
        priority = 6,
        severity = Severity.ERROR,
        enabledByDefault = false,
        moreInfo = @Suppress("LintImplUnexpectedDomain") "https://errorprone.info/bugpattern/Immutable",
        implementation = IMPLEMENTATION,
      )

    /**
     * A map of known immutable types.
     *
     * The keys are fully qualified type names, and the values are lists of type parameter names that are "contained" by this type and must
     * themselves be immutable (e.g., "E" in `ImmutableList<E>`).
     */
    private val KNOWN_IMMUTABLE: Map<String, List<String>> =
      mapOf(
        // Primitive types and wrappers
        "boolean" to emptyList(),
        "byte" to emptyList(),
        "char" to emptyList(),
        "double" to emptyList(),
        "float" to emptyList(),
        "int" to emptyList(),
        "long" to emptyList(),
        "short" to emptyList(),
        "void" to emptyList(),
        "java.lang.Boolean" to emptyList(),
        "java.lang.Byte" to emptyList(),
        "java.lang.Character" to emptyList(),
        "java.lang.Double" to emptyList(),
        "java.lang.Float" to emptyList(),
        "java.lang.Integer" to emptyList(),
        "java.lang.Long" to emptyList(),
        "java.lang.Short" to emptyList(),
        "java.lang.Void" to emptyList(),
        // Core Java
        "java.lang.Class" to emptyList(),
        "java.lang.Enum" to listOf("E"),
        "java.lang.String" to emptyList(),
        "java.lang.annotation.Annotation" to emptyList(),
        "java.lang.invoke.MethodHandle" to emptyList(),
        "java.math.BigDecimal" to emptyList(),
        "java.math.BigInteger" to emptyList(),
        "java.net.Inet4Address" to emptyList(),
        "java.net.Inet6Address" to emptyList(),
        "java.net.InetAddress" to emptyList(),
        "java.net.URI" to emptyList(),
        "java.net.http.HttpClient" to emptyList(),
        "java.nio.ByteOrder" to emptyList(),
        "java.nio.charset.Charset" to emptyList(),
        "java.nio.file.Path" to emptyList(),
        "java.nio.file.WatchEvent" to emptyList(),
        "java.nio.file.attribute.AclEntry" to emptyList(),
        "java.nio.file.attribute.FileTime" to emptyList(),
        "java.util.AbstractMap.SimpleImmutableEntry" to listOf("K", "V"),
        "java.util.Locale" to emptyList(),
        "java.util.Optional" to listOf("T"),
        "java.util.OptionalDouble" to emptyList(),
        "java.util.OptionalInt" to emptyList(),
        "java.util.OptionalLong" to emptyList(),
        "java.util.UUID" to emptyList(),
        "java.util.regex.Pattern" to emptyList(),
        // Java Time
        "java.time.Clock" to emptyList(),
        "java.time.Duration" to emptyList(),
        "java.time.Instant" to emptyList(),
        "java.time.LocalDate" to emptyList(),
        "java.time.LocalDateTime" to emptyList(),
        "java.time.LocalTime" to emptyList(),
        "java.time.MonthDay" to emptyList(),
        "java.time.OffsetDateTime" to emptyList(),
        "java.time.OffsetTime" to emptyList(),
        "java.time.Period" to emptyList(),
        "java.time.Year" to emptyList(),
        "java.time.YearMonth" to emptyList(),
        "java.time.ZoneId" to emptyList(),
        "java.time.ZoneOffset" to emptyList(),
        "java.time.ZonedDateTime" to emptyList(),
        "java.time.chrono.AbstractChronology" to emptyList(),
        "java.time.chrono.ChronoLocalDate" to emptyList(),
        "java.time.chrono.ChronoLocalDateTime" to listOf("D"),
        "java.time.chrono.ChronoPeriod" to emptyList(),
        "java.time.chrono.ChronoZonedDateTime" to listOf("D"),
        "java.time.chrono.Chronology" to emptyList(),
        "java.time.chrono.Era" to emptyList(),
        "java.time.chrono.HijrahChronology" to emptyList(),
        "java.time.chrono.HijrahDate" to emptyList(),
        "java.time.chrono.IsoChronology" to emptyList(),
        "java.time.chrono.JapaneseChronology" to emptyList(),
        "java.time.chrono.JapaneseDate" to emptyList(),
        "java.time.chrono.JapaneseEra" to emptyList(),
        "java.time.chrono.MinguoChronology" to emptyList(),
        "java.time.chrono.MinguoDate" to emptyList(),
        "java.time.chrono.ThaiBuddhistChronology" to emptyList(),
        "java.time.chrono.ThaiBuddhistDate" to emptyList(),
        "java.time.format.DateTimeFormatter" to emptyList(),
        "java.time.format.DecimalStyle" to emptyList(),
        "java.time.temporal.TemporalField" to emptyList(),
        "java.time.temporal.TemporalUnit" to emptyList(),
        "java.time.temporal.ValueRange" to emptyList(),
        "java.time.temporal.WeekFields" to emptyList(),
        "java.time.zone.ZoneOffsetTransition" to emptyList(),
        "java.time.zone.ZoneOffsetTransitionRule" to emptyList(),
        "java.time.zone.ZoneRules" to emptyList(),
        "java.time.zone.ZoneRulesProvider" to emptyList(),
        // Android
        "android.net.Uri" to emptyList(),
        "android.util.Size" to emptyList(),
        // Guava
        "com.google.common.base.CharMatcher" to emptyList(),
        "com.google.common.base.Converter" to emptyList(),
        "com.google.common.base.Joiner" to emptyList(),
        "com.google.common.base.Optional" to listOf("T"),
        "com.google.common.base.Splitter" to emptyList(),
        "com.google.common.collect.ContiguousSet" to listOf("C"),
        "com.google.common.collect.ImmutableBiMap" to listOf("K", "V"),
        "com.google.common.collect.ImmutableCollection" to listOf("E"),
        "com.google.common.collect.ImmutableList" to listOf("E"),
        "com.google.common.collect.ImmutableListMultimap" to listOf("K", "V"),
        "com.google.common.collect.ImmutableMap" to listOf("K", "V"),
        "com.google.common.collect.ImmutableMultimap" to listOf("K", "V"),
        "com.google.common.collect.ImmutableMultiset" to listOf("E"),
        "com.google.common.collect.ImmutableRangeMap" to listOf("K", "V"),
        "com.google.common.collect.ImmutableRangeSet" to listOf("C"),
        "com.google.common.collect.ImmutableSet" to listOf("E"),
        "com.google.common.collect.ImmutableSetMultimap" to listOf("K", "V"),
        "com.google.common.collect.ImmutableSortedMap" to listOf("K", "V"),
        "com.google.common.collect.ImmutableSortedMultiset" to listOf("E"),
        "com.google.common.collect.ImmutableSortedSet" to listOf("E"),
        "com.google.common.collect.ImmutableTable" to listOf("R", "C", "V"),
        "com.google.common.collect.Range" to listOf("C"),
        "com.google.common.graph.ImmutableGraph" to listOf("N"),
        "com.google.common.graph.ImmutableNetwork" to listOf("N", "E"),
        "com.google.common.graph.ImmutableValueGraph" to listOf("N", "V"),
        "com.google.common.hash.HashCode" to emptyList(),
        "com.google.common.io.BaseEncoding" to emptyList(),
        "com.google.common.net.MediaType" to emptyList(),
        "com.google.common.primitives.UnsignedInteger" to emptyList(),
        "com.google.common.primitives.UnsignedLong" to emptyList(),
        // Protobuf
        "com.google.protobuf.ByteString" to emptyList(),
        "com.google.protobuf.Descriptors.Descriptor" to emptyList(),
        "com.google.protobuf.Descriptors.EnumDescriptor" to emptyList(),
        "com.google.protobuf.Descriptors.EnumValueDescriptor" to emptyList(),
        "com.google.protobuf.Descriptors.FieldDescriptor" to emptyList(),
        "com.google.protobuf.Descriptors.FileDescriptor" to emptyList(),
        "com.google.protobuf.Descriptors.OneofDescriptor" to emptyList(),
        "com.google.protobuf.Descriptors.ServiceDescriptor" to emptyList(),
        "com.google.protobuf.Extension" to emptyList(),
        "com.google.protobuf.ExtensionRegistry.ExtensionInfo" to emptyList(),
        "com.google.protobuf.GeneratedMessage" to emptyList(),
        "com.google.protobuf.Parser" to emptyList(),
        // Kotlin
        "kotlin.Lazy" to listOf("T"),
        "kotlin.Pair" to listOf("A", "B"),
        "kotlin.Triple" to listOf("A", "B", "C"),
        "kotlin.UByte" to emptyList(),
        "kotlin.UInt" to emptyList(),
        "kotlin.ULong" to emptyList(),
        "kotlin.UShort" to emptyList(),
        "kotlin.Unit" to emptyList(),
        "kotlin.jvm.internal.LongCompanionObject" to emptyList(),
        "kotlin.jvm.internal.StringCompanionObject" to emptyList(),
        "kotlin.ranges.IntRange" to emptyList(),
        "kotlin.ranges.LongRange" to emptyList(),
        "kotlin.ranges.UIntRange" to emptyList(),
        "kotlin.ranges.ULongRange" to emptyList(),
        "kotlin.reflect.KClass" to emptyList(),
        "kotlin.text.Regex" to emptyList(),
        "kotlin.time.Duration" to emptyList(),
      )
  }
}
