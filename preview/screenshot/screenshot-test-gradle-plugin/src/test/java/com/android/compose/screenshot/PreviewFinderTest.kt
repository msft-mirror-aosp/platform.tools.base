/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.compose.screenshot

import com.google.common.truth.Truth
import com.android.tools.render.compose.ComposeScreenshot
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

class PreviewFinderTest {
    @get:Rule
    val tempDirRule = TemporaryFolder()

    // TODO(b/315048068): Add a unit test that finds previews on the classpath
    @Test
    fun testDiscoverPreviewsWithEmptyClassPath() {
        val outputFile = tempDirRule.newFile("outputFile")
        discoverPreviews(listOf(tempDirRule.root), listOf(File("empty/jar.jar")), listOf(), listOf(), listOf(), outputFile.toPath())

        Truth.assertThat(outputFile.readText()).isEqualTo("""
            {
              "screenshots": []
            }
        """.trimIndent())
    }

    @Test
    fun testConfigureInput() {
        val classpath = listOf("path/to/classes.jar","path/to/R.jar")
        val previewsFile = tempDirRule.newFile("previews_discovered.json")
        previewsFile.writeText("""
            {
              "screenshots": [
                {
                  "methodFQN": "com.example.agptest.ExampleInstrumentedTest.previewThere",
                  "methodParams": [],
                  "previewParams": {
                    "showBackground": "true"
                  },
                  "previewId": "com.example.agptest.ExampleInstrumentedTest.previewThere_3d8b4969_da39a3ee",
                  "previewType": "COMPOSE"
                }
              ]
            }
        """.trimIndent())
        val previewRendering = configureInput(classpath,
            classpath,
            "fontsPath",
            "layoutlibpath",
            "outputFolder",
            "metaDataFolder",
            "namespace",
            "resourceApkPath",
            previewsFile,
            "resultsFilePath"
            )
        assertEquals("fontsPath", previewRendering.fontsPath)
        assertEquals("layoutlibpath", previewRendering.layoutlibPath)
        assertEquals("metaDataFolder", previewRendering.metaDataFolder)
        assertEquals("namespace", previewRendering.namespace)
        assertEquals("resourceApkPath", previewRendering.resourceApkPath)
        assertEquals("resultsFilePath", previewRendering.resultsFilePath)
        assertEquals(2, previewRendering.classPath.size)
        assertEquals(2, previewRendering.projectClassPath.size)
        assertEquals(1, previewRendering.screenshots.size)
        val screenshot = previewRendering.screenshots[0]
        assertEquals("com.example.agptest.ExampleInstrumentedTest.previewThere", screenshot.methodFQN)
        assertEquals("com.example.agptest.ExampleInstrumentedTest.previewThere_3d8b4969_da39a3ee", screenshot.previewId)
        assert(screenshot is ComposeScreenshot)
    }

    @Test
    fun testSortListOfSortedMaps() {
        val inputList = listOf(
            sortedMapOf(Pair("provider", "b")),
            sortedMapOf(),
            sortedMapOf(Pair("provider", "a")),
            sortedMapOf(Pair("a", "b")))

        val sortedList = sortListOfSortedMaps(inputList)

        val expectedList = listOf(
            sortedMapOf(),
            sortedMapOf(Pair("a", "b")),
            sortedMapOf(Pair("provider", "a")),
            sortedMapOf(Pair("provider", "b"))
        )
        assertEquals(expectedList, sortedList)
    }

    @Test
    fun testSortListOfSortedMapsWithMultipleKeys() {
        val inputList = listOf(
            sortedMapOf(Pair("c", "d"), Pair("provider", "a")),
            sortedMapOf(Pair("z", "z"), Pair("provider", "b")),
            sortedMapOf(Pair("provider", "b")),
            sortedMapOf(Pair("provider", "a"), Pair("z", "z")),
            sortedMapOf(Pair("provider", "a"), Pair("z", "y")),
            sortedMapOf(Pair("a", "b"), Pair("b", "z")),
            sortedMapOf(Pair("a", "b"), Pair("b", "a")),
            sortedMapOf(Pair("a", "b")),
            sortedMapOf())

        val sortedList = sortListOfSortedMaps(inputList)

        val expectedList = listOf(
            sortedMapOf(),
            sortedMapOf(Pair("a", "b")),
            sortedMapOf(Pair("a", "b"), Pair("b", "a")),
            sortedMapOf(Pair("a", "b"), Pair("b", "z")),
            sortedMapOf(Pair("c", "d"), Pair("provider", "a")),
            sortedMapOf(Pair("provider", "a"), Pair("z", "y")),
            sortedMapOf(Pair("provider", "a"), Pair("z", "z")),
            sortedMapOf(Pair("provider", "b")),
            sortedMapOf(Pair("provider", "b"), Pair("z", "z")))
        assertEquals(expectedList, sortedList)
    }
}
