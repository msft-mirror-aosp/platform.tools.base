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

package com.android.tools.render.animation

import androidx.compose.animation.tooling.ComposeAnimatedProperty
import androidx.compose.animation.tooling.ComposeAnimation
import androidx.compose.animation.tooling.ComposeAnimationType
import androidx.compose.animation.tooling.TransitionInfo
import com.android.tools.render.CALLBACK_EXECUTIONS_PER_ROUND
import com.android.tools.render.CALLBACK_ROUNDS
import com.android.tools.render.CALLBACK_TIME_STEP_NANOS
import com.android.tools.render.DEFAULT_RENDER_THREAD_TIMEOUT_SECONDS
import com.android.tools.render.RENDER_TIMEOUT_SECONDS
import com.android.tools.render.StaticPreviewCallbackRunner
import com.android.tools.render.common.ComponentSourceLocation
import com.android.tools.render.common.ComponentTreeExtractor.findField
import com.android.tools.render.common.ComponentTreeExtractor.findZeroArgMethod
import com.android.tools.render.common.ComponentTreeExtractor.invokeGetter
import com.android.tools.render.common.ComposeParameterExtractor
import com.android.tools.render.findComposeViewAdapters
import com.android.tools.render.findTimeoutException
import com.android.tools.render.resetComposeUiDispatcherScheduling
import com.android.tools.render.rethrowIfFatalVirtualMachineError
import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.RenderTask
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.util.Disposer
import java.awt.image.BufferedImage
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Inspects Compose slot-table animations and steps `androidx.compose.ui.tooling.animation.PreviewAnimationClock` inside a single warm
 * Layoutlib [RenderTask] session:
 * 1. **Static animation detection ([detectAnimationsFromAdapter]):** Short-circuits via `ComposeViewAdapter.hasAnimations()` when false and
 *    delegates animated-preview discovery to `ui-tooling`'s `AnimationSearch` without initializing `PreviewAnimationClock` or mutating live
 *    preview state.
 * 2. **State setup ([configureStates]):** Toggles `fromState` -> `toState` and `AnimatedVisibility` `Enter`/`Exit` segments on the render
 *    thread (including an automatic non-empty trajectory when initial and target states start equal).
 * 3. **Curve & frame capture ([stepAndCaptureAnimation]):** Runs a size-measurement pass when size-dependent containers are present, reads
 *    [TransitionInfo] curves and `AnimationSpec` metadata, samples adaptive timestamps via [AnimationFrameSampler], and renders frame PNGs.
 *
 * ### Architectural Boundary with `androidx.compose.ui:ui-tooling`
 * `androidx.compose.animation.tooling.*` ([ComposeAnimation], [ComposeAnimationType], [ComposeAnimatedProperty], [TransitionInfo]) is
 * loaded from the shared parent classloader (`ALLOWED_PACKAGES_FROM_PARENT` in `ClassLoaderConstants.kt`) and used directly without
 * reflection, matching Android Studio's `compose-designer` `AnimationClock.kt`. `ComposeViewAdapter`, `PreviewAnimationClock`, and
 * `AnimationSearch` live in the user project's `DefaultModuleClassLoader` (with `kotlin.*` repackaged to
 * `_layoutlib_._internal_.kotlin.*`):
 * - **Clock subscription & stepping (`animate`):** Setting `tools:animationClockStartTime="0"` causes `ComposeViewAdapter` to run
 *   `AnimationSearch.attachAllAnimations` and subscribe supported animations to `PreviewAnimationClock`, which [AnimationPreviewRunner]
 *   drives via `setClockTime`, `updateFromAndToStates`, `updateAnimatedVisibilityState`, `getTransitions`, and `getAnimatedProperties`.
 * - **Static slot-table discovery (`--detect-animations`):** `ComposeViewAdapter.onLayout` already evaluates `AnimationSearch.searchAny`
 *   into `adapter.hasAnimations()`, so static previews short-circuit immediately. When animations are present,
 *   [UiToolingAnimationSearchBridge] runs `Search.addAnimations(allGroups)` + `Search.getAnimations()` **without** `Search.track()`, and
 *   [indexSlotTreeMetadata] attaches user `.kt` [ComponentSourceLocation]s, `animate*AsState` call names, and scoped child containers.
 */
object AnimationPreviewRunner {
  private val logger = Logger.getLogger(AnimationPreviewRunner::class.java.name)

