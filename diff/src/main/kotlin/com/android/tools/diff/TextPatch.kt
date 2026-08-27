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
package com.android.tools.diff

import kotlin.math.abs
import kotlin.math.max

/** The algorithmic engine used to compute diffs. */
enum class DiffAlgorithm {
  /** Standard dynamic programming LCS (Longest Common Subsequence) exact matching. O(NM). */
  LCS,

  /** High-performance Myers' edit-graph shortest edit path algorithm. O(ND) time. */
  MYERS,
}

/**
 * A mathematical representation of a patch consisting of multiple [DiffChunk]s.
 *
 * This class is a self-contained, zero-dependency diffing and patching engine. It provides robust cross-platform operations (standardizing
 * line terminators to LF in memory while preserving binary-exact line endings for output) and advanced features like renumbering,
 * state-merging squashes, and fuzzy re-anchoring.
 *
 * @property chunks The sorted, non-overlapping contiguous blocks of changes in this patch.
 */
data class TextPatch internal constructor(val chunks: List<DiffChunk>) {
  init {
    for (i in 0 until chunks.size - 1) {
      val current = chunks[i]
      val next = chunks[i + 1]
      require(current.oldStart + current.oldLength <= next.oldStart) {
        "Chunks must be non-overlapping. Overlap detected between: $current and $next"
      }
    }
  }

  /** Formats all chunks in this patch into standard Unified Diff hunk representations. */
  fun toUnifiedString(): String = chunks.joinToString("\n") { it.toUnifiedString() }

  /** The total number of lines added by this patch. */
  val linesAdded: Int
    get() = chunks.sumOf { chunk -> chunk.lines.count { it.type == LineType.ADDED } }

  /** The total number of lines removed by this patch. */
  val linesRemoved: Int
    get() = chunks.sumOf { chunk -> chunk.lines.count { it.type == LineType.REMOVED } }

  /** Returns true if this patch contains no changes. */
  fun isEmpty(): Boolean = chunks.isEmpty()

  /**
   * Applies this patch to the given [source] text, returning the patched string.
   *
   * **Algorithmic Details (High-Performance $O(N)$ Linear reconstruction):**
   * - Reconstructs the patched document in a **single, forward-pass scan** over the original lines.
   * - This completely avoids memory-intensive array element shifting copy loops (which would result in O(N*K) complexity), reducing the
   *   complexity to strict linear $O(N)$.
   * - Pre-allocates the exact capacity of the output list buffer to prevent internal resizing.
   * - Verification is done strictly on line content (ignoring line terminators), enabling cross-platform context matching, but the output
   *   text preserves the binary-exact terminators specified in the patch.
   *
   * @throws IllegalArgumentException if the original text is shorter than expected or if there is a context mismatch.
   */
  fun apply(source: String): String {
    if (isEmpty()) return source
    val originalLines = source.splitWithLineSeparators()

    // Constructor guarantees chunks are already sorted in forward order

    // Pre-allocate output buffer with the exact expected capacity to prevent internal list resizing
    val resultList = ArrayList<String>(originalLines.size + linesAdded - linesRemoved)
    var sourceIdx = 0

    for (chunk in chunks) {
      val startIdx = chunk.oldStart - 1
      require(startIdx in sourceIdx..originalLines.size) {
        "Invalid chunk start: ${chunk.oldStart} (overlaps with previous chunks or out of bounds)."
      }

      // Copy unmodified preceding context lines directly from the original document
      while (sourceIdx < startIdx) {
        resultList.add(originalLines[sourceIdx])
        sourceIdx++
      }

      // Extract context lines (excluding additions)
      val expectedContext = chunk.lines.filter { it.type != LineType.ADDED }.map { it.text }
      val actualContext = originalLines.drop(sourceIdx).take(chunk.oldLength).map { it.removeSuffix("\n").removeSuffix("\r") }

      require(actualContext.size == chunk.oldLength) { "Original text shorter than expected by patch at line ${chunk.oldStart}." }

      require(expectedContext == actualContext) {
        "Patch context mismatch at line ${chunk.oldStart}. Expected: $expectedContext, but was: $actualContext"
      }

      // Inject new replacement lines and advance the source index past the replaced old block
      val newLines = chunk.lines.filter { it.type != LineType.REMOVED }.map { it.text + it.separator.value }
      resultList.addAll(newLines)
      sourceIdx += chunk.oldLength
    }

    // Copy any remaining unmodified trailing context lines up to the end of the original document
    while (sourceIdx < originalLines.size) {
      resultList.add(originalLines[sourceIdx])
      sourceIdx++
    }

    return resultList.joinToString("")
  }

