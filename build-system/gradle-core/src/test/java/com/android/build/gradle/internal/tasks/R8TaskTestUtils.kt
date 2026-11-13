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

package com.android.build.gradle.internal.tasks

import com.android.build.gradle.internal.dexing.createR8Tools
import com.android.build.gradle.internal.dexing.withR8ClassLoader
import com.android.build.gradle.internal.transforms.NoOpMessageReceiver
import com.android.builder.dexing.ClassFileInputs
import java.io.File
import java.nio.file.Path

internal val testR8Jar: File by lazy { File(Class.forName("com.android.tools.r8.R8").protectionDomain.codeSource.location.toURI()) }

internal inline fun <T> withTestR8ClassLoader(block: (ClassLoader) -> T): T = withR8ClassLoader(setOf(testR8Jar), block = block)

internal fun generateTestDexArchive(classesDirOrJar: File, dexArchivePath: File, globalSyntheticsOutput: Path) {
  withTestR8ClassLoader { r8ClassLoader ->
    val r8Tools = createR8Tools(r8ClassLoader)
    r8Tools.createClassFileProviderFactory(emptyList()).use { classpath ->
      val builder =
        r8Tools.createD8DexBuilder(
          minSdkVersion = 1,
          debuggable = true,
          dexPerClass = true,
          withDesugaring = false,
          desugarBootclasspath = classpath,
          desugarClasspath = classpath,
          coreLibDesugarConfig = null,
          enableApiModeling = true,
          messageReceiver = NoOpMessageReceiver(),
          useNoOpGlobalSyntheticsConsumer = false,
        )
      ClassFileInputs.fromPath(classesDirOrJar.toPath()).use { input ->
        input.entries { _, _ -> true }.use { entries -> builder.convert(entries, dexArchivePath.toPath(), globalSyntheticsOutput) }
      }
    }
  }
}