  private const val TOOLS_NAMESPACE_DECL = """xmlns:tools="http://schemas.android.com/tools""""
  private const val COMPOSE_ADAPTER_TAG = "<androidx.compose.ui.tooling.ComposeViewAdapter"
  private val EXISTING_CLOCK_ATTR_REGEX = Regex("""\btools:animationClockStartTime\s*=\s*"[^"]*"""")
  private val COLOR_TO_STRING_REGEX =
    Regex("""Color\(\s*([+-]?\d*\.?\d+)\s*,\s*([+-]?\d*\.?\d+)\s*,\s*([+-]?\d*\.?\d+)\s*,\s*([+-]?\d*\.?\d+)""")

  /** The prefix of the labels of the animations `AnimatedVisibility` and `AnimatedContent` create (e.g. "Built-in alpha"). */
  private const val BUILT_IN_PREFIX = "Built-in "
  private val ANIMATED_CONTAINERS = setOf("AnimatedVisibility", "AnimatedContent")
  /** Animation types whose curves can depend on layout measurements after the clock advances (e.g. `expandVertically`). */
  private val SIZE_DEPENDENT_ANIMATION_TYPES = setOf("ANIMATED_VISIBILITY", "ANIMATED_CONTENT", "ANIMATE_CONTENT_SIZE")
  /** Layouts that only wrap the content of an `AnimatedVisibility`: the composable they wrap names it better. */
  private val GENERIC_LAYOUTS = setOf("Box", "Row", "Column", "Surface", "Card", "Layout")
  private val EFFECTS = setOf("LaunchedEffect", "DisposableEffect", "SideEffect")
  private const val MAX_TRANSITION_SEARCH_DEPTH = 4
  private const val MAX_CONTENT_SEARCH_DEPTH = 12

  private val CLOCK_MAP_FIELDS =
    listOf(
      "transitionClocks",
      "animatedVisibilityClocks",
      "animateXAsStateClocks",
      "animatedContentClocks",
      "infiniteTransitionClocks",
    )

  private val FRAMEWORK_SOURCE_FILES =
    setOf(
      "Transition.kt",
      "AnimateAsState.kt",
      "AnimatedVisibility.kt",
      "AnimatedContent.kt",
      "InfiniteTransition.kt",
      "AnimationModifier.kt",
      "ComposeViewAdapter.android.kt",
      "Inspectable.android.kt",
    )

  private val ANIMATE_AS_STATE_CALL_NAMES =
    setOf(
      "animateFloatAsState",
      "animateDpAsState",
      "animateColorAsState",
      "animateIntAsState",
      "animateOffsetAsState",
      "animateSizeAsState",
      "animateIntOffsetAsState",
      "animateIntSizeAsState",
      "animateRectAsState",
      "animateValueAsState",
    )

  private class BridgeHolder(val bridge: UiToolingAnimationSearchBridge?)

  /** Per-`ComposeViewAdapter` class cache of resolved `ui-tooling` `AnimationSearch` reflection handles. */
  private val animationSearchBridgeByAdapterClass =
    object : ClassValue<BridgeHolder>() {
      override fun computeValue(type: Class<*>): BridgeHolder = BridgeHolder(UiToolingAnimationSearchBridge.resolve(type))
    }

  /**
   * Injects `tools:animationClockStartTime="[startTimeMillis]"` into a `<ComposeViewAdapter .../>` XML layout string so
   * `ComposeViewAdapter.init(AttributeSet)` initializes `PreviewAnimationClock`.
   */
  fun injectAnimationClockStartTime(xmlLayout: String, startTimeMillis: Long = 0L): String {
    if (!xmlLayout.contains(COMPOSE_ADAPTER_TAG)) return xmlLayout
    val attr = """tools:animationClockStartTime="$startTimeMillis""""
    if (EXISTING_CLOCK_ATTR_REGEX.containsMatchIn(xmlLayout)) {
      return xmlLayout.replace(EXISTING_CLOCK_ATTR_REGEX, attr)
    }
    val withNamespace =
      if (xmlLayout.contains("xmlns:tools=")) {
        xmlLayout
      } else {
        xmlLayout.replaceFirst(COMPOSE_ADAPTER_TAG, "$COMPOSE_ADAPTER_TAG\n    $TOOLS_NAMESPACE_DECL")
      }
    return withNamespace.replaceFirst(COMPOSE_ADAPTER_TAG, "$COMPOSE_ADAPTER_TAG\n    $attr")
  }

  /**
   * Inspects a rendered `ComposeViewAdapter` (without modifying its clock) to determine whether the preview contains Compose animations and
   * extract their types, labels, and user `.kt` source locations from `ui-tooling`'s `AnimationSearch`.
   *
   * `ComposeViewAdapter.onLayout` already runs `AnimationSearch.searchAny` and caches the boolean in `hasAnimations()`, so static previews
   * (`hasAnimations() == false`) return immediately without inspecting slot trees.
   *
   * @param slotTrees Optional pre-extracted `SlotTreeKt.asTree` roots for [adapter], shared with `ComposeParameterExtractor` to avoid
   *   converting the slot table twice.
   */
  fun detectAnimationsFromAdapter(
    adapter: Any,
    slotTrees: List<Any>? = null,
  ): Pair<Boolean, List<DetectedAnimationSummary>> {
    val hasAnimationsMethod = findZeroArgMethod(adapter.javaClass, "hasAnimations")
    if (hasAnimationsMethod != null) {
      val hasAnimationsFlag =
        try {
          hasAnimationsMethod.invoke(adapter) as? Boolean
        } catch (e: Exception) {
          logger.log(Level.FINE, "Failed to invoke ComposeViewAdapter.hasAnimations()", e)
          null
        }
      if (hasAnimationsFlag == false) {
        return false to emptyList()
      }
      val detected = extractSlotTableAnimations(adapter, slotTrees)
      return ((hasAnimationsFlag == true) || detected.isNotEmpty()) to detected
    }

    val detected = extractSlotTableAnimations(adapter, slotTrees)
    return detected.isNotEmpty() to detected
  }

  private fun extractSlotTableAnimations(adapter: Any, slotTrees: List<Any>? = null): List<DetectedAnimationSummary> {
    return try {
      val roots = slotTrees ?: ComposeParameterExtractor.extractSlotTrees(adapter)
      if (roots.isEmpty()) emptyList() else detectAnimationsViaUiToolingSearch(adapter, roots).orEmpty()
    } catch (e: Exception) {
      logger.log(Level.FINE, "Failed to extract animation summaries from slot table", e)
      emptyList()
    } catch (e: LinkageError) {
      logger.log(Level.FINE, "Linkage error extracting animation summaries from slot table", e)
      emptyList()
    }
  }

  /**
   * Runs `androidx.compose.ui.tooling.animation.AnimationSearch` from [adapter]'s `ClassLoader` on [roots] without calling `Search.track()`
   * (so live preview state is never mutated), returning `null` only if `AnimationSearch` is unavailable or [roots] are not `Group`
   * instances from that `ClassLoader`.
   */
  @VisibleForTesting
  internal fun detectAnimationsViaUiToolingSearch(adapter: Any, roots: List<Any>): List<DetectedAnimationSummary>? {
    if (roots.isEmpty()) return emptyList()
    val bridge = animationSearchBridgeByAdapterClass.get(adapter.javaClass).bridge ?: return null
    return bridge.detect(roots)
  }

  /** Source-site metadata recorded for a runtime animation/modifier object during [indexSlotTreeMetadata]. */
  private class ObjectSourceSite(
    val userLocation: ComponentSourceLocation?,
    val animateXCallName: String?,
    val animateXCallLocation: ComponentSourceLocation?,
    val visitOrder: Int,
  )

  /** Candidate scoped `Transition.AnimatedVisibility` or `Transition.AnimatedContent` container encountered in the slot tree. */
  private class ScopedContainerCandidate(
    val type: String,
    val label: String,
    val sourceLocation: ComponentSourceLocation?,
    val siteKey: String,
    val visitOrder: Int,
  )

  /** Single-pass slot-tree enrichment index pairing runtime animation objects from `AnimationSearch` with user `.kt` call sites. */
  private class IndexedSlotMetadata(
    val siteByObject: IdentityHashMap<Any, ObjectSourceSite>,
    val containerSiteByTransition: IdentityHashMap<Any, Pair<ComponentSourceLocation?, Int>>,
    val scopedContainers: List<ScopedContainerCandidate>,
  )

  private fun nonBlankString(target: Any?, getter: String = "label"): String? =
    (target?.let { invokeGetter(it, getter) } as? String)?.takeIf { it.isNotBlank() }

  /**
   * Cached reflective bridge to `androidx.compose.ui.tooling.animation.AnimationSearch` for a specific preview `ClassLoader`.
   *
   * Invokes `Search.addAnimations(allGroups)` + `Search.getAnimations()` directly instead of `AnimationSearch.attachAllAnimations` because
   * `attachAllAnimations` calls `Search.track()`, which invokes `clock()` and `setClockTime(0L)` and would mutate live `Transition` /
   * `ToolingState` instances during a static render.
   */
  private class UiToolingAnimationSearchBridge(
    private val groupClass: Class<*>,
    private val groupGetChildren: Method,
    private val groupGetName: Method,
    private val ctor: Constructor<*>,
    private val ctorArgs: Array<Any>,
    private val setToSearchField: Field,
    private val triggerSearchField: Field?,
    private val addAnimationsMethod: Method,
    private val getAnimationsMethod: Method,
  ) {
    fun detect(roots: List<Any>): List<DetectedAnimationSummary>? {
      if (roots.any { !groupClass.isInstance(it) }) return null

      return try {
        // 1. Flatten all `Group`s in pre-order (matching `PreviewUtils.findAll { true }` in `ui-tooling`) and note whether any
        //    `AnimatedVisibility` or `AnimatedContent` group exists (for scoped `transition.AnimatedVisibility` child containers).
        val allGroups = ArrayList<Any>(64)
        var hasAnimatedContainerGroup = false
        fun flatten(group: Any) {
          allGroups.add(group)
          val gName = groupGetName.invoke(group) as? String
          if (gName == "AnimatedVisibility" || gName == "AnimatedContent") hasAnimatedContainerGroup = true
          (groupGetChildren.invoke(group) as? Collection<*>)?.forEach { if (it != null) flatten(it) }
        }
        roots.forEach(::flatten)

        // 2. Run `addAnimations(allGroups)` on every search in `setToSearch` (plus `triggerSearch` in Compose 1.12+) without `track()`.
        val animSearch = ctor.newInstance(*ctorArgs)
        val searches = LinkedHashSet<Any>()
        (setToSearchField.get(animSearch) as? Collection<*>)?.forEach { if (it != null) searches.add(it) }
        (triggerSearchField?.get(animSearch) as? Collection<*>)?.forEach { if (it != null) searches.add(it) }
        if (searches.isEmpty()) return null

        val discoveredBySearch = ArrayList<Pair<String, List<Any>>>(searches.size)
        var anyDiscovered = false
        for (search in searches) {
          addAnimationsMethod.invoke(search, allGroups)
          val items = (getAnimationsMethod.invoke(search) as? Set<*>)?.filterNotNull().orEmpty()
          if (items.isNotEmpty()) anyDiscovered = true
          discoveredBySearch.add(search.javaClass.simpleName to items)
        }
        if (!anyDiscovered && !hasAnimatedContainerGroup) return emptyList()

        // 3. Enrich discovered animations with user `.kt` source locations and deduplicate container transitions before `TransitionSearch`.
        val metadata = indexSlotTreeMetadata(roots)
        val orderedOut = ArrayList<Pair<Int, DetectedAnimationSummary>>()
        val claimedTransitions = IdentityHashMap<Any, Boolean>()
        val standaloneContainerSiteKeys = HashSet<String>()
        val seenKeys = HashSet<String>()
        var fallbackOrder = 1_000_000

        fun emit(key: String, order: Int?, type: String, label: String, loc: ComponentSourceLocation?) {
          if (seenKeys.add(key)) {
            orderedOut.add((order ?: fallbackOrder++) to DetectedAnimationSummary(type = type, label = label, sourceLocation = loc))
          }
        }

        // Process `AnimatedVisibilitySearch` and `AnimatedContentSearch` first so their backing `Transition`s are claimed before
        // `TransitionSearch` (matching `AnimationSearch.attachAllAnimations`).
        discoveredBySearch.sortBy { (name, _) -> if (name == "AnimatedVisibilitySearch" || name == "AnimatedContentSearch") 0 else 1 }

        for ((searchName, items) in discoveredBySearch) {
          for (item in items) {
            when (searchName) {
              "AnimatedVisibilitySearch",
              "AnimatedContentSearch" -> {
                val isVis = searchName == "AnimatedVisibilitySearch"
                val type = if (isVis) "ANIMATED_VISIBILITY" else "ANIMATED_CONTENT"
                // Compose 1.5..1.10 stores `Transition<*>` directly; Compose 1.12+ wraps it in `*SearchInfo` with `.transition`/`.label`.
                val transition = invokeGetter(item, "transition") ?: item
                claimedTransitions[transition] = true
                val label =
                  (if (item !== transition) nonBlankString(item) else null)
                    ?: nonBlankString(transition)
                    ?: if (isVis) "AnimatedVisibility" else "AnimatedContent"
                val containerSite = metadata.containerSiteByTransition[transition]
                val objSite = metadata.siteByObject[transition]
                val loc = containerSite?.first ?: objSite?.userLocation
                standaloneContainerSiteKeys.add("$type:${loc?.fileName}:${loc?.lineNumber}")
                emit("$type:${System.identityHashCode(transition)}", containerSite?.second ?: objSite?.visitOrder, type, label, loc)
              }
              "TransitionSearch" -> {
                val transition = invokeGetter(item, "transition") ?: item
                if (claimedTransitions.containsKey(transition)) continue
                val site = metadata.siteByObject[transition]
                val label =
                  nonBlankString(transition)
                    ?: (if (item !== transition) nonBlankString(item) else null)
                    ?: invokeGetter(transition, "currentState")?.javaClass?.simpleName?.takeIf { it.isNotBlank() }
                    ?: "Transition"
                emit("TR:${System.identityHashCode(transition)}", site?.visitOrder, "TRANSITION", label, site?.userLocation)
              }
              "AnimateXAsStateSearch" -> {
                val animatable = invokeGetter(item, "animatable") ?: item
                val site = metadata.siteByObject[animatable]
                val label =
                  nonBlankString(animatable)
                    ?: (if (item !== animatable) nonBlankString(item) else null)
                    ?: site?.animateXCallName
                    ?: "animateValueAsState"
                emit(
                  "AX:${System.identityHashCode(animatable)}",
                  site?.visitOrder,
                  "ANIMATE_X_AS_STATE",
                  label,
                  site?.animateXCallLocation ?: site?.userLocation,
                )
              }
              "InfiniteTransitionSearch" -> {
                val inf = invokeGetter(item, "infiniteTransition") ?: item
                val site = metadata.siteByObject[inf]
                val label = nonBlankString(inf) ?: (if (item !== inf) nonBlankString(item) else null) ?: "InfiniteTransition"
                emit("INF:${System.identityHashCode(inf)}", site?.visitOrder, "INFINITE_TRANSITION", label, site?.userLocation)
              }
              "AnimateContentSizeSearch" -> {
                val site = metadata.siteByObject[item]
                val loc = site?.userLocation
                val key = if (loc != null) "SIZE:${loc.fileName}:${loc.lineNumber}" else "SIZE:${System.identityHashCode(item)}"
                emit(key, site?.visitOrder, "ANIMATE_CONTENT_SIZE", "animateContentSize", loc)
              }
              "TargetBasedSearch",
              "DecaySearch" -> {
                val isTarget = searchName == "TargetBasedSearch"
                val site = metadata.siteByObject[item]
                emit(
                  "${if (isTarget) "TB" else "DECAY"}:${System.identityHashCode(item)}",
                  site?.visitOrder,
                  if (isTarget) "TARGET_BASED" else "DECAY",
                  if (isTarget) "TargetBasedAnimation" else "DecayAnimation",
                  site?.userLocation,
                )
              }
              "TriggerSearch" -> {
                val animObj = invokeGetter(item, "animationObject") ?: item
                val site = metadata.siteByObject[animObj] ?: metadata.siteByObject[item]
                emit(
                  "TRIG:${System.identityHashCode(animObj)}",
                  site?.visitOrder,
                  "ANIMATABLE",
                  nonBlankString(item) ?: "Animatable",
                  site?.userLocation,
                )
              }
              else -> {
                val site = metadata.siteByObject[item]
                val label = nonBlankString(item) ?: searchName.removeSuffix("Search").ifBlank { "Animation" }
                emit("OTHER:$searchName:${System.identityHashCode(item)}", site?.visitOrder, "UNSUPPORTED", label, site?.userLocation)
              }
            }
          }
        }

        // Include scoped `transition.AnimatedVisibility` / `transition.AnimatedContent` child containers that `AnimationSearch` leaves to
        // their parent `TransitionClock`, so size-dependent child animations still trigger [shouldRunSizeDependentMeasurePass].
        for (scoped in metadata.scopedContainers) {
          if (scoped.siteKey !in standaloneContainerSiteKeys) {
            emit("SCOPED:${scoped.siteKey}:${scoped.label}", scoped.visitOrder, scoped.type, scoped.label, scoped.sourceLocation)
          }
        }

        orderedOut.sortBy { it.first }
        orderedOut.map { it.second }
      } catch (e: Exception) {
        logger.log(Level.FINE, "AnimationSearch bridge failed", e)
        null
      } catch (e: LinkageError) {
        logger.log(Level.FINE, "AnimationSearch bridge linkage error", e)
        null
      }
    }

    companion object {
      fun resolve(adapterClass: Class<*>): UiToolingAnimationSearchBridge? {
        val cl = adapterClass.classLoader ?: return null
        return try {
          val groupClass = Class.forName("androidx.compose.ui.tooling.data.Group", false, cl)
          val groupGetChildren = groupClass.getMethod("getChildren")
          val groupGetName = groupClass.getMethod("getName")
          val animSearchClass = Class.forName("androidx.compose.ui.tooling.animation.AnimationSearch", false, cl)
          val searchClass = Class.forName("androidx.compose.ui.tooling.animation.AnimationSearch\$Search", false, cl)
          val addAnimationsMethod = searchClass.getMethod("addAnimations", Collection::class.java)
          val getAnimationsMethod = searchClass.getMethod("getAnimations")
          val setToSearchField = findField(animSearchClass, "setToSearch") ?: return null
          val triggerSearchField = findField(animSearchClass, "triggerSearch")

          // Compose 1.5..1.10 uses a 2-arg `(Function0, Function0)` constructor; Compose 1.12+ uses a 1-arg `(Function0)` constructor.
          // Because `DefaultModuleClassLoader` repackages `kotlin.*` to `_layoutlib_._internal_.kotlin.*`, match `Function0` by interface
          // name suffix and build a reusable dynamic proxy once per `adapterClass`.
          val ctor =
            animSearchClass.constructors.firstOrNull { c ->
              (c.parameterCount == 1 || c.parameterCount == 2) && c.parameterTypes.all { it.isInterface && it.name.endsWith(".Function0") }
            } ?: return null
          val fn0Interface = ctor.parameterTypes[0]
          val unitInstance = runCatching {
            val kotlinPkg = fn0Interface.name.substringBeforeLast(".jvm.functions.")
            Class.forName("$kotlinPkg.Unit", false, cl).getField("INSTANCE").get(null)
          }
            .getOrNull()
          val noOpFn0 =
            Proxy.newProxyInstance(cl, arrayOf(fn0Interface)) { proxy, method, args ->
              when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NoOpAnimationSearchFunction0"
                else -> unitInstance
              }
            }
          val ctorArgs = Array<Any>(ctor.parameterCount) { noOpFn0 }

          UiToolingAnimationSearchBridge(
            groupClass = groupClass,
            groupGetChildren = groupGetChildren,
            groupGetName = groupGetName,
            ctor = ctor,
            ctorArgs = ctorArgs,
            setToSearchField = setToSearchField,
            triggerSearchField = triggerSearchField,
            addAnimationsMethod = addAnimationsMethod,
            getAnimationsMethod = getAnimationsMethod,
          )
        } catch (_: Exception) {
          null
        } catch (_: LinkageError) {
          null
        }
      }
    }
  }

