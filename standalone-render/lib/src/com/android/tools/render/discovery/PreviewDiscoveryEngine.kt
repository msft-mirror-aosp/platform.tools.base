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

package com.android.tools.render.discovery

import com.android.tools.render.StandaloneRenderModelModule
import com.android.tools.render.model.DiscoveredPreview
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** Discovers Compose preview annotation parameters for a method from compiled bytecode. */
class PreviewDiscoveryEngine(private val module: StandaloneRenderModelModule) {

  private val classLoader: ClassLoader
    get() = module.environment.moduleClassLoaderManager.getShared(this::class.java.classLoader).classLoader

  companion object {
    private const val PREVIEW_DESC = "Landroidx/compose/ui/tooling/preview/Preview;"
  }

  /** Discovers the @Preview annotation parameters for the given [methodFQN] and [previewId]. */
  fun discover(methodFQN: String, previewId: String): DiscoveredPreview? {
    val className = methodFQN.substringBeforeLast(".")
    val methodName = methodFQN.substringAfterLast(".")

    val classBytes = findClassBytes(className) ?: return null

    var discoveredPreviewParams: MutableMap<String, String>? = null

    val classReader = ClassReader(classBytes)
    classReader.accept(
      object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
          access: Int,
          name: String,
          descriptor: String,
          signature: String?,
          exceptions: Array<out String>?,
        ): MethodVisitor? {
          if (name != methodName) return null
          if ((access and Opcodes.ACC_SYNTHETIC) != 0) return null

          return object : MethodVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
              if (desc == PREVIEW_DESC) {
                val previewParams = mutableMapOf<String, String>()
                discoveredPreviewParams = previewParams
                return object : AnnotationVisitor(Opcodes.ASM9) {
                  override fun visit(name: String?, value: Any?) {
                    if (name != null && value != null) {
                      previewParams[name] =
                        when (value) {
                          is Type -> value.className
                          else -> value.toString()
                        }
                    }
                  }

                  override fun visitEnum(name: String?, descriptor: String?, value: String?) {
                    if (name != null && value != null) {
                      previewParams[name] = value
                    }
                  }
                }
              }
              return null
            }
          }
        }
      },
      ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )

    val params = discoveredPreviewParams ?: return null
    return DiscoveredPreview(methodFQN = methodFQN, previewId = previewId, previewParams = params)
  }

  /**
   * Retrieves raw .class bytes from the [classLoader] for [className].
   *
   * Tries direct package path first (e.g. `com/example/MyClass.class`), then falls back to replacing dots with `$` from right to left to
   * support nested and companion classes (e.g. `MyClass$Companion.class`).
   */
  private fun findClassBytes(className: String): ByteArray? {
    // 1. First attempt: standard package path where all dots are package separators (com/example/MyClass.class)
    val directResource = "${className.replace('.', '/')}.class"
    classLoader.getResourceAsStream(directResource)?.use {
      return it.readBytes()
    }

    // 2. Fallback: handle inner/nested classes and companion objects by replacing dots with '$' from right to left
    // (e.g. "com.example.MyClass.Inner" -> "com/example/MyClass$Inner.class")
    var candidate = className
    while (candidate.contains('.')) {
      val idx = candidate.lastIndexOf('.')
      candidate = candidate.substring(0, idx) + "$" + candidate.substring(idx + 1)
      val resource = "${candidate.replace('.', '/')}.class"
      classLoader.getResourceAsStream(resource)?.use {
        return it.readBytes()
      }
    }
    return null
  }
}
