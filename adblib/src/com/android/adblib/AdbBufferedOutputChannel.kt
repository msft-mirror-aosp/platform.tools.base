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
package com.android.adblib

import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException

/**
 * A [AdbOutputChannel] that requires calling [AutoShutdown.shutdown] to prevent data loss, typically to allow flushing any pending writes
 * to the underlying resource. Specifically, calling [close] on an [AdbBufferedOutputChannel] does **not** guarantee buffered data is
 * written to the underlying resource.
 */
interface AdbBufferedOutputChannel : AdbOutputChannel, AutoShutdown {

  /**
   * Writes all currently buffered data to the underlying resource
   * * Throws [ClosedChannelException] if [close] or [shutdown] were previously called
   * * Throws [AsynchronousCloseException] if [close] is called __while this function is suspended__
   * * Throws [java.io.IOException] if an I/O occurs writing to the underlying resource
   */
  suspend fun flush()

  /**
   * Writes all currently buffered data to the underlying resource (like [flush]), but also indicate that no more [write] calls are allowed,
   * i.e. subsequent calls to [write], [flush] or [shutdown] will throw [java.nio.channels.ClosedChannelException].
   * * Throws [ClosedChannelException] if [close] or [shutdown] were previously called
   * * Throws [AsynchronousCloseException] if [close] is called __while this function is suspended__
   * * Throws [java.io.IOException] if an I/O occurs writing to the underlying resource
   */
  override suspend fun shutdown()
}