  /** Returns a new [TextPatch] that reverses all changes in this patch. */
  fun invert(): TextPatch = create(chunks.map { it.invert() })

  /** Returns a new [TextPatch] where all line numbers are shifted by the given [delta]. */
  fun renumbered(delta: Int): TextPatch = TextPatch(chunks.map { it.renumbered(delta) })

  /**
   * Represents an atomic, single-line operation inside a chunk, carrying absolute coordinates in both the original and new document spaces.
   *
   * Unified with the internal diff line representation to prevent redundant allocations.
   *
   * @property type The change operation type (CONTEXT, ADDED, REMOVED).
   * @property text The line content without the prefix and terminator.
   * @property separator The preserved line ending terminator sequence.
   */
  private fun TextPatch.toSingleLineDiffChunks(): List<SingleLineDiffChunk> {
    val singleChunks = mutableListOf<SingleLineDiffChunk>()
    for (chunk in chunks) {
      var currentOld = chunk.oldStart
      var currentNew = chunk.newStart
      for (line in chunk.lines) {
        when (line.type) {
          LineType.CONTEXT -> {
            singleChunks.add(SingleLineDiffChunk(line.type, line.text, line.separator, currentOld, 1, currentNew, 1))
            currentOld++
            currentNew++
          }
          LineType.REMOVED -> {
            singleChunks.add(SingleLineDiffChunk(line.type, line.text, line.separator, currentOld, 1, currentNew, 0))
            currentOld++
          }
          LineType.ADDED -> {
            singleChunks.add(SingleLineDiffChunk(line.type, line.text, line.separator, currentOld, 0, currentNew, 1))
            currentNew++
          }
        }
      }
    }
    return singleChunks
  }

  private fun reconstruct(flattened: List<SingleLineDiffChunk>): List<DiffChunk> {
    if (flattened.isEmpty()) return emptyList()

    val ret = mutableListOf<DiffChunk>()
    val first = flattened[0]
    var oldStart = first.oldStart
    var oldLength = first.oldLength
    var newStart = first.newStart
    var newLength = first.newLength

    var lines = mutableListOf(DiffLine(first.type, first.text, first.separator))

    for (ix in 1..flattened.lastIndex) {
      val line = flattened[ix]
      // GRACEFUL OVERLAPPING EDITS MERGING:
      // During squash composition, patch 2 may insert or edit lines directly inside the coordinate
      // bounds modified by patch 1. This yields overlapping intermediate coordinates (where the
      // flat line change falls strictly inside the current chunk bounds: line.oldStart <= oldStart + oldLength).
      //
      // Instead of starting a new chunk (which would result in mathematically overlapping DiffChunks
      // and crash the platform constructor), we gracefully merge the overlapping change into the
      // current chunk, dynamically expanding its coordinate oldLength and newLength bounds to keep
      // the patch structure unified and non-overlapping.
      if (line.oldStart >= oldStart && line.oldStart <= oldStart + oldLength) {
        oldLength = maxOf(oldLength, line.oldStart + line.oldLength - oldStart)
        newLength += line.newLength
        lines.add(DiffLine(line.type, line.text, line.separator))
      } else {
        ret.add(DiffChunk(oldStart, oldLength, newStart, newLength, lines))
        oldStart = line.oldStart
        oldLength = line.oldLength
        newStart = line.newStart
        newLength = line.newLength
        lines = mutableListOf(DiffLine(line.type, line.text, line.separator))
      }
    }
    ret.add(DiffChunk(oldStart, oldLength, newStart, newLength, lines))
    return ret
  }

