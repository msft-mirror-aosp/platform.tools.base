/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.BooleanOption
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import org.gradle.tooling.BuildException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class JacocoWithUnitTestReportTest(private val isJacocoPluginAppliedFromBuildFile: Boolean) {

  @get:Rule val testProject = GradleTestProjectBuilder().fromTestProject("unitTesting").create()

  companion object {

    @JvmStatic @Parameterized.Parameters(name = "isJacocoPluginAppliedFromBuildFile_{0}") fun params() = arrayOf(true, false)
  }

  @Before
  fun setup() {
    if (isJacocoPluginAppliedFromBuildFile) {
      TestFileUtils.appendToFile(testProject.buildFile, "apply plugin: 'jacoco'\n")
    }

    // Only UnitTest coverage is needed for these tests, but validate that classes are not
    // instrumented twice when AndroidTest coverage is enabled: b/281266702
    TestFileUtils.appendToFile(
      testProject.buildFile,
      "android.buildTypes.debug.enableUnitTestCoverage = true\n" + "android.buildTypes.debug.enableAndroidTestCoverage = true",
    )
  }

  @Test
  fun `test expected report contents`() {
    val run = testProject.executor().run("createDebugUnitTestCoverageReport")
    checkHighlightedSourceCodeReportFiles(testProject.buildDir)
    val reportDir = FileUtils.join(testProject.buildDir, "reports", "coverage", "test", "debug")
    val generatedCoverageReport = File(reportDir, "index.html")
    run.stdout.use { assertThat(ScannerSubject.assertThat(it).contains("View coverage report at ${generatedCoverageReport.toURI()}")) }
    assertThat(generatedCoverageReport.exists()).isTrue()
    val generatedCoverageReportHTML = generatedCoverageReport.readLines().joinToString("\n")
    val reportTitle = Regex("<span class=\"el_report\">(.*?)</span").find(generatedCoverageReportHTML)
    val totalCoverageMetricsContents = Regex("<tfoot>(.*?)</tfoot>").find(generatedCoverageReportHTML)
    val totalCoverageInfo = Regex("<td class=\"ctr2\">(.*?)</td>").find(totalCoverageMetricsContents?.groups?.first()!!.value)
    val totalUnitTestCoveragePercentage = totalCoverageInfo!!.groups[1]!!.value
    // Checks if the report title is expected.
    assertThat(reportTitle!!.groups[1]!!.value).isEqualTo("debug")
    // Checks if the total line coverage on unit tests exceeds 0% i.e
    assertThat(totalUnitTestCoveragePercentage.trimEnd('%').toInt() > 0).isTrue()

    // Verify XML report is generated
    assertThat(File(reportDir, "report.xml").exists()).isTrue()

    // Verify that only the debug reports have been created.
    val testReports = FileUtils.join(testProject.buildDir, "reports", "tests")
    assertThat(testReports.listFiles().map(File::getName)).containsExactly("testDebugUnitTest")
  }

  @Test
  fun `test expected report contents with aggregation enabled`() {
    val run = testProject.executor().with(BooleanOption.REPORT_AGGREGATION_SUPPORT, true).run("createDebugUnitTestCoverageReport")

    val reportDir = FileUtils.join(testProject.buildDir, "reports", "coverage", "test", "debug")
    val generatedCoverageReport = File(reportDir, "index.html")

    run.stdout.use { assertThat(ScannerSubject.assertThat(it).contains("View coverage report at ${generatedCoverageReport.toURI()}")) }

    assertThat(generatedCoverageReport.exists()).isTrue()

    // Verify XML report is generated
    assertThat(File(reportDir, "report.xml").exists()).isTrue()

    // Verify new format files
    assertThat(File(reportDir, "data/report-data.js").exists()).isTrue()
    assertThat(File(reportDir, "css/style.css").exists()).isTrue()
    assertThat(File(reportDir, "javascript/codecoveragescript.js").exists()).isTrue()
    assertThat(File(reportDir, "javascript/sourceviewscript.js").exists()).isTrue()

    // Verify source files are generated
    val sourceFilesDir = File(reportDir, "sourcefiles")
    assertThat(sourceFilesDir.exists()).isTrue()
    // It should contain something like com.android.tests/MainActivity.java.json.js
    // In unitTesting project, we have MainActivity.java and someKotlinCode.kt
    val expectedSourceJson = sourceFilesDir.walk().filter { it.extension == "js" }.toList()
    assertThat(expectedSourceJson.map { it.name }).containsAtLeast("MainActivity.java.json.js", "someKotlinCode.kt.json.js")

    val report = parseReportJs<TestCoverageReport>(File(reportDir, "data/report-data.js"))

    assertThat(report.name).isEqualTo("unitTesting")
    assertThat(report.timeStamp).isNotEmpty()
    assertThat(report.numberOfTestSuites).isEqualTo(1)
    assertThat(report.numberOfModules).isEqualTo(1)
    assertThat(report.numberOfPackages).isEqualTo(1)
    assertThat(report.numberOfClasses).isEqualTo(3)

    // Top-level testSuiteCoverages
    assertThat(report.testSuiteCoverages).hasSize(1)
    val topSuite = report.testSuiteCoverages.single { it.name == "UnitTest" }
    assertThat(topSuite.variantCoverages).hasSize(1)
    verifyVariantCoverage(
      topSuite.variantCoverages.single { it.name == "debug" },
      instPercent = 88,
      instCovered = 23,
      instTotal = 26,
      branchPercent = 0,
    )

    // Modules
    assertThat(report.modules).hasSize(1)
    val appModule = report.modules.single { it.name == ":" }

    // Module testSuiteCoverages
    assertThat(appModule.testSuiteCoverages).hasSize(1)
    val moduleSuite = appModule.testSuiteCoverages.single { it.name == "UnitTest" }
    verifyVariantCoverage(
      moduleSuite.variantCoverages.single { it.name == "debug" },
      instPercent = 88,
      instCovered = 23,
      instTotal = 26,
      branchPercent = null,
    )

    // Packages
    assertThat(appModule.packages).hasSize(1)
    val appPackage = appModule.packages.single { it.name == "com.android.tests" }
    assertThat(appPackage.moduleName).isEqualTo(":")

    // Package testSuiteCoverages
    assertThat(appPackage.testSuiteCoverages).hasSize(1)
    val packageSuite = appPackage.testSuiteCoverages.single { it.name == "UnitTest" }
    verifyVariantCoverage(
      packageSuite.variantCoverages.single { it.name == "debug" },
      instPercent = 88,
      instCovered = 23,
      instTotal = 26,
      branchPercent = null,
    )

    // Classes
    assertThat(appPackage.classes).hasSize(3)

    // Foo Class
    val fooClass = appPackage.classes.single { it.name == "Foo" }
    assertThat(fooClass.packageName).isEqualTo("com.android.tests")
    assertThat(fooClass.sourceFileName).isEqualTo("Foo.java")
    assertThat(fooClass.testSuiteCoverages).hasSize(1)
    val fooSuite = fooClass.testSuiteCoverages.single { it.name == "UnitTest" }
    verifyVariantCoverage(
      fooSuite.variantCoverages.single { it.name == "debug" },
      instPercent = 100,
      instCovered = 5,
      instTotal = 5,
      branchPercent = null,
    )
    assertThat(fooClass.variantSourceFilePaths)
      .containsExactly(TestVariantSourceFilePath("debug", "src/main/java/com.android.tests/Foo.java"))
    assertThat(fooClass.methods).hasSize(2)
    val fooInitMethod = fooClass.methods.single { it.name == "<init>()V" }
    assertThat(fooInitMethod.variantLineNumbers).containsExactly(TestVariantLineNumber("debug", 3))
    val fooFooMethod = fooClass.methods.single { it.name == "foo()Ljava/lang/String;" }
    assertThat(fooFooMethod.variantLineNumbers).containsExactly(TestVariantLineNumber("debug", 5))

    // MainActivity Class
    val mainActivityClass = appPackage.classes.single { it.name == "MainActivity" }
    assertThat(mainActivityClass.packageName).isEqualTo("com.android.tests")
    assertThat(mainActivityClass.sourceFileName).isEqualTo("MainActivity.java")
    assertThat(mainActivityClass.testSuiteCoverages).hasSize(1)
    val mainSuite = mainActivityClass.testSuiteCoverages.single { it.name == "UnitTest" }
    verifyVariantCoverage(
      mainSuite.variantCoverages.single { it.name == "debug" },
      instPercent = 0,
      instCovered = 0,
      instTotal = 3,
      branchPercent = null,
    )
    assertThat(mainActivityClass.variantSourceFilePaths)
      .containsExactly(TestVariantSourceFilePath("debug", "src/main/java/com.android.tests/MainActivity.java"))
    assertThat(mainActivityClass.methods).hasSize(1)
    val mainInitMethod = mainActivityClass.methods.single { it.name == "<init>()V" }
    assertThat(mainInitMethod.variantLineNumbers).containsExactly(TestVariantLineNumber("debug", 5))

    // KotlinDataClass Class
    val kotlinDataClass = appPackage.classes.single { it.name == "KotlinDataClass" }
    assertThat(kotlinDataClass.packageName).isEqualTo("com.android.tests")
    assertThat(kotlinDataClass.sourceFileName).isEqualTo("someKotlinCode.kt")
    assertThat(kotlinDataClass.testSuiteCoverages).hasSize(1)
    val kotlinSuite = kotlinDataClass.testSuiteCoverages.single { it.name == "UnitTest" }
    verifyVariantCoverage(
      kotlinSuite.variantCoverages.single { it.name == "debug" },
      instPercent = 100,
      instCovered = 18,
      instTotal = 18,
      branchPercent = null,
    )
    assertThat(kotlinDataClass.variantSourceFilePaths)
      .containsExactly(TestVariantSourceFilePath("debug", "src/main/java/com.android.tests/someKotlinCode.kt"))
    assertThat(kotlinDataClass.methods).hasSize(3)
    val kotlinInitMethod = kotlinDataClass.methods.single { it.name == "<init>(Ljava/lang/String;)V" }
    assertThat(kotlinInitMethod.variantLineNumbers).containsExactly(TestVariantLineNumber("debug", 19))
    val kotlinInitMarkerMethod =
      kotlinDataClass.methods.single { it.name == "<init>(Ljava/lang/String;ILkotlin/jvm/internal/DefaultConstructorMarker;)V" }
    assertThat(kotlinInitMarkerMethod.variantLineNumbers).containsExactly(TestVariantLineNumber("debug", 19))
    val kotlinGetNameMethod = kotlinDataClass.methods.single { it.name == "getName()Ljava/lang/String;" }
    assertThat(kotlinGetNameMethod.variantLineNumbers).containsExactly(TestVariantLineNumber("debug", 19))
  }

  // Regression test for b/188953818.
  private fun checkHighlightedSourceCodeReportFiles(buildDir: File) {
    val reportPackageInfoDir = FileUtils.join(buildDir, "reports", "coverage", "test", "debug", "com.android.tests")
    assertThat(FileUtils.join(reportPackageInfoDir, "MainActivity.java.html").exists()).isTrue()
    assertThat(FileUtils.join(reportPackageInfoDir, "someKotlinCode.kt.html").exists()).isTrue()
  }

  @Test(expected = BuildException::class)
  fun `report not generated for build types with unit test coverage disabled`() {
    // Build fails as the code coverage report task has not been registered as there is no
    // code coverage data for the release build type.
    testProject.execute("createReleaseUnitTestCoverageReport")
  }

  private inline fun <reified T> parseReportJs(file: File): T {
    val text = file.readText().trim()
    assertThat(text).startsWith("const fullReport = ")
    assertThat(text).endsWith(";")

    val json = text.removePrefix("const fullReport = ").trim().removeSuffix(";").trim()
    return Gson().fromJson(json, T::class.java)
  }

  private fun verifyVariantCoverage(
    variant: TestVariantCoverage,
    variantName: String = "debug",
    instPercent: Int,
    instCovered: Int,
    instTotal: Int,
    branchPercent: Int? = 0,
    branchCovered: Int = 0,
    branchTotal: Int = 0,
  ) {
    assertThat(variant.name).isEqualTo(variantName)
    assertThat(variant.instruction.percent).isEqualTo(instPercent)
    assertThat(variant.instruction.covered).isEqualTo(instCovered)
    assertThat(variant.instruction.total).isEqualTo(instTotal)
    assertThat(variant.branch.percent).isEqualTo(branchPercent)
    assertThat(variant.branch.covered).isEqualTo(branchCovered)
    assertThat(variant.branch.total).isEqualTo(branchTotal)
  }

  // --- Data classes for parsing JSON from report files ---

  private data class TestCoverageReport(
    val name: String,
    val timeStamp: String,
    val modules: List<TestModuleReport>,
    val testSuiteCoverages: List<TestSuiteCoverageReport>,
    @SerializedName("numberOfTestsSuites") val numberOfTestSuites: Int,
    val numberOfModules: Int,
    val numberOfPackages: Int,
    val numberOfClasses: Int,
  )

  private data class TestModuleReport(
    val name: String,
    val testSuiteCoverages: List<TestSuiteCoverageReport>,
    val packages: List<TestPackageReport>,
  )

  private data class TestSuiteCoverageReport(val name: String, val variantCoverages: List<TestVariantCoverage>)

  private data class TestPackageReport(
    val name: String,
    val moduleName: String,
    val testSuiteCoverages: List<TestSuiteCoverageReport>,
    val classes: List<TestClassReport>,
  )

  private data class TestClassReport(
    val name: String,
    val packageName: String,
    val sourceFileName: String,
    val testSuiteCoverages: List<TestSuiteCoverageReport>,
    val variantSourceFilePaths: List<TestVariantSourceFilePath>,
    val methods: List<TestMethodReport>,
  )

  private data class TestVariantSourceFilePath(val variantName: String, val path: String)

  private data class TestMethodReport(val name: String, val variantLineNumbers: List<TestVariantLineNumber>)

  private data class TestVariantLineNumber(val variantName: String, val lineNumber: Int)

  private data class TestVariantCoverage(val name: String, val instruction: TestCoverageInfo, val branch: TestCoverageInfo)

  private data class TestCoverageInfo(val percent: Int?, val covered: Int, val total: Int)
}
