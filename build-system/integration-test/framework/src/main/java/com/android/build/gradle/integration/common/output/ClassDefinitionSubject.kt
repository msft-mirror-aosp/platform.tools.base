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

package com.android.build.gradle.integration.common.output

import com.android.testutils.truth.DexClassSubject
import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.IterableSubject
import com.google.common.truth.StringSubject
import com.google.common.truth.Subject

/**
 * A [Subject] for [ClassDefinition]
 *
 * This is not meant to be generated manually. It is returned by [ClassesSubject.classDefinition]
 */
class ClassDefinitionSubject(metadata: FailureMetadata, actual: ClassDefinition) :
  Subject<ClassDefinitionSubject, ClassDefinition>(metadata, actual) {

  companion object {
    /** Method for getting the subject factory (for use with assertAbout()) */
    internal fun classes(): Factory<ClassDefinitionSubject, ClassDefinition> {
      return Factory<ClassDefinitionSubject, ClassDefinition> { metadata, actual -> ClassDefinitionSubject(metadata, actual) }
    }
  }

  fun superClass(): StringSubject = check("superClass()").that(actual().superClass)

  fun interfaces(): IterableSubject = check("interfaces()").that(actual().interfaces)

  fun innerClasses(): IterableSubject = check("innerClasses()").that(actual().innerClasses)

  fun fields(): IterableSubject = check("fields()").that(actual().fields)

  fun methods(): IterableSubject = check("methods()").that(actual().methods)

  fun fieldByName(name: String): StringSubject = check("fieldByName($name)").that(actual().fieldByName(name))

  /**
   * Returns the list of methods invoked by the methods matching the provided name.
   *
   * The subject contains all the method references found in the implementation of the methods. The value is coming from
   * [ccom.android.tools.smali.dexlib2.iface.reference.MethodReference.toString].
   */
  fun invocationListForMethod(name: String): IterableSubject {
    // validates the method exists
    methods().contains(name)

    if (actual() !is ClassDefinitionFromDex) {
      failWithActual(Fact.simpleFact("methodByName only works on dex files"))
      // needed to satisfy compiler, but the line above will throw already
      throw RuntimeException("methodByName only works on dex files")
    }

    val dexActual = actual() as ClassDefinitionFromDex
    // this should succeed since we checked earlier
    val methods = dexActual.methodsWithImplementations()[name]!!

    val list = buildList {
      for (method in methods) {
        DexClassSubject.checkMethodInvokes(method) { methodReference ->
          add(methodReference.toString())
          false
        }
      }
    }

    return check("invocationListForMethod($name)").that(list)
  }
}
