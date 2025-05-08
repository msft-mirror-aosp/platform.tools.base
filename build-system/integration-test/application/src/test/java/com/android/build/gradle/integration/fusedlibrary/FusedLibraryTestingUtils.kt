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

package com.android.build.gradle.integration.fusedlibrary

import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.google.common.truth.Truth
import org.gradle.internal.impldep.org.apache.maven.model.io.xpp3.MavenXpp3Reader
import java.nio.file.Path
import kotlin.io.path.isRegularFile

internal fun assertExpectedPomDependencies(pom: Path, dependencies: List<String>) {
    Truth.assertThat(pom.isRegularFile()).isTrue()
    val xmlMavenPomReader = MavenXpp3Reader()
    pom.toFile().inputStream().use { inStream ->
        val parsedPom = xmlMavenPomReader.read(inStream)
        assertThat(parsedPom.dependencies.map {
            "${it.groupId}:${it.artifactId}:${it.version} scope:${it.scope}"
        }).containsExactlyElementsIn(dependencies)
    }
}
