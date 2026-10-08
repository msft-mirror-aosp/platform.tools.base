/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.build.gradle.integration.application;

import static com.android.build.gradle.integration.common.truth.TruthHelper.assertThat;

import com.android.Version;
import com.android.build.gradle.integration.common.fixture.GradleBuildResult;
import com.android.build.gradle.integration.common.fixture.GradleProject;
import com.android.build.gradle.integration.common.fixture.GradleTestProject;
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification;
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp;
import com.android.build.gradle.integration.common.truth.ScannerSubject;
import com.android.build.gradle.integration.common.utils.TestFileUtils;
import com.android.build.gradle.internal.coverage.JacocoConfigurations;
import com.android.build.gradle.options.BooleanOption;
import com.android.build.gradle.options.StringOption;
import com.android.testutils.MavenRepoGenerator;
import com.android.testutils.TestInputsGenerator;
import com.android.testutils.apk.Apk;
import com.android.testutils.apk.Dex;
import com.android.testutils.truth.DexClassSubject;
import com.android.testutils.truth.DexSubject;
import com.android.utils.FileUtils;

import com.google.common.base.Charsets;
import com.google.common.truth.Truth8;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Scanner;

@RunWith(Parameterized.class)
public class JacocoTest {

    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    public static Collection<Boolean> data() {
        return Arrays.asList(true, false);
    }

    private final boolean runWithBuiltInPlatform;

    @Rule
    public final GradleTestProject project;

