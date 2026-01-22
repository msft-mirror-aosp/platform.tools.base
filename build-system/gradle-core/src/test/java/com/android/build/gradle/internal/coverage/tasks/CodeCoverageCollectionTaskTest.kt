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

package com.android.build.gradle.internal.coverage.tasks

import com.android.build.gradle.internal.coverage.tasks.CodeCoverageCollectionTask.CodeCoverageCollectionWorkerAction
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

class CodeCoverageCollectionTaskTest {

    @get:Rule
    var mTemporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun testFormatProjectName() {
        assertThat(CodeCoverageCollectionWorkerAction.formatProjectName(":app"))
            .isEqualTo("App")
        assertThat(CodeCoverageCollectionWorkerAction.formatProjectName(":core:datastore"))
            .isEqualTo("CoreDatastore")
        assertThat(CodeCoverageCollectionWorkerAction.formatProjectName("app"))
            .isEqualTo("App")
        assertThat(CodeCoverageCollectionWorkerAction.formatProjectName(""))
            .isEqualTo("")
        assertThat(CodeCoverageCollectionWorkerAction.formatProjectName(":"))
            .isEqualTo("")
    }

    @Test
    fun testInjectMetadataInXmlReport() {
        val tempDir = mTemporaryFolder.newFolder()
        val xmlFile = copyResourceToFolder("jacocoReport/com/android/tools/build/tests/myapplication/report.xml", tempDir)

        val properties = mapOf(
            "moduleName" to "app",
            "testSuiteName" to "UnitTest",
            "testedVariantName" to "debug"
        )
        val sourceFolders = listOf(
            "src/main/java",
            "src/main/kotlin"
        )

        CodeCoverageCollectionWorkerAction.injectMetadataInXmlReport(
            xmlFile,
            properties,
            sourceFolders
        )

        assertThat(xmlFile).exists()

        val docFactory = DocumentBuilderFactory.newInstance()
        docFactory.isValidating = false
        docFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        docFactory.isIgnoringElementContentWhitespace = true
        val docBuilder = docFactory.newDocumentBuilder()
        val document: Document = docBuilder.parse(xmlFile)

        val propertiesNode = document.getElementsByTagName("properties").item(0) as Element
        assertThat(propertiesNode).isNotNull()

        val propertyElements = propertiesNode.childNodes
        assertThat(propertyElements.length).isEqualTo(3)

        for (i in 0 until propertyElements.length) {
            val propertyElement = propertyElements.item(i)
            val name = propertyElement.attributes.getNamedItem("name").nodeValue
            val value = propertyElement.attributes.getNamedItem("value").nodeValue
            assertThat(properties).containsEntry(name, value)
        }

        val sourcesNode = document.getElementsByTagName("sources").item(0) as Element
        assertThat(sourcesNode).isNotNull()

        val fileElements = sourcesNode.childNodes
        assertThat(fileElements.length).isEqualTo(2)

        for (i in 0 until fileElements.length) {
            val fileElement = fileElements.item(i)
            val path = fileElement.attributes.getNamedItem("path").nodeValue
            assertThat(sourceFolders).contains(path)
        }
    }

    @Throws(IOException::class)
    private fun copyResourceToFolder(fileName: String, folder: File?): File {
        val inputStream = javaClass.classLoader.getResourceAsStream(fileName)
            ?: throw IOException("Resource not found: $fileName")

        val file = File(folder, fileName)
        FileUtils.mkdirs(file.getParentFile())

        file.outputStream().use { fileOut ->
            inputStream.use { it.copyTo(fileOut) }
        }
        return file
    }
}