  /**
   * Walks [roots] in pre-order once (only when animations were found) to map runtime objects in `Group.data` and `Group.modifierInfo` to
   * their nearest enclosing user `.kt` [ComponentSourceLocation] and `animate*AsState` call site, and to record scoped
   * `Transition.AnimatedVisibility` / `Transition.AnimatedContent` extension containers.
   */
  private fun indexSlotTreeMetadata(roots: List<Any>): IndexedSlotMetadata {
    val siteByObject = IdentityHashMap<Any, ObjectSourceSite>()
    val containerSiteByTransition = IdentityHashMap<Any, Pair<ComponentSourceLocation?, Int>>()
    val scopedContainers = ArrayList<ScopedContainerCandidate>()
    var orderCounter = 0

    fun recordModifierElements(modifier: Any, site: ObjectSourceSite) {
      if (modifier.javaClass.simpleName == "CombinedModifier") {
        findField(modifier.javaClass, "outer")?.get(modifier)?.let { recordModifierElements(it, site) }
        findField(modifier.javaClass, "inner")?.get(modifier)?.let { recordModifierElements(it, site) }
      } else {
        siteByObject.putIfAbsent(modifier, site)
      }
    }

    fun visit(
      group: Any,
      enclosingUserLocation: ComponentSourceLocation?,
      enclosingAnimateXCall: Pair<String, ComponentSourceLocation?>?,
    ) {
      val groupOrder = orderCounter++
      val name = (invokeGetter(group, "name") as? String).orEmpty()
      val ownLoc = extractGroupLocation(group)
      val userLoc = ownLoc?.takeIf { isUserSourceFile(it.fileName) } ?: enclosingUserLocation
      val children = invokeGetter(group, "children") as? Iterable<*>

      // Propagate typed wrapper names (`animateFloatAsState`, `animateDpAsState`, etc.) down to the inner `Animatable`.
      val nextAnimateXCall =
        if (name in ANIMATE_AS_STATE_CALL_NAMES && name != "animateValueAsState") name to userLoc else enclosingAnimateXCall

      if (name == "AnimatedVisibility" || name == "AnimatedContent") {
        val summaryType = if (name == "AnimatedVisibility") "ANIMATED_VISIBILITY" else "ANIMATED_CONTENT"
        val directTransition =
          children
            ?.firstOrNull { it != null && invokeGetter(it, "name") == "updateTransition" }
            ?.let { transitionsIn(it, depth = 0).firstOrNull() }
        if (directTransition != null) {
          // Standalone `AnimatedVisibility` / `AnimatedContent`: link its internal `updateTransition` back to this call site.
          containerSiteByTransition.putIfAbsent(directTransition, userLoc to groupOrder)
        } else if (ownLoc == null || isUserSourceFile(ownLoc.fileName)) {
          // Scoped `transition.AnimatedVisibility` / `transition.AnimatedContent` called from user code (no `updateTransition` child).
          scopedContainers.add(
            ScopedContainerCandidate(
              type = summaryType,
              label = name,
              sourceLocation = userLoc,
              siteKey = "$summaryType:${userLoc?.fileName}:${userLoc?.lineNumber}",
              visitOrder = groupOrder,
            )
          )
        }
      }

      val site =
        ObjectSourceSite(
          userLocation = userLoc,
          animateXCallName = nextAnimateXCall?.first,
          animateXCallLocation = nextAnimateXCall?.second,
          visitOrder = groupOrder,
        )

      (invokeGetter(group, "data") as? Iterable<*>)?.forEach { if (it != null) siteByObject.putIfAbsent(it, site) }
      (invokeGetter(group, "modifierInfo") as? Iterable<*>)?.forEach { modInfo ->
        modInfo?.let { invokeGetter(it, "modifier") }?.let { recordModifierElements(it, site) }
      }
      children?.forEach { if (it != null) visit(it, userLoc, nextAnimateXCall) }
    }

    roots.forEach { visit(it, null, null) }
    return IndexedSlotMetadata(siteByObject, containerSiteByTransition, scopedContainers)
  }

  private fun extractGroupLocation(group: Any): ComponentSourceLocation? {
    val loc = invokeGetter(group, "location") ?: return null
    val sourceFile = (invokeGetter(loc, "sourceFile") as? String).orEmpty()
    val lineNumber = (invokeGetter(loc, "lineNumber") as? Number)?.toInt() ?: -1
    val packageHash = (invokeGetter(loc, "packageHash") as? Number)?.toInt()?.takeIf { it != -1 }
    return if (sourceFile.isNotBlank() && lineNumber >= 0) ComponentSourceLocation(sourceFile, lineNumber, packageHash) else null
  }

  private fun isUserSourceFile(fileName: String): Boolean =
    fileName.isNotBlank() && fileName !in FRAMEWORK_SOURCE_FILES && !fileName.endsWith(".android.kt")

