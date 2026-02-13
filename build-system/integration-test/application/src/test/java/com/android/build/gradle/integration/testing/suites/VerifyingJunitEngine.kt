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

package com.android.build.gradle.integration.testing.suites

import com.android.build.api.testsuites.TestEngineInputProperty
import com.android.build.api.testsuites.TestSuiteExecutionClient
import java.io.File
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.EngineDescriptor

/**
 * A JUnit engine used for verifying that the AGP Test task correctly wires inputs. It walks BINARY_FOLDERS, logs every file found, and
 * ensures all .class files can be loaded. It also verifies that all classes specified in EXPECTED_CLASSES are present in the classpath.
 *
 * NOTE: This class avoids using lambdas, anonymous classes, or companion objects to simplify bundling into integration test JARs.
 */
class VerifyingJunitEngine : TestEngine {

  private val inputParams = TestSuiteExecutionClient.default()
  private val logger = TestEngineLogger(File(inputParams.getInputParameter(TestEngineInputProperty.LOGGING_FILE)))

  override fun getId(): String {
    return "verifying-junit-engine"
  }

  override fun discover(discoveryRequest: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor {
    val engineDescriptor = EngineDescriptor(uniqueId, "Verifying Engine")
    val testDescriptor = VerifyingTestDescriptor(uniqueId.append("method", "verify-binary-folders"))
    engineDescriptor.addChild(testDescriptor)
    return engineDescriptor
  }

  override fun execute(p0: ExecutionRequest?) {
    if (p0 == null) return
    val executionRequest = p0
    val listener: EngineExecutionListener = executionRequest.engineExecutionListener
    val root = executionRequest.rootTestDescriptor
    listener.executionStarted(root)

    var test = root
    val children = root.children
    if (children.size == 1) {
      test = children.iterator().next()
    }

    if (test !== root) {
      listener.executionStarted(test)
    }

    try {
      val binaryFolders = inputParams.getInputParameter(TestEngineInputProperty.BINARY_FOLDERS)
      logger.info("BINARY_FOLDERS: '$binaryFolders'")
      if (binaryFolders.isEmpty()) {
        reportFailure(listener, test, root, Exception("BINARY_FOLDERS is empty"))
        return
      }

      val rawFolders = binaryFolders.split(File.pathSeparator)
      val folders = mutableListOf<String>()
      for (f in rawFolders) {
        if (f.isNotEmpty()) {
          folders.add(f)
        }
      }

      if (folders.isEmpty()) {
        reportFailure(listener, test, root, Exception("No non-empty folders found in BINARY_FOLDERS: '$binaryFolders'"))
        return
      }

      val loadFailures = mutableListOf<String>()
      var classesFoundCount = 0

      for (folderPath in folders) {
        val folder = File(folderPath)
        if (folder.exists()) {
          logger.info("Checking folder: ${folder.absolutePath}")
          classesFoundCount += recursiveWalkAndLoad(folder, folder, loadFailures)
        } else {
          logger.warn("Folder does not exist: $folderPath")
        }
      }

      if (classesFoundCount == 0) {
        reportFailure(listener, test, root, Exception("No class files found in binary folders: $binaryFolders"))
        return
      }

      if (loadFailures.size > 0) {
        val sb = StringBuilder("Failed to load some classes from BINARY_FOLDERS: ")
        for (failure in loadFailures) {
          sb.append(failure).append("; ")
        }
        reportFailure(listener, test, root, Exception(sb.toString()))
        return
      }

      val expectedClassesStr =
        try {
          inputParams.getInputParameter("com.android.junit.engine.expected.classes")
        } catch (e: Exception) {
          ""
        }
      if (expectedClassesStr.isNotEmpty()) {
        val expectedClassesArray = expectedClassesStr.split(',')
        for (c in expectedClassesArray) {
          val trimmed = c.trim()
          if (trimmed.isEmpty()) continue
          try {
            val loadedClass = Class.forName(trimmed, false, this.javaClass.classLoader)
            logger.info("Successfully loaded expected class: ${loadedClass.name}")
          } catch (t: Throwable) {
            reportFailure(listener, test, root, Exception("Failed to load expected class $trimmed: " + t.message))
            return
          }
        }
        logger.info("All expected classes found and loaded: $expectedClassesStr")
      }

      if (test !== root) {
        listener.executionFinished(test, TestExecutionResult.successful())
      }
      listener.executionFinished(root, TestExecutionResult.successful())
    } catch (t: Throwable) {
      reportFailure(listener, test, root, t)
    }
  }

  private fun recursiveWalkAndLoad(current: File, root: File, loadFailures: MutableList<String>): Int {
    val files = current.listFiles()
    if (files == null) return 0

    var count = 0
    for (file in files) {
      if (file.isDirectory) {
        count += recursiveWalkAndLoad(file, root, loadFailures)
      } else if (file.isFile) {
        logger.info("Found file: ${file.absolutePath}")
        val name = file.name
        if (name.endsWith(".class")) {
          count++
          val relativePath = file.absolutePath.substring(root.absolutePath.length + 1)
          val className = relativePath.substring(0, relativePath.length - 6).replace(File.separatorChar, '.')
          try {
            val loadedClass = Class.forName(className, false, this.javaClass.classLoader)
            logger.info("Successfully loaded class found in folder: ${loadedClass.name}")
          } catch (t: Throwable) {
            logger.error("Failed to load class $className: ${t.message}")
            loadFailures.add(className + ": " + t.message)
          }
        }
      }
    }
    return count
  }

  private fun reportFailure(listener: EngineExecutionListener, test: TestDescriptor, root: TestDescriptor, t: Throwable) {
    if (test !== root) {
      listener.executionFinished(test, TestExecutionResult.failed(t))
    }
    listener.executionFinished(root, TestExecutionResult.successful())
  }
}

class VerifyingTestDescriptor(uniqueId: UniqueId) : AbstractTestDescriptor(uniqueId, "verifying descriptor") {
  override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST
}
