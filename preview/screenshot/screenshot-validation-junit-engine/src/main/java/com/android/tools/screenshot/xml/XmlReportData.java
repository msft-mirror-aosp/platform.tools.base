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

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.junit.platform.engine.TestExecutionResult.Status.ABORTED;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.junit.platform.commons.util.ExceptionUtils;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Copied from org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener.
 * <p>
 * We've made some changes so that the generated XML report file now includes report-entry data
 * within {@code <property>} elements inside the {@code <testsuite>} and {@code <testcase>} elements.
 */
class XmlReportData {

    private static final int MILLIS_PER_SECOND = 1000;

    private final Map<TestIdentifier, TestExecutionResult> finishedTests = new ConcurrentHashMap<>();
    private final Map<TestIdentifier, String> skippedTests = new ConcurrentHashMap<>();
    private final Map<TestIdentifier, Instant> startInstants = new ConcurrentHashMap<>();
    private final Map<TestIdentifier, Instant> endInstants = new ConcurrentHashMap<>();
    private final Map<TestIdentifier, List<ReportEntry>> reportEntries = new ConcurrentHashMap<>();

    private final TestPlan testPlan;
    private final Clock clock;

    XmlReportData(TestPlan testPlan, Clock clock) {
        this.testPlan = testPlan;
        this.clock = clock;
    }

    TestPlan getTestPlan() {
        return this.testPlan;
    }

    Clock getClock() {
        return this.clock;
    }

    void markSkipped(TestIdentifier testIdentifier, String reason) {
        this.skippedTests.put(testIdentifier, reason == null ? "" : reason);
    }

    void markStarted(TestIdentifier testIdentifier) {
        this.startInstants.put(testIdentifier, this.clock.instant());
    }

    void markFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        this.endInstants.put(testIdentifier, this.clock.instant());
        if (result.getStatus() == ABORTED) {
            String reason = result.getThrowable().map(ExceptionUtils::readStackTrace).orElse("");
            this.skippedTests.put(testIdentifier, reason);
        }
        else {
            this.finishedTests.put(testIdentifier, result);
        }
    }

    void addReportEntry(TestIdentifier testIdentifier, ReportEntry entry) {
        List<ReportEntry> entries = this.reportEntries.computeIfAbsent(testIdentifier, key -> new ArrayList<>());
        entries.add(entry);
    }

    boolean wasSkipped(TestIdentifier testIdentifier) {
        return findSkippedAncestor(testIdentifier).isPresent();
    }

    double getDurationInSeconds(TestIdentifier testIdentifier) {
        Instant startInstant = this.startInstants.getOrDefault(testIdentifier, Instant.EPOCH);
        Instant endInstant = this.endInstants.getOrDefault(testIdentifier, startInstant);
        return Duration.between(startInstant, endInstant).toMillis() / (double) MILLIS_PER_SECOND;
    }

    String getSkipReason(TestIdentifier testIdentifier) {
        return findSkippedAncestor(testIdentifier).map(skippedTestIdentifier -> {
            String reason = this.skippedTests.get(skippedTestIdentifier);
            if (!testIdentifier.equals(skippedTestIdentifier)) {
                reason = "parent was skipped: " + reason;
            }
            return reason;
        }).orElse(null);
    }

    List<TestExecutionResult> getResults(TestIdentifier testIdentifier) {
        return getAncestors(testIdentifier).stream() //
                .map(this.finishedTests::get) //
                .filter(Objects::nonNull) //
                .collect(toList());
    }

    List<ReportEntry> getReportEntries(TestIdentifier testIdentifier) {
        return this.reportEntries.getOrDefault(testIdentifier, emptyList());
    }

    private Optional<TestIdentifier> findSkippedAncestor(TestIdentifier testIdentifier) {
        return findAncestor(testIdentifier, this.skippedTests::containsKey);
    }

    private Optional<TestIdentifier> findAncestor(TestIdentifier testIdentifier, Predicate<TestIdentifier> predicate) {
        Optional<TestIdentifier> current = Optional.of(testIdentifier);
        while (current.isPresent()) {
            if (predicate.test(current.get())) {
                return current;
            }
            current = this.testPlan.getParent(current.get());
        }
        return Optional.empty();
    }

    private List<TestIdentifier> getAncestors(TestIdentifier testIdentifier) {
        TestIdentifier current = testIdentifier;
        List<TestIdentifier> ancestors = new ArrayList<>();
        while (current != null) {
            ancestors.add(current);
            current = this.testPlan.getParent(current).orElse(null);
        }
        return ancestors;
    }

}