  private fun singleFrameFallbackManifest(
    previewId: String,
    methodFQN: String,
    displayName: String?,
    initialResult: RenderResult,
    postProcessImage: (RenderResult) -> BufferedImage?,
    saveFrameImage: (Int, BufferedImage) -> String,
    detectedSummaries: List<DetectedAnimationSummary> = emptyList(),
  ): PreviewAnimationManifest {
    val fallbackImg = postProcessImage(initialResult)
    val path = if (fallbackImg != null) saveFrameImage(0, fallbackImg) else ""
    return PreviewAnimationManifest(
      previewId = previewId,
      methodFQN = methodFQN,
      displayName = displayName,
      width = fallbackImg?.width ?: 0,
      height = fallbackImg?.height ?: 0,
      maxDurationMillis = 0L,
      maxDurationPerIterationMillis = 0L,
      effectiveDurationMillis = 0L,
      frameCount = if (path.isNotEmpty()) 1 else 0,
      animations = emptyList(),
      detectedAnimations = detectedSummaries,
      frames = if (path.isNotEmpty()) listOf(RenderedAnimationFrame(index = 0, timeMillis = 0L, imagePath = path)) else emptyList(),
    )
  }

  /**
   * Drives `PreviewAnimationClock` on an already-inflated and callback-settled [RenderTask], configuring initial/target states, extracting
   * [TransitionInfo] curves, and rendering `options.frameCount` PNG frames.
   */
  internal fun stepAndCaptureAnimation(
    previewId: String,
    methodFQN: String,
    displayName: String?,
    renderTask: RenderTask,
    initialResult: RenderResult,
    options: AnimationRenderOptions,
    postProcessImage: (RenderResult) -> BufferedImage?,
    saveFrameImage: (Int, BufferedImage) -> String,
    renderLogger: RenderLogger? = null,
  ): PreviewAnimationManifest {
    val adapter =
      findComposeViewAdapters(initialResult.rootViews).firstOrNull()
        ?: return singleFrameFallbackManifest(previewId, methodFQN, displayName, initialResult, postProcessImage, saveFrameImage)

    val composeClassLoader = adapter.javaClass.classLoader
    var (_, detectedSummaries) = detectAnimationsFromAdapter(adapter)
    val clock =
      getPreviewAnimationClock(adapter)
        ?: return singleFrameFallbackManifest(
          previewId,
          methodFQN,
          displayName,
          initialResult,
          postProcessImage,
          saveFrameImage,
          detectedSummaries,
        )

    // Reserve virtual clock headroom past the initial renderExecutingCallbacks settle rounds so
    // per-session elapsed time stays strictly monotonic within this session and across sessions.
    val initialHeadroomNanos = (CALLBACK_ROUNDS * CALLBACK_EXECUTIONS_PER_ROUND) * CALLBACK_TIME_STEP_NANOS
    StaticPreviewCallbackRunner.virtualTimeNanos.addAndGet(initialHeadroomNanos)
    var sessionTimeNanos = initialHeadroomNanos

    // Step 1: Configure initial/target states on the render thread.
    val stateSetupChanged = callOnRenderThread(renderTask) { configureStates(clock, options) }

    // If states changed (e.g. Transition switched from VISIBLE->VISIBLE to GONE->VISIBLE), run a
    // settle pass so Compose recomposes the Transition segment and registers child animations.
    if (stateSetupChanged) {
      sessionTimeNanos += CALLBACK_TIME_STEP_NANOS
      StaticPreviewCallbackRunner.virtualTimeNanos.addAndGet(CALLBACK_TIME_STEP_NANOS)
      val settleResult = drainCallbacksAndRender(renderTask, composeClassLoader, sessionTimeNanos, renderLogger)
      if (settleResult != null) {
        try {
          settleResult.rethrowIfFatalVirtualMachineError()
          settleResult.renderResult.exception.findTimeoutException()?.let { throw it }
          if (settleResult.renderResult.status == com.android.ide.common.rendering.api.Result.Status.ERROR_TIMEOUT) {
            throw TimeoutException("Render timed out while settling Compose states")
          }
          findComposeViewAdapters(settleResult.rootViews).firstOrNull()?.let { postToggleAdapter ->
            detectedSummaries = detectAnimationsFromAdapter(postToggleAdapter).second
          }
        } finally {
          Disposer.dispose(settleResult)
        }
      } else {
        detectedSummaries = detectAnimationsFromAdapter(adapter).second
      }
    }

    // Step 1b: Measure pass. Size animations (e.g. the expandVertically of an AnimatedVisibility, AnimatedContent, or
    // Modifier.animateContentSize) only know their target size once their content is composed and measured, which happens once the clock is
    // past the start: when the preview has such an animation (in the post-toggle slot table), render the end of the animation once, so that
    // the TransitionInfo curves read in step 2 have the measured sizes rather than an empty size all along.
    if (shouldRunSizeDependentMeasurePass(detectedSummaries)) {
      val measureAtMs = callOnRenderThread(renderTask) { (invokeZeroArg(clock, "getMaxDuration") as? Number)?.toLong() ?: 0L }
      if (measureAtMs > 0L) {
        callOnRenderThread(renderTask) { invokeSetClockTime(clock, measureAtMs) }
        sessionTimeNanos += CALLBACK_TIME_STEP_NANOS
        StaticPreviewCallbackRunner.virtualTimeNanos.addAndGet(CALLBACK_TIME_STEP_NANOS)
        val measureResult = drainCallbacksAndRender(renderTask, composeClassLoader, sessionTimeNanos, renderLogger)
        if (measureResult != null) {
          try {
            measureResult.rethrowIfFatalVirtualMachineError()
            measureResult.renderResult.exception.findTimeoutException()?.let { throw it }
            if (measureResult.renderResult.status == com.android.ide.common.rendering.api.Result.Status.ERROR_TIMEOUT) {
              throw TimeoutException("Render timed out during animation measure pass")
            }
          } finally {
            Disposer.dispose(measureResult)
          }
        }
      }
    }

    // Step 2: Query subscribed animations, TransitionInfo curves, and max durations on render thread.
    val (animations, unsupported, durations) =
      callOnRenderThread(renderTask) {
        // Seek to 0 first so curves and durations reflect the established segment.
        invokeSetClockTime(clock, 0L)
        val maxDur = (invokeZeroArg(clock, "getMaxDuration") as? Number)?.toLong() ?: 0L
        val maxIterDur = (invokeZeroArg(clock, "getMaxDurationPerIteration") as? Number)?.toLong() ?: 0L
        val extracted = extractSubscribedAnimations(clock, detectedSummaries, animatedContainersByTransition(adapter))
        val unsupportedLabels = extractUnsupportedAnimations(clock)
        Triple(extracted, unsupportedLabels, maxDur to maxIterDur)
      }

    val (maxDurationMs, maxIterationMs) = durations
    val effectiveDurationMs =
      (options.durationMillisOverride
          ?: maxDurationMs.takeIf { it > 0L }
          ?: maxIterationMs.takeIf { it > 0L }
          ?: animations.maxOfOrNull { it.durationMillis }?.takeIf { it > 0L }
          ?: 500L)
        .coerceIn(0L, 15_000L)

    val requestedFrames = options.frameCount.coerceIn(2, 60)
    val effectiveFrameCount = if (effectiveDurationMs == 0L && animations.isEmpty()) 1 else requestedFrames
    // Adaptive sampling: spend the frame budget where the extracted curves move fastest (e.g. the
    // rise into a spring overshoot), keeping 0 and effectiveDurationMs, a 16 ms minimum gap, and
    // falling back to even spacing when no numeric curve is available.
    val timestamps =
      if (effectiveFrameCount <= 1) {
        listOf(0L)
      } else {
        AnimationFrameSampler.sampleTimestampsForAnimations(effectiveDurationMs, effectiveFrameCount, animations)
      }

    val renderedFrames = mutableListOf<RenderedAnimationFrame>()
    var frameWidth = 0
    var frameHeight = 0

    // Step 3: Step PreviewAnimationClock across timestamps inside the single warm RenderTask session.
    for ((frameIndex, timeMs) in timestamps.withIndex()) {
      val propSnapshot =
        callOnRenderThread(renderTask) {
          invokeSetClockTime(clock, timeMs)
          sampleCurrentProperties(clock)
        }

      sessionTimeNanos += CALLBACK_TIME_STEP_NANOS
      StaticPreviewCallbackRunner.virtualTimeNanos.addAndGet(CALLBACK_TIME_STEP_NANOS)
      val frameResult = drainCallbacksAndRender(renderTask, composeClassLoader, sessionTimeNanos, renderLogger)
      if (frameResult != null) {
        try {
          frameResult.rethrowIfFatalVirtualMachineError()
          frameResult.renderResult.exception.findTimeoutException()?.let { throw it }
          if (frameResult.renderResult.status == com.android.ide.common.rendering.api.Result.Status.ERROR_TIMEOUT) {
            throw TimeoutException("Render timed out at ${timeMs}ms")
          }
          val image = postProcessImage(frameResult)
          if (image != null) {
            frameWidth = image.width
            frameHeight = image.height
            val savedPath = saveFrameImage(frameIndex, image)
            renderedFrames.add(
              RenderedAnimationFrame(
                index = frameIndex,
                timeMillis = timeMs,
                imagePath = savedPath,
                properties = propSnapshot,
              )
            )
          }
        } finally {
          Disposer.dispose(frameResult)
        }
      }
    }

    return PreviewAnimationManifest(
      previewId = previewId,
      methodFQN = methodFQN,
      displayName = displayName,
      width = frameWidth,
      height = frameHeight,
      maxDurationMillis = maxDurationMs,
      maxDurationPerIterationMillis = maxIterationMs,
      effectiveDurationMillis = effectiveDurationMs,
      frameCount = renderedFrames.size,
      animations = animations,
      detectedAnimations = detectedSummaries,
      unsupportedAnimations = unsupported,
      frames = renderedFrames,
    )
  }

  private fun getPreviewAnimationClock(adapter: Any): Any? =
    invokeZeroArg(adapter, "getClock") ?: runCatching { findField(adapter.javaClass, "clock")?.get(adapter) }.getOrNull()

  private fun findClockMethod(clock: Any, functionName: String, parameterCount: Int): Method? =
    clock.javaClass.methods
      .firstOrNull { it.name.substringBefore('-') == functionName && it.parameterCount == parameterCount }
      ?.apply { isAccessible = true }

