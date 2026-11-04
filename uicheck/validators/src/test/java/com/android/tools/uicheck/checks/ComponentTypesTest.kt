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

package com.android.tools.uicheck.checks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentTypesTest {

  /** Verifies Compose and View button, text field, and container constant sets and non-wide button exclusions. */
  @Test
  fun verifiesComponentTypeConstants() {
    assertTrue("Button" in COMPOSE_BUTTON_NAMES)
    assertTrue("MaterialButton" in VIEW_BUTTON_NAMES)
    assertTrue("ExtendedFloatingActionButton" in VIEW_BUTTON_NAMES)
    assertTrue("IconButton" in COMPOSE_NON_WIDE_BUTTON_NAMES)
    assertTrue("ImageButton" in IMAGE_VIEW_BUTTON_NAMES)
    assertTrue("TextField" in COMPOSE_TEXT_FIELD_NAMES)
    assertTrue("SecureTextField" in COMPOSE_TEXT_FIELD_NAMES)
    assertTrue("BasicSecureTextField" in COMPOSE_TEXT_FIELD_NAMES)
    assertTrue("TextInputEditText" in VIEW_TEXT_FIELD_NAMES)
    assertTrue("Box" in COMPOSE_CONTAINER_NAMES)
    assertFalse("Button" in COMPOSE_NON_WIDE_BUTTON_NAMES)
  }
}
