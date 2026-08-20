/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.instrumentation

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for b/483853092. Verifies that classes.jar from a library is not locked after a build, allowing for a successful clean.
 */
class AsmTransformApiFileLockTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication(":app") {
      android { namespace = "com.example.myapplication" }
      dependencies { implementation(project(":lib")) }
      files {
        add(
          "src/main/java/com/example/myapplication/MainActivity.java",
          """
          package com.example.myapplication;
          import com.example.lib.SomeClass;
          public class MainActivity {
              SomeClass someClass = new SomeClass();
          }
          """
            .trimIndent(),
        )
      }
    }

    androidLibrary(":lib") {
      android { namespace = "com.example.lib" }
      files { add("src/main/java/com/example/lib/SomeClass.java", "package com.example.lib; public class SomeClass {}") }
    }
  }

  @Test
  fun testCleanAfterAssembleWithInstrumentation() {
    val appBuildFile = rule.build.directory.resolve("app/build.gradle").toFile()
    appBuildFile.appendText(
      "\n" +
        """
        import com.android.build.api.instrumentation.*
        import org.objectweb.asm.ClassVisitor
        import org.objectweb.asm.util.TraceClassVisitor

        abstract class MyVisitorFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {
            @Override
            ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
                return new TraceClassVisitor(nextClassVisitor, new java.io.PrintWriter(System.out))
            }
            @Override
            boolean isInstrumentable(ClassData classData) {
                return true
            }
        }

        androidComponents {
            onVariants(selector().all(), { variant ->
                variant.instrumentation.transformClassesWith(
                    MyVisitorFactory.class,
                    InstrumentationScope.ALL) {}
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES)
            })
        }
        """
          .trimIndent() +
        "\n"
    )

    rule.build.executor.run(":app:assembleDebug")
    rule.build.executor.run("clean")
  }
}
