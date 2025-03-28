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

import com.android.adblib.AdbChannelFactory
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.tools.debugging.impl.JdwpProxySocketServerImpl
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress

/**
 * Maintains a JDWP socket proxy for the given [process] on a given device.
 *
 * The proxy creates a [server socket][AdbChannelFactory.createServerSocket] on
 * `localhost` (see [JdwpProxySocketServerStatus.socketAddress]), then it accepts JDWP
 * connections from external Java debuggers (e.g. IntelliJ or Android Studio) on that server
 * socket.
 *
 * Each time a new socket connection is opened by an external debugger, the proxy opens
 * a JDWP session to the process on the device (see [JdwpProcess.withJdwpSession]) and forwards
 * (both ways) JDWP protocol packets between the external debugger and the process on
 * the device.
 *
 * The proxy is active as soon as [proxyStatusFlow] is collected, and until [JdwpProcess.scope]
 * is cancelled.
 */
interface JdwpProxySocketServer {

    /**
     * The JDWP process this proxy applies to
     */
    val process: JdwpProcess

    /**
     * The [StateFlow] of [JdwpProxySocketServerStatus], corresponding to the state of the JDWP proxy
     * and socket between an external debugger and the Android Process.
     *
     * @see JdwpProxySocketServerStatus
     */
    val proxyStatusFlow: StateFlow<JdwpProxySocketServerStatus>
}

/**
 * The current value of [JdwpProxySocketServer.proxyStatusFlow]
 */
val JdwpProxySocketServer.proxyStatus: JdwpProxySocketServerStatus
    get() = proxyStatusFlow.value

/**
 * Status of JDWP Session proxy external Java debuggers can use to connect to a
 * [JdwpProcess].
 *
 * @see JdwpProcess.jdwpProxySocketServer
 */
data class JdwpProxySocketServerStatus(
    /**
     * The process ID
     */
    val pid: Int,

    /**
     * The [InetSocketAddress] (typically on `localhost`) a Java debugger can use to open a
     * JDWP debugging session with the Android process. If the value is `null`, the debugger
     * connection is not ready yet.
     *
     * @see JdwpProxySocketServer
     */
    val socketAddress: InetSocketAddress? = null,

    /**
     * `true` if there is an active JDWP debugging session on [socketAddress].
     *
     * @see JdwpProxySocketServer
     */
    val isExternalDebuggerAttached: Boolean = false,
)

private val jdwpProxySocketServerKey =
    CoroutineScopeCache.Key<JdwpProxySocketServer>("${JdwpProxySocketServer::class.simpleName}")

/**
 * Returns the [JdwpProxySocketServer] for this [JdwpProcess]
 */
val JdwpProcess.jdwpProxySocketServer: JdwpProxySocketServer
    get() {
        return this.cache.getOrPut(jdwpProxySocketServerKey) {
            // Return the default implementation unless the process provides
            // a custom one. In the case of JdwpProcessDelegate, for example,
            // we want to re-use the same proxy as the delegate process, to avoid
            // creating additional (and redundant) socket servers.
            if (this is CustomJdwpProxySocketServerProvider) {
                createProxy()
            } else {
                JdwpProxySocketServerImpl(this)
            }
        }
    }

/**
 * Interface a [JdwpProcess] can implement to return a custom [JdwpProxySocketServer]
 */
internal interface CustomJdwpProxySocketServerProvider {
    /**
     * Creates an instance of [JdwpProxySocketServer] for this process. This function
     * is internal only, as it is an implementation detail of custom [JdwpProcess]
     * implementations.
     */
    fun createProxy(): JdwpProxySocketServer
}
