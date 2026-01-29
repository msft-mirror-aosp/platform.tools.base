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

package com.android.tools.backup

import org.junit.Test
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.TestClass

class AndroidBackupJUnit4Runner(private val testClass: Class<*>?) : BlockJUnit4ClassRunner(testClass) {

  override fun computeTestMethods(): MutableList<FrameworkMethod> {
    val allMethods = TestClass(testClass).annotatedMethods
    val myTestMethods = mutableListOf<FrameworkMethod>()

    for (method in allMethods) {
      if (
        method.getAnnotation(BeforeBackup::class.java) != null ||
          method.getAnnotation(BeforeRestore::class.java) != null ||
          method.getAnnotation(BetweenBackupAndRestore::class.java) != null ||
          method.getAnnotation(Test::class.java) != null
      ) {
        myTestMethods.add(method)
      }
    }
    return myTestMethods
  }

  override fun validateInstanceMethods(errors: List<Throwable>) {
    super.validatePublicVoidNoArgMethods(BeforeBackup::class.java, false, errors)
    super.validatePublicVoidNoArgMethods(BeforeRestore::class.java, false, errors)
    super.validatePublicVoidNoArgMethods(BetweenBackupAndRestore::class.java, false, errors)
    super.validateTestMethods(errors)
  }
}
