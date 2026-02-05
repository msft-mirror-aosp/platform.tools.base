/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.tools.screenshot.renderer

import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.screenshot.PreviewScreenshotTestEngineInput.RendererInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.io.Serializable
import java.net.URLClassLoader

/**
 * A wrapper for the LayoutLib that isolates it within a dedicated class loader.
 *
 * LayoutLib performs critical bytecode transformations on Android framework classes at load time. However, the JUnit test engine may load
 * these same classes prematurely, bypassing the necessary transformations and causing rendering failures.
 *
 * This class mitigates this issue by loading LayoutLib and its dependencies into a separate [URLClassLoader]. This ensures that LayoutLib's
 * custom class loader can operate without interference.
 *
 * Communication with the isolated renderer is handled by serializing and deserializing data objects (like [PreviewScreenshot]) across the
 * class loader boundary.
 */
class Renderer : Closeable {

  private val isolatedClassLoaderForRendering: ClassLoader
  private val rendererInstance: Closeable

  init {
    val fontsPath = RendererInput.fontsPath.absolutePath.ifBlank { null }
    val resourceApkPath = RendererInput.resourceApkPath.absolutePath.ifBlank { null }
    val namespace = RendererInput.namespace
    val classPath = (RendererInput.mainAllClassPath + RendererInput.screenshotAllClassPath)
    val projectClassPath = (RendererInput.mainProjectClassPath + RendererInput.screenshotProjectClassPath)
    val layoutLibClassPath = RendererInput.layoutlibClassPath
    val layoutlibDataDir = RendererInput.layoutlibDataDir

    val platformClassLoader = ClassLoader.getPlatformClassLoader()

    isolatedClassLoaderForRendering = URLClassLoader(layoutLibClassPath.map { it.toURI().toURL() }.toTypedArray(), platformClassLoader)

    val rendererClass = isolatedClassLoaderForRendering.loadClass(com.android.tools.render.Renderer::class.java.name)

    val constructor =
      rendererClass.getConstructor(
        String::class.java, // fontsPath
        String::class.java, // resourceApkPath
        String::class.java, // namespace
        List::class.java, // classPath
        List::class.java, // projectClassPath
        String::class.java, // layoutlibPath
      )

    // Create an instance of the renderer by invoking the constructor.
    rendererInstance =
      constructor.newInstance(
        fontsPath,
        resourceApkPath,
        namespace,
        classPath.map { it.absolutePath },
        projectClassPath.map { it.absolutePath },
        layoutlibDataDir.absolutePath,
      ) as Closeable
  }

  /**
   * Renders a given [PreviewScreenshot] and saves the output to the specified folder.
   *
   * This method orchestrates the rendering process across the class loader boundary. It first serializes the [screenshot] object, transfers
   * it to the isolated class loader, and invokes the underlying renderer. The resulting [PreviewScreenshotResult] is then serialized back
   * into the context of the current class loader before being returned.
   *
   * @param screenshot The preview information to be rendered.
   * @param outputFolderPath The path to the directory where the rendered image will be saved.
   * @return A list of [PreviewScreenshotResult] objects, each detailing the outcome for a rendered preview.
   */
  fun render(screenshot: PreviewScreenshot, outputFolderPath: String): List<PreviewScreenshotResult> {
    // 1. Copy the PreviewScreenshot object to the isolated class loader's context.
    // This is necessary because the rendererInstance exists within that isolated class loader,
    // and it expects types defined within its own context, not the application's class loader.
    val copiedScreenshot = copyObject(screenshot, isolatedClassLoaderForRendering)

    // 2. Use reflection to find the 'render' method on the rendererInstance.
    // We need to load the parameter class type from the isolated class loader to find the
    // correct method signature.
    val previewScreenshotClass = isolatedClassLoaderForRendering.loadClass(PreviewScreenshot::class.java.name)
    val renderMethod = rendererInstance.javaClass.getMethod("render", previewScreenshotClass, String::class.java)

    // 3. Invoke the 'render' method. The result will be a List<PreviewScreenshotResult>
    // but its class definition will be from the isolated class loader.
    val resultFromIsolatedLoader = renderMethod.invoke(rendererInstance, copiedScreenshot, outputFolderPath) as Serializable

    // 4. Copy the result back from the isolated class loader's context to the current
    // class loader's context. This allows us to cast it to the expected return type.
    val resultInCurrentLoader = copyObject(resultFromIsolatedLoader, this.javaClass.classLoader)

    // 5. Cast the copied result to the correct type and return it.
    @Suppress("UNCHECKED_CAST")
    return resultInCurrentLoader as List<PreviewScreenshotResult>
  }

  /**
   * Copies a [Serializable] object from one class loader context to another.
   *
   * This is achieved by serializing the object into a byte array and then deserializing it using a custom [ObjectInputStream] that resolves
   * classes using the [targetClassLoader].
   *
   * @param obj The serializable object instance to copy.
   * @param targetClassLoader The class loader to use for deserializing the new instance.
   * @return A new instance of the object, loaded by the [targetClassLoader].
   */
  private fun copyObject(obj: Serializable, targetClassLoader: ClassLoader): Serializable {
    // 1. Serialize the object from the source class loader into a byte array
    val byteOut = ByteArrayOutputStream()
    ObjectOutputStream(byteOut).use { it.writeObject(obj) }
    val bytes = byteOut.toByteArray()

    // 2. Deserialize the byte array into a new object using the target class loader
    val byteIn = ByteArrayInputStream(bytes)

    // A custom ObjectInputStream is needed to resolve the class from the correct loader
    val objectIn =
      object : ObjectInputStream(byteIn) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
          return Class.forName(desc.name, false, targetClassLoader)
        }
      }

    @Suppress("UNCHECKED_CAST")
    return objectIn.use { it.readObject() as Serializable }
  }

  override fun close() {
    rendererInstance.close()
  }
}
