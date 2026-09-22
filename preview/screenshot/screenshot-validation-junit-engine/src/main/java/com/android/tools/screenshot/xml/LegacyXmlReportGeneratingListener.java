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

package com.android.tools.screenshot.xml;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;

import com.android.tools.screenshot.PreviewScreenshotTestEngineInput;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Copied from org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener.
 * <p>
 * We've made some changes so that the generated XML report file now includes report-entry data
 * within {@code <property>} elements inside the {@code <testsuite>} and {@code <testcase>} elements.
 */
public class LegacyXmlReportGeneratingListener implements TestExecutionListener {

    private final Path reportsDir;
    private final PrintWriter out;
    private final Clock clock;

    private XmlReportData reportData;

    public LegacyXmlReportGeneratingListener() {
        this.reportsDir = PreviewScreenshotTestEngineInput.XmlReportInput.INSTANCE
                .getOutputDirectory().toPath();
        this.out = new PrintWriter(System.out);
        this.clock = Clock.systemDefaultZone();
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        this.reportData = new XmlReportData(testPlan, clock);
        try {
            Files.createDirectories(this.reportsDir);
        }
        catch (IOException e) {
            printException("Could not create reports directory: " + this.reportsDir, e);
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        this.reportData = null;
    }

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        this.reportData.markSkipped(testIdentifier, reason);
        writeXmlReportInCaseOfRoot(testIdentifier);
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        this.reportData.markStarted(testIdentifier);
    }

    @Override
    public void reportingEntryPublished(TestIdentifier testIdentifier, ReportEntry entry) {
        this.reportData.addReportEntry(testIdentifier, entry);
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        this.reportData.markFinished(testIdentifier, result);
        writeXmlReportInCaseOfRoot(testIdentifier);
    }

    private void writeXmlReportInCaseOfRoot(TestIdentifier testIdentifier) {
        if (isRoot(testIdentifier)) {
            String rootName = testIdentifier.getUniqueIdObject().getSegments().get(0).getValue();
            writeXmlReportSafely(testIdentifier, rootName);
        }
    }

    private void writeXmlReportSafely(TestIdentifier testIdentifier, String rootName) {
        Path xmlFile = this.reportsDir.resolve("TEST-" + rootName + ".xml");
        // Write to a temporary file and move it into place instead of writing xmlFile directly:
        // writing in place leaves a partially overwritten, unparsable report behind when this JVM
        // is killed mid-write or when two test workers write the same file (b/479862361). The
        // temporary name intentionally does not match the "TEST-*.xml" pattern report readers use.
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(this.reportsDir, rootName + "-", ".xml.tmp");
            try (Writer fileWriter = Files.newBufferedWriter(tempFile)) {
                new XmlReportWriter(this.reportData).writeXmlReport(testIdentifier, fileWriter);
            }
            move(tempFile, xmlFile);
            tempFile = null;
        }
        catch (XMLStreamException | IOException e) {
            printException("Could not write XML report: " + xmlFile, e);
        }
        finally {
            deleteIfExistsQuietly(tempFile);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteIfExistsQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        }
        catch (IOException ignored) {
            // The file lives in the task's output directory and is deleted before the next run.
        }
    }

    private boolean isRoot(TestIdentifier testIdentifier) {
        return !testIdentifier.getParentIdObject().isPresent();
    }

    private void printException(String message, Exception exception) {
        out.println(message);
        exception.printStackTrace(out);
    }

}
