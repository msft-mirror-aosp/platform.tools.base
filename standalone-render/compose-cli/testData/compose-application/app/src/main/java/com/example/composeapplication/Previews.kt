package com.example.composeapplication

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.example.composeapplication.ui.theme.ComposeApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Preview
@Composable
fun PreviewSmall() {
    ComposeApplicationTheme {
        Button(onClick = {}) {
            Text("Ok")
        }
    }
}

@Preview
@Composable
fun PreviewLarge() {
    ComposeApplicationTheme {
        Scaffold(
            topBar = {
                Text(text = "Title")
            },
            bottomBar = {
                Row {
                    Button(onClick = {}) {
                        Text(text = "Ok")
                    }
                    Button(onClick = {}) {
                        Text(text = "Cancel")
                    }
                }
            }
        ) { padding ->
            LazyColumn(modifier = Modifier.padding(padding)) {
                (0..10).forEach {  i ->
                    item {
                        Row {
                            Image(painter = painterResource(id = R.drawable.ic_launcher_foreground), contentDescription = "Android face")
                            Text(text = "Row $i")
                            Checkbox(checked = i % 2 == 0, onCheckedChange = {})
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun PreviewCoroutines() {
    val scope = CoroutineScope(Dispatchers.Main)
    scope.launch {  }
    ComposeApplicationTheme {
        Row {
            Button(onClick = {}) {
                Text(text = "Ok")
            }
        }
    }
}

private var infiniteAnimationActive = false
private var infiniteAnimationDisposed = true

/**
 * Only renders like [PreviewSmall] if the [LaunchedEffect] has run before the image is taken.
 *
 * Note: `ScreenshotRenderTest` renders this preview after [PreviewInfiniteAnimation], so clearing [infiniteAnimationActive] during
 * composition causes any leaked coroutine or undisposed effect from [PreviewInfiniteAnimation] to fail if it runs again.
 */
@Preview
@Composable
fun PreviewLaunchedEffect() {
    infiniteAnimationActive = false
    var text by remember { mutableStateOf("LaunchedEffect did not run") }
    LaunchedEffect(Unit) {
        check(infiniteAnimationDisposed) { "Previous preview composition was not disposed" }
        text = "Ok"
    }
    ComposeApplicationTheme {
        Button(onClick = {}) {
            Text(text)
        }
    }
}

/**
 * Only renders like [PreviewSmall] if both callback rounds and the intermediate render pass run: Round 1's [LaunchedEffect] attaches a
 * [layout] modifier, Round 1's render pass measures the layout and triggers that modifier, and Round 2's [LaunchedEffect] updates the
 * button text to `"Ok"`.
 */
@Preview
@Composable
fun PreviewTwoRoundLaunchedEffect() {
    var readyForLayout by remember { mutableStateOf(false) }
    var layoutMeasured by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("Two-round LaunchedEffect did not run") }
    LaunchedEffect(Unit) { readyForLayout = true }
    LaunchedEffect(layoutMeasured) {
        if (layoutMeasured) {
            text = "Ok"
        }
    }
    ComposeApplicationTheme {
        Button(
            onClick = {},
            modifier =
                if (readyForLayout) {
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layoutMeasured = true
                        layout(placeable.width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
                } else {
                    Modifier
                },
        ) {
            Text(text)
        }
    }
}

/**
 * Has an infinite animation, a [LaunchedEffect], and an unscoped [AndroidUiDispatcher.Main] coroutine that throw if they are still running
 * once a subsequent preview renders, verifying that the composition is disposed and `AndroidUiDispatcher.Main` queues are cleared when the
 * preview finishes rather than leaking into later previews.
 */
@Preview
@Composable
fun PreviewInfiniteAnimation() {
    DisposableEffect(Unit) {
        infiniteAnimationActive = true
        infiniteAnimationDisposed = false
        onDispose {
            infiniteAnimationDisposed = true
        }
    }
    remember {
        CoroutineScope(AndroidUiDispatcher.Main).launch {
            while (true) {
                withFrameNanos {}
                check(infiniteAnimationActive) { "Unscoped coroutine was not cleared when preview finished" }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {}
            check(infiniteAnimationActive) { "LaunchedEffect was not disposed when preview finished" }
        }
    }
    ComposeApplicationTheme {
        CircularProgressIndicator()
    }
}

/** Its [LaunchedEffect] throws, which must make the screenshot fail. */
@Preview
@Composable
fun PreviewLaunchedEffectThrows() {
    LaunchedEffect(Unit) { throw IllegalStateException("Thrown from LaunchedEffect") }
    ComposeApplicationTheme {
        Button(onClick = {}) {
            Text("Ok")
        }
    }
}

/**
 * Its [LaunchedEffect] waits until the last callback execution of the first round and then keeps the main thread busy, ignoring
 * interruptions, for longer than the renderer allows the callbacks of a preview to run, which must make the screenshot fail without
 * affecting the previews rendered after it.
 */
@Preview
@Composable
fun PreviewLaunchedEffectBusy() {
    LaunchedEffect(Unit) {
        // Advance through the first 9 of StaticPreviewCallbackRunner.CALLBACK_EXECUTIONS_PER_ROUND (10) steps so the busy wait runs on
        // the 10th and final callback step of the first round.
        repeat(9) { withFrameNanos {} }
        val timeoutMs = java.lang.Long.getLong("layoutlib.callbacks.timeout.ms", 10_000L)
        val end = System.nanoTime() + (timeoutMs + 1_500L) * 1_000_000L
        while (System.nanoTime() < end) {
            // Busy wait.
        }
        repeat(2) { withFrameNanos {} }
        throw IllegalStateException("LaunchedEffect of timed-out preview was not disposed")
    }
    ComposeApplicationTheme {
        Button(onClick = {}) {
            Text("Ok")
        }
    }
}