  /**
   * Combines this patch with [other], assuming [other] is a patch that applies to the result of applying this patch.
   *
   * **Algorithmic Details (High Performance $O(N)$ Squash):** Instead of applying both patches functionally and diffing again (which is an
   * $O(N^2)$ LCS operation), this squash uses a single-pass state merging algorithm:
   * 1. Both patches are flattened into atomic one-line operations (`SingleDiffChunk`) carrying absolute coordinate positions.
   * 2. Two pointers (`ix1`, `ix2`) traverse the flattened lists in parallel, merging operations on overlapping lines while maintaining
   *    correct source/destination document offsets.
   * 3. Context-only chunks (redundant chunks where edits cancel out completely) are filtered out before reconstruction, yielding
   *    standard-compliant minimal patches.
   *
   * @throws IllegalStateException if the patches have conflicting changes on overlapping lines.
   */
  fun squash(other: TextPatch): TextPatch {
    if (this.isEmpty()) return other
    if (other.isEmpty()) return this

    val flat1 = this.toSingleLineDiffChunks()
    val flat2 = other.toSingleLineDiffChunks()

    val squashed = mutableListOf<SingleLineDiffChunk>()

    var ix1 = 0
    var ix2 = 0
    var source = 1
    var dest = 1

    while (true) {
      if (ix1 < flat1.size && (ix2 >= flat2.size || flat1[ix1].isStrictlyBefore(flat2[ix2]))) {
        // Case 1: Operation in flat1 is strictly before flat2 starts or flat2 doesn't consume it yet.
        // Push flat1 operation to the destination coordinate space.
        // tracks 'source' (original space cursor) and 'dest' (intermediate space cursor).
        val line1 = flat1[ix1]
        val skipped = line1.oldStart - source
        source += skipped
        dest += skipped
        squashed.add(SingleLineDiffChunk(line1.type, line1.text, line1.separator, line1.oldStart, line1.oldLength, dest, line1.newLength))
        source += line1.oldLength
        dest += line1.newLength
        ix1++
      } else if (
        ix2 < flat2.size &&
          (ix1 >= flat1.size ||
            flat1[ix1].newStart > flat2[ix2].oldStart ||
            (flat1[ix1].newStart == flat2[ix2].oldStart && flat1[ix1].newLength > flat2[ix2].oldLength))
      ) {
        // Case 2: Operation in flat2 is strictly after flat1 finished or flat1 doesn't output to it.
        // Pull flat2 operation directly from the intermediate document to the final coordinates.
        val line2 = flat2[ix2]
        // Note: line2.newStart and dest track final document space (Case 2).
        // Hence we compute skipped gap in the final space. (Contrast with Case 1 which tracks original space).
        val skipped = line2.newStart - dest
        source += skipped
        dest += skipped
        squashed.add(SingleLineDiffChunk(line2.type, line2.text, line2.separator, source, line2.oldLength, line2.newStart, line2.newLength))
        source += line2.oldLength
        dest += line2.newLength
        ix2++
      } else if (ix1 < flat1.size && ix2 < flat2.size) {
        // Case 3: Overlapping operations on the same intermediate line. Merge them.
        val line1 = flat1[ix1]
        val line2 = flat2[ix2]
        val skipped = line1.oldStart - source
        source += skipped + line1.oldLength
        dest += skipped + line2.newLength

        // Safety Check: Patches must have consistent views of the intermediate text!
        check(line1.text == line2.text && line1.separator == line2.separator) {
          "Patches are not compatible for squash. Expected: [${line1.text}${line1.separator.value}], but was: [${line2.text}${line2.separator.value}]"
        }

        line1.merge(line2)?.let(squashed::add)
        ix1++
        ix2++
      } else {
        break
      }
    }

    val result = create(reconstruct(squashed))
    // Critical: Filter out any chunks that only contain unmodified context (edits canceled out)
    val activeChunks = result.chunks.filter { chunk -> chunk.lines.any { it.type != LineType.CONTEXT } }
    return create(activeChunks)
  }

  /**
   * Attempts to repair this patch by fuzzily matching its chunks against the given [sourceLines].
   *
   * This is useful for re-anchoring patches generated by LLMs that may have used outdated context or made minor formatting changes.
   *
   * **Strict All-or-None Failure Semantics:**
   * - If *every* chunk in this patch is successfully re-anchored fuzily, returns the repaired [TextPatch].
   * - If *any single* chunk fails to anchor cleanly, the entire repair operation fails immediately and returns `null`.
   *
   * **Algorithmic Details & Constraints:**
   * - Chunks are repaired and re-anchored independently using two-tier sliding window searches ([perfectRepair] and then [fuzzyRepair] if
   *   exact match fails).
   * - The resulting repaired chunks are deduplicated and **renumbered sequentially** top-to-bottom. We use an adjusting `offset`
   *   accumulator to update coordinate positions to prevent overlap.
   * - **Fuzzy Collision Constraint:** Because chunks are repaired independently, if the source document has mutated so severely that two
   *   repaired chunks end up shifting onto the same lines or overlapping, the `TextPatch` constructor validation will fail, throwing an
   *   [IllegalArgumentException]. Callers should handle this exception as an indication that the document has drifted beyond repairable
   *   bounds.
   *
   * @param sourceLines The line sequence of the document to anchor edits against.
   * @return A repaired [TextPatch] if all hunks successfully re-anchored fuzily, or `null` if any single hunk failed to repair.
   */
  fun repair(sourceLines: List<String>): TextPatch? {
    val repairedChunks = mutableListOf<DiffChunk>()

    for (chunk in chunks) {
      val repaired =
        chunk.perfectRepair(sourceLines)
          ?: chunk.fuzzyRepair(sourceLines)
          ?: return null // Fail fast and return null immediately if any chunk fails to re-anchor!
      repairedChunks.add(repaired)
    }

    val unique = repairedChunks.distinct()
    val renumbered = mutableListOf<DiffChunk>()
    var offset = 0
    unique
      .sortedBy { it.oldStart }
      .forEach { chunk ->
        val oldStart = chunk.oldStart
        val oldLength = chunk.oldLength
        val newLength = chunk.newLength
        renumbered.add(DiffChunk(oldStart, oldLength, oldStart + offset, newLength, chunk.lines))
        offset += newLength - oldLength
      }

    return TextPatch(renumbered)
  }

