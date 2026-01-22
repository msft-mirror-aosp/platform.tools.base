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
package com.android.tools.leakcanarylib.parser

import com.android.tools.leakcanarylib.data.LeakTraceNodeType
import com.android.tools.leakcanarylib.data.LeakingStatus
import com.android.tools.leakcanarylib.data.Node

/**
 * Utility class for parsing the string representation of leak trace nodes into structured `Node` objects.
 */
class NodeParser {

    companion object {
        private val leakingStatusRegex =
            Regex("""Leaking: (YES|NO|UNKNOWN)(?: \((.*)\))?""")
        private val leakingStatusMultiLineRegex =
            Regex("""Leaking:\s*(YES|NO|UNKNOWN)\s*\((.*)""")
        private val retainingRegex =
            Regex("""Retaining (\d+(?:\.\d+)?)\s*(\w+)?B? in (\d+) objects""")

        /**
         * Parses a list of strings representing a leak trace node and its associated reference line (if present). This method first
         * separates the lines describing the leaking object itself from the reference lines, then parses each part separately and
         * combines them into a single `Node`.
         *
         * @param inputLines: The list of lines to parse.
         * @param firstLinePrefix: Prefix expected on the first line of the leaking object's description.
         * @param additionalLinesPrefix: Prefix expected on subsequent lines of the leaking object's description.
         * @param referenceLinePrefix: Prefix expected on reference lines (if present).
         *
         * @return A `Node` object representing the parsed leak trace node.
         */
        fun parse(
            inputLines: List<String>,
            firstLinePrefix: String,
            additionalLinesPrefix: String,
            multiLinePrefix: String,
            referenceLinePrefix: String
        ): Node {
            val lines = inputLines.map { it.trim() }
            val firstReferenceLineIndex =
                lines.indexOfFirst { it.startsWith(referenceLinePrefix) }

            val (objectLines, referenceLines) = if (firstReferenceLineIndex == -1) {
                Pair(lines, emptyList())
            } else {
                Pair(
                    lines.subList(0, firstReferenceLineIndex),
                    lines.subList(firstReferenceLineIndex, lines.size)
                )
            }

            val leakingNode =
                parseLeakNode(objectLines, firstLinePrefix, multiLinePrefix, additionalLinesPrefix)
            leakingNode.referencingField =
                ReferencingFieldParser.parseReferencingField(referenceLines, additionalLinesPrefix)
            return leakingNode
        }

        /**
         * Parses a list of strings representing the core information of a leak trace node (excluding reference lines). This method
         * extracts details like the class name, type, leaking status, retained memory information, and any additional notes.
         *
         * @param inputLines: The list of lines to parse.
         * @param firstLinePrefix: Prefix expected on the first line.
         * @param additionalLinesPrefix: Prefix expected on subsequent lines.
         *
         * @return A `Node` object representing the parsed leak trace node.
         * @throws IllegalArgumentException: If the input lines are malformed or missing essential information.
         */
        fun parseLeakNode(
            inputLines: List<String>,
            firstLinePrefix: String,
            multiLinePrefix: String,
            additionalLinesPrefix: String
        ): Node {
            var className: String? = null
            var type: LeakTraceNodeType? = null
            var leakingStatus: LeakingStatus? = null
            var leakingStatusReason = ""
            var retainedHeapSize: String? = null
            var retainedObjectCount: Int? = null
            var leakingMultiLine = false
            var isClassTypeFound = false
            val leakingStatusReasonBuilder = StringBuilder()
            val notes = mutableListOf<String>()

            for (line in inputLines) {
                when {
                    line.startsWith(firstLinePrefix) -> {
                        val formattedLine = line.removePrefix(firstLinePrefix).trim()

                        isClassTypeFound = isClassTypeExist(formattedLine)
                        if(isClassTypeFound) {
                            val parts = formattedLine.split(" ")
                            className = parts.getOrNull(0)
                            type = parts.lastOrNull()?.let { LeakTraceNodeType.valueOf(it.uppercase()) }
                        }
                        else {
                            className = formattedLine
                        }
                    }

                    !isClassTypeFound -> {
                        val formattedLine = line.removePrefix(multiLinePrefix).trim()
                        isClassTypeFound = isClassTypeExist(formattedLine)
                        if (isClassTypeFound) {
                            val parts = formattedLine.split(" ")
                            className += if (parts.size > 1) parts[0] else ""
                            type =
                                parts.lastOrNull()
                                    ?.let { LeakTraceNodeType.valueOf(it.uppercase()) }
                        } else {
                            className += formattedLine
                        }
                    }

                    leakingStatusRegex.matches(line.removePrefix(additionalLinesPrefix)) -> {
                        val matchResult =
                            leakingStatusRegex.find(line.removePrefix(additionalLinesPrefix))!!
                        leakingStatus = LeakingStatus.fromString(matchResult.groupValues[1])
                        leakingStatusReason = matchResult.groupValues[2]
                    }

                    leakingStatusMultiLineRegex.matches(line.removePrefix(additionalLinesPrefix)) -> {
                        val matchResult =
                            leakingStatusMultiLineRegex.find(line.removePrefix(additionalLinesPrefix))!!
                        leakingStatus = LeakingStatus.fromString(matchResult.groupValues[1])
                        leakingStatusReasonBuilder.append(matchResult.groupValues[2].trim())
                        leakingMultiLine = true
                    }

                    leakingMultiLine -> {
                        leakingStatusReasonBuilder.append(" ")
                            .append(line.removePrefix(additionalLinesPrefix))

                        leakingMultiLine = !line.endsWith(")")
                        if(!leakingMultiLine)
                            leakingStatusReason = leakingStatusReasonBuilder.toString().trimEnd().dropLast(1)
                    }

                    retainingRegex.matches(line.removePrefix(additionalLinesPrefix)) -> {
                        val matchResult =
                            retainingRegex.find(line.removePrefix(additionalLinesPrefix))!!
                        val (sizeString, unit, countString) = matchResult.destructured
                        retainedHeapSize = "$sizeString $unit"
                        retainedObjectCount = countString.toInt()
                    }

                    line.startsWith(additionalLinesPrefix) -> {
                        notes.add(line.removePrefix(additionalLinesPrefix))
                    }
                }
            }

            requireNotNull(type) { "Invalid object type" }
            requireNotNull(className) { "Class name not found" }
            requireNotNull(leakingStatus) { "Leaking status not found" }

            return Node(
                type,
                className,
                leakingStatus,
                leakingStatusReason,
                retainedHeapSize,
                retainedObjectCount,
                notes,
                null
            )
        }

        fun isClassTypeExist(line: String): Boolean {
            if (line.isEmpty())
                return false
            val parts = line.split(" ")

            return parts.last() == LeakTraceNodeType.INSTANCE.name.lowercase()
                    || parts.last() == LeakTraceNodeType.CLASS.name.lowercase()
                    || parts.last() == LeakTraceNodeType.ARRAY.name.lowercase()
        }
    }
}
