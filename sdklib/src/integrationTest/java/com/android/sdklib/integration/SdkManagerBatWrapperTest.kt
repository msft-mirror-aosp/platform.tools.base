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

package com.android.sdklib.integration

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.copyTo
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SdkManagerBatWrapperTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var extractedDir: Path
  private lateinit var sdkManagerBinary: Path

  val deprecationWarning =
    """
    WARNING: The SDK Manager CLI tool (sdkmanager) is deprecated. Android CLI will be used instead.
    The 'android' binary can also be found in the cmdline-tools directory, and 'android sdk' is the replacement for 'sdkmanager'.
    To learn more about the Android CLI and how to use it, see the documentation (https://d.android.com/tools/agents/android-cli)
    """
      .trimIndent()
      .replace("\n", "\r\n")

  val usage =
    """
    Usage:
      sdkmanager [--uninstall] [<common args>] [--package_file=<file>] [<packages>...]
      sdkmanager --update [<common args>]
      sdkmanager --list [<common args>]
      sdkmanager --list_installed [<common args>]
      sdkmanager --version

    With --install (optional), installs or updates packages.
        By default, the listed packages are installed or (if already installed)
        updated to the latest version.
    With --uninstall, uninstall the listed packages.

        <package> is a sdk-style path (e.g. "build-tools/23.0.0" or
                 "platforms/android-23").
        <package-file> is a text file where each line is a sdk-style path
                       of a package to install or uninstall.
        Multiple --package_file arguments may be specified in combination
        with explicit paths.

    With --update, all installed packages are updated to the latest version.

    With --list, all installed and available packages are printed out.

    With --list_installed, all installed packages are printed out.

    With --version, prints the current version of sdkmanager.

    Common Arguments:
        --sdk_root=<sdkRootPath>: Use the specified SDK root instead of the SDK
                                  containing this tool

        --channel=<channelId>: Include packages in channels up to <channelId>.
                               Common channels are:
                               0 (Stable), 1 (Beta), 2 (Dev), and 3 (Canary).

    * If the env var REPO_OS_OVERRIDE is set to "windows",
      "macosx", or "linux", packages will be downloaded for that OS.
    """
      .trimIndent()
      .replace("\n", "\r\n")

  @Before
  fun setUp() {
    Assume.assumeTrue("Skipping Windows batch script tests on non-Windows OS", System.getProperty("os.name").lowercase().contains("win"))
    extractedDir = temporaryFolder.newFolder("extracted").toPath()
    val zipFile = AndroidSdkCommandLineToolsPlatform.WINDOWS.zipFile
    ZipInputStream(Files.newInputStream(zipFile).buffered()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.isDirectory) continue
        val file = extractedDir.resolve(entry.name)
        Files.createDirectories(file.parent)
        Files.copy(zip, file)
      }
    }
    sdkManagerBinary = extractedDir.resolve("cmdline-tools/bin/sdkmanager.bat")
  }

  private fun createRecorderScript(): File {
    val recorder = temporaryFolder.newFile("recorder.bat")
    recorder.writeText(
      """
      @echo off
      if not "%JAVA_TOOL_OPTIONS%"=="" echo JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS%
      if not "%HTTP_PROXY%"=="" echo HTTP_PROXY=%HTTP_PROXY%
      if not "%NO_PROXY%"=="" echo NO_PROXY=%NO_PROXY%
      echo %*
      """
        .trimIndent()
    )
    return recorder
  }

  private data class ProcessResult(val returnCode: Int, val stdout: String, val stderr: String)

  private fun runSdkManager(vararg args: String, env: Map<String, String> = emptyMap()): ProcessResult {
    val outFile = temporaryFolder.newFile()
    val errFile = temporaryFolder.newFile()

    val commandLine = mutableListOf("cmd.exe", "/c", sdkManagerBinary.toString())
    commandLine.addAll(args)

    val pb = ProcessBuilder(commandLine).redirectOutput(outFile).redirectError(errFile)

    pb.environment().remove("ANDROID_HOME")
    pb.environment().remove("ANDROID_CLI_BIN")
    pb.environment().putAll(env)

    val returnCode = pb.start().waitFor()
    val stdout = outFile.readText().trim()
    val stderr = errFile.readText().trim()
    return ProcessResult(returnCode, stdout, stderr)
  }

  @Test
  fun testMissingSdkRootShowsLegacyErrorAndExits1() {
    val standaloneDir = temporaryFolder.newFolder("random_tools", "bin").toPath()
    val isolatedBinary = standaloneDir.resolve("sdkmanager.bat")
    sdkManagerBinary.copyTo(isolatedBinary)

    val outFile = temporaryFolder.newFile()
    val errFile = temporaryFolder.newFile()
    val processBuilder = ProcessBuilder("cmd.exe", "/c", isolatedBinary.toString(), "--list").redirectOutput(outFile).redirectError(errFile)
    processBuilder.environment().remove("ANDROID_HOME")

    val returnCode = processBuilder.start().waitFor()
    val stderr = Files.readAllLines(errFile.toPath()).joinToString("\n")
    assertThat(returnCode).isEqualTo(1)
    assertThat(stderr).contains("Error: Could not determine SDK root.")
    assertThat(stderr).contains("Either specify it explicitly with --sdk_root=")
  }

  @Test
  fun testAutomaticSdkRootDetection() {
    val rootDir = temporaryFolder.newFolder("auto_root_sdk").toPath()
    val binDir = rootDir.resolve("cmdline-tools/latest/bin")
    Files.createDirectories(binDir)
    val isolatedBinary = binDir.resolve("sdkmanager.bat")
    sdkManagerBinary.copyTo(isolatedBinary)

    val mockAndroid = binDir.resolve("android.bat")
    mockAndroid
      .toFile()
      .writeText(
        """
        @echo off
        echo %*
        """
          .trimIndent()
      )

    val outFile = temporaryFolder.newFile()
    val errFile = temporaryFolder.newFile()
    val processBuilder = ProcessBuilder("cmd.exe", "/c", isolatedBinary.toString(), "--list").redirectOutput(outFile).redirectError(errFile)
    processBuilder.environment().remove("ANDROID_HOME")
    processBuilder.environment().remove("ANDROID_CLI_BIN")

    val returnCode = processBuilder.start().waitFor()
    val stdout = outFile.readText().trim()
    assertThat(returnCode).isEqualTo(0)
    assertThat(stdout).isEqualTo("--sdk=\"${rootDir.toAbsolutePath()}\" sdk list --all")
  }

  @Test
  fun testUnversionedCmdlineToolsBinDoesNotAutoDetectRoot() {
    val rootDir = temporaryFolder.newFolder("legacy_auto_root_sdk").toPath()
    val binDir = rootDir.resolve("cmdline-tools/bin")
    Files.createDirectories(binDir)
    val isolatedBinary = binDir.resolve("sdkmanager.bat")
    sdkManagerBinary.copyTo(isolatedBinary)

    val mockAndroid = binDir.resolve("android.bat")
    mockAndroid
      .toFile()
      .writeText(
        """
        @echo off
        echo %*
        """
          .trimIndent()
      )

    val outFile = temporaryFolder.newFile()
    val errFile = temporaryFolder.newFile()
    val processBuilder = ProcessBuilder("cmd.exe", "/c", isolatedBinary.toString(), "--list").redirectOutput(outFile).redirectError(errFile)
    processBuilder.environment().remove("ANDROID_HOME")
    processBuilder.environment().remove("ANDROID_CLI_BIN")

    val returnCode = processBuilder.start().waitFor()
    val stderr = errFile.readText().trim()
    assertThat(returnCode).isEqualTo(1)
    assertThat(stderr).contains("Error: Could not determine SDK root.")
  }

  @Test
  fun testHelpFlagShowsUsageAndExitsCleanly() {
    val res = runSdkManager("--help")
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo(usage)
    assertThat(res.stderr).isEqualTo(deprecationWarning)
  }

  @Test
  fun testVersionFlagShowsAndroidCliVersion() {
    val recorder = temporaryFolder.newFile("version_recorder.bat")
    recorder.writeText(
      """
      @echo off
      if "%~1"=="--version" (
        echo mock-cli-version-1.2.3
      ) else (
        echo unexpected-args: %*
        exit /b 1
      )
      """
        .trimIndent()
    )

    val res = runSdkManager("--version", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout.trim()).isEqualTo("mock-cli-version-1.2.3 (Android CLI)")
    assertThat(res.stderr).isEqualTo(deprecationWarning)
  }

  @Test
  fun testVersionFlagWithSpacesInAndroidCliBinPath() {
    val spaceDir = temporaryFolder.newFolder("CLI Path With Spaces")
    val recorder = File(spaceDir, "version_recorder.bat")
    recorder.writeText(
      """
      @echo off
      if "%~1"=="--version" (
        echo mock-cli-version-spaces-4.5.6
      ) else (
        echo unexpected-args: %*
        exit /b 1
      )
      """
        .trimIndent()
    )

    val res = runSdkManager("--version", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout.trim()).isEqualTo("mock-cli-version-spaces-4.5.6 (Android CLI)")
    assertThat(res.stderr).isEqualTo(deprecationWarning)
  }

  @Test
  fun testVersionFlagWithSurroundingQuotesInAndroidCliBin() {
    val recorder = temporaryFolder.newFile("quoted_env_version_recorder.bat")
    recorder.writeText(
      """
      @echo off
      if "%~1"=="--version" (
        echo mock-cli-version-quoted-7.8.9
      ) else (
        echo unexpected-args: %*
        exit /b 1
      )
      """
        .trimIndent()
    )

    val res = runSdkManager("--version", env = mapOf("ANDROID_CLI_BIN" to "\"${recorder.absolutePath}\""))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout.trim()).isEqualTo("mock-cli-version-quoted-7.8.9 (Android CLI)")
    assertThat(res.stderr).isEqualTo(deprecationWarning)
  }

  @Test
  fun testInstallCommandWithSurroundingQuotesInAndroidCliBin() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager("\"platforms;android-34\"", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to "\"${recorder.absolutePath}\""))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk install \"platforms/android-34\"")
  }

  @Test
  fun testVersionFlagReturnsUnknownWhenAndroidCliFails() {
    val failingAndroidBin = temporaryFolder.newFile("failing_android.bat")
    failingAndroidBin.writeText("@exit /b 1")

    val siblingAndroidBat = extractedDir.resolve("cmdline-tools/bin/android.bat")
    if (Files.exists(siblingAndroidBat)) {
      Files.delete(siblingAndroidBat)
    }
    val siblingAndroidExe = extractedDir.resolve("cmdline-tools/bin/android.exe")
    if (Files.exists(siblingAndroidExe)) {
      Files.delete(siblingAndroidExe)
    }

    val res = runSdkManager("--version", env = mapOf("ANDROID_CLI_BIN" to failingAndroidBin.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout.trim()).isEqualTo("unknown (Android CLI)")
    assertThat(res.stderr).isEqualTo(deprecationWarning)
  }

  @Test
  fun testNoArgumentsShowsHelpAndExitsWithError() {
    val res = runSdkManager()
    assertThat(res.returnCode).isEqualTo(1)
    assertThat(res.stderr).isEqualTo("$deprecationWarning\r\n\r\n$usage")
  }

  @Test
  fun testInstallCommandTranslation() {
    val recorder = createRecorderScript()
    val res = runSdkManager("\"platforms;android-34\"", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk install \"platforms/android-34\"")
  }

  @Test
  fun testUninstallCommandTranslation() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--uninstall",
        "\"platforms;android-34\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk remove \"platforms/android-34\"")
  }

  @Test
  fun testUpdateCommandTranslation() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--update", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk update")
  }

  @Test
  fun testListCommandTranslation() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--list", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk list --all")
  }

  @Test
  fun testListInstalledCommandTranslation() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--list_installed", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk list")
  }

  @Test
  fun testLicensesCommandOutputsDeprecationWarning() {
    val res = runSdkManager("--licenses")
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr.trim()).contains("Warning: The --licenses option is no longer needed")
    assertThat(res.stdout.trim()).isEmpty()
  }

  @Test
  fun testSdkRootExplicitFlag() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--list", "--sdk_root=/custom/sdk/path", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/custom/sdk/path\" sdk list --all")
  }

  @Test
  fun testPackageFileParsingWithCommentsAndCarriageReturns() {
    val recorder = createRecorderScript()
    val pkgFile = temporaryFolder.newFile("packages.txt")
    pkgFile.writeText("# This is a comment\r\n" + "platforms;android-34\r\n" + "\r\n" + "build-tools;34.0.0\n")

    val res =
      runSdkManager(
        "--package_file=${pkgFile.absolutePath}",
        "--sdk_root=\"/custom/sdk/path\"",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/custom/sdk/path\" sdk install \"platforms/android-34\" \"build-tools/34.0.0\"")
  }

  @Test
  fun testMultiplePackageFilesAndExplicitPackagesCombined() {
    val recorder = createRecorderScript()
    val file1 = temporaryFolder.newFile("f1.txt")
    file1.writeText("platforms;android-33\n")
    val file2 = temporaryFolder.newFile("f2.txt")
    file2.writeText("build-tools;33.0.0\n")

    val res =
      runSdkManager(
        "--package_file=${file1.absolutePath}",
        "\"platforms;android-34\"",
        "--package_file=${file2.absolutePath}",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout)
      .isEqualTo("--sdk=\"/fake/sdk\" sdk install \"platforms/android-33\" \"platforms/android-34\" \"build-tools/33.0.0\"")
  }

  @Test
  fun testChannelFlags() {
    val recorder = createRecorderScript()

    val resBeta = runSdkManager("--channel=1", "--list", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(resBeta.returnCode).isEqualTo(0)
    assertThat(resBeta.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk list --all --beta")

    val resDev = runSdkManager("--channel=2", "--list", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(resDev.returnCode).isEqualTo(0)
    assertThat(resDev.stderr).contains("Warning: Channel 2 (Dev) is not discretely supported; mapping to --canary.")
    assertThat(resDev.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk list --all --canary")

    val resCanary = runSdkManager("--channel=3", "--list", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(resCanary.returnCode).isEqualTo(0)
    assertThat(resCanary.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk list --all --canary")
  }

  @Test
  fun testLegacyFlagsEmitWarningAndAreIgnored() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--include_obsolete",
        "--newer",
        "--no_https",
        "--verbose",
        "--list",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr).contains("Warning: Flag --include_obsolete is no longer supported. Ignoring.")
    assertThat(res.stderr).contains("Warning: Flag --newer is no longer supported. Ignoring.")
    assertThat(res.stderr).contains("Warning: Flag --no_https is no longer supported. Ignoring.")
    assertThat(res.stderr).contains("Warning: Flag --verbose is no longer supported. Ignoring.")
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk list --all")
  }

  @Test
  fun testProxyFlagsEmitWarning() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--proxy=http",
        "--proxy_host=proxy.example.com",
        "--proxy_port=8080",
        "\"platforms;android-34\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr).contains("Warning: Proxy options are no longer needed; using default system proxy configuration.")
    assertThat(res.stdout).contains("--sdk=\"/fake/sdk\" sdk install \"platforms/android-34\"")
  }

  @Test
  fun testSocksProxyFlagsEmitWarning() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--proxy=socks",
        "--proxy_host=socks.example.com",
        "--proxy_port=1080",
        "\"platforms;android-34\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr).contains("Warning: Proxy options are no longer needed; using default system proxy configuration.")
    assertThat(res.stdout).contains("--sdk=\"/fake/sdk\" sdk install \"platforms/android-34\"")
  }

  @Test
  fun testNoProxyFlagIsHandledWithWarning() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--no_proxy",
        "\"platforms;android-34\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr).contains("Warning: Proxy options are no longer needed; using default system proxy configuration.")
    assertThat(res.stdout).contains("--sdk=\"/fake/sdk\" sdk install \"platforms/android-34\"")
  }

  @Test
  fun testMultiPackageUninstallCommandTranslation() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--uninstall",
        "\"platforms;android-34\"",
        "\"build-tools;34.0.0\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout.trim()).isEqualTo("--sdk=\"/fake/sdk\" sdk remove \"platforms/android-34\" \"build-tools/34.0.0\"")
  }

  @Test
  fun testSpaceSeparatedSdkRoot() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--sdk_root", "/fake/sdk", "--list", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk list --all")
  }

  @Test
  fun testSpaceSeparatedChannel() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--channel", "beta", "--list", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk list --all --beta")
  }

  @Test
  fun testUnrecognizedOptionShowsErrorAndHelpAndExits1() {
    val res = runSdkManager("\"--custom_unrecognized_flag=foo\"", "\"platforms;android-34\"", "--sdk_root=/fake/sdk")
    assertThat(res.returnCode).isEqualTo(1)
    assertThat(res.stderr).contains("Unknown option: '--custom_unrecognized_flag=foo'")
    assertThat(res.stderr).contains("Usage:")
  }

  @Test
  fun testSdkRootWithSemicolonPreserved() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "\"--sdk_root=C:\\fake;sdk\\path\"",
        "\"platforms;android-34\"",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"C:\\fake;sdk\\path\" sdk install \"platforms/android-34\"")
  }

  @Test
  fun testProxyHostWithSemicolonPreserved() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--proxy=http",
        "\"--proxy_host=my;proxy;host.com\"",
        "--proxy_port=8080",
        "\"platforms;android-34\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr).contains("Warning: Proxy options are no longer needed; using default system proxy configuration.")
    assertThat(res.stdout).contains("--sdk=\"/fake/sdk\" sdk install \"platforms/android-34\"")
  }

  @Test
  fun testSdkRootWithSpacesInQuotes() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--sdk_root=C:\\My SDK", "\"platforms;android-34\"", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"C:\\My SDK\" sdk install \"platforms/android-34\"")
  }

  @Test
  fun testInstallPackageWithSpaces() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "C:\\My Local Packages\\platform-34",
        "\"build-tools;34.0.0\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk install \"C:\\My Local Packages\\platform-34\" \"build-tools/34.0.0\"")
  }

  @Test
  fun testUninstallPackageWithSpaces() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--uninstall",
        "C:\\My Local Packages\\platform-34",
        "\"build-tools;34.0.0\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).contains("--sdk=\"/fake/sdk\" sdk remove \"C:\\My Local Packages\\platform-34\" \"build-tools/34.0.0\"")
  }

  @Test
  fun testRepoOsOverrideSetsPlatformFlagForInstallAndUpdateOnly() {
    val recorder = createRecorderScript()
    val hostArch =
      when (System.getProperty("os.arch")) {
        "aarch64",
        "arm64" -> "arm64"
        else -> "x86_64"
      }

    val installRes =
      runSdkManager(
        "\"platforms;android-34\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "linux"),
      )
    assertThat(installRes.returnCode).isEqualTo(0)
    assertThat(installRes.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk install --platform=\"linux_$hostArch\" \"platforms/android-34\"")

    val updateRes =
      runSdkManager(
        "--update",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "macosx"),
      )
    assertThat(updateRes.returnCode).isEqualTo(0)
    assertThat(updateRes.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk update --platform=\"macosx_$hostArch\"")

    val updateMacShortRes =
      runSdkManager(
        "--update",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "mac"),
      )
    assertThat(updateMacShortRes.returnCode).isEqualTo(0)
    assertThat(updateMacShortRes.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk update --platform=\"mac_$hostArch\"")

    val updateWinRes =
      runSdkManager(
        "--update",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "windows"),
      )
    assertThat(updateWinRes.returnCode).isEqualTo(0)
    assertThat(updateWinRes.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk update --platform=\"windows_$hostArch\"")

    val explicitArchRes =
      runSdkManager(
        "--update",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "macosx_arm64"),
      )
    assertThat(explicitArchRes.returnCode).isEqualTo(0)
    assertThat(explicitArchRes.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk update --platform=\"macosx_arm64\"")

    val listRes =
      runSdkManager(
        "--list",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "linux"),
      )
    assertThat(listRes.returnCode).isEqualTo(0)
    assertThat(listRes.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk list --all")

    val uninstallRes =
      runSdkManager(
        "--uninstall",
        "\"platforms;android-34\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "linux"),
      )
    assertThat(uninstallRes.returnCode).isEqualTo(0)
    assertThat(uninstallRes.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk remove \"platforms/android-34\"")
  }

  @Test
  fun testRepoOsOverrideWithSurroundingQuotes() {
    val recorder = createRecorderScript()
    val hostArch =
      when (System.getProperty("os.arch")) {
        "aarch64",
        "arm64" -> "arm64"
        else -> "x86_64"
      }

    val installRes =
      runSdkManager(
        "\"platforms;android-34\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "\"linux\""),
      )
    assertThat(installRes.returnCode).isEqualTo(0)
    assertThat(installRes.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk install --platform=\"linux_$hostArch\" \"platforms/android-34\"")

    val updateRes =
      runSdkManager(
        "--update",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "\"macosx_arm64\""),
      )
    assertThat(updateRes.returnCode).isEqualTo(0)
    assertThat(updateRes.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk update --platform=\"macosx_arm64\"")
  }

  @Test
  fun testRepoOsOverrideEmptyStringDoesNotAddPlatformFlag() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "\"platforms;android-34\"",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "\"\""),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk install \"platforms/android-34\"")
  }

  @Test
  fun testPackageFileWithMetacharactersDoesNotExecuteInjectedCommands() {
    val recorder = createRecorderScript()
    val markerFile = temporaryFolder.root.toPath().resolve("pwned_file.txt")
    val pkgFile = temporaryFolder.newFile("malicious_packages.txt")
    pkgFile.writeText("platforms;android-34 & echo pwned > \"${markerFile.toAbsolutePath()}\"\r\n")

    runSdkManager("--package_file=${pkgFile.absolutePath}", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))

    assertThat(Files.exists(markerFile)).isFalse()
  }

  @Test
  fun testCommandLineArgumentWithMetacharactersDoesNotInjectCommands() {
    val recorder = createRecorderScript()
    val markerFile = temporaryFolder.root.toPath().resolve("pwned_arg.txt")

    runSdkManager(
      "\"platforms;android-34 & echo pwned > \\\"${markerFile.toAbsolutePath()}\\\"\"",
      "--sdk_root=/fake/sdk",
      env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
    )

    assertThat(Files.exists(markerFile)).isFalse()
  }

  @Test
  fun testPackageFileWithQuotedPackageWithSpaces() {
    val recorder = createRecorderScript()
    val pkgFile = temporaryFolder.newFile("quoted_packages.txt")
    pkgFile.writeText("\"C:\\My Local Packages\\platform-34\"\r\nbuild-tools;34.0.0\r\n")

    val res =
      runSdkManager(
        "--package_file=${pkgFile.absolutePath}",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )

    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk install \"C:\\My Local Packages\\platform-34\" \"build-tools/34.0.0\"")
  }

  @Test
  fun testPackageWithMetacharactersWithoutSpacesIsQuoted() {
    val recorder = createRecorderScript()
    val pkgFile = temporaryFolder.newFile("meta_packages.txt")
    pkgFile.writeText("platforms;android-34&foo\r\n")

    val res =
      runSdkManager(
        "--package_file=${pkgFile.absolutePath}",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )

    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk install \"platforms/android-34&foo\"")
  }

  @Test
  fun testMissingPackageFilePrintsErrorAndExits1() {
    val res = runSdkManager("--package_file=non_existent_packages.txt", "--sdk_root=/fake/sdk")
    assertThat(res.returnCode).isEqualTo(1)
    assertThat(res.stderr).contains("Error: Package file not found: 'non_existent_packages.txt'")
  }

  @Test
  fun testPackageWithParenthesesCommasOrEqualsIsQuoted() {
    val recorder = createRecorderScript()
    val pkgFile = temporaryFolder.newFile("delim_packages.txt")
    pkgFile.writeText("platforms;android-34(1)\r\npackage,1\r\npackage=2\r\n")

    val res =
      runSdkManager(
        "--package_file=${pkgFile.absolutePath}",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )

    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=\"/fake/sdk\" sdk install \"platforms/android-34(1)\" \"package,1\" \"package=2\"")
  }
}