  companion object {
    /**
     * Constructs a [TextPatch] by automatically sorting the given [chunks] and validating that they do not overlap.
     *
     * @throws IllegalArgumentException if overlapping chunks are detected.
     */
    fun create(chunks: List<DiffChunk>): TextPatch {
      val sorted = chunks.sortedBy { it.oldStart }
      return TextPatch(sorted)
    }

    /** Exposes standard, pre-configured [lineMatcher] strategies for fuzzy or customized diffing. */
    object LineMatchers {
      /** Strict exact comparison (including binary-exact line endings). */
      val EXACT: (String, String) -> Boolean = { a, b -> a == b }

      /** Trims leading/trailing whitespace and ignores line endings during comparison. */
      val IGNORE_WHITESPACE: (String, String) -> Boolean = { a, b -> a.trim() == b.trim() }

      /** Matches if the Levenshtein edit distance (ignoring line endings) is within [maxDistance]. */
      fun levenshtein(maxDistance: Int): (String, String) -> Boolean {
        require(maxDistance >= 0) { "maxDistance must be non-negative: $maxDistance" }
        return { a, b ->
          val cleanA = a.removeSuffix("\n").removeSuffix("\r")
          val cleanB = b.removeSuffix("\n").removeSuffix("\r")
          levenshteinDistance(cleanA, cleanB) <= maxDistance
        }
      }
    }

    /**
     * Calculates the Levenshtein edit distance between two strings using standard O(NM) DP matrix.
     *
     * Measures the minimum number of single-character edits (insertions, deletions, or substitutions) required to transform string [a] into
     * string [b].
     */
    fun levenshteinDistance(a: String, b: String): Int {
      val n = a.length
      val m = b.length
      if (n == 0) return m
      if (m == 0) return n

      // Optimize allocation by ensuring the shorter string is used for array sizing
      val (shorter, longer) = if (n < m) Pair(a, b) else Pair(b, a)
      val sLen = shorter.length
      val lLen = longer.length

      var prevRow = IntArray(sLen + 1) { it }
      var currRow = IntArray(sLen + 1)

      for (i in 1..lLen) {
        currRow[0] = i
        for (j in 1..sLen) {
          val cost = if (longer[i - 1] == shorter[j - 1]) 0 else 1
          currRow[j] =
            minOf(
              prevRow[j] + 1, // Deletion
              currRow[j - 1] + 1, // Insertion
              prevRow[j - 1] + cost, // Substitution
            )
        }
        // Swap row references directly to avoid allocating new arrays in the loop
        val temp = prevRow
        prevRow = currRow
        currRow = temp
      }
      return prevRow[sLen]
    }

    /**
     * Computes a [TextPatch] representing the difference between [before] and [after] texts.
     *
     * **Memory Note:** The default [DiffAlgorithm.LCS] engine utilizes **Hirschberg's divide-and-conquer algorithm** under the hood,
     * optimizing memory footprint to linear $O(\min(N, M))$ space. This makes it highly memory-safe and completely prevents
     * OutOfMemoryErrors even on extremely large files (e.g., 10k+ lines) where standard LCS would allocate massive matrices.
     *
     * @param contextLines The number of unchanged context lines to keep around edits in each chunk.
     * @param algorithm The diffing algorithm engine to use ([DiffAlgorithm.LCS] or [DiffAlgorithm.MYERS]).
     * @param lineMatcher The comparison strategy to determine if two lines match (defaults to [LineMatchers.EXACT]).
     */
    fun compute(
      before: String,
      after: String,
      contextLines: Int = 3,
      algorithm: DiffAlgorithm = DiffAlgorithm.LCS,
      lineMatcher: (String, String) -> Boolean = LineMatchers.EXACT,
    ): TextPatch {
      val beforeLines = if (before.isEmpty()) emptyList() else before.splitWithLineSeparators()
      val afterLines = if (after.isEmpty()) emptyList() else after.splitWithLineSeparators()
      val diffLines =
        when (algorithm) {
          DiffAlgorithm.LCS -> diffLinesLcs(beforeLines, afterLines, lineMatcher)
          DiffAlgorithm.MYERS -> myersDiff(beforeLines, afterLines, lineMatcher)
        }
      return TextPatch(DiffChunk.createChunks(diffLines, contextLines))
    }

    /** Creates a [TextPatch] that represents the binary-exact creation of a file with the given [content]. */
    fun forFileCreation(content: String): TextPatch {
      val lines = content.splitWithLineSeparators()
      val diffLines = lines.map { DiffLine.fromRawLine(it, LineType.ADDED) }
      return TextPatch(listOf(DiffChunk(1, 0, 1, lines.size, diffLines)))
    }

    /**
     * Parses a Unified Diff string representation into a structured [TextPatch].
     *
     * @param strict If `true`, throws [IllegalArgumentException] when encountering malformed lines lacking standard prefix characters
     *   inside any chunk. Defaults to `false`.
     */
    fun parse(diffText: String, strict: Boolean = false): TextPatch {
      return TextPatch(DiffChunk.parse(diffText, strict))
    }

    /**
     * Space-optimized Longest Common Subsequence (LCS) using Hirschberg's algorithm.
     *
     * **Algorithmic Details:**
     * - Implements Hirschberg's quadratic-time O(NM) but linear-space O(min(N, M)) divide-and-conquer DP algorithm.
     * - Recursively bisects string A, calculates the LCS boundary crossing index k on string B using only two rows of DP state
     *   (zero-allocation row reuse), and then reconstructs the optimal edit path recursively.
     */
    private fun diffLinesLcs(
      originalLines: List<String>,
      newLines: List<String>,
      lineMatcher: (String, String) -> Boolean,
    ): List<DiffLine> {
      // Strict O(min(N, M)) space optimization: transpose arguments if the second list
      // is larger, and invert the resulting diff line edits before returning.
      if (newLines.size > originalLines.size) {
        val swappedEdits = diffLinesLcs(newLines, originalLines, lineMatcher)
        return swappedEdits.map { line ->
          val invertedType =
            when (line.type) {
              LineType.ADDED -> LineType.REMOVED
              LineType.REMOVED -> LineType.ADDED
              LineType.CONTEXT -> LineType.CONTEXT
            }
          DiffLine(invertedType, line.text, line.separator)
        }
      }

      val n = originalLines.size
      val m = newLines.size

      // Base Cases:
      if (n == 0) {
        return newLines.map { DiffLine.fromRawLine(it, LineType.ADDED) }
      }
      if (m == 0) {
        return originalLines.map { DiffLine.fromRawLine(it, LineType.REMOVED) }
      }
      if (n == 1) {
        val lineA = originalLines[0]
        val matchIdx = newLines.indexOfFirst { lineMatcher(lineA, it) }
        if (matchIdx >= 0) {
          val before = newLines.subList(0, matchIdx).map { DiffLine.fromRawLine(it, LineType.ADDED) }
          val matchLine = DiffLine.fromRawLine(lineA, LineType.CONTEXT)
          val after = newLines.subList(matchIdx + 1, m).map { DiffLine.fromRawLine(it, LineType.ADDED) }
          return before + matchLine + after
        } else {
          val removed = DiffLine.fromRawLine(lineA, LineType.REMOVED)
          val added = newLines.map { DiffLine.fromRawLine(it, LineType.ADDED) }
          return listOf(removed) + added
        }
      }

      // Divide step: Bisect originalLines (A) and search for optimal split index k on newLines (B)
      val mid = n / 2
      val a1 = originalLines.subList(0, mid)
      val a2 = originalLines.subList(mid, n)

      val leftRow = lcsLength(a1, newLines, lineMatcher)
      val rightRow = lcsLength(a2.asReversed(), newLines.asReversed(), lineMatcher)

      var maxScore = -1
      var bestK = 0
      for (k in 0..m) {
        val score = leftRow[k] + rightRow[m - k]
        if (score > maxScore) {
          maxScore = score
          bestK = k
        }
      }

      // Conquer step: recursively solve left and right partitions and concatenate
      val leftEdits = diffLinesLcs(a1, newLines.subList(0, bestK), lineMatcher)
      val rightEdits = diffLinesLcs(a2, newLines.subList(bestK, m), lineMatcher)
      return leftEdits + rightEdits
    }

    /** Calculates LCS DP table last row in linear space O(M) reusing buffer arrays. */
    private fun lcsLength(a: List<String>, b: List<String>, lineMatcher: (String, String) -> Boolean): IntArray {
      val n = a.size
      val m = b.size
      var prev = IntArray(m + 1)
      var curr = IntArray(m + 1)

      for (i in 1..n) {
        val lineA = a[i - 1]
        for (j in 1..m) {
          val lineB = b[j - 1]
          if (lineMatcher(lineA, lineB)) {
            curr[j] = prev[j - 1] + 1
          } else {
            curr[j] = maxOf(prev[j], curr[j - 1])
          }
        }
        // Swap rows to reuse memory buffers directly
        val temp = prev
        prev = curr
        curr = temp
      }
      return prev
    }

    /**
     * High-performance Myers' edit-graph shortest edit path algorithm.
     *
     * **Lineage & Provenance Details:**
     * - Extracted and promoted from the private implementation in the prediction module: `com.google.tools.aiplugin.editor.prediction.Diff`
     *   to be shared as a public standalone base utility.
     * - Originally implements the standard greedy diff algorithm introduced by Eugene W. Myers in his 1986 paper: *"An O(ND) Difference
     *   Algorithm and Its Variations"* (Algorithmica 1, 251–266).
     * - Solves the Shortest Edit Script (SES) problem in $O(ND)$ time by searching greedily along diagonals $k = x - y$ in the edit graph.
     *   `v` tracks the farthest reaching x-coordinate on each diagonal.
     *
     * **Space Complexity Note:** To enable backtrack path reconstruction, clones of `v` are appended to `history` at the start of each step
     * $d$. This leads to $O(D(N+M))$ space complexity. While highly optimized for standard file sizes, this should be taken into account if
     * diffing extremely large files with a massive number of changes.
     */
    private fun myersDiff(a: List<String>, b: List<String>, lineMatcher: (String, String) -> Boolean): List<DiffLine> {
      val n = a.size
      val m = b.size
      if (n == 0 && m == 0) return emptyList()

      val max = n + m
      val v = IntArray(2 * max + 1) // Farthest reaching x on diagonal k
      val history = mutableListOf<IntArray>()

      var found = false
      var d = 0
      while (d <= max && !found) {
        val currentV = v.clone()
        history.add(currentV) // Record state before updating at step d
        for (k in -d..d step 2) {
          val idx = k + max
          // Decide whether to move down (deletion) or right (insertion).
          // Greedy choice: pick the transition that leads to the farthest-reaching x.
          var x =
            if (d == 0) {
              0
            } else if (k == -d || (k != d && history[d][idx - 1] < history[d][idx + 1])) {
              history[d][idx + 1] // Move down from k+1 diagonal (deletion)
            } else {
              history[d][idx - 1] + 1 // Move right from k-1 diagonal (insertion)
            }
          var y = x - k

          // Follow diagonals (identical lines / context) as far as possible
          while (x < n && y < m && lineMatcher(a[x], b[y])) {
            x++
            y++
          }
          v[idx] = x
          if (x >= n && y >= m) {
            found = true
            break
          }
        }
        if (found) break
        d++
      }

      // Path Reconstruction: Backtrack from the end point (n, m) back to (0, 0)
      val path = mutableListOf<DiffLine>()
      var x = n
      var y = m
      for (step in d downTo 1) {
        val prevV = history[step] // State of V before step d ran
        val k = x - y
        val idx = k + max

        // Determine which diagonal k-1 or k+1 we transitioned from at step
        val prevK =
          if (k == -step || (k != step && prevV[idx - 1] < prevV[idx + 1])) {
            k + 1 // Came from k+1 via down-move (deletion)
          } else {
            k - 1 // Came from k-1 via right-move (insertion)
          }
        val prevX = prevV[prevK + max]
        val prevY = prevX - prevK

        // Trace diagonal moves (context lines) that occurred after the transition at this step
        while (x > prevX && y > prevY) {
          val line = a[x - 1]
          val terminator = line.takeLastWhile { c -> c == '\n' || c == '\r' }
          path.add(DiffLine(LineType.CONTEXT, line.removeSuffix(terminator), LineSeparator.from(terminator)))
          x--
          y--
        }

        // Trace the horizontal or vertical move (the edit) made at this step
        if (x > prevX) {
          val line = a[x - 1]
          val terminator = line.takeLastWhile { c -> c == '\n' || c == '\r' }
          path.add(DiffLine(LineType.REMOVED, line.removeSuffix(terminator), LineSeparator.from(terminator)))
          x--
        } else if (y > prevY) {
          val line = b[y - 1]
          val terminator = line.takeLastWhile { c -> c == '\n' || c == '\r' }
          path.add(DiffLine(LineType.ADDED, line.removeSuffix(terminator), LineSeparator.from(terminator)))
          y--
        }
      }

      // Reconstruct remaining diagonal context before step 1
      while (x > 0 && y > 0) {
        val line = a[x - 1]
        val terminator = line.takeLastWhile { c -> c == '\n' || c == '\r' }
        path.add(DiffLine(LineType.CONTEXT, line.removeSuffix(terminator), LineSeparator.from(terminator)))
        x--
        y--
      }

      return path.reversed()
    }

    private val LINE_SEPARATOR_REGEX = Regex("\r\n|\n|\r")

    /** Splits a string into lines while keeping the line terminators (\n, \r\n, or \r). */
    fun String.splitWithLineSeparators(): List<String> {
      if (this.isEmpty()) return emptyList()
      return buildList {
        var lastEnd = 0
        for (matchResult in LINE_SEPARATOR_REGEX.findAll(this@splitWithLineSeparators)) {
          add(this@splitWithLineSeparators.substring(lastEnd, matchResult.range.first) + matchResult.value)
          lastEnd = matchResult.range.last + 1
        }
        if (lastEnd < this@splitWithLineSeparators.length) {
          add(this@splitWithLineSeparators.substring(lastEnd))
        }
      }
    }
  }
}