  @VisibleForTesting
  internal data class ClockEntry(
    val composeAnimation: ComposeAnimation,
    val animationClock: Any,
    val type: ComposeAnimationType = composeAnimation.type,
  )

  @VisibleForTesting
  internal fun collectAllClockEntries(clock: Any): List<ClockEntry> {
    val entries = mutableListOf<ClockEntry>()
    fun addEntriesFrom(map: Map<*, *>?) {
      if (map == null) return
      for ((anim, animClock) in map.entries) {
        if (anim is ComposeAnimation && animClock != null) {
          entries.add(ClockEntry(anim, animClock))
        }
      }
    }
    for (field in CLOCK_MAP_FIELDS) addEntriesFrom(getClockMap(clock, field))
    if (entries.isEmpty()) {
      // Forward compatibility with Compose ui-tooling 1.12+, which unified per-type clock maps into `animationClocks`.
      addEntriesFrom(getClockMap(clock, "animationClocks"))
    }
    return entries
  }

  private fun getClockMap(clock: Any, fieldName: String): Map<*, *>? {
    val capitalized = fieldName.replaceFirstChar { it.uppercaseChar() }
    return (invokeZeroArg(clock, "get$capitalized") as? Map<*, *>)
      ?: (invokeZeroArg(clock, fieldName) as? Map<*, *>)
      ?: runCatching { findField(clock.javaClass, fieldName)?.get(clock) as? Map<*, *> }.getOrNull()
  }

  private fun readInitialAndTarget(entry: ClockEntry): Pair<Any?, Any?> {
    val stateObj = invokeGetter(entry.animationClock, "state") ?: return null to null
    return invokeGetter(stateObj, "initial") to invokeGetter(stateObj, "target")
  }

  @VisibleForTesting
  internal fun configureStates(clock: Any, options: AnimationRenderOptions): Boolean {
    val entries = collectAllClockEntries(clock)
    var statesChanged = false

    val updateFromAndTo = findClockMethod(clock, "updateFromAndToStates", 3)
    val updateVisibility = findClockMethod(clock, "updateAnimatedVisibilityState", 2)

    for (entry in entries) {
      val anim = entry.composeAnimation
      val label = anim.label.orEmpty()
      val matchesTarget =
        options.animationLabel.isNullOrBlank() || options.animationLabel == "*" || options.animationLabel.equals(label, ignoreCase = true)

      when (entry.type) {
        ComposeAnimationType.ANIMATED_VISIBILITY -> {
          val availableStates = anim.states.toList()
          val desiredStr = if (matchesTarget) options.toState ?: options.fromState else null
          if (desiredStr != null && updateVisibility != null) {
            val matched =
              availableStates.firstOrNull { it.toString().equals(desiredStr, ignoreCase = true) }
                ?: when (desiredStr.lowercase(Locale.ROOT)) {
                  "true",
                  "visible",
                  "enter" -> availableStates.firstOrNull { it.toString().equals("Enter", ignoreCase = true) }
                  "false",
                  "gone",
                  "exit" -> availableStates.firstOrNull { it.toString().equals("Exit", ignoreCase = true) }
                  else -> null
                }
            if (matched != null) {
              runCatching { updateVisibility.invoke(clock, anim, matched) }
              statesChanged = true
            }
          }
        }
        ComposeAnimationType.TRANSITION_ANIMATION,
        ComposeAnimationType.ANIMATED_CONTENT -> {
          if (updateFromAndTo != null) {
            val (curInitial, curTarget) = readInitialAndTarget(entry)
            val expandedStates = expandCandidateStates(curTarget ?: curInitial, anim.states.toList())
            val (newFrom, newTo) =
              if (matchesTarget && (!options.fromState.isNullOrBlank() || !options.toState.isNullOrBlank())) {
                val resolvedFrom = options.fromState?.let { parseStateValue(it, curInitial ?: curTarget, expandedStates) } ?: curInitial
                val resolvedTo = options.toState?.let { parseStateValue(it, curTarget ?: curInitial, expandedStates) } ?: curTarget
                resolvedFrom to resolvedTo
              } else if (curInitial != null && curInitial == curTarget) {
                // Auto-pick a distinct state so Transition has a non-zero animation trajectory.
                // When starting from Boolean `false` (e.g. `isExpanded = false`), animate forward `false -> true`.
                val altState = expandedStates.firstOrNull { it != curTarget }
                if (altState != null) {
                  if (curInitial == false) curInitial to altState else altState to curTarget
                } else {
                  curInitial to curTarget
                }
              } else {
                curInitial to curTarget
              }

            if (newFrom != null && newTo != null && (newFrom != curInitial || newTo != curTarget)) {
              runCatching { updateFromAndTo.invoke(clock, anim, newFrom, newTo) }
              statesChanged = true
            }
          }
        }
        ComposeAnimationType.ANIMATE_X_AS_STATE -> {
          if (updateFromAndTo != null) {
            val (curInitial, curTarget) = readInitialAndTarget(entry)
            val sample = curTarget ?: curInitial
            val expandedStates = expandCandidateStates(sample, anim.states.toList())
            val (newFrom, newTo) =
              if (matchesTarget && (!options.fromState.isNullOrBlank() || !options.toState.isNullOrBlank())) {
                val resolvedFrom = options.fromState?.let { parseAnimateXValue(it, sample, expandedStates) } ?: curInitial
                val resolvedTo = options.toState?.let { parseAnimateXValue(it, sample, expandedStates) } ?: curTarget
                resolvedFrom to resolvedTo
              } else if (sample != null && curInitial == curTarget) {
                defaultAnimateXEndpoints(sample, expandedStates)
              } else {
                curInitial to curTarget
              }

            if (newFrom != null && newTo != null && (newFrom != curInitial || newTo != curTarget)) {
              runCatching { updateFromAndTo.invoke(clock, anim, newFrom, newTo) }
              statesChanged = true
            }
          }
        }
        else -> Unit
      }
    }
    val needsClockSync = statesChanged || entries.any { it.type == ComposeAnimationType.ANIMATED_VISIBILITY }
    if (needsClockSync) {
      invokeSetClockTime(clock, 0L)
    }
    return needsClockSync
  }

  private fun expandCandidateStates(sample: Any?, rawStates: List<Any>): List<Any> {
    if (rawStates.size >= 2) return rawStates
    return when (sample) {
      is Boolean -> listOf(!sample, sample)
      is Enum<*> -> sample.javaClass.enumConstants?.filterIsInstance<Any>().orEmpty().ifEmpty { rawStates }
      is Int -> if (sample == 0) listOf(0, 1) else listOf(0, sample)
      else -> rawStates
    }
  }

  private fun parseStateValue(text: String, sample: Any?, candidates: List<Any>): Any? {
    val trimmed = text.trim()
    candidates
      .firstOrNull { it.toString().equals(trimmed, ignoreCase = true) }
      ?.let {
        return it
      }
    return when (sample) {
      is Boolean ->
        when (trimmed.lowercase(Locale.ROOT)) {
          "true",
          "1",
          "visible",
          "expanded",
          "on" -> true
          "false",
          "0",
          "gone",
          "collapsed",
          "off" -> false
          else -> null
        }
      is Int -> trimmed.toIntOrNull()
      is Long -> trimmed.toLongOrNull()
      is Float -> trimmed.removeSuffix("f").toFloatOrNull()
      is Double -> trimmed.toDoubleOrNull()
      is String -> trimmed
      else -> null
    }
  }

  private fun parseAnimateXValue(text: String, sample: Any?, candidates: List<Any>): Any? {
    parseStateValue(text, sample, candidates)?.let {
      return it
    }
    val trimmed = text.trim().removeSuffix(".dp").removeSuffix("dp").removeSuffix("f")
    return trimmed.toFloatOrNull()
  }

  private fun defaultAnimateXEndpoints(sample: Any, candidates: List<Any>): Pair<Any?, Any?> {
    if (candidates.size >= 2) {
      val alt = candidates.firstOrNull { it != sample }
      if (alt != null) return alt to sample
    }
    return when {
      sample is Float -> if (sample == 0f) (0f to 1f) else (0f to sample)
      sample is Int -> if (sample == 0) (0 to 100) else (0 to sample)
      sample.javaClass.name == "androidx.compose.ui.unit.Dp" -> {
        val v = (invokeGetter(sample, "value") as? Number)?.toFloat() ?: 0f
        if (v == 0f) (0f to 24f) else (0f to v)
      }
      else -> sample to sample
    }
  }

  private fun collectAllTransitionAnimationStates(transitionObj: Any): List<Any> {
    val direct = (invokeGetter(transitionObj, "animations") as? Iterable<*>)?.filterNotNull().orEmpty()
    val childTransitions = (invokeGetter(transitionObj, "transitions") as? Iterable<*>)?.filterNotNull().orEmpty()
    return direct + childTransitions.flatMap(::collectAllTransitionAnimationStates)
  }

