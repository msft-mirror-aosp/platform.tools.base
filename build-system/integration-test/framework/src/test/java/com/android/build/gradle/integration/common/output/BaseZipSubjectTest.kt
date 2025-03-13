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

import com.android.build.gradle.integration.common.dependencies.AarBuilder
import com.android.build.gradle.integration.common.dependencies.AarBuilderImpl
import com.android.build.gradle.integration.common.dependencies.JarBuilder
import com.android.build.gradle.integration.common.dependencies.JarBuilderImpl
import com.google.common.truth.ExpectFailure
import com.google.common.truth.TruthFailureSubject
import org.jetbrains.annotations.CheckReturnValue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.io.path.writeBytes

val FAKE_CLASS: ByteArray =
    byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

@Suppress("UnstableApiUsage")
abstract class BaseZipSubjectTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @CheckReturnValue
    protected fun createAar(name: String, action: AarBuilder.() -> Unit): SimpleZip {
        val builder = AarBuilderImpl("groupId", "artifactId", "1.0")
        action(builder)

        val path = temporaryFolder.newFile(name).toPath()
        path.writeBytes(builder.toLibraryData().content)

        return SimpleZip(path)
    }

    @CheckReturnValue
    protected fun createJar(name: String, action: JarBuilder.() -> Unit): SimpleZip {
        val builder = JarBuilderImpl()
        action(builder)

        val path = temporaryFolder.newFile(name).toPath()
        path.writeBytes(builder.getContent())

        return SimpleZip(path)
    }
}

fun AssertionError.assert(action: TruthFailureSubject.() -> Unit) {
    action(ExpectFailure.assertThat(this))
}
