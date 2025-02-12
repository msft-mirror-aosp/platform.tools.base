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

import com.google.common.truth.FailureMetadata
import com.google.common.truth.IterableSubject
import com.google.common.truth.Subject
import org.objectweb.asm.tree.ClassNode

class ClassSubject(
    metadata: FailureMetadata,
    actual: ClassDefinition
): Subject<ClassSubject, ClassDefinition>(metadata, actual) {

    companion object {
        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun classNodes(): Factory<ClassSubject, ClassDefinition> {
            return Factory<ClassSubject, ClassDefinition> { metadata, actual ->
                ClassSubject(metadata, actual)
            }
        }
    }

    fun interfaces(): IterableSubject {
        return check("interfaces()").that(actual().interfaces)
    }

    fun innerClasses(): IterableSubject {
        return check("innerClasses()").that(actual().innerClasses)
    }

    fun fields(): IterableSubject {
        return check("fields()").that(actual().fields)
    }

    fun methods(): IterableSubject {
        return check("methods()").that(actual().methods)
    }
}