  @VisibleForTesting
  internal fun extractSubscribedAnimations(
    clock: Any,
    detectedSummaries: List<DetectedAnimationSummary>,
    containers: Map<Any, AnimatedContainerInfo> = emptyMap(),
  ): List<SubscribedAnimationInfo> {
    val entries = collectAllClockEntries(clock)
    val getTransitionsMethod = findClockMethod(clock, "getTransitions", 2)
    val remainingDetected = detectedSummaries.toMutableList()

    return entries.mapIndexed { index, entry ->
      val anim = entry.composeAnimation
      val label = anim.label?.takeIf { it.isNotBlank() } ?: "Animation ${index + 1}"
      val matchedSummaryIdx =
        remainingDetected.indexOfFirst { it.label.equals(label, ignoreCase = true) }.takeIf { it >= 0 }
          ?: remainingDetected.indexOfFirst { summaryMatchesType(it.type, entry.type) }.takeIf { it >= 0 }
      val sourceLoc = matchedSummaryIdx?.let { remainingDetected.removeAt(it).sourceLocation }

      val stateObj = invokeGetter(entry.animationClock, "state")
      val (initVal, targetVal) = readInitialAndTarget(entry)
      val (fromStateStr, toStateStr) =
        when (entry.type) {
          ComposeAnimationType.ANIMATED_VISIBILITY -> {
            val s = stateObj?.toString() ?: "Enter"
            if (s.equals("Enter", ignoreCase = true)) "Hidden" to "Enter" else "Visible" to "Exit"
          }
          ComposeAnimationType.INFINITE_TRANSITION -> "Loop" to "Loop"
          else -> initVal?.let(::formatAnimatedValue)?.first to targetVal?.let(::formatAnimatedValue)?.first
        }

      val sample = targetVal ?: initVal
      val candidateStates =
        when (entry.type) {
          ComposeAnimationType.ANIMATED_VISIBILITY -> listOf("Enter", "Exit")
          ComposeAnimationType.INFINITE_TRANSITION -> listOf("Loop")
          else -> expandCandidateStates(sample, anim.states.toList()).map { formatAnimatedValue(it).first }.distinct()
        }

      val durationMs = (invokeZeroArg(entry.animationClock, "getMaxDuration") as? Number)?.toLong() ?: 0L
      val stepMs = maxOf(8L, (durationMs / 30L).coerceAtLeast(8L))
      val transitionInfos =
        if (getTransitionsMethod != null) {
          (runCatching { getTransitionsMethod.invoke(clock, anim, stepMs) }.getOrNull() as? Iterable<*>)
            ?.filterIsInstance<TransitionInfo>()
            .orEmpty()
        } else {
          emptyList()
        }

      val specByPropLabel = extractSpecsByPropertyLabel(entry)
      // TransitionInfos come in the order of the animations of the transition and of its child transitions: use it to give each track
      // its own spec, since several tracks can have the same label (e.g. the "Built-in alpha" of two AnimatedVisibility children). The
      // two lists are aligned by label, in order, so that an animation missing from either one does not shift the others.
      val orderedTracks = transitionTracksInOrder(entry, containers).orEmpty()
      var trackCursor = 0
      fun orderedTrackOf(trackLabel: String): TrackMeta? {
        val idx = (trackCursor until orderedTracks.size).firstOrNull { orderedTracks[it].label == trackLabel } ?: return null
        trackCursor = idx + 1
        return orderedTracks[idx]
      }
      val tracks = transitionInfos.map { trInfo ->
        val propLabel = trInfo.label.ifBlank { label }
        val specTypeShort = trInfo.specType.substringAfterLast('.').ifBlank { "AnimationSpec" }

        var detectedKind = "Other"
        val sortedPoints =
          trInfo.values.entries
            .map { (timeMillis, v) ->
              val (formatted, kind, components) = formatAnimatedValue(v)
              if (detectedKind == "Other" && kind != "Other") detectedKind = kind
              AnimatedPropertyPoint(
                timeMillis = timeMillis,
                value = formatted,
                numericValue = components?.firstOrNull(),
                components = components,
              )
            }
            .sortedBy { it.timeMillis }

        val meta = orderedTrackOf(propLabel)
        AnimationTransitionTrack(
          propertyLabel = propLabel,
          valueKind = detectedKind,
          specType = specTypeShort,
          spec = meta?.spec ?: specByPropLabel[propLabel] ?: specByPropLabel["*"],
          startTimeMillis = trInfo.startTimeMillis,
          endTimeMillis = trInfo.endTimeMillis,
          initialValue = sortedPoints.firstOrNull()?.value.orEmpty(),
          targetValue = sortedPoints.lastOrNull()?.value.orEmpty(),
          points = sortedPoints,
          displayLabel = meta?.displayLabel,
        )
      }

      val primarySpec = tracks.firstNotNullOfOrNull { it.spec } ?: specByPropLabel["*"]
      SubscribedAnimationInfo(
        id = "anim_$index",
        type = entry.type.name,
        label = label,
        states = candidateStates,
        fromState = fromStateStr,
        toState = toStateStr,
        durationMillis = durationMs,
        sourceLocation = sourceLoc,
        spec = primarySpec,
        tracks = tracks,
      )
    }
  }

  private fun summaryMatchesType(summaryType: String, clockType: ComposeAnimationType): Boolean =
    when (clockType) {
      ComposeAnimationType.TRANSITION_ANIMATION -> summaryType == "TRANSITION"
      ComposeAnimationType.ANIMATED_VISIBILITY -> summaryType == "ANIMATED_VISIBILITY"
      ComposeAnimationType.ANIMATED_CONTENT -> summaryType == "ANIMATED_CONTENT"
      ComposeAnimationType.ANIMATE_X_AS_STATE -> summaryType == "ANIMATE_X_AS_STATE"
      ComposeAnimationType.INFINITE_TRANSITION -> summaryType == "INFINITE_TRANSITION"
      else -> false
    }

  /** The label, spec and display label (see [AnimationTransitionTrack.displayLabel]) of an animation of a transition. */
  private data class TrackMeta(val label: String, val spec: AnimationSpecSummary?, val displayLabel: String?)

  private fun transitionObjectOf(entry: ClockEntry): Any? =
    when (entry.type) {
      ComposeAnimationType.ANIMATED_VISIBILITY ->
        invokeGetter(entry.composeAnimation, "childTransition") ?: entry.composeAnimation.animationObject
      ComposeAnimationType.TRANSITION_ANIMATION,
      ComposeAnimationType.ANIMATED_CONTENT -> entry.composeAnimation.animationObject
      else -> null
    }

  /**
   * The [TrackMeta] of each animation of the transition of [entry] and of its child transitions, in the order of
   * `PreviewAnimationClock.getTransitions` (the animations of a transition, then those of its children), or `null` if [entry] is not a
   * transition. The built-in animations of a child transition of an `AnimatedVisibility` or `AnimatedContent` (see [containers]) are named
   * after what it shows.
   */
  private fun transitionTracksInOrder(entry: ClockEntry, containers: Map<Any, AnimatedContainerInfo>): List<TrackMeta>? {
    val transitionObj = transitionObjectOf(entry) ?: return null
    val result = mutableListOf<TrackMeta>()
    fun collect(transition: Any, container: AnimatedContainerInfo?) {
      val info = containers[transition] ?: container
      val targetState = invokeGetter(transition, "targetState")?.toString()
      for (animState in (invokeGetter(transition, "animations") as? Iterable<*>)?.filterNotNull().orEmpty()) {
        val label = (invokeGetter(animState, "label") as? String).orEmpty()
        val spec = invokeGetter(animState, "animationSpec")?.let(::summarizeAnimationSpec)
        result.add(TrackMeta(label, spec, info?.let { builtInDisplayLabel(label, it, targetState) }))
      }
      for (child in (invokeGetter(transition, "transitions") as? Iterable<*>)?.filterNotNull().orEmpty()) collect(child, info)
    }
    collect(transitionObj, null)
    return result
  }

  /** What an `AnimatedVisibility` or `AnimatedContent` shows: the first composable called inside it, and its own call site. */
  internal data class AnimatedContainerInfo(val contentName: String?, val callSite: ComponentSourceLocation?)

  /**
   * The readable name of the built-in track [label] (e.g. "Built-in alpha") of an `AnimatedVisibility` or `AnimatedContent` showing [info],
   * whose child transition targets [targetState] (`Visible` when entering, `PostExit` when exiting), or `null` if [label] is not a built-in
   * track or nothing names the container. E.g. `DetailLegend · expand`, `Badge · fade in`, `SleepBar.kt:112 · fade`.
   */
  internal fun builtInDisplayLabel(label: String, info: AnimatedContainerInfo, targetState: String?): String? {
    if (!label.startsWith(BUILT_IN_PREFIX)) return null
    val property = label.removePrefix(BUILT_IN_PREFIX)
    if (property.contains("InterruptionHandlingOffset")) return null
    val name = info.contentName ?: info.callSite?.let { "${it.fileName}:${it.lineNumber}" } ?: return null
    val entering = targetState == "Visible"
    val exiting = targetState == "PostExit"
    val action =
      when (property) {
        "alpha" -> if (entering) "fade in" else if (exiting) "fade out" else "fade"
        "scale" -> if (entering) "scale in" else if (exiting) "scale out" else "scale"
        "shrink/expand" -> if (entering) "expand" else if (exiting) "shrink" else "size"
        "slide" -> if (entering) "slide in" else if (exiting) "slide out" else "slide"
        else -> property
      }
    return "$name · $action"
  }

  /**
   * The [AnimatedContainerInfo] of each `AnimatedVisibility` and `AnimatedContent` of the slot table of [adapter], by the identity of the
   * transitions remembered in it (its child transition), to name their built-in tracks. Read once the content is composed (after the
   * measure pass), so that the composable it shows is in the slot table.
   */
  @VisibleForTesting
  internal fun animatedContainersByTransition(
    adapter: Any,
    slotTrees: List<Any>? = null,
  ): Map<Any, AnimatedContainerInfo> {
    val result = IdentityHashMap<Any, AnimatedContainerInfo>()
    try {
      fun visit(group: Any) {
        val groupName = (invokeGetter(group, "name") as? String).orEmpty()
        if (groupName == "AnimatedVisibility" || groupName == "AnimatedContent") {
          val callSite = extractGroupLocation(group)
          val info = AnimatedContainerInfo(contentName = contentComposableName(group, callSite?.fileName), callSite = callSite)
          transitionsIn(group, depth = 0).forEach { result.putIfAbsent(it, info) }
        }
        (invokeGetter(group, "children") as? Iterable<*>)?.forEach { if (it != null) visit(it) }
      }
      (slotTrees ?: ComposeParameterExtractor.extractSlotTrees(adapter)).forEach(::visit)
    } catch (e: Exception) {
      logger.log(Level.FINE, "Failed to name the AnimatedVisibility/AnimatedContent tracks from the slot table", e)
    } catch (e: LinkageError) {
      logger.log(Level.FINE, "Linkage error naming the AnimatedVisibility/AnimatedContent tracks from the slot table", e)
    }
    return result
  }

