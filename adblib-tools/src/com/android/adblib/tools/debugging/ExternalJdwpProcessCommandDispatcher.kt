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
package com.android.adblib.tools.debugging

import com.android.adblib.AdbSession
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.tools.debugging.ExternalJdwpProcessCommandDispatcher.ProcessCommand
import com.android.adblib.tools.debugging.impl.AbstractJdwpProcessDelegateProvider
import com.android.adblib.tools.debugging.processinventory.server.ProcessInventoryServer
import com.android.adblib.tools.debugging.utils.ConcurrentAutoCloseableCollection

/**
 * A component that allows sending [ProcessCommand] to an external source for execution (e.g. [ProcessInventoryServer]) for a given
 * [JdwpProcess]
 */
interface ExternalJdwpProcessCommandDispatcher {

  /** The [JdwpProcess] this dispatcher is attached to */
  val process: JdwpProcess

  /**
   * Starts executing [ProcessCommand] dispatched from the external source this [ExternalJdwpProcessCommandDispatcher] is connected to, e.g.
   * a "process inventory server"
   */
  suspend fun start()

  /**
   * Sends a [ProcessCommand] for execution to the external source this [ExternalJdwpProcessCommandDispatcher] is connected to, e.g. a
   * "process inventory server"
   */
  suspend fun executeCommand(command: ProcessCommand)

  /** Base class of all supported commands */
  sealed class ProcessCommand(val pid: Int) {

    override fun toString(): String {
      return "${this::class.simpleName}(pid=$pid)"
    }

    /** Resume a JDWP process in a "waiting for debugger" state */
    class ResumeJdwpProcess(pid: Int) : ProcessCommand(pid)
  }
}

/**
 * A factory of [ExternalJdwpProcessCommandDispatcher], typically injected into an [AdbSession] with the
 * [AdbSession.addExternalJdwpProcessCommandDispatcherFactory]
 */
interface ExternalJdwpProcessCommandDispatcherFactory : AutoCloseable {

  /**
   * Creates an [ExternalJdwpProcessCommandDispatcher] for the given [process] if appropriate, or returns `null` if this factory does not
   * want to provide one.
   */
  suspend fun create(process: JdwpProcess): ExternalJdwpProcessCommandDispatcher?
}

/** The [CoroutineScopeCache.Key] for the list of [ExternalJdwpProcessCommandDispatcherFactory] */
private val externalJdwpProcessCommandDispatcherFactoryListKey =
  CoroutineScopeCache.Key<ConcurrentAutoCloseableCollection<ExternalJdwpProcessCommandDispatcherFactory>>(
    "externalJdwpProcessCommandDispatcherFactoryListKey"
  )

/** The list of [ExternalJdwpProcessCommandDispatcherFactory] associated to this [AdbSession] */
internal val AdbSession.externalJdwpProcessCommandDispatcherFactoryList:
  ConcurrentAutoCloseableCollection<ExternalJdwpProcessCommandDispatcherFactory>
  get() = this.cache.getOrPut(externalJdwpProcessCommandDispatcherFactoryListKey) { ConcurrentAutoCloseableCollection() }

/** Adds a [ExternalJdwpProcessCommandDispatcherFactory] to this [AdbSession] */
fun AdbSession.addExternalJdwpProcessCommandDispatcherFactory(factory: ExternalJdwpProcessCommandDispatcherFactory) {
  externalJdwpProcessCommandDispatcherFactoryList.add(factory)
}

/** The [CoroutineScopeCache.Key] for the list of [ExternalJdwpProcessCommandDispatcher] */
private val externalJdwpProcessCommandDispatcherListKey =
  CoroutineScopeCache.Key<List<ExternalJdwpProcessCommandDispatcher>>("externalJdwpProcessCommandDispatcherListKey")

/** The list of [ExternalJdwpProcessCommandDispatcher] associated to this [JdwpProcess] */
internal suspend fun JdwpProcess.externalJdwpProcessCommandDispatcherList(): List<ExternalJdwpProcessCommandDispatcher> {
  val process = this
  return process.cache.getOrPutSuspending(externalJdwpProcessCommandDispatcherListKey) {
    if (process is AbstractJdwpProcessDelegateProvider) {
      process.abstractJdwpProcess().externalJdwpProcessCommandDispatcherList().map { dispatcher ->
        ExternalJdwpProcessCommandDispatcherDelegate(process, dispatcher)
      }
    } else {
      device.session.externalJdwpProcessCommandDispatcherFactoryList.mapNotNull { factory -> factory.create(process) }
    }
  }
}

private class ExternalJdwpProcessCommandDispatcherDelegate(
  override val process: JdwpProcess,
  private val delegate: ExternalJdwpProcessCommandDispatcher,
) : ExternalJdwpProcessCommandDispatcher by delegate
