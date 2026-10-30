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

package com.example.composeapplication

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A collapsed bar that expands, with a legend shown by an AnimatedVisibility child that expands vertically (like JetLagged's SleepBar): the
 * size animation of the legend depends on the size of content that the static render never composes.
 */
@Preview(widthDp = 200)
@Composable
fun PreviewExpandVertically() {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val transition = updateTransition(targetState = expanded, label = "expanded")
  val height by
    transition.animateDp(label = "height", transitionSpec = { spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow) }) {
      if (it) 100.dp else 24.dp
    }
  Column(Modifier.clickable { expanded = !expanded }) {
    Spacer(Modifier.fillMaxWidth().height(height).background(Color.Blue))
    transition.AnimatedVisibility(
      visible = { it },
      enter = fadeIn(tween(500)) + expandVertically(tween(500)),
      exit = fadeOut(tween(500)) + shrinkVertically(tween(500)),
    ) {
      Legend()
    }
  }
}

@Composable
private fun Legend() {
  Row(Modifier.padding(8.dp)) {
    Text("Awake")
    Text("REM")
    Text("Deep")
  }
}

/**
 * A parent `Transition` (initially `false`) that has no animations of its own and only drives a child `transition.AnimatedVisibility`:
 * requires `setClockTime(0L)` after `updateFromAndToStates` so snapshot notifications flush before composition/measurement.
 */
@Preview(widthDp = 200)
@Composable
fun PreviewTransitionAnimatedVisibility() {
  val transition = updateTransition(targetState = false, label = "visibilityOnly")
  Column {
    transition.AnimatedVisibility(
      visible = { it },
      enter = fadeIn(tween(300)) + expandVertically(tween(300)),
      exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
    ) {
      Legend()
    }
  }
}

/**
 * A top-level `AnimatedVisibility(visible = false)` stepped without explicit `fromState`/`toState`: requires `setClockTime(0L)` so the
 * `Enter` state set during initial tracking is flushed via `Snapshot.sendApplyNotifications()` before child transition measurement.
 */
@Preview(widthDp = 200)
@Composable
fun PreviewHiddenAnimatedVisibility() {
  Column {
    AnimatedVisibility(
      visible = false,
      label = "topHidden",
      enter = fadeIn(tween(400)) + expandVertically(tween(400)),
      exit = fadeOut(tween(400)) + shrinkVertically(tween(400)),
    ) {
      Legend()
    }
  }
}

/**
 * A bar whose height peaks at 50% of its animation: `keyframes` of 400 ms going linearly from 24.dp up to 120.dp at 200 ms, then down to
 * 48.dp.
 */
@Preview(widthDp = 200)
@Composable
fun PreviewKeyframesPeak() {
  val transition = updateTransition(targetState = false, label = "peak")
  val height by
    transition.animateDp(
      label = "height",
      transitionSpec = {
        keyframes {
          durationMillis = 400
          120.dp at 200
        }
      },
    ) {
      if (it) 48.dp else 24.dp
    }
  Spacer(Modifier.fillMaxWidth().height(height).background(Color.Blue))
}
