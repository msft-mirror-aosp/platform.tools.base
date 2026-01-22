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
package com.android.tools.leakcanarylib.data

import com.android.tools.leakcanarylib.parser.NodeParser

/**
 * This class represents node in a memory leak trace analysis.
 * Each node holds information about a specific object, its type, its role in the leak, and related memory statistics.
*/
data class Node(
    var nodeType: LeakTraceNodeType,
    var className: String,
    var leakingStatus: LeakingStatus,
    var leakingStatusReason: String,
    var retainedHeapSize: String?,
    var retainedObjectCount: Int?,
    var notes: List<String>,
    var referencingField: ReferencingField?
) {

    companion object {
        const val ZERO_WIDTH_SPACE = '\u200b'
        fun fromString(input: String): Node {
            return if (input.startsWith("╰→ ")) {
                NodeParser.parseLeakNode(
                    inputLines = input.lines(),
                    firstLinePrefix = "╰→ ",
                    multiLinePrefix = "${ZERO_WIDTH_SPACE}  ",
                    additionalLinesPrefix = "${ZERO_WIDTH_SPACE}     "
                )
            } else {
                NodeParser.parse(
                    inputLines = input.lines(),
                    firstLinePrefix = "├─ ",
                    multiLinePrefix = "│  ",
                    additionalLinesPrefix = "│    ",
                    referenceLinePrefix = "│    ↓",
                )
            }
        }
    }

    /**
     * Returns a string representation of this node.
     */
    override fun toString(): String {
        return this.toString("", "", true)
    }

    internal fun toString(
        firstLinePrefix: String,
        additionalLinesPrefix: String,
        showLeakingStatus: Boolean,
        typeName: String = this.nodeType.name.lowercase()
    ): String {
        val leakStatus = when (leakingStatus) {
            LeakingStatus.UNKNOWN -> "UNKNOWN"
            LeakingStatus.NO -> "NO ($leakingStatusReason)"
            LeakingStatus.YES -> "YES ($leakingStatusReason)"
        }

        val result = buildString {
            append("$firstLinePrefix$className $typeName")
            if (showLeakingStatus) {
                append("\n${additionalLinesPrefix}Leaking: $leakStatus")
            }
            if (retainedHeapSize != null) {
                append("\n${additionalLinesPrefix}Retaining $retainedHeapSize in $retainedObjectCount objects")
            }
            for (label in notes) {
                append("\n${additionalLinesPrefix}$label")
            }

            if (referencingField != null) {
                append(referencingField.toString())
            }
        }
        return result
    }
}
