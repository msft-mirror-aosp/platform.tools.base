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
package com.android.tools.render.drawable

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import com.android.SdkConstants.TOOLS_URI

/**
 * [ImageView] used by [DrawableScreenshot] to preview a drawable in a given state and level, which cannot be expressed with plain
 * `ImageView` XML attributes:
 * - `tools:drawableState`: comma-separated states, for example `"pressed,checked"`. `"disabled"` renders the drawable in its disabled
 *   state.
 * - `tools:drawableLevel`: the level of the drawable, for example for a `<level-list>`, from 0 to 10000.
 */
class DrawablePreviewView(context: Context, attrs: AttributeSet?) : ImageView(context, attrs) {

  /**
   * The values of `tools:drawableState`. Kept out of the view class so that [com.android.tools.render.validation.ParameterValidators] can
   * validate states without loading Android classes.
   */
  object States {
    /** Renders the drawable in its disabled state, by disabling the view. */
    const val DISABLED = "disabled"

    /** The other state names, mapped to their state attribute. */
    val ATTRS: Map<String, Int> =
      mapOf(
        "pressed" to android.R.attr.state_pressed,
        "focused" to android.R.attr.state_focused,
        "selected" to android.R.attr.state_selected,
        "checked" to android.R.attr.state_checked,
        "checkable" to android.R.attr.state_checkable,
        "activated" to android.R.attr.state_activated,
        "hovered" to android.R.attr.state_hovered,
      )
  }

  init {
    val states = attrs?.getAttributeValue(TOOLS_URI, ATTR_DRAWABLE_STATE)
    val level = attrs?.getAttributeValue(TOOLS_URI, ATTR_DRAWABLE_LEVEL)

    val stateAttrs =
      states
        ?.split(',')
        ?.mapNotNull { raw ->
          val name = raw.trim()
          when {
            name.isEmpty() -> null
            name == States.DISABLED -> {
              isEnabled = false
              null
            }
            else -> States.ATTRS[name] ?: throw IllegalArgumentException("Unknown drawable state '$name'")
          }
        }
        ?.toIntArray()
    if (stateAttrs != null && stateAttrs.isNotEmpty()) {
      setImageState(stateAttrs, /* merge= */ true)
    }

    level?.trim()?.takeIf { it.isNotEmpty() }?.let { setImageLevel(it.toInt()) }
  }

  // Layoutlib leaves AttachInfo.mHasWindowFocus false, so selectors starting with
  // android:state_window_focused="false" (such as @android:drawable/btn_default) would otherwise
  // match that item before pressed or focused states.
  override fun hasWindowFocus(): Boolean = true

  companion object {
    const val ATTR_DRAWABLE_STATE = "drawableState"
    const val ATTR_DRAWABLE_LEVEL = "drawableLevel"
  }
}
