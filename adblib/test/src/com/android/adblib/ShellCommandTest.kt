/*
 * Copyright (C) 2021 The Android Open Source Project
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

import org.junit.Assert
import org.junit.Test

class ShellCommandTest {

  @Test
  fun test_escapeDeviceFileName_doesNothingForNormalCharacters() {
    // Act
    val result = ShellCommand.escapeDeviceFileName("foobar")

    // Assert
    Assert.assertEquals("foobar", result)
  }

  @Test
  fun test_escapeDeviceFileName_escapesSpecialCharacters() {
    // Act
    val result = ShellCommand.escapeDeviceFileName("foo bar&")

    // Assert
    Assert.assertEquals("foo\\ bar\\&", result)
  }

  @Test
  fun test_mapDevicePathSegments_preservesEmpty() {
    // Act
    val result = ShellCommand.mapDevicePathSegments("") { it }

    // Assert
    Assert.assertEquals("", result)
  }

  @Test
  fun test_mapDevicePathSegments_preservesRoot() {
    // Act
    val result = ShellCommand.mapDevicePathSegments("/") { it }

    // Assert
    Assert.assertEquals("/", result)
  }

  @Test
  fun test_mapDevicePathSegments_preservesEmptySegments() {
    // Act
    val result = ShellCommand.mapDevicePathSegments("/a//b") { it }

    // Assert
    Assert.assertEquals("/a//b", result)
  }

  @Test
  fun test_mapDevicePathSegments_supportsEscapingAllSegments() {
    // Act
    val result = ShellCommand.mapDevicePathSegments("/a b//b&c") { ShellCommand.escapeDevicePath(it) }

    // Assert
    Assert.assertEquals("/a\\ b//b\\&c", result)
  }
}
