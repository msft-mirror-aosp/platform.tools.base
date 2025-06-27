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

package com.android.tools.journeys.testengine.output

import androidx.test.tools.crawler.output.Crawl
import com.android.tools.journeys.testengine.descriptor.JourneyFileDescriptor
import com.android.tools.journeys.testengine.descriptor.PromptDescriptor
import com.google.common.io.Resources
import com.google.gson.Gson
import com.google.protobuf.TextFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.times
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.eq
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ProgressReporterTest {

    @get:Rule
    val mockitoJUnitRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var listener: EngineExecutionListener

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val outContent = ByteArrayOutputStream()
    private val gson = Gson()
    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        System.setOut(PrintStream(outContent))
        tempDir = tempFolder.root.toPath()
    }

    @After
    fun tearDown() {
        System.setOut(System.out)
    }

    @Test
    fun `reports correctly for proto with incorrect contextual roboscripts`() {
        val crawl = loadCrawlFromResource("robo_results_incorrect_contextual.textproto")

        val prompts =
            listOf(
                "Tap on the search icon and enter 'Compose'",
                "Tap on the 'Compose' topic",
                "Save the first post",
                "Go to saved posts",
                "Confirm that there is a single saved post that belongs to the 'Compose' topic",
            )
        val state = CrawlProcessingState(createJourneyFileDescriptor(prompts))
        val reporter = ProgressReporter(state, listener, tempDir)

        reporter.onCrawlReceived(crawl)
        reporter.reportSkippedPrompts()

        val statuses =
            listOf(
                TestExecutionResult.Status.SUCCESSFUL,
                TestExecutionResult.Status.SUCCESSFUL,
                TestExecutionResult.Status.SUCCESSFUL,
                TestExecutionResult.Status.FAILED,
                TestExecutionResult.Status.ABORTED,
            )
        val artifactLinesGroupedByPrompt =
            listOf(
                listOf(
                    "ActionPerformed.action0.description=Launched package com.google.samples.apps.nowinandroid.demo.debug",
                    "ActionPerformed.action0.durationInMillis=5223",
                    "ActionPerformed.action0.result=ACTION_SUCCESS",
                    "ActionPerformed.action0.screenshotPath=${tempDir.resolve("action0.png")}",
                    "ActionPerformed.action3.description=Tap on the search icon to open the search bar.",
                    "ActionPerformed.action3.durationInMillis=5684",
                    "ActionPerformed.action3.result=ACTION_SUCCESS",
                    "ActionPerformed.action3.modelReasoning=The goal is to tap on the search icon and enter 'Compose'. The search icon is available on the screen, so I should tap on it.",
                    "ActionPerformed.action3.screenshotPath=${tempDir.resolve("action3.png")}",
                    "ActionPerformed.action4.description=Enter 'Compose' into the search bar.",
                    "ActionPerformed.action4.durationInMillis=1896",
                    "ActionPerformed.action4.result=ACTION_SUCCESS",
                    "ActionPerformed.action4.modelReasoning=The goal is to tap on the search icon and enter 'Compose'. I have already tapped on the search icon. Now I need to enter 'Compose' into the search bar.",
                    "ActionPerformed.action4.screenshotPath=${tempDir.resolve("action4.png")}",
                    "ActionPerformed.action5.description=Enter 'Compose' into the search bar.",
                    "ActionPerformed.action5.durationInMillis=1018",
                    "ActionPerformed.action5.result=ACTION_SUCCESS",
                    "ActionPerformed.action5.modelReasoning=The goal is to tap on the search icon and enter 'Compose'. I have already tapped on the search icon. Now I need to enter 'Compose' into the search bar.",
                    "ActionPerformed.action5.screenshotPath=${tempDir.resolve("action5.png")}",
                    "ActionPerformed.action6.description=Enter 'Compose' into the search bar.",
                    "ActionPerformed.action6.durationInMillis=881",
                    "ActionPerformed.action6.result=ACTION_SUCCESS",
                    "ActionPerformed.action6.modelReasoning=The goal is to tap on the search icon and enter 'Compose'. I have already tapped on the search icon. Now I need to enter 'Compose' into the search bar.",
                    "ActionPerformed.action6.screenshotPath=${tempDir.resolve("action6.png")}",
                    "PromptComplete.prompt0.modelReasoning=I have already tapped on the search icon and entered 'Compose' into the search bar. The screen now shows the search results for 'Compose'.",
                    "PromptComplete.prompt0.screenshotPath=${tempDir.resolve("action7.png")}",
                ),
                listOf(
                    "ActionPerformed.action7.description=Tap on the 'Compose' topic.",
                    "ActionPerformed.action7.durationInMillis=3476",
                    "ActionPerformed.action7.result=ACTION_SUCCESS",
                    "ActionPerformed.action7.modelReasoning=The current goal is to tap on the 'Compose' topic. The 'Compose' topic is visible on the screen, so I should tap on it.",
                    "ActionPerformed.action7.screenshotPath=${tempDir.resolve("action7.png")}",
                    "PromptComplete.prompt1.modelReasoning=The current goal is to tap on the 'Compose' topic. I have already tapped on the 'Compose' topic, so the goal is complete.",
                    "PromptComplete.prompt1.screenshotPath=${tempDir.resolve("action8.png")}",
                ),
                listOf(
                    "ActionPerformed.action8.description=Tap on the bookmark icon to save the first post.",
                    "ActionPerformed.action8.durationInMillis=3560",
                    "ActionPerformed.action8.result=ACTION_SUCCESS",
                    "ActionPerformed.action8.modelReasoning=The goal is to save the first post. The bookmark icon is the way to save the post.",
                    "ActionPerformed.action8.screenshotPath=${tempDir.resolve("action8.png")}",
                    "PromptComplete.prompt2.modelReasoning=The bookmark icon was tapped, and the goal is to save the first post.",
                    "PromptComplete.prompt2.screenshotPath=${tempDir.resolve("action9.png")}",
                ),
                listOf(
                    "ActionPerformed.action9.description=Tap on the 'Saved' tab to go to saved posts.",
                    "ActionPerformed.action9.durationInMillis=6113",
                    "ActionPerformed.action9.result=ACTION_SUCCESS",
                    "ActionPerformed.action9.modelReasoning=The current goal is to go to saved posts. The 'Saved' tab is visible at the bottom of the screen, so I should tap on it.",
                    "ActionPerformed.action9.screenshotPath=${tempDir.resolve("action9.png")}",
                    "PromptComplete.prompt3.modelReasoning=The 'Saved' tab should have navigated to the saved posts within the app. Since a browser window opened instead, something went wrong, and the goal cannot be completed.",
                    "PromptComplete.prompt3.screenshotPath=${tempDir.resolve("action11.png")}",
                ),
                emptyList(),
            )

        verifyConsoleOutput(
            artifactLinesGroupedByPrompt.flatMap {
                it.map { line -> "[additionalTestArtifacts]Journeys.$line" }
            }
        )
        verifyFileSystemArtifacts(listOf(0, 3, 4, 5, 6, 7, 8, 9, 11))
        verifyListenerCalls(prompts, statuses, artifactLinesGroupedByPrompt)
    }

    @Test
    fun `reports correctly for proto with prompt evaluation limit reached`() {
        val crawl = loadCrawlFromResource("robo_results_eval_limit.textproto")

        val prompts =
            listOf(
                "Tap on the search icon and enter 'Compose'",
                "Tap on the 'Compose' topic",
                "Save the first post",
                "Go to saved posts",
                "Confirm that there is a single saved post that belongs to the 'Compose' topic",
            )
        val state = CrawlProcessingState(createJourneyFileDescriptor(prompts))
        val reporter = ProgressReporter(state, listener, tempDir)

        reporter.onCrawlReceived(crawl)
        reporter.reportSkippedPrompts()

        val statuses =
            listOf(
                TestExecutionResult.Status.FAILED,
                TestExecutionResult.Status.ABORTED,
                TestExecutionResult.Status.ABORTED,
                TestExecutionResult.Status.ABORTED,
                TestExecutionResult.Status.ABORTED,
            )
        val artifactLinesGroupedByPrompt =
            listOf(
                listOf(
                    "ActionPerformed.action0.description=Launched package com.google.samples.apps.nowinandroid.demo.debug",
                    "ActionPerformed.action0.durationInMillis=5909",
                    "ActionPerformed.action0.result=ACTION_SUCCESS",
                    "ActionPerformed.action0.screenshotPath=${tempDir.resolve("action0.png")}",
                    "ActionPerformed.action1.description=Tap on the search icon to open the search bar.",
                    "ActionPerformed.action1.durationInMillis=6740",
                    "ActionPerformed.action1.result=ACTION_SUCCESS",
                    "ActionPerformed.action1.modelReasoning=The current goal is to tap on the search icon and enter 'Compose' into the search bar. The search bar is not currently open, so I need to tap on the search icon first.",
                    "ActionPerformed.action1.screenshotPath=${tempDir.resolve("action1.png")}",
                    "ActionPerformed.action2.description=Enter 'Compose' into the search bar at the top of the screen.",
                    "ActionPerformed.action2.durationInMillis=3259",
                    "ActionPerformed.action2.result=ACTION_SUCCESS",
                    "ActionPerformed.action2.modelReasoning=The current goal is to enter 'Compose' into the search bar. The search bar is currently open and focused, so I need to enter the text.",
                    "ActionPerformed.action2.screenshotPath=${tempDir.resolve("action2.png")}",
                    "ActionPerformed.action3.description=Enter 'Compose' into the search bar at the top of the screen.",
                    "ActionPerformed.action3.durationInMillis=658",
                    "ActionPerformed.action3.result=ACTION_SUCCESS",
                    "ActionPerformed.action3.modelReasoning=The current goal is to enter 'Compose' into the search bar. The search bar is currently open and focused, so I need to enter the text.",
                    "ActionPerformed.action3.screenshotPath=${tempDir.resolve("action3.png")}",
                    "ActionPerformed.action4.description=Enter 'Compose' into the search bar at the top of the screen.",
                    "ActionPerformed.action4.durationInMillis=84",
                    "ActionPerformed.action4.result=ACTION_SUCCESS",
                    "ActionPerformed.action4.modelReasoning=The current goal is to enter 'Compose' into the search bar. The search bar is currently open and focused, so I need to enter the text.",
                    "ActionPerformed.action4.screenshotPath=${tempDir.resolve("action4.png")}",
                    "ActionPerformed.action5.description=Tap on the 'Next' button to accept the stylus input.",
                    "ActionPerformed.action5.durationInMillis=3869",
                    "ActionPerformed.action5.result=ACTION_SUCCESS",
                    "ActionPerformed.action5.modelReasoning=The current goal is to enter 'Compose' into the search bar. The stylus input is currently active, and the text 'Compose' has been written. I need to tap the 'Next' button to accept the stylus input and enter the text into the search bar.",
                    "ActionPerformed.action5.screenshotPath=${tempDir.resolve("action5.png")}",
                    "ActionPerformed.action6.description=Tap on the 'Cancel' button to dismiss the stylus input.",
                    "ActionPerformed.action6.durationInMillis=3984",
                    "ActionPerformed.action6.result=ACTION_SUCCESS",
                    "ActionPerformed.action6.modelReasoning=The current goal is to enter 'Compose' into the search bar. The stylus input is currently active, but the text is not correct. I need to tap the 'Cancel' button to dismiss the stylus input and try again.",
                    "ActionPerformed.action6.screenshotPath=${tempDir.resolve("action6.png")}",
                    "ActionPerformed.action7.description=Enter 'Compose' into the search bar at the top of the screen.",
                    "ActionPerformed.action7.durationInMillis=3123",
                    "ActionPerformed.action7.result=ACTION_SUCCESS",
                    "ActionPerformed.action7.modelReasoning=The current goal is to enter 'Compose' into the search bar. The search bar is currently open and focused, but the previous attempt to enter the text was interrupted by the stylus input. I need to enter the text again.",
                    "ActionPerformed.action7.screenshotPath=${tempDir.resolve("action7.png")}",
                    "ActionPerformed.action8.description=Enter 'Compose' into the search bar at the top of the screen.",
                    "ActionPerformed.action8.durationInMillis=325",
                    "ActionPerformed.action8.result=ACTION_SUCCESS",
                    "ActionPerformed.action8.modelReasoning=The current goal is to enter 'Compose' into the search bar. The search bar is currently open and focused, but the previous attempt to enter the text was interrupted by the stylus input. I need to enter the text again.",
                    "ActionPerformed.action8.screenshotPath=${tempDir.resolve("action8.png")}",
                    "ActionPerformed.action9.description=Enter 'Compose' into the search bar at the top of the screen.",
                    "ActionPerformed.action9.durationInMillis=92",
                    "ActionPerformed.action9.result=ACTION_SUCCESS",
                    "ActionPerformed.action9.modelReasoning=The current goal is to enter 'Compose' into the search bar. The search bar is currently open and focused, but the previous attempt to enter the text was interrupted by the stylus input. I need to enter the text again.",
                    "ActionPerformed.action9.screenshotPath=${tempDir.resolve("action9.png")}",
                    "PromptComplete.prompt0.modelReasoning=",
                    "PromptComplete.prompt0.screenshotPath=${tempDir.resolve("action10.png")}"
                ),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            )

        verifyConsoleOutput(
            artifactLinesGroupedByPrompt.flatMap {
                it.map { line -> "[additionalTestArtifacts]Journeys.$line" }
            }
        )
        verifyFileSystemArtifacts((0..10).toList())
        verifyListenerCalls(prompts, statuses, artifactLinesGroupedByPrompt)
    }

    private fun verifyConsoleOutput(expectedLines: List<String>) {
        val actualLines = outContent.toString().trim().split(System.lineSeparator())
        assertEquals("Console output lines do not match", expectedLines, actualLines)
    }

    private fun verifyListenerCalls(
        prompts: List<String>,
        statuses: List<TestExecutionResult.Status>,
        artifactLinesGroupedByPrompt: List<List<String>>,
    ) {
        val inOrder = inOrder(listener)

        for ((index, prompt) in prompts.withIndex()) {
            val status = statuses[index]
            val testDescriptorCaptor = ArgumentCaptor.forClass(TestDescriptor::class.java)
            val resultCaptor = ArgumentCaptor.forClass(TestExecutionResult::class.java)
            val reportEntryCaptor = ArgumentCaptor.forClass(ReportEntry::class.java)
            val skipReasonCaptor = ArgumentCaptor.forClass(String::class.java)

            when (status) {
                TestExecutionResult.Status.ABORTED -> {
                    val skippedPromptsSize = prompts.size - index
                    inOrder
                        .verify(listener, times(skippedPromptsSize))
                        .executionSkipped(testDescriptorCaptor.capture(), skipReasonCaptor.capture())
                    assertEquals(
                        prompts.subList(index, prompts.size),
                        testDescriptorCaptor.allValues.map { it -> it.displayName })
                    assertEquals(
                        List(skippedPromptsSize) { "Skipped due to failure at previous step (index ${index - 1})" },
                        skipReasonCaptor.allValues,
                    )
                    break
                }

                else -> {
                    inOrder.verify(listener).executionStarted(testDescriptorCaptor.capture())
                    assertEquals(prompt, testDescriptorCaptor.value.displayName)

                    val expectedReportEntries = artifactLinesGroupedByPrompt[index].mapNotNull { line ->
                        val parts = line.split("=")
                        parts.takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].isNotEmpty() }?.let { (key, value) ->
                            ReportEntry.from(mapOf(key to value))
                        }
                    }

                    inOrder
                        .verify(listener, times(expectedReportEntries.size))
                        .reportingEntryPublished(eq(testDescriptorCaptor.value), reportEntryCaptor.capture())
                    expectedReportEntries.forEachIndexed { index, reportEntry ->
                        assertEquals(reportEntry.keyValuePairs, expectedReportEntries[index].keyValuePairs)
                    }

                    inOrder
                        .verify(listener)
                        .executionFinished(testDescriptorCaptor.capture(), resultCaptor.capture())
                    assertEquals(status, resultCaptor.value.status)
                }
            }
        }
    }

    private fun verifyFileSystemArtifacts(actionIds: List<Int>) {
        for (actionId in actionIds) {
            val screenshotFile = tempDir.resolve("action$actionId.png")
            assertTrue("Screenshot file should exist: $screenshotFile", Files.exists(screenshotFile))
        }
    }

    /** Utility function to load a Crawl proto from a textproto resource file. */
    private fun loadCrawlFromResource(resourceName: String): Crawl {
        val roboResults =
            Resources.toString(Resources.getResource(resourceName), StandardCharsets.UTF_8)
        val builder = Crawl.newBuilder()
        TextFormat.merge(roboResults, builder)
        return builder.build()
    }

    /**
     * Creates a JourneyFileDescriptor hierarchy based on the prompts that would have been
     * discovered from a journey file.
     *
     */
    private fun createJourneyFileDescriptor(prompts: List<String>): JourneyFileDescriptor {
        val engineDescriptor =
            object :
                AbstractTestDescriptor(UniqueId.forEngine("journeys-test-engine"), "JourneysTestEngine") {
                override fun getType() = TestDescriptor.Type.CONTAINER
            }

        val journeyFileDescriptor =
            JourneyFileDescriptor(
                parentId = engineDescriptor.uniqueId,
                journeyFile = File("journey.xml"),
                journeyName = "My Test Journey",
            )

        prompts.forEachIndexed { index, text ->
            val promptDescriptor = PromptDescriptor(journeyFileDescriptor.uniqueId, text, index)
            journeyFileDescriptor.addChild(promptDescriptor)
        }

        return journeyFileDescriptor
    }
}