    public JacocoTest(boolean runWithBuiltInPlatform) {
        this.runWithBuiltInPlatform = runWithBuiltInPlatform;
        this.project = GradleTestProject.builder()
                .fromTestApp(TEST_APP)
                .addGradleProperty(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
                .withAdditionalMavenRepo(fakeAgentRepo())
                .create();
    }

    private static final String CLASS_NAME = "com/example/B";
    private static final String CLASS_FULL_TYPE = "L" + CLASS_NAME + ";";
    private static final String CLASS_SRC_LOCATION = "src/main/java/" + CLASS_NAME + ".java";

    private static final String CLASS_CONTENT =
            "package com.example;\n"
                    + "public class B { }";

    private static final GradleProject TEST_APP =
            HelloWorldApp.forPlugin("com.android.application");

    private static final String AGENT_COORDINATE_PREFIX = "com.android.tools.test:coverage-agent:";
    private static final String AGENT_ATTACHER_CLASS =
            "Lcom/android/tools/coverage/CoverageAgentAttacher;";

    // A fake release of the on-device coverage agent, newer than any real AGP version, that exists
    // only in the test project's additional Maven repository. It contains a marker class instead
    // of the real attacher, so the content of an androidTest APK built against it tells which
    // release was used.
    private static final String NEWER_FAKE_AGENT_VERSION = "99.0.0";
    private static final String FAKE_AGENT_MARKER_CLASS_NAME =
            "com/android/tools/coverage/FakeCoverageAgentMarker";
    private static final String FAKE_AGENT_MARKER_CLASS_TYPE =
            "L" + FAKE_AGENT_MARKER_CLASS_NAME + ";";

    private static MavenRepoGenerator fakeAgentRepo() {
        try {
            byte[] fakeAgentJar =
                    TestInputsGenerator.jarWithEmptyClasses(
                            Collections.singleton(FAKE_AGENT_MARKER_CLASS_NAME));
            return new MavenRepoGenerator(
                    Collections.singletonList(
                            new MavenRepoGenerator.Library(
                                    AGENT_COORDINATE_PREFIX + NEWER_FAKE_AGENT_VERSION,
                                    fakeAgentJar)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the fake coverage agent jar", e);
        }
    }

    private File getCoverageDir() {
        return runWithBuiltInPlatform
                ? new File(project.getProjectDir(), "build/intermediates/test_suite_code_coverage/debug/connectedDebugAndroidTest")
                : new File(project.getProjectDir(), "build/outputs/code_coverage/debugAndroidTest/connected");
    }

    @Before
    public void setup() throws Exception {
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\nandroid.buildTypes.debug.enableAndroidTestCoverage = true\n");
    }

    @Test
    public void addAndRemoveClass() throws Exception {
        project.execute("assembleDebug");
        assertThat(project.getApk("debug")).doesNotContainClass(CLASS_FULL_TYPE);

        TemporaryProjectModification.doTest(
                project,
                modifiedProject -> {
                    modifiedProject.addFile(CLASS_SRC_LOCATION, CLASS_CONTENT);
                    project.execute("assembleDebug");
                    assertThat(project.getApk("debug")).containsClass(CLASS_FULL_TYPE);
                });
        project.execute("assembleDebug");
        assertThat(project.getApk("debug")).doesNotContainClass(CLASS_FULL_TYPE);
    }

    /** Regression test for http://b/65573026. */
    @Test
    public void testDisablingAndEnablingJacoco() throws IOException, InterruptedException {
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\nandroid.buildTypes.debug.enableAndroidTestCoverage = false\n");
        project.executor().run("assembleDebug");

        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\nandroid.buildTypes.debug.enableAndroidTestCoverage = true\n");
        project.executor().run("assembleDebug");
    }

    @Test
    public void testJarIsProcessedByJacoco() throws Exception {
        TestInputsGenerator.jarWithEmptyClasses(
                new File(project.getProjectDir(), "generated-classes.jar").toPath(),
                Collections.singleton("test/A"));

        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "android.applicationVariants.all { variant ->\n"
                        + "    def generated = project.files(\"generated-classes.jar\")\n"
                        + "    variant.registerPostJavacGeneratedBytecode(generated)\n"
                        + "}\n"
                        + "android.buildTypes.debug.enableAndroidTestCoverage = true\n");

        project.executor().with(BooleanOption.USE_NEW_DSL, false).run("assembleDebug");

        Apk apk = project.getApk(GradleTestProject.ApkType.DEBUG);
        Truth8.assertThat(apk.getMainDexFile()).isPresent();
        Dex dexClasses = apk.getMainDexFile().get();
        DexSubject.assertThat(dexClasses).containsClass("Ltest/A;");
        DexClassSubject.assertThat(dexClasses.getClasses().get("Ltest/A;")).hasField("$jacocoData");
    }

    /** Regression test for http://b/152872138. */
    @Test
    public void testDisablingBuildFeaturesInAppAndLib() throws IOException, InterruptedException {
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\n\n"
                        + "android {\n"
                        + "  buildFeatures {\n"
                        + "    aidl = false\n"
                        + "    renderScript = false\n"
                        + "  }\n"
                        + "}\n");
        project.executor()
                .withArgument("--dry-run")
                // https://issuetracker.google.com/146163513
                .run("createDebugAndroidTestCoverageReport");

        TestFileUtils.searchAndReplace(
                project.getBuildFile(), "com.android.application", "com.android.library");
        project.executor()
                .withArgument("--dry-run")
                // https://issuetracker.google.com/146163513
                .run("createDebugAndroidTestCoverageReport");
    }

    /** Regression test for b/154069245. */
    @Test
    public void testIncrementalChangesProcessedOnce() throws Exception {
        project.execute("assembleDebug");

        File dataSrc = new File(project.getMainSrcDir(), "test/Data.java");
        FileUtils.mkdirs(dataSrc.getParentFile());
        String dataSrcContent = "package test; public class Data {} class AnotherClass {}";
        Files.write(dataSrc.toPath(), dataSrcContent.getBytes(Charsets.UTF_8));

        GradleBuildResult result =
                project.executor().withEnableInfoLogging(true).run("assembleDebug");
        int numberOfFilesProcessed = 0;
        try (Scanner scanner = result.getStdout()) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.contains("Instrumenting file: ")) {
                    numberOfFilesProcessed++;
                }
            }
        }
        assertThat(numberOfFilesProcessed).named("number of files with Jacoco").isEqualTo(2);
    }

    @Test
    public void testChangedJars() throws Exception {
        project.executor().run("assembleDebug");

        File strings = new File(project.getProjectDir(), "src/main/res/values/strings.xml");
        FileUtils.mkdirs(strings.getParentFile());
        String dataSrcContent =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<resources>\n"
                        + "    <string name=\"app_name\">HelloWorld</string>\n"
                        + "    <string name=\"app_name2\">HelloWorld2</string>\n"
                        + "</resources>\n";
        Files.write(strings.toPath(), dataSrcContent.getBytes(Charsets.UTF_8));

        GradleBuildResult result =
                project.executor().withEnableInfoLogging(true).run("assembleDebug");
        int numberOfJarsProcessed = 0;
        int numberOfFilesProcessed = 0;
        try (Scanner scanner = result.getStdout()) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.contains("Instrumenting jar: ")) {
                    numberOfJarsProcessed++;
                } else if (line.contains("Instrumenting file: ")) {
                    numberOfFilesProcessed++;
                }
            }
        }
        assertThat(numberOfJarsProcessed)
                .named("number of jars processed with Jacoco")
                .isEqualTo(1);
        assertThat(numberOfFilesProcessed)
                .named("number of files processed with Jacoco")
                .isEqualTo(0);
    }

    @Test
    public void testJacocoBypassWithOnTheFly() throws Exception {
        GradleBuildResult result = project.executor()
                .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                .withEnableInfoLogging(true)
                .run("assembleDebug");

        try (Scanner scanner = result.getStdout()) {
            while (scanner.hasNextLine()) {
                assertThat(scanner.nextLine()).doesNotContain("Instrumenting file: ");
            }
        }

        try (Apk mainApk = project.getApk(GradleTestProject.ApkType.DEBUG)) {
            Dex mainDexClasses = mainApk.getMainDexFile().get();
            DexClassSubject classSubject = DexClassSubject.assertThat(
                    mainDexClasses.getClasses().get("Lcom/example/helloworld/HelloWorld;"));

            classSubject.doesNotHaveMethod("$jacocoInit");
            classSubject.doesNotHaveField("$jacocoData");
        }
    }

