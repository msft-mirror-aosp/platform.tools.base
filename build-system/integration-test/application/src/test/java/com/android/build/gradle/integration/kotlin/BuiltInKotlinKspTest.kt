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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.KgpTestLayer
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.testutils.TestUtils
import org.junit.Rule
import org.junit.Test

/** Tests KSP compatibility with built-in Kotlin across KGP test layers. */
class BuiltInKotlinKspTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withGradleOptions {
        // On KGP < 2.3.0-Beta2 (MINIMUM = 2.2.10), KSP 2.3.10 falls back to KspAATask.nonSelfDeps(),
        // which accesses cross-project configurations and is incompatible with project isolation.
        if (KgpTestLayer.fromSystemProperty() == KgpTestLayer.MINIMUM) {
          withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        }
      }
      .from {
        genericProject(":processor") {
          applyPlugin(PluginType.JAVA_LIBRARY)
          dependencies {
            implementation(
              "com.google.devtools.ksp:symbol-processing-api:${TestUtils.KSP_VERSION_FOR_TESTS}"
            )
          }
          files {
            add(
              "src/main/java/com/example/processor/TestSymbolProcessor.java",
              // language=java
              """
              package com.example.processor;

              import com.google.devtools.ksp.processing.CodeGenerator;
              import com.google.devtools.ksp.processing.Dependencies;
              import com.google.devtools.ksp.processing.Resolver;
              import com.google.devtools.ksp.processing.SymbolProcessor;
              import com.google.devtools.ksp.symbol.KSAnnotated;
              import com.google.devtools.ksp.symbol.KSClassDeclaration;
              import com.google.devtools.ksp.symbol.KSFile;
              import java.io.IOException;
              import java.io.OutputStream;
              import java.io.OutputStreamWriter;
              import java.io.Writer;
              import java.nio.charset.StandardCharsets;
              import java.util.Collections;
              import java.util.Iterator;
              import java.util.List;

              public class TestSymbolProcessor implements SymbolProcessor {
                private final CodeGenerator codeGenerator;
                private boolean invoked = false;

                public TestSymbolProcessor(CodeGenerator codeGenerator) {
                  this.codeGenerator = codeGenerator;
                }

                @Override
                public List<KSAnnotated> process(Resolver resolver) {
                  if (invoked) {
                    return Collections.emptyList();
                  }
                  invoked = true;
                  Iterator<KSAnnotated> symbols =
                      resolver
                          .getSymbolsWithAnnotation(
                              "com.example.annotation.TriggerGeneration", false)
                          .iterator();
                  while (symbols.hasNext()) {
                    KSAnnotated symbol = symbols.next();
                    if (symbol instanceof KSClassDeclaration) {
                      KSClassDeclaration classDecl = (KSClassDeclaration) symbol;
                      String pkg = classDecl.getPackageName().asString();
                      String generatedName = classDecl.getSimpleName().asString() + "Generated";
                      KSFile containingFile = classDecl.getContainingFile();
                      Dependencies dependencies =
                          new Dependencies(false, new KSFile[] {containingFile});
                      try (OutputStream out =
                              codeGenerator.createNewFile(dependencies, pkg, generatedName, "kt");
                          Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                        writer.write(
                            "package "
                                + pkg
                                + "\n\nclass "
                                + generatedName
                                + " {\n  fun greet(): String = \"Hello from KSP\"\n}\n");
                      } catch (IOException e) {
                        throw new RuntimeException(e);
                      }
                    }
                  }
                  return Collections.emptyList();
                }
              }
              """
                .trimIndent(),
            )
            add(
              "src/main/java/com/example/processor/TestSymbolProcessorProvider.java",
              // language=java
              """
              package com.example.processor;

              import com.google.devtools.ksp.processing.SymbolProcessor;
              import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
              import com.google.devtools.ksp.processing.SymbolProcessorProvider;

              public class TestSymbolProcessorProvider implements SymbolProcessorProvider {
                @Override
                public SymbolProcessor create(SymbolProcessorEnvironment environment) {
                  return new TestSymbolProcessor(environment.getCodeGenerator());
                }
              }
              """
                .trimIndent(),
            )
            add(
              "src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider",
              "com.example.processor.TestSymbolProcessorProvider",
            )
          }
        }

        androidApplication {
          applyPlugin(PluginType.KSP)
          dependencies { add("ksp", project(":processor")) }
          files {
            add(
              "src/main/kotlin/com/example/annotation/TriggerGeneration.kt",
              // language=kotlin
              """
              package com.example.annotation

              annotation class TriggerGeneration
              """
                .trimIndent(),
            )
            add(
              "src/main/kotlin/com/example/app/Foo.kt",
              // language=kotlin
              """
              package com.example.app

              import com.example.annotation.TriggerGeneration

              @TriggerGeneration
              class Foo {
                fun message(): String = FooGenerated().greet()
              }
              """
                .trimIndent(),
            )
          }
        }
      }

  @Test
  fun testKspWithBuiltInKotlin() {
    val build = rule.build
    // KSP 2.3.10 calls Configuration.setVisible(boolean), which is deprecated in Gradle 9.8.2.
    val executor = build.executor.withFailOnWarning(false)
    // TODO(b/483083119): Remove once KSP switches to lazy kotlinCompileProvider.flatMap { it.libraries }.
    // On KGP 2.5.0-Beta2 (LATEST), DefaultKotlinBasePlugin.apply() no longer initializes
    // VariantImplementationFactories.getProvider(project) during plugin application (unlike
    // KotlinBasePluginWrapper or KGP <= 2.4.20). When KSP 2.3.10 configures kspDebugKotlin
    // (KspAATask.kt:280), it eagerly calls kotlinCompileProvider.get().libraries inside its
    // tasks.register configuration action; if compileDebugKotlin is instantiated there for the
    // first time, GradleCompileTaskProvider.<init> -> VariantImplementationFactories.getProvider
    // calls project.tasks.withType(UsesVariantImplementationFactories).configureEach(...) while
    // Gradle's DefaultMutationGuard is active on project.tasks, throwing IllegalMutationException.
    // Realizing :app:compileDebugKotlin before :app:assembleDebug on LATEST avoids this KSP/KGP
    // interaction until KSP switches to lazy kotlinCompileProvider.flatMap { it.libraries }.
    if (KgpTestLayer.fromSystemProperty() == KgpTestLayer.LATEST) {
      executor.run(":app:compileDebugKotlin", ":app:assembleDebug")
    } else {
      executor.run(":app:assembleDebug")
    }
    build.androidApplication().assertApk(ApkSelector.DEBUG) {
      classes()
        .containsAtLeast(
          "com/example/annotation/TriggerGeneration",
          "com/example/app/Foo",
          "com/example/app/FooGenerated",
        )
    }
  }
}
