/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.android.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.backup

/**
 * A lightweight, self-contained logger implementation for host-driven testing to run flawlessly on standard JVM classpaths without IDE
 * platform library dependencies.
 */
class Logger private constructor(private val name: String) {

  companion object {
    @JvmStatic fun getInstance(clazz: Class<*>): Logger = Logger(clazz.name)
  }

  fun info(msg: String) {
    println("[INFO] $msg")
  }

  fun warning(msg: String, throwable: Throwable? = null) {
    System.err.println("[WARNING] $msg")
    throwable?.printStackTrace()
  }

  fun error(msg: String, throwable: Throwable? = null) {
    System.err.println("[ERROR] $msg")
    throwable?.printStackTrace()
  }
}