@Test
public void testOnTheFlyRequiresReportAggregation() throws Exception {
    File coverageDir = getCoverageDir();
    FileUtils.mkdirs(coverageDir);
    File dummyPb = new File(coverageDir, "coverage_metadata.pb");
    Files.write(dummyPb.toPath(), "dummy content".getBytes(Charsets.UTF_8));

    GradleBuildResult result = project.executor()
            .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
            .with(BooleanOption.REPORT_AGGREGATION_SUPPORT, false)
            .withArgument("-x")
            .withArgument("connectedDebugAndroidTest")
            .expectFailure()
            .run("createDebugAndroidTestCoverageReport");

    assertThat(result.getFailureMessage()).contains(
            "On-the-fly coverage requires 'android.experimental.reportAggregationSupport' to be enabled.");
}

@Test
public void testOnTheFlySuccessWithEmptyPbFiles() throws Exception {
    File coverageDir = getCoverageDir();
    FileUtils.mkdirs(coverageDir);
    // Protocol Buffers allow 0-byte files as valid empty serialized messages.
    File metadataPb = new File(coverageDir, "coverage_metadata.pb");
    Files.write(metadataPb.toPath(), new byte[0]);
    File hitsPb = new File(coverageDir, "coverage_hits.pb");
    Files.write(hitsPb.toPath(), new byte[0]);

    project.executor()
            .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
            .with(BooleanOption.REPORT_AGGREGATION_SUPPORT, true)
            .withArgument("-x")
            .withArgument("connectedDebugAndroidTest")
            .run("createDebugAndroidTestCoverageReport");

    // Verify report.xml was successfully generated
    File reportXml = new File(project.getProjectDir(), "build/reports/coverage/androidTest/debug/connected/report.xml");
    assertThat(reportXml.isFile()).isTrue();
    String content = new String(java.nio.file.Files.readAllBytes(reportXml.toPath()), Charsets.UTF_8);
    assertThat(content).contains("<?xml");
}

@Test
public void testOnTheFlyAnchorTaskIsRegisteredAndRuns() throws Exception {
    File coverageDir = getCoverageDir();
    FileUtils.mkdirs(coverageDir);
    // Protocol Buffers allow 0-byte files as valid empty serialized messages.
    File metadataPb = new File(coverageDir, "coverage_metadata.pb");
    Files.write(metadataPb.toPath(), new byte[0]);
    File hitsPb = new File(coverageDir, "coverage_hits.pb");
    Files.write(hitsPb.toPath(), new byte[0]);

    // Run the main anchor task "createDebugCoverageReport"
    project.executor()
            .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
            .with(BooleanOption.REPORT_AGGREGATION_SUPPORT, true)
            .withArgument("-x")
            .withArgument("connectedDebugAndroidTest")
            .run("createDebugCoverageReport");

    // Verify report.xml was successfully generated via the anchor task flow
    File reportXml = new File(project.getProjectDir(), "build/reports/coverage/androidTest/debug/connected/report.xml");
    assertThat(reportXml.isFile()).isTrue();
}

