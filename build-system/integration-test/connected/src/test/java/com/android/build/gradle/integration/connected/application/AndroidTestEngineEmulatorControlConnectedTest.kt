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

package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.integration.utp.applyAndroidTestConfiguration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val GRPC_PORT = "8554"

@RunWith(Parameterized::class)
class AndroidTestEngineEmulatorControlConnectedTest(val runWithBuiltInPlatform: Boolean) {

  @Rule @JvmField val EMULATOR = getEmulator("-grpc", GRPC_PORT, "-grpc-use-token")

  @get:Rule
  val rule =
    GradleRule.configure().from {
      applyAndroidTestConfiguration(runWithBuiltInPlatform = runWithBuiltInPlatform)
      androidApplication {
        android { testOptions.emulatorControl.enable = true }
        files {
          remove("src/androidTest/java/com/example/android/kotlin/InstrumentedTest.kt")
          add(
            "src/androidTest/java/com/example/android/kotlin/InstrumentedTest.kt",
            """
            package com.example.android.kotlin

            import androidx.test.ext.junit.runners.AndroidJUnit4
            import androidx.test.platform.app.InstrumentationRegistry
            import org.junit.Assert.assertEquals
            import org.junit.Assert.assertNotNull
            import org.junit.Assert.assertTrue
            import org.junit.Test
            import org.junit.runner.RunWith

            @RunWith(AndroidJUnit4::class)
            class ExampleInstrumentedTest {
                @Test
                fun testEmulatorControlArgsInjected() {
                    val args = InstrumentationRegistry.getArguments()
                    assertEquals("$GRPC_PORT", args.getString("grpc.port"))
                    val token = args.getString("grpc.token")
                    assertNotNull("grpc.token should not be null", token)
                    assertTrue("grpc.token should not be empty", token!!.isNotEmpty())
                }
            }
            """
              .trimIndent(),
          )
        }
      }
    }

  @Test
  fun runTestAndVerifyEmulatorControlInjected() {
    val executor = rule.build.executor
    executor.run(":app:connectedAndroidTest")
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