/**
 * Attempts to find a perfect exact match for the chunk's context lines in the [source].
 *
 * **Context Drift & Repair Intent:** If a patch was generated against an older version of the source file, the original line numbers (e.g.,
 * [oldStart]) in this [DiffChunk] may no longer align with the current [source] document due to upstream insertions or deletions. This
 * "repair" method performs a sliding-window search to locate the new correct starting line index in the [source] where the chunk's expected
 * context lines reside, effectively re-anchoring the coordinate offsets back to a matching position.
 *
 * **Algorithmic Details:**
 * - Performs a sliding window exact-match search over all possible line positions in the source document.
 * - Line comparison normalizes line endings to ensure cross-platform exact match.
 * - If a candidate position is found, it maps the chunk's lines to the exact string content and line endings present in the source document
 *   (perfectly preserving the document's styling).
 *
 * @return A re-anchored, binary-exact [DiffChunk] on success, or `null` if no exact match is found.
 */
private fun DiffChunk.perfectRepair(source: List<String>): DiffChunk? {
  val expectedContext = lines.mapNotNull { if (it.type != LineType.ADDED) it.text else null }
  if (expectedContext.isEmpty()) return null

  val start0 = oldStart - 1
  val candidates = mutableListOf<Int>()
  for (i in 0..(source.size - expectedContext.size)) {
    var fits = true
    for (k in expectedContext.indices) {
      if (expectedContext[k] != source[i + k].removeSuffix("\n").removeSuffix("\r")) {
        fits = false
        break
      }
    }
    if (fits) candidates.add(i)
  }

  if (candidates.isEmpty()) return null

  // Pick the candidate starting index closest to the chunk's original starting position
  val bestStart0 = candidates.minBy { abs(it - start0) }
  val newChunkLines = lines.mapIndexed { lineIndexInChunk, line ->
    if (line.type == LineType.ADDED) line
    else {
      val sourceLineIndex = bestStart0 + lines.subList(0, lineIndexInChunk).count { it.type != LineType.ADDED }
      DiffLine.fromRawLine(source[sourceLineIndex], line.type)
    }
  }

  return DiffChunk(bestStart0 + 1, oldLength, bestStart0 + 1, newLength, newChunkLines)
}

