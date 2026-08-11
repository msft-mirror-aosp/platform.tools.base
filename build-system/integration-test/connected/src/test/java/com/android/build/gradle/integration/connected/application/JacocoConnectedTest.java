/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.integration.connected.application;

import static com.android.testutils.truth.PathSubject.assertThat;

import com.android.build.gradle.integration.common.fixture.GradleTestProject;
import com.android.build.gradle.integration.common.fixture.app.KotlinHelloWorldApp;
import com.android.build.gradle.integration.common.utils.TestFileUtils;
import com.android.build.gradle.integration.connected.utils.EmulatorUtils;
import com.android.build.gradle.options.BooleanOption;
import com.android.utils.FileUtils;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

@RunWith(Parameterized.class)
public class JacocoConnectedTest {

    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    public static Collection<Boolean> data() {
        return Arrays.asList(true, false);
    }

    private final boolean runWithBuiltInPlatform;

    public JacocoConnectedTest(boolean runWithBuiltInPlatform) {
        this.runWithBuiltInPlatform = runWithBuiltInPlatform;
    }

    @ClassRule public static final ExternalResource emulator = EmulatorUtils.getEmulator();

    @Rule
    public final GradleTestProject project =
            GradleTestProject.builder()
                    .fromTestApp(KotlinHelloWorldApp.forPlugin("com.android.application"))
                    .create();