  /** The transitions remembered in [group] and its first descendants (where `createChildTransition` remembers its child transition). */
  private fun transitionsIn(group: Any, depth: Int): List<Any> {
    if (depth > MAX_TRANSITION_SEARCH_DEPTH) return emptyList()
    val own = (invokeGetter(group, "data") as? Iterable<*>)?.filterNotNull().orEmpty().filter { it.javaClass.simpleName == "Transition" }
    val children = (invokeGetter(group, "children") as? Iterable<*>)?.filterNotNull().orEmpty()
    return own + children.flatMap { transitionsIn(it, depth + 1) }
  }

  /**
   * The name of the first composable called from user code inside the `AnimatedVisibility`/`AnimatedContent` [group] (e.g. `DetailLegend`),
   * preferring a specific one to a layout (`Box`, `Row`...) that only wraps it, or `null` if there is none.
   */
  private fun contentComposableName(group: Any, callSiteFile: String?): String? {
    var layout: String? = null
    fun visit(g: Any, depth: Int): String? {
      if (depth > MAX_CONTENT_SEARCH_DEPTH) return null
      for (child in (invokeGetter(g, "children") as? Iterable<*>)?.filterNotNull().orEmpty()) {
        val name = (invokeGetter(child, "name") as? String).orEmpty()
        val location = extractGroupLocation(child)
        // The content lambda is written at the call site: its calls are in the same file (the implementation's calls are not).
        val inContent =
          location != null && (if (callSiteFile != null) location.fileName == callSiteFile else isUserSourceFile(location.fileName))
        if (inContent && name.firstOrNull()?.isUpperCase() == true && name !in ANIMATED_CONTAINERS && name !in EFFECTS) {
          if (name !in GENERIC_LAYOUTS) return name
          if (layout == null) layout = name
        }
        visit(child, depth + 1)?.let {
          return it
        }
      }
      return null
    }
    return visit(group, 0) ?: layout
  }

  private fun extractSpecsByPropertyLabel(entry: ClockEntry): Map<String, AnimationSpecSummary> {
    val result = mutableMapOf<String, AnimationSpecSummary>()
    fun recordAnimStates(states: Iterable<Any>) {
      for (animState in states) {
        val propLabel = (invokeGetter(animState, "label") as? String).orEmpty()
        val spec = invokeGetter(animState, "animationSpec") ?: continue
        val summary = summarizeAnimationSpec(spec)
        if (propLabel.isNotBlank()) result[propLabel] = summary
        result.putIfAbsent("*", summary)
      }
    }

    when (entry.type) {
      ComposeAnimationType.ANIMATE_X_AS_STATE -> {
        val spec = invokeGetter(entry.composeAnimation, "animationSpec")
        if (spec != null) {
          val summary = summarizeAnimationSpec(spec)
          result["*"] = summary
          val label = entry.composeAnimation.label.orEmpty()
          if (label.isNotBlank()) result[label] = summary
        }
      }
      ComposeAnimationType.TRANSITION_ANIMATION,
      ComposeAnimationType.ANIMATED_VISIBILITY,
      ComposeAnimationType.ANIMATED_CONTENT -> {
        transitionObjectOf(entry)?.let { recordAnimStates(collectAllTransitionAnimationStates(it)) }
      }
      ComposeAnimationType.INFINITE_TRANSITION -> {
        val anims = (invokeGetter(entry.composeAnimation.animationObject, "animations") as? Iterable<*>)?.filterNotNull().orEmpty()
        recordAnimStates(anims)
      }
      else -> Unit
    }
    return result
  }

  internal fun summarizeAnimationSpec(spec: Any): AnimationSpecSummary {
    val simpleName = spec.javaClass.simpleName.ifBlank { spec.javaClass.name.substringAfterLast('.') }
    return when (simpleName) {
      "SpringSpec" ->
        AnimationSpecSummary(
          specType = "SpringSpec",
          dampingRatio = (invokeGetter(spec, "dampingRatio") as? Number)?.toFloat(),
          stiffness = (invokeGetter(spec, "stiffness") as? Number)?.toFloat(),
        )
      "TweenSpec" ->
        AnimationSpecSummary(
          specType = "TweenSpec",
          durationMillis = (invokeGetter(spec, "durationMillis") as? Number)?.toInt(),
          delayMillis = (invokeGetter(spec, "delay") as? Number)?.toInt(),
          easing = invokeGetter(spec, "easing")?.let(::describeEasing),
        )
      "InfiniteRepeatableSpec",
      "RepeatableSpec" -> {
        val innerSummary = invokeGetter(spec, "animation")?.let(::summarizeAnimationSpec)
        AnimationSpecSummary(
          specType = simpleName,
          dampingRatio = innerSummary?.dampingRatio,
          stiffness = innerSummary?.stiffness,
          durationMillis = innerSummary?.durationMillis,
          delayMillis = innerSummary?.delayMillis,
          easing = innerSummary?.easing,
        )
      }
      "KeyframesSpec" -> {
        val config = invokeGetter(spec, "config")
        AnimationSpecSummary(
          specType = "KeyframesSpec",
          durationMillis = (config?.let { invokeGetter(it, "durationMillis") } as? Number)?.toInt(),
          delayMillis = (config?.let { invokeGetter(it, "delayMillis") } as? Number)?.toInt(),
        )
      }
      "SnapSpec" ->
        AnimationSpecSummary(specType = "SnapSpec", durationMillis = 0, delayMillis = (invokeGetter(spec, "delay") as? Number)?.toInt())
      else -> AnimationSpecSummary(specType = simpleName)
    }
  }

  /**
   * Names a Compose `Easing`: well-known `CubicBezierEasing` presets (or `cubic-bezier(...)`), `"Linear"` when `transform(t) ≈ t`,
   * `"Custom"` for lambdas/anonymous classes, otherwise the easing's class simple name.
   */
  internal fun describeEasing(easing: Any): String {
    if (easing.javaClass.simpleName == "CubicBezierEasing") {
      val a = findField(easing.javaClass, "a")?.get(easing) as? Number
      val b = findField(easing.javaClass, "b")?.get(easing) as? Number
      val c = findField(easing.javaClass, "c")?.get(easing) as? Number
      val d = findField(easing.javaClass, "d")?.get(easing) as? Number
      if (a != null && b != null && c != null && d != null) {
        val af = a.toFloat()
        val bf = b.toFloat()
        val cf = c.toFloat()
        val df = d.toFloat()
        return when {
          approx(af, 0.4f) && approx(bf, 0.0f) && approx(cf, 0.2f) && approx(df, 1.0f) -> "FastOutSlowIn"
          approx(af, 0.0f) && approx(bf, 0.0f) && approx(cf, 0.2f) && approx(df, 1.0f) -> "LinearOutSlowIn"
          approx(af, 0.4f) && approx(bf, 0.0f) && approx(cf, 1.0f) && approx(df, 1.0f) -> "FastOutLinearIn"
          else -> String.format(Locale.US, "cubic-bezier(%.2f, %.2f, %.2f, %.2f)", af, bf, cf, df)
        }
      }
    }
    val cls = easing.javaClass
    if (cls.name.contains("LinearEasing", ignoreCase = true)) return "Linear"
    // Easings declared as Kotlin lambdas (e.g. `LinearEasing = Easing { it }` shows up as
    // `EasingKt$$Lambda/0x...`) carry no useful name, so probe their curve instead.
    val transform = probeEasingTransform(easing)
    if (transform != null && LINEAR_PROBE_POINTS.all { t -> transform(t)?.let { abs(it - t) < 1e-3f } == true }) {
      return "Linear"
    }
    val isLambdaOrAnonymous =
      cls.isSynthetic ||
        cls.isAnonymousClass ||
        cls.name.contains("\$\$Lambda") ||
        cls.name.contains("\$lambda") ||
        cls.simpleName.isBlank()
    return if (isLambdaOrAnonymous) "Custom" else cls.simpleName
  }