@Test
public void testOnTheFlyFailsIfPbFilesAreMissing() throws Exception {

    File coverageDir = getCoverageDir();
    FileUtils.mkdirs(coverageDir);
    // Put a pb file to satisfy the outer check in doTaskAction()
    File dummyPb = new File(coverageDir, "some_unrelated_file.pb");
    Files.write(dummyPb.toPath(), "dummy content".getBytes(Charsets.UTF_8));

    GradleBuildResult result = project.executor()
            .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
            .with(BooleanOption.REPORT_AGGREGATION_SUPPORT, true)
            .withArgument("-x")
            .withArgument("connectedDebugAndroidTest")
            .expectFailure()
            .run("createDebugAndroidTestCoverageReport");

    assertThat(result.getFailureMessage()).contains(
            "On-the-fly coverage is enabled but required .pb files were not found.");
}

@Test
public void testLegacyFailsIfNoLegacyFilesFoundEvenWithPb() throws Exception {
    File coverageDir = getCoverageDir();
    FileUtils.mkdirs(coverageDir);
    // Only plant a pb file, no ec/exec files
    File dummyPb = new File(coverageDir, "coverage_metadata.pb");
    Files.write(dummyPb.toPath(), "dummy content".getBytes(Charsets.UTF_8));

    GradleBuildResult result = project.executor()
            .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, false)
            .with(BooleanOption.REPORT_AGGREGATION_SUPPORT, true)
            .withArgument("-x")
            .withArgument("connectedDebugAndroidTest")
            .expectFailure()
            .run("createDebugAndroidTestCoverageReport");

    assertThat(result.getFailureMessage()).contains(
            "failed because no coverage data was found.");
}

    @Test
    public void testLibraryJacocoBypassWithOnTheFly() throws Exception {
        TestFileUtils.searchAndReplace(
                project.getBuildFile(), "com.android.application", "com.android.library");

        GradleBuildResult result = project.executor()
                .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                .withEnableInfoLogging(true)
                .run("assembleDebugAndroidTest");

        try (Scanner scanner = result.getStdout()) {
            while (scanner.hasNextLine()) {
                assertThat(scanner.nextLine()).doesNotContain("Instrumenting file: ");
            }
        }

        try (Apk androidTestApk = project.getApk(GradleTestProject.ApkType.ANDROIDTEST_DEBUG)) {
            Dex dexClasses = androidTestApk.getMainDexFile().get();
            DexClassSubject classSubject = DexClassSubject.assertThat(
                    dexClasses.getClasses().get("Lcom/example/helloworld/HelloWorldTest;"));

            classSubject.doesNotHaveMethod("$jacocoInit");
            classSubject.doesNotHaveField("$jacocoData");
        }
    }

    @Test
    public void androidTestApksDoNotIncludeOfflineInstrumentation() throws Exception {
        project.executor().run("assembleDebug", "assembleDebugAndroidTest");

        try (Apk androidTestApk = project.getApk(GradleTestProject.ApkType.ANDROIDTEST_DEBUG)) {
            Dex dexClasses = androidTestApk.getMainDexFile().get();
            DexClassSubject.assertThat(
                            dexClasses.getClasses().get("Lcom/example/helloworld/HelloWorldTest;"))
                    .doesNotHaveField("\\$jacoco");
        }
        try (Apk mainApk = project.getApk(GradleTestProject.ApkType.DEBUG)) {
            Dex mainDexClasses = mainApk.getMainDexFile().get();
            DexClassSubject.assertThat(
                            mainDexClasses.getClasses().get("Lcom/example/helloworld/HelloWorld;"))
                    .hasMethod("$jacocoInit");
        }

        // Check library androidTest apk test classes do not contain instrumentation.
        TestFileUtils.searchAndReplace(
                project.getBuildFile(), "com.android.application", "com.android.library");
        project.executor().run("clean", "assembleDebugAndroidTest");
        try (Apk libraryAndroidTestApk =
                project.getApk(GradleTestProject.ApkType.ANDROIDTEST_DEBUG)) {
            Dex libraryDexClasses = libraryAndroidTestApk.getMainDexFile().get();
            DexClassSubject.assertThat(
                            libraryDexClasses
                                    .getClasses()
                                    .get("Lcom/example/helloworld/HelloWorldTest;"))
                    .doesNotHaveMethod("$jacocoInit");
        }
    }

    @Test
    public void onTheFlyAgentIsResolvedAtTheAgpVersion() throws Exception {
        String agentPrefix = "com.android.tools.test:coverage-agent:";
        String expectedCoordinate = agentPrefix + Version.ANDROID_GRADLE_PLUGIN_VERSION;

        // The agent is released in lockstep with AGP, so the androidTest runtime classpath must
        // resolve it at exactly the running plugin version and at no other version.
        GradleBuildResult result =
                project.executor()
                        .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                        .run(
                                ":dependencies",
                                "--configuration",
                                "debugAndroidTestRuntimeClasspath");
        boolean agentSeen = false;
        try (Scanner scanner = result.getStdout()) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.contains(agentPrefix)) {
                    agentSeen = true;
                    assertThat(line).contains(expectedCoordinate);
                    // A "-> x.y.z" suffix would mean Gradle substituted another agent version.
                    assertThat(line).doesNotContain("->");
                }
            }
        }
        assertThat(agentSeen).named("coverage agent on debugAndroidTestRuntimeClasspath").isTrue();

        // The agent's attacher and native libraries are packaged into the androidTest APK.
        project.executor()
                .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                .run("assembleDebugAndroidTest");
        try (Apk androidTestApk = project.getApk(GradleTestProject.ApkType.ANDROIDTEST_DEBUG)) {
            assertThat(androidTestApk)
                    .containsClass("Lcom/android/tools/coverage/CoverageAgentAttacher;");
            assertThat(androidTestApk).contains("lib/x86_64/coverage_agent.so");
            assertThat(androidTestApk).contains("lib/arm64-v8a/coverage_agent.so");
        }
    }

    @Test
    public void onTheFlyAgentVersionIsForcedToAgpVersion() throws Exception {
        String agentPrefix = "com.android.tools.test:coverage-agent:";
        String staleCoordinate = agentPrefix + "0.1.0-alpha01";

        // A build author pinning the agent to a stale, independently versioned release must not
        // win conflict resolution: AGP forces the agent to its own version.
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\ndependencies {\n"
                        + "    androidTestImplementation '"
                        + staleCoordinate
                        + "'\n"
                        + "}\n");

        // androidTestImplementation feeds both classpaths; the force must cover both.
        for (String configuration :
                Arrays.asList(
                        "debugAndroidTestCompileClasspath", "debugAndroidTestRuntimeClasspath")) {
            GradleBuildResult result =
                    project.executor()
                            .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                            .run(":dependencies", "--configuration", configuration);
            boolean staleForced = false;
            try (Scanner scanner = result.getStdout()) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.contains(agentPrefix)) {
                        // Every occurrence ends up at the AGP version, whether requested directly
                        // ("group:name:version") or forced ("group:name:requested -> resolved").
                        String resolved =
                                line.contains(" -> ")
                                        ? line.substring(line.indexOf(" -> ") + 4)
                                        : line.substring(
                                                line.indexOf(agentPrefix) + agentPrefix.length());
                        assertThat(resolved.trim().split(" ")[0])
                                .named(configuration + ": " + line)
                                .isEqualTo(Version.ANDROID_GRADLE_PLUGIN_VERSION);
                        // ... and the stale request is reported as substituted.
                        if (line.contains(
                                staleCoordinate + " -> " + Version.ANDROID_GRADLE_PLUGIN_VERSION)) {
                            staleForced = true;
                        }
                    }
                }
            }
            assertThat(staleForced)
                    .named("stale agent version forced to AGP version on " + configuration)
                    .isTrue();
        }

        // The forced resolution must also work for real: the stale version does not exist in
        // any repository, so the build only succeeds if AGP's version is what gets resolved.
        project.executor()
                .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                .run("assembleDebugAndroidTest");
        try (Apk androidTestApk = project.getApk(GradleTestProject.ApkType.ANDROIDTEST_DEBUG)) {
            assertThat(androidTestApk)
                    .containsClass("Lcom/android/tools/coverage/CoverageAgentAttacher;");
            assertThat(androidTestApk).contains("lib/x86_64/coverage_agent.so");
        }
    }

    @Test
    public void onTheFlyAgentVersionCanBeOverriddenWithAGradleProperty() throws Exception {
        String staleCoordinate = AGENT_COORDINATE_PREFIX + "0.1.0-alpha01";
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\ndependencies {\n"
                        + "    androidTestImplementation '"
                        + staleCoordinate
                        + "'\n"
                        + "}\n");

        // A project can pin the agent to another release than the one bundled with its AGP
        // version, e.g. to pick up an agent fix without upgrading the plugin. The override, not
        // the AGP version, is then what both androidTest classpaths resolve, and the force follows
        // it: the stale version declared by the build author is substituted with the override...
        for (String configuration :
                Arrays.asList(
                        "debugAndroidTestCompileClasspath", "debugAndroidTestRuntimeClasspath")) {
            String agentReport = agentDependencyReport(configuration, NEWER_FAKE_AGENT_VERSION);
            assertThat(agentReport)
                    .named(configuration)
                    .contains(staleCoordinate + " -> " + NEWER_FAKE_AGENT_VERSION);
            assertAgentResolvedTo(agentReport, configuration, NEWER_FAKE_AGENT_VERSION);
        }

        // ... and what gets packaged into the androidTest APK: the fake release only has the
        // marker class, the real one only has the attacher.
        project.executor()
                .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                .with(StringOption.COVERAGE_AGENT_VERSION_OVERRIDE, NEWER_FAKE_AGENT_VERSION)
                .run("assembleDebugAndroidTest");
        try (Apk androidTestApk = project.getApk(GradleTestProject.ApkType.ANDROIDTEST_DEBUG)) {
            assertThat(androidTestApk).containsClass(FAKE_AGENT_MARKER_CLASS_TYPE);
            assertThat(androidTestApk).doesNotContainClass(AGENT_ATTACHER_CLASS);
        }
    }

    /**
     * Runs the dependency report of the given configuration with on-the-fly coverage enabled and
     * the agent version overridden, and returns the lines of the report that mention the agent.
     */
    private String agentDependencyReport(String configuration, String agentVersionOverride)
            throws Exception {
        GradleBuildResult result =
                project.executor()
                        .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                        .with(StringOption.COVERAGE_AGENT_VERSION_OVERRIDE, agentVersionOverride)
                        .run(":dependencies", "--configuration", configuration);
        StringBuilder agentLines = new StringBuilder();
        try (Scanner scanner = result.getStdout()) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.contains(AGENT_COORDINATE_PREFIX)) {
                    agentLines.append(line).append('\n');
                }
            }
        }
        assertThat(agentLines.toString()).named("coverage agent on " + configuration).isNotEmpty();
        return agentLines.toString();
    }

    /**
     * Asserts that every agent line of a dependency report resolves to the given version, whether
     * the agent is requested directly ("group:name:version") or substituted ("group:name:requested
     * -> resolved").
     */
    private static void assertAgentResolvedTo(
            String agentReport, String configuration, String version) {
        for (String line : agentReport.split("\n")) {
            String resolved =
                    line.contains(" -> ")
                            ? line.substring(line.indexOf(" -> ") + 4)
                            : line.substring(
                                    line.indexOf(AGENT_COORDINATE_PREFIX)
                                            + AGENT_COORDINATE_PREFIX.length());
            assertThat(resolved.trim().split(" ")[0])
                    .named(configuration + ": " + line)
                    .isEqualTo(version);
        }
    }

    @Test
    public void checkJacocoAntConfiguration() throws IOException, InterruptedException {
        // jacoco ant configuration exists by default due to the usage from
        // TestResultsCollectionTask for the aggregated test reporting feature
        GradleBuildResult result = project.executor().run(":dep");
        try (Scanner scanner = result.getStdout()) {
            ScannerSubject.assertThat(scanner)
                    .contains(JacocoConfigurations.ANT_CONFIGURATION_NAME);
        }
    }
}