    @Before
    public void setUp() throws IOException {
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\nandroid.buildTypes.debug.enableAndroidTestCoverage = true");
        // fail fast if no response
        project.addAdbTimeout();
        // run the uninstall tasks in order to (1) make sure nothing is installed at the beginning
        // of each test and (2) check the adb connection before taking the time to build anything.
        project.executor()
                .with(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
                .run("uninstallAll");
    }

    @Test
    public void connectedCheck() throws Exception {
        project.executor()
                .with(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
                .run("connectedCheck");
        assertThat(project.file("build/reports/coverage/androidTest/debug/connected/index.html"))
                .exists();
        assertThat(
                        project.file(
                                "build/reports/coverage/androidTest/debug/connected/com.example.helloworld/HelloWorld.html"))
                .exists();
    }

    @Test
    public void onTheFlyConnectedCheck() throws Exception {
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\n"
                        + "dependencies {\n"
                        + "  androidTestImplementation"
                        + " 'com.android.tools.test:coverage-agent:1.0.0'\n"
                        + "}");

        project.executor()
                .with(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
                .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                .with(BooleanOption.REPORT_AGGREGATION_SUPPORT, true)
                .run("createDebugCoverageReport");

        // 1. Verify that raw binary .pb files were successfully written and pulled to host output directory
        File coverageDir = getCoverageDir();
        List<File> pbFiles = FileUtils.find(coverageDir, Pattern.compile(".*\\.pb"));
        Truth.assertThat(pbFiles.size()).isAtLeast(2);

        // 2. Verify that the reporting task successfully compiled the standardized report.xml
        File reportXml =
                project.file("build/reports/coverage/androidTest/debug/connected/report.xml");
        assertThat(reportXml).exists();

        // 3. Verify XML contents contain HelloWorld coverage trace
        String content = Files.readString(reportXml.toPath());
        Truth.assertThat(content).contains("<package name=\"com/example/helloworld\">");
        Truth.assertThat(content).contains("<class name=\"com/example/helloworld/HelloWorld\"");
    }

    @Test
    public void connectedCheckWithOrchestrator() throws Exception {
        runConnectedCheckAndAssertCoverageReportExists(/*enableClearPackageDataOption=*/ false);
    }

    /** Regression test for http://b/123987001. */
    @Test
    public void connectedCheckWithOrchestratorAndClearPackageDataEnabled() throws Exception {
        runConnectedCheckAndAssertCoverageReportExists(/*enableClearPackageDataOption=*/ true);
    }

    private void runConnectedCheckAndAssertCoverageReportExists(
            boolean enableClearPackageDataOption) throws Exception {
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\n"
                        + "android.defaultConfig.minSdkVersion 16\n"
                        + "android.defaultConfig.testInstrumentationRunner"
                        + " 'androidx.test.runner.AndroidJUnitRunner'\n"
                        + "android.defaultConfig.testInstrumentationRunnerArguments package:"
                        + " 'com.example.helloworld'\n"
                        + (enableClearPackageDataOption
                                ? "android.defaultConfig.testInstrumentationRunnerArguments"
                                        + " clearPackageData: 'true'\n"
                                        + "android.defaultConfig.testInstrumentationRunnerArguments"
                                        + " useTestStorageService: 'true'\n"
                                : "")
                        + "android.testOptions.execution = 'ANDROIDX_TEST_ORCHESTRATOR'\n"
                        // Orchestrator requires some setup time and it usually takes
                        // about an minute. Increase the timeout for running "am instrument" command
                        // to 3 minutes.
                        + "android.adbOptions.timeOutInMs=180000\n"
                        + "dependencies {\n"
                        + "  androidTestImplementation 'androidx.test:core:1.4.0-alpha06'\n"
                        + "  androidTestImplementation 'androidx.test.ext:junit:1.1.3-alpha02'\n"
                        + "  androidTestImplementation 'androidx.test:monitor:1.4.0-alpha06'\n"
                        + "  androidTestImplementation 'androidx.test:rules:1.4.0-alpha06'\n"
                        + "  androidTestImplementation 'androidx.test:runner:1.4.0-alpha06'\n"
                        + "  androidTestUtil 'androidx.test.services:test-services:1.5.0-alpha02'\n"
                        + "  androidTestUtil 'androidx.test:orchestrator:1.5.0-alpha02'\n"
                        + "}");
        TestFileUtils.appendToFile(
                project.getGradlePropertiesFile(),
                "android.useAndroidX=true");

        String testSrc =
                "package com.example.helloworld;\n"
                        + "\n"
                        + "import androidx.test.ext.junit.runners.AndroidJUnit4;\n"
                        + "import org.junit.Rule;\n"
                        + "import org.junit.Test;\n"
                        + "import org.junit.runner.RunWith;\n"
                        + "\n"
                        + "@RunWith(AndroidJUnit4.class)\n"
                        + "public class ExampleTest {\n"
                        + "    @Test\n"
                        + "    public void test1() { }\n"
                        + "\n"
                        + "    @Test\n"
                        + "    public void test2() { }\n"
                        + "}\n";
        Path exampleTest =
                project.getProjectDir()
                        .toPath()
                        .resolve("src/androidTest/java/com/example/helloworld/ExampleTest.java");
        Files.createDirectories(exampleTest.getParent());
        Files.write(exampleTest, testSrc.getBytes());

        // This example project uses deprecated "android.support.test.runner.AndroidJUnit4" runner
        // which cannot be used with androidx.test.ext.junit.runners.AndroidJUnit4 together. So,
        // deleting it here.
        Path deprecatedTest =
                project.getProjectDir()
                        .toPath()
                        .resolve("src/androidTest/java/com/example/helloworld/HelloWorldTest.java");
        Files.deleteIfExists(deprecatedTest);

        project.executor()
                .with(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
                .run("connectedCheck");
        List<File> files = FileUtils.find(getCoverageDir(), Pattern.compile(".*\\.ec"));

        // ExampleTest has 2 methods, and there should be at least 2 .ec files
        Truth.assertThat(files.size()).isAtLeast(2);
        assertThat(
                        project.file(
                                "build/reports/coverage/androidTest/debug/connected/com.example.helloworld/index.html"))
                .exists();
    }

    /** Regression test for http://b/152872138. */
    @Test
    public void testDisablingBuildFeatures() throws IOException, InterruptedException {
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
                .with(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
                .run("connectedCheck");
        assertThat(
                        project.file(
                                "build/reports/coverage/androidTest/debug/connected/com.example.helloworld/HelloWorld.html"))
                .exists();
        String expectedReportXml =
                "<package name=\"com/example/helloworld\"><class"
                    + " name=\"com/example/helloworld/HelloWorld\""
                    + " sourcefilename=\"HelloWorld.kt\"><method name=\"&lt;init&gt;\" desc=\"()V\""
                    + " line=\"6\"><counter type=\"INSTRUCTION\" missed=\"0\""
                    + " covered=\"3\"/><counter type=\"LINE\" missed=\"0\" covered=\"1\"/><counter"
                    + " type=\"COMPLEXITY\" missed=\"0\" covered=\"1\"/><counter type=\"METHOD\""
                    + " missed=\"0\" covered=\"1\"/></method><method name=\"onCreate\""
                    + " desc=\"(Landroid/os/Bundle;)V\" line=\"9\"><counter type=\"INSTRUCTION\""
                    + " missed=\"0\" covered=\"6\"/><counter type=\"LINE\" missed=\"0\""
                    + " covered=\"3\"/><counter type=\"COMPLEXITY\" missed=\"0\""
                    + " covered=\"1\"/><counter type=\"METHOD\" missed=\"0\""
                    + " covered=\"1\"/></method><counter type=\"INSTRUCTION\" missed=\"0\""
                    + " covered=\"9\"/><counter type=\"LINE\" missed=\"0\" covered=\"4\"/><counter"
                    + " type=\"COMPLEXITY\" missed=\"0\" covered=\"2\"/><counter type=\"METHOD\""
                    + " missed=\"0\" covered=\"2\"/><counter type=\"CLASS\" missed=\"0\""
                    + " covered=\"1\"/></class><sourcefile name=\"HelloWorld.kt\"><line nr=\"6\""
                    + " mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/><line nr=\"9\" mi=\"0\" ci=\"2\""
                    + " mb=\"0\" cb=\"0\"/><line nr=\"10\" mi=\"0\" ci=\"3\" mb=\"0\""
                    + " cb=\"0\"/><line nr=\"12\" mi=\"0\" ci=\"1\" mb=\"0\" cb=\"0\"/><counter"
                    + " type=\"INSTRUCTION\" missed=\"0\" covered=\"9\"/><counter type=\"LINE\""
                    + " missed=\"0\" covered=\"4\"/><counter type=\"COMPLEXITY\" missed=\"0\""
                    + " covered=\"2\"/><counter type=\"METHOD\" missed=\"0\""
                    + " covered=\"2\"/><counter type=\"CLASS\" missed=\"0\""
                    + " covered=\"1\"/></sourcefile><counter type=\"INSTRUCTION\" missed=\"0\""
                    + " covered=\"9\"/><counter type=\"LINE\" missed=\"0\" covered=\"4\"/><counter"
                    + " type=\"COMPLEXITY\" missed=\"0\" covered=\"2\"/><counter type=\"METHOD\""
                    + " missed=\"0\" covered=\"2\"/><counter type=\"CLASS\" missed=\"0\""
                    + " covered=\"1\"/></package><counter type=\"INSTRUCTION\" missed=\"0\""
                    + " covered=\"9\"/><counter type=\"LINE\" missed=\"0\" covered=\"4\"/><counter"
                    + " type=\"COMPLEXITY\" missed=\"0\" covered=\"2\"/><counter type=\"METHOD\""
                    + " missed=\"0\" covered=\"2\"/><counter type=\"CLASS\" missed=\"0\""
                    + " covered=\"1\"/></report>";
        Truth.assertThat(
                        Files.readString(
                                project.file(
                                                "build/reports/coverage/androidTest/debug/connected/report.xml")
                                        .toPath()))
                .contains(expectedReportXml);
    }

    @Test
    public void testOnTheFlyConnectedCheckWithMultiModule() throws Exception {
        // 1. Include :libModule in settings.gradle
        TestFileUtils.appendToFile(project.getSettingsFile(), "\ninclude ':libModule'\n");

        // 2. Create the libModule directory and its build.gradle
        File libDir = new File(project.getProjectDir(), "libModule");
        FileUtils.mkdirs(libDir);
        File libBuildFile = new File(libDir, "build.gradle");
        Files.write(
                libBuildFile.toPath(),
                Arrays.asList(
                        "apply plugin: 'com.android.library'",
                        "android {",
                        "    namespace = 'com.example.libmodule'",
                        "    compileSdkVersion = " + GradleTestProject.DEFAULT_COMPILE_SDK_VERSION,
                        "}"));

        // 3. Create LibClass inside the library module
        File libSrcDir = new File(libDir, "src/main/java/com/example/libmodule");
        FileUtils.mkdirs(libSrcDir);
        File libClassFile = new File(libSrcDir, "LibClass.java");
        Files.write(
                libClassFile.toPath(),
                Arrays.asList(
                        "package com.example.libmodule;",
                        "public class LibClass {",
                        "    public static int add(int a, int b) { return a + b; }",
                        "}"));

        // Create AppHelper inside the main application module to trigger safe coverage
        File appSrcDir = new File(project.getProjectDir(), "src/main/java/com/example/helloworld");
        FileUtils.mkdirs(appSrcDir);
        File appHelperFile = new File(appSrcDir, "AppHelper.java");
        Files.write(
                appHelperFile.toPath(),
                Arrays.asList(
                        "package com.example.helloworld;",
                        "public class AppHelper {",
                        "    public static int getSomething() { return 42; }",
                        "}"));

        // 4. Overwrite HelloWorldTest to call our new LibClass and AppHelper methods
        Path testPath =
                project.getProjectDir()
                        .toPath()
                        .resolve("src/androidTest/java/com/example/helloworld/HelloWorldTest.java");
        Files.createDirectories(testPath.getParent());
        Files.write(
                testPath,
                Arrays.asList(
                        "package com.example.helloworld;",
                        "import org.junit.Test;",
                        "import static org.junit.Assert.assertEquals;",
                        "public class HelloWorldTest {",
                        "    @Test",
                        "    public void testLibModuleCoverage() {",
                        "        assertEquals(42, AppHelper.getSomething());",
                        "        assertEquals(5, com.example.libmodule.LibClass.add(2, 3));",
                        "    }",
                        "}"));

        // 5. Add dependencies to the main application
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\n"
                        + "dependencies {\n"
                        + "  implementation project(':libModule')\n"
                        + "  androidTestImplementation"
                        + " 'com.android.tools.test:coverage-agent:1.0.0'\n"
                        + "}");

        // 6. Execute the on-the-fly coverage collection task
        project.executor()
                .with(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
                .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                .with(BooleanOption.REPORT_AGGREGATION_SUPPORT, true)
                .run("createDebugCoverageReport");

        // 7. Verify that raw binary .pb files were successfully written and pulled to host output
        // directory
        File coverageDir = getCoverageDir();
        List<File> pbFiles = FileUtils.find(coverageDir, Pattern.compile(".*\\.pb"));
        Truth.assertThat(pbFiles.size()).isAtLeast(2);

        // 8. Verify that the reporting task successfully compiled the standardized report.xml
        File reportXml =
                project.file("build/reports/coverage/androidTest/debug/connected/report.xml");
        assertThat(reportXml).exists();

        // 9. Verify XML contents contain app module coverage but not library module coverage
        String content = Files.readString(reportXml.toPath());
        Truth.assertThat(content).contains("<package name=\"com/example/helloworld\">");
        Truth.assertThat(content).contains("<class name=\"com/example/helloworld/AppHelper\"");
        Truth.assertThat(content).doesNotContain("<package name=\"com/example/libmodule\">");
    }

    @Test
    public void testOnTheFlyConnectedCheckWithComplexParameters() throws Exception {
        // Create ComplexParamClass inside the main application module as a complex Kotlin class
        File appSrcDir = new File(project.getProjectDir(), "src/main/java/com/example/helloworld");
        FileUtils.mkdirs(appSrcDir);
        File complexParamClassFile = new File(appSrcDir, "ComplexParamClass.kt");
        Files.write(
                complexParamClassFile.toPath(),
                Arrays.asList(
                        "package com.example.helloworld",
                        "",
                        "annotation class Composable",
                        "",
                        "class ComplexParamClass {",
                        "    companion object {",
                        "        @JvmStatic",
                        "        fun process(a: String, b: Int, c: Double, d: Long): Double {",
                        "            var result = b + c + d",
                        "            if (a == \"complex\") {",
                        "                try {",
                        "                    for (i in 0..10) {",
                        "                        result += i",
                        "                        if (result > 50.0) {",
                        "                            result /= 2.0",
                        "                        }",
                        "                    }",
                        "                } catch (e: Exception) {",
                        "                    result -= 1.0",
                        "                }",
                        "            }",
                        "            return result",
                        "        }",
                        "    }",
                        "    ",
                        "    @Composable",
                        "    fun MyComplexScreen(onBackClick: Runnable) {",
                        "        onBackClick.run()",
                        "    }",
                        "}"));

        // Create a test method inside HelloWorldTest.java to invoke ComplexParamClass.process and
        // MyComplexScreen
        Path testPath =
                project.getProjectDir()
                        .toPath()
                        .resolve("src/androidTest/java/com/example/helloworld/HelloWorldTest.java");
        Files.createDirectories(testPath.getParent());
        Files.write(
                testPath,
                Arrays.asList(
                        "package com.example.helloworld;",
                        "import org.junit.Test;",
                        "import static org.junit.Assert.assertTrue;",
                        "public class HelloWorldTest {",
                        "    @Test",
                        "    public void testComplexParamsCoverage() {",
                        "        assertTrue(ComplexParamClass.process(\"complex\", 5, 100.5, 0L) >"
                                + " 0.0);",
                        "        ComplexParamClass complexInstance = new ComplexParamClass();",
                        "        complexInstance.MyComplexScreen(() -> {",
                        "            System.out.println(\"Composable backclicked\");",
                        "        });",
                        "    }",
                        "}"));

        // Add dependencies to the main application
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\n"
                        + "dependencies {\n"
                        + "  androidTestImplementation"
                        + " 'com.android.tools.test:coverage-agent:1.0.0'\n"
                        + "}");

        // Execute the on-the-fly coverage collection task
        project.executor()
                .with(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
                .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
                .with(BooleanOption.REPORT_AGGREGATION_SUPPORT, true)
                .run("createDebugCoverageReport");

        // Verify that raw binary .pb files were successfully written and pulled to host output
        // directory
        File coverageDir = getCoverageDir();
        List<File> pbFiles = FileUtils.find(coverageDir, Pattern.compile(".*\\.pb"));
        Truth.assertThat(pbFiles.size()).isAtLeast(2);

        // Verify that the reporting task successfully compiled the standardized report.xml
        File reportXml =
                project.file("build/reports/coverage/androidTest/debug/connected/report.xml");
        assertThat(reportXml).exists();

        // Verify XML contents contain app module coverage and the complex parameters class
        String content = Files.readString(reportXml.toPath());
        Truth.assertThat(content).contains("<package name=\"com/example/helloworld\">");
        Truth.assertThat(content)
                .contains("<class name=\"com/example/helloworld/ComplexParamClass\"");
    }

    private File getCoverageDir() {
        if (runWithBuiltInPlatform) {
            return project.file("build/intermediates/test_suite_code_coverage");
        } else {
            return project.file("build/outputs/code_coverage");
        }
    }
}