  private val LINEAR_PROBE_POINTS = floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)

  /**
   * Returns a function invoking `Easing.transform(Float)` on [easing] via reflection, or `null` if no such method exists. Prefers the
   * interface declaration, since lambda/hidden classes themselves are not public.
   */
  private fun probeEasingTransform(easing: Any): ((Float) -> Float?)? {
    fun isTransform(m: Method) =
      m.name == "transform" && m.parameterCount == 1 && m.parameterTypes[0] == java.lang.Float.TYPE && m.returnType == java.lang.Float.TYPE
    val interfaces = LinkedHashSet<Class<*>>()
    fun collectInterfaces(c: Class<*>?) {
      if (c == null) return
      for (i in c.interfaces) if (interfaces.add(i)) collectInterfaces(i)
      collectInterfaces(c.superclass)
    }
    collectInterfaces(easing.javaClass)
    val method =
      interfaces.firstNotNullOfOrNull { i -> i.methods.firstOrNull(::isTransform) }
        ?: easing.javaClass.methods.firstOrNull(::isTransform)
        ?: easing.javaClass.declaredMethods.firstOrNull(::isTransform)
        ?: return null
    runCatching { method.isAccessible = true }
    return { t -> runCatching { (method.invoke(easing, t) as? Number)?.toFloat() }.getOrNull() }
  }

  private fun approx(a: Float, b: Float): Boolean = abs(a - b) < 0.005f

  @VisibleForTesting
  internal fun extractUnsupportedAnimations(clock: Any): List<String> {
    val set =
      (invokeZeroArg(clock, "getTrackedUnsupportedAnimations") as? Iterable<*>)
        ?: (runCatching { findField(clock.javaClass, "trackedUnsupportedAnimations")?.get(clock) }.getOrNull() as? Iterable<*>)
        ?: return emptyList()
    return set.filterIsInstance<ComposeAnimation>().mapNotNull { it.label?.takeIf(String::isNotBlank) }.distinct()
  }

  @VisibleForTesting
  internal fun sampleCurrentProperties(clock: Any): Map<String, String> {
    val entries = collectAllClockEntries(clock)
    val getProps = findClockMethod(clock, "getAnimatedProperties", 1) ?: return emptyMap()
    val snapshot = LinkedHashMap<String, String>()
    for (entry in entries) {
      val animLabel = entry.composeAnimation.label.orEmpty()
      val props =
        (runCatching { getProps.invoke(clock, entry.composeAnimation) }.getOrNull() as? Iterable<*>)
          ?.filterIsInstance<ComposeAnimatedProperty>()
          .orEmpty()
      for (prop in props) {
        val pLabel = prop.label
        val key =
          if (animLabel.isNotBlank() && pLabel.isNotBlank() && animLabel != pLabel) "$animLabel.$pLabel" else pLabel.ifBlank { animLabel }
        if (key.isNotBlank()) {
          snapshot[key] = formatAnimatedValue(prop.value).first
        }
      }
    }
    return snapshot
  }

  /**
   * Formats an arbitrary Compose animated value (`Float`, `Dp`, `Int`, `Color`, `Offset`, `IntOffset`, `Size`, `IntSize`, `Enum`,
   * `Boolean`, etc.) into `(displayString, kind, numericComponents)`.
   */
  internal fun formatAnimatedValue(value: Any?): Triple<String, String, List<Double>?> {
    if (value == null) return Triple("null", "Other", null)
    return when (value) {
      is Float -> Triple(String.format(Locale.US, "%.3f", value), "Float", listOf(value.toDouble()))
      is Double -> Triple(String.format(Locale.US, "%.3f", value), "Float", listOf(value))
      is Int -> Triple(value.toString(), "Int", listOf(value.toDouble()))
      is Long -> Triple(value.toString(), "Int", listOf(value.toDouble()))
      is Boolean -> Triple(value.toString(), "Boolean", listOf(if (value) 1.0 else 0.0))
      is Enum<*> -> Triple(value.name, "Enum", listOf(value.ordinal.toDouble()))
      else -> {
        val className = value.javaClass.name
        when (className) {
          "androidx.compose.ui.unit.Dp" -> {
            val v = (invokeGetter(value, "value") as? Number)?.toDouble() ?: 0.0
            Triple(String.format(Locale.US, "%.2f.dp", v), "Dp", listOf(v))
          }
          "androidx.compose.ui.graphics.Color" -> {
            val str = value.toString()
            val colorMatch = COLOR_TO_STRING_REGEX.find(str)
            val r = (invokeZeroArg(value, "getRed") as? Number)?.toDouble() ?: colorMatch?.groupValues?.get(1)?.toDoubleOrNull()
            val g = (invokeZeroArg(value, "getGreen") as? Number)?.toDouble() ?: colorMatch?.groupValues?.get(2)?.toDoubleOrNull()
            val b = (invokeZeroArg(value, "getBlue") as? Number)?.toDouble() ?: colorMatch?.groupValues?.get(3)?.toDoubleOrNull()
            val a = (invokeZeroArg(value, "getAlpha") as? Number)?.toDouble() ?: colorMatch?.groupValues?.get(4)?.toDoubleOrNull()
            if (r != null && g != null && b != null && a != null) {
              val ri = (r * 255.0).roundToInt().coerceIn(0, 255)
              val gi = (g * 255.0).roundToInt().coerceIn(0, 255)
              val bi = (b * 255.0).roundToInt().coerceIn(0, 255)
              val ai = (a * 255.0).roundToInt().coerceIn(0, 255)
              Triple(String.format(Locale.US, "rgba(%d, %d, %d, %.2f)", ri, gi, bi, ai / 255.0), "Color", listOf(r, g, b, a))
            } else {
              Triple(str, "Color", null)
            }
          }
          "androidx.compose.ui.geometry.Offset",
          "androidx.compose.ui.unit.IntOffset",
          "androidx.compose.ui.geometry.Size",
          "androidx.compose.ui.unit.IntSize" -> {
            val isSize = className.endsWith("Size")
            val isInt = className.contains(".Int")
            val packed = (findField(value.javaClass, "packedValue")?.get(value) as? Number)?.toLong()
            if (packed == null) {
              Triple(value.toString(), if (isSize) "Size" else "Offset", null)
            } else if (isInt) {
              val first = (packed shr 32).toInt()
              val second = (packed and 0xFFFFFFFFL).toInt()
              val formatted = if (isSize) "${first}x$second" else "($first, $second)"
              Triple(formatted, if (isSize) "IntSize" else "IntOffset", listOf(first.toDouble(), second.toDouble()))
            } else {
              val first = Float.fromBits((packed shr 32).toInt()).toDouble()
              val second = Float.fromBits((packed and 0xFFFFFFFFL).toInt()).toDouble()
              val formatted = String.format(Locale.US, if (isSize) "%.1fx%.1f" else "(%.1f, %.1f)", first, second)
              Triple(formatted, if (isSize) "Size" else "Offset", listOf(first, second))
            }
          }
          else -> Triple(value.toString(), "Other", null)
        }
      }
    }
  }

  private fun invokeSetClockTime(clock: Any, timeMillis: Long) {
    findClockMethod(clock, "setClockTime", 1)?.invoke(clock, timeMillis)
  }

  private fun invokeZeroArg(target: Any, methodName: String): Any? {
    val method =
      target.javaClass.methods.firstOrNull { (it.name == methodName || it.name.startsWith("$methodName$")) && it.parameterCount == 0 }
        ?: target.javaClass.declaredMethods.firstOrNull {
          (it.name == methodName || it.name.startsWith("$methodName$")) && it.parameterCount == 0
        }
        ?: return null
    return runCatching {
      method.isAccessible = true
      method.invoke(target)
    }
      .getOrNull()
  }

  @VisibleForTesting
  internal fun shouldRunSizeDependentMeasurePass(detectedSummaries: List<DetectedAnimationSummary>): Boolean = detectedSummaries.any {
    it.type in SIZE_DEPENDENT_ANIMATION_TYPES
  }

  private fun <T> callOnRenderThread(renderTask: RenderTask, block: () -> T): T {
    val holder = AtomicReference<T>()
    try {
      renderTask
        .runAsyncRenderActionWithSession(
          { holder.set(block()) },
          DEFAULT_RENDER_THREAD_TIMEOUT_SECONDS,
          TimeUnit.SECONDS,
        )
        .get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw e
    } catch (e: ExecutionException) {
      rethrowIfInterruptedOrFatalJvmError(e)
      throw e
    }
    @Suppress("UNCHECKED_CAST")
    return holder.get() as T
  }

  private fun drainCallbacksAndRender(
    renderTask: RenderTask,
    composeClassLoader: ClassLoader?,
    sessionTimeNanos: Long,
    renderLogger: RenderLogger? = null,
  ): RenderResult? =
    drainCallbacksAndRender(
      prepareDispatcher =
        composeClassLoader?.let { cl ->
          {
            renderTask.runAsyncRenderActionWithSession(
              { resetComposeUiDispatcherScheduling(cl) },
              DEFAULT_RENDER_THREAD_TIMEOUT_SECONDS,
              TimeUnit.SECONDS,
            )
          }
        },
      executeCallbacks = { nanos -> renderTask.executeCallbacks(nanos) },
      renderFrame = { renderTask.render() },
      sessionTimeNanos = sessionTimeNanos,
      renderLogger = renderLogger,
    )

  @VisibleForTesting
  internal fun <R> drainCallbacksAndRender(
    prepareDispatcher: (() -> CompletableFuture<*>)?,
    executeCallbacks: (Long) -> CompletableFuture<*>,
    renderFrame: () -> CompletableFuture<R>,
    sessionTimeNanos: Long,
    renderLogger: RenderLogger? = null,
  ): R {
    if (prepareDispatcher != null) {
      try {
        prepareDispatcher().get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
      } catch (e: TimeoutException) {
        val msg = "Timed out resetting Compose UI dispatcher scheduling at ${sessionTimeNanos}ns"
        renderLogger?.error(null, msg, e, null, null)
        logger.log(Level.WARNING, msg, e)
        throw e
      } catch (e: ExecutionException) {
        rethrowIfInterruptedOrFatalJvmError(e)
        logger.log(Level.FINE, "Failed to reset Compose UI dispatcher scheduling", e)
      }
    }
    try {
      executeCallbacks(sessionTimeNanos).get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw e
    } catch (e: TimeoutException) {
      val msg = "Timed out executing layoutlib callbacks at ${sessionTimeNanos}ns"
      renderLogger?.error(null, msg, e, null, null)
      logger.log(Level.WARNING, msg, e)
      throw e
    } catch (e: ExecutionException) {
      rethrowIfInterruptedOrFatalJvmError(e)
      val cause = e.cause ?: e
      if (cause is TimeoutException) {
        logger.log(Level.FINE, "Executing layoutlib callbacks took longer than expected at ${sessionTimeNanos}ns", cause)
      } else {
        // Match StaticPreviewCallbackRunner.renderExecutingCallbacks: report non-fatal callback
        // failures (e.g. a throwing LaunchedEffect) to RenderLogger without aborting frame rendering.
        val msg = "Error while executing layoutlib callbacks at ${sessionTimeNanos}ns: $cause"
        renderLogger?.error(null, msg, cause, null, null)
        logger.log(Level.WARNING, msg, cause)
      }
    }
    try {
      return renderFrame().get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw e
    } catch (e: TimeoutException) {
      val msg = "Timed out rendering animation frame at ${sessionTimeNanos}ns"
      renderLogger?.error(null, msg, e, null, null)
      logger.log(Level.WARNING, msg, e)
      throw e
    } catch (e: ExecutionException) {
      rethrowIfInterruptedOrFatalJvmError(e)
      throw e
    }
  }

  private fun rethrowIfInterruptedOrFatalJvmError(e: ExecutionException) {
    var current: Throwable? = e.cause
    var depth = 0
    while (current != null && depth < 16) {
      if (current is InterruptedException) {
        Thread.currentThread().interrupt()
        throw current
      }
      if (current is VirtualMachineError && current !is StackOverflowError) {
        throw current
      }
      current = current.cause
      depth++
    }
  }
}
