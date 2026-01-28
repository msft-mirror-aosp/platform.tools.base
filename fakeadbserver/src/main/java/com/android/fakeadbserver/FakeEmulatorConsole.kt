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
package com.android.fakeadbserver

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A fake emulator console that runs a server on an available port to simulate `adb emu` commands.
 *
 * This class is [AutoCloseable] and must be closed to release network and thread resources.
 */
class FakeEmulatorConsole(private val avdName: String, private val avdPath: String) : AutoCloseable {

  private val serverSocket: ServerSocketChannel = ServerSocketChannel.open()
  private var serverSocketLocalAddress: InetSocketAddress? = null
  private val clientSockets = CopyOnWriteArrayList<SocketChannel>()
  private val executor: ExecutorService = Executors.newCachedThreadPool()
  private var runEmulatorTask: Future<*>? = null
  private val isShutdown = AtomicBoolean(false)

  fun start() {
    assert(
      runEmulatorTask == null // Do not reuse the emulator
    )
    serverSocket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    serverSocketLocalAddress = serverSocket.localAddress as InetSocketAddress

    runEmulatorTask = executor.submit { run() }
  }

  val port: Int
    get() = serverSocketLocalAddress!!.port

  private fun run() {
    while (!isShutdown.get()) {
      try {
        val socket = serverSocket.accept()
        clientSockets.add(socket)
        executor.submit { handleConnection(socket) }
      } catch (_: IOException) {
        // Expected during shutdown
      }
    }
  }

  private fun handleConnection(socket: SocketChannel) {
    try {
      val input = BufferedReader(InputStreamReader(socket.socket().getInputStream()))
      val output = PrintWriter(socket.socket().getOutputStream(), true) // autoFlush=true

      output.write("OK\r\n") // Initial greeting
      output.flush()
      while (true) {
        val line = input.readLine() ?: break
        when (line) {
          "avd name" -> {
            output.write("$avdName\r\nOK\r\n")
            output.flush()
          }
          "avd path" -> {
            output.write("$avdPath\r\nOK\r\n")
            output.flush()
          }
          "ping" -> {
            output.write("I am alive!\r\nOK\r\n")
            output.flush()
          }
          "help" -> {
            output.write("OK\r\n")
            output.flush()
          }
          else -> {
            output.write("KO: unknown command\r\n")
            output.flush()
          }
        }
      }
    } catch (_: IOException) {
      // Ignore socket closing exceptions, which are expected during shutdown
    } finally {
      clientSockets.remove(socket)
      runCatching { socket.close() }
    }
  }

  override fun close() {
    if (isShutdown.compareAndSet(false, true)) {
      runEmulatorTask?.cancel(true)
      runCatching { serverSocket.close() }
      clientSockets.forEach { runCatching { it.close() } }
      clientSockets.clear()
      executor.shutdown()
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow()
      }
    }
  }
}