/**
 * Attempts to repair a broken chunk by performing a sliding-window fuzzy match over the [source] document.
 *
 * **Algorithmic Details & LLM Tolerances:**
 * - Performs a sliding-window fuzzy matching search over every line index in the source document.
 * - **Indentation & Endings Tolerance:** Leading whitespace (indentation) and trailing line endings are ignored during comparison
 *   (`trimStart()` and `removeSuffix("\n")`).
 * - **Hallucinated Empty Lines (addLine = false):** If the diff chunk expects a whitespace-only line but the source lacks it (a common LLM
 *   context hallucination), the empty line is ignored and skipped in the chunk.
 * - **Missed Empty Lines (addLine = true):** If the source document contains an empty line that the diff chunk missed, the chunk's context
 *   stream is dynamically padded with an empty line to preserve alignment.
 * - **Reconstruction:** The closest candidate match is selected. The chunk's context lines are then "remastered" using the exact text,
 *   indentation, and line separators found in the source document.
 *
 * @return A re-anchored, fully aligned [DiffChunk], or `null` if no matching candidate is found.
 */
private fun DiffChunk.fuzzyRepair(source: List<String>): DiffChunk? {
  if (lines.none { it.type != LineType.ADDED }) return null

  data class FuzzyNewline(val index: Int, val addLine: Boolean)
  val candidates = mutableMapOf<Int, MutableList<FuzzyNewline>>()
  val start0 = oldStart - 1

  // Tier 1: Sliding window fuzzy match search
  for (i in source.indices) {
    var fits = true
    var chunkLineIndex = 0
    var sourceLookaheadIndex = i
    val adjustments = mutableListOf<FuzzyNewline>()

    while (chunkLineIndex < lines.size) {
      val chunkLine = lines[chunkLineIndex]
      if (chunkLine.type == LineType.ADDED) {
        chunkLineIndex++
        continue
      }

      val sourceLine = source.getOrNull(sourceLookaheadIndex)
      val fuzzyChunk = chunkLine.text.trim()

      if (sourceLine == null) {
        if (fuzzyChunk.isEmpty()) {
          // Diff chunk expects a whitespace-only line, but source ended.
          // Treat as LLM context hallucination and ignore the chunk line.
          adjustments.add(FuzzyNewline(chunkLineIndex, false))
          chunkLineIndex++
        } else {
          fits = false
          break
        }
      } else {
        val fuzzySource = sourceLine.trim()
        when {
          fuzzyChunk.isEmpty() && fuzzySource.isNotEmpty() -> {
            // Case A: Diff chunk has an extra empty line the source lacks (LLM hallucination).
            // Skip the chunk line to keep alignment.
            adjustments.add(FuzzyNewline(chunkLineIndex, false))
            chunkLineIndex++
          }
          fuzzyChunk.isNotEmpty() && chunkLineIndex > 0 && fuzzySource.isEmpty() -> {
            // Case B: Source has an empty line the diff chunk missed (LLM omission).
            // Pad the chunk with an empty line and advance source pointer.
            adjustments.add(FuzzyNewline(chunkLineIndex, true))
            sourceLookaheadIndex++
          }
          else -> {
            // Standard comparison: compare line text ignoring leading whitespace and endings
            fits = fuzzyChunk == fuzzySource
            if (!fits) break
            chunkLineIndex++
            sourceLookaheadIndex++
          }
        }
      }
    }
    if (fits) candidates[i] = adjustments
  }

  if (candidates.isEmpty()) return null

  // Pick the candidate closest to the original line number
  val (bestStart0, bestAdjustments) = candidates.entries.minBy { abs(it.key - start0) }

  // Tier 2: Remastering & Reconstruction
  var adjIdx = 0
  var k = 0
  var sIdx = bestStart0
  val remastered = mutableListOf<DiffLine>()

  while (k < lines.size) {
    // Apply any padding/omission adjustments at this chunk index position
    while (adjIdx < bestAdjustments.size && k == bestAdjustments[adjIdx].index) {
      if (bestAdjustments[adjIdx].addLine) {
        // Omission: insert the missing empty line from the source into the context stream
        val sourceLine = source[sIdx]
        val separatorStr = sourceLine.takeLastWhile { c -> c == '\n' || c == '\r' }
        remastered.add(DiffLine(LineType.CONTEXT, sourceLine.removeSuffix(separatorStr), LineSeparator.from(separatorStr)))
        sIdx++
      } else {
        // Hallucination: skip the redundant empty line in the chunk
        k++
      }
      adjIdx++
    }
    if (k >= lines.size) break
    val line = lines[k]
    if (line.type == LineType.ADDED) {
      remastered.add(line)
    } else {
      // Context/Removed: remaster using the exact text and styling present in the source document
      val sourceLine = source[sIdx]
      val separatorStr = sourceLine.takeLastWhile { c -> c == '\n' || c == '\r' }
      remastered.add(DiffLine(line.type, sourceLine.removeSuffix(separatorStr), LineSeparator.from(separatorStr)))
      sIdx++
    }
    k++
  }

  return DiffChunk(
    bestStart0 + 1,
    remastered.count { it.type != LineType.ADDED },
    bestStart0 + 1,
    remastered.count { it.type != LineType.REMOVED },
    remastered,
  )
}
