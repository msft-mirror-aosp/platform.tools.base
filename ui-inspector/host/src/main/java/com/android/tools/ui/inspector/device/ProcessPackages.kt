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

package com.android.tools.ui.inspector.device

import com.android.adblib.AdbDeviceServices
import com.android.adblib.DeviceSelector

/** Shell command whose output states, for every running process, which packages it belongs to. */
internal const val PROCESS_PACKAGES_SHELL_COMMAND = "dumpsys activity processes"

/**
 * A running process and the packages it belongs to, according to the activity manager.
 *
 * [packageNames] lists the packages whose Android components run in this process. A package whose code or resources the process merely
 * loaded is not in the list; the activity manager tracks those separately, as dependencies.
 */
internal data class ProcessPackages(val pid: String, val packageNames: Set<String>)

/**
 * Queries [device] for which packages each running process belongs to, one [ProcessPackages] per process the activity manager knows.
 *
 * A process UID normally identifies the owning app. Under legacy sharedUserId that stops working: sibling packages run their processes
 * under one shared UID. The activity manager still knows which package each process belongs to, and this query reads that answer from its
 * process dump.
 */
internal suspend fun AdbDeviceServices.getPackagesByPid(device: DeviceSelector): List<ProcessPackages> =
  parseProcessPackages(shellAsTextOrThrow(device, PROCESS_PACKAGES_SHELL_COMMAND).stdout)

/** Matches a ProcessRecord block header, `*APP* UID <uid> ProcessRecord{<id> <pid>:<processName>/<user>}`, capturing the pid. */
private val PROCESS_RECORD_HEADER_REGEX = Regex("""^\s*\*(?:APP|PERS)\*\s+UID\s+\d+\s+ProcessRecord\{\S+\s+(\d+):[^/\s]+/\S+}\s*$""")

/** Matches the `packageList={a, b}` line of a ProcessRecord block, capturing the comma-separated package names. */
private val PACKAGE_LIST_REGEX = Regex("""^\s*packageList=\{([^}]*)}\s*$""")

/**
 * Parses `dumpsys activity processes` [output] into one [ProcessPackages] per process.
 *
 * The dump reports a process as a ProcessRecord block: a header line carrying the pid, then a `packageList` line carrying the packages. The
 * parser pairs each header with the first `packageList` line after it. It skips blocks whose header or package list is malformed, and it
 * keeps only the first block for each pid. The result therefore contains exactly the processes the dump describes unambiguously.
 */
internal fun parseProcessPackages(output: String): List<ProcessPackages> {
  val processes = LinkedHashMap<String, ProcessPackages>()
  var pendingPid: String? = null
  output.lineSequence().forEach { line ->
    val header = PROCESS_RECORD_HEADER_REGEX.matchEntire(line)
    if (header != null) {
      pendingPid = header.groupValues[1]
      return@forEach
    }
    // Any other block header ends the pending block: a packageList line may only bind to the header it belongs to.
    if (line.trimStart().startsWith("*")) {
      pendingPid = null
      return@forEach
    }
    val packageList = PACKAGE_LIST_REGEX.matchEntire(line) ?: return@forEach
    val pid = pendingPid ?: return@forEach
    pendingPid = null
    val packageNames = packageList.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (packageNames.isNotEmpty()) {
      processes.putIfAbsent(pid, ProcessPackages(pid = pid, packageNames = packageNames))
    }
  }
  return processes.values.toList()
}
