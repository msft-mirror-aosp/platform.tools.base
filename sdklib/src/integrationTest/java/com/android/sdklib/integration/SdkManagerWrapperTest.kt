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

class SdkManagerWrapperTest {

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

  private fun getCurrentPlatform(): AndroidSdkCommandLineToolsPlatform {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    return when {
      osName.contains("mac") && (osArch.contains("aarch64") || osArch.contains("arm64")) -> AndroidSdkCommandLineToolsPlatform.MAC_ARM64
      osName.contains("mac") -> AndroidSdkCommandLineToolsPlatform.MAC_X86_64
      osName.contains("win") -> AndroidSdkCommandLineToolsPlatform.WINDOWS
      else -> AndroidSdkCommandLineToolsPlatform.LINUX
    }
  }

  @Before
  fun setUp() {
    Assume.assumeFalse("Skipping POSIX shell script tests on Windows OS", System.getProperty("os.name").lowercase().contains("win"))
    extractedDir = temporaryFolder.newFolder("extracted").toPath()
    val zipFile = getCurrentPlatform().zipFile
    ZipInputStream(Files.newInputStream(zipFile).buffered()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.isDirectory) continue
        val file = extractedDir.resolve(entry.name)
        Files.createDirectories(file.parent)
        Files.copy(zip, file)
        // Java's ZipInputStream does not preserve POSIX file permissions, so we explicitly
        // restore executable permissions on binaries extracted into bin/ for sub-process execution.
        if (entry.name.contains("/bin/")) {
          file.toFile().setExecutable(true)
        }
      }
    }
    sdkManagerBinary = extractedDir.resolve("cmdline-tools/bin/sdkmanager")
  }

  private fun createRecorderScript(): File {
    val recorder = temporaryFolder.newFile("recorder.sh")
    recorder.writeText(
      """
      #!/bin/sh
      [ -n "${'$'}JAVA_TOOL_OPTIONS" ] && echo "JAVA_TOOL_OPTIONS=${'$'}JAVA_TOOL_OPTIONS"
      [ -n "${'$'}HTTP_PROXY" ] && echo "HTTP_PROXY=${'$'}HTTP_PROXY"
      [ -n "${'$'}NO_PROXY" ] && echo "NO_PROXY=${'$'}NO_PROXY"
      echo "$@"
      """
        .trimIndent()
    )
    recorder.setExecutable(true)
    return recorder
  }

  private fun runSdkManager(vararg args: String, env: Map<String, String> = emptyMap()): ProcessResult {
    val outFile = temporaryFolder.newFile()
    val errFile = temporaryFolder.newFile()
    val command = mutableListOf("sh", sdkManagerBinary.toString())
    command.addAll(args)

    val processBuilder = ProcessBuilder(command).redirectOutput(outFile).redirectError(errFile)
    processBuilder.environment().putAll(env)

    val returnCode = processBuilder.start().waitFor()
    return ProcessResult(
      returnCode = returnCode,
      stdout = Files.readAllLines(outFile.toPath()).joinToString("\n"),
      stderr = Files.readAllLines(errFile.toPath()).joinToString("\n"),
    )
  }

  data class ProcessResult(val returnCode: Int, val stdout: String, val stderr: String)

  @Test
  fun testNoArgsShowsHelpAndExits1() {
    val res = runSdkManager()
    assertThat(res.returnCode).isEqualTo(1)
    assertThat(res.stdout).isEmpty()
    assertThat(res.stderr).isEqualTo("$deprecationWarning\n\n$usage")
  }

  @Test
  fun testHelpFlagShowsHelpAndExits0() {
    val res = runSdkManager("--help")
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo(usage)
    assertThat(res.stderr.trim()).isEqualTo(deprecationWarning)
  }

  @Test
  fun testVersionFlagShowsAndroidCliVersion() {
    val recorder = temporaryFolder.newFile("version_recorder.sh")
    recorder.writeText(
      """
      #!/bin/sh
      if [ "$1" = "--version" ]; then
        echo "mock-cli-version-1.2.3"
      else
        echo "unexpected-args: $@"
        exit 1
      fi
      """
        .trimIndent()
    )
    recorder.setExecutable(true)

    val res = runSdkManager("--version", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout.trim()).isEqualTo("mock-cli-version-1.2.3 (Android CLI)")
  }

  @Test
  fun testVersionFlagReturnsUnknownWhenAndroidCliFails() {
    val failingAndroidBin = temporaryFolder.newFile("failing_android.sh")
    failingAndroidBin.writeText(
      """
      #!/bin/sh
      exit 1
      """
        .trimIndent()
    )
    failingAndroidBin.setExecutable(true)

    val siblingAndroid = extractedDir.resolve("cmdline-tools/bin/android")
    if (Files.exists(siblingAndroid)) {
      Files.delete(siblingAndroid)
    }

    val res = runSdkManager("--version", env = mapOf("ANDROID_CLI_BIN" to failingAndroidBin.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout.trim()).isEqualTo("unknown (Android CLI)")
  }

  @Test
  fun testMissingSdkRootShowsLegacyErrorAndExits1() {
    val standaloneDir = temporaryFolder.newFolder("random_tools", "bin").toPath()
    val isolatedBinary = standaloneDir.resolve("sdkmanager")
    sdkManagerBinary.copyTo(isolatedBinary)
    isolatedBinary.toFile().setExecutable(true)

    val outFile = temporaryFolder.newFile()
    val errFile = temporaryFolder.newFile()
    val processBuilder = ProcessBuilder("sh", isolatedBinary.toString(), "--list").redirectOutput(outFile).redirectError(errFile)
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
    val isolatedBinary = binDir.resolve("sdkmanager")
    sdkManagerBinary.copyTo(isolatedBinary)
    isolatedBinary.toFile().setExecutable(true)

    val mockAndroid = binDir.resolve("android")
    mockAndroid
      .toFile()
      .writeText(
        """
        #!/bin/sh
        echo "$@"
        """
          .trimIndent()
      )
    mockAndroid.toFile().setExecutable(true)

    val outFile = temporaryFolder.newFile()
    val errFile = temporaryFolder.newFile()
    val processBuilder = ProcessBuilder("sh", isolatedBinary.toString(), "--list").redirectOutput(outFile).redirectError(errFile)
    processBuilder.environment().remove("ANDROID_HOME")
    processBuilder.environment().remove("ANDROID_CLI_BIN")

    val returnCode = processBuilder.start().waitFor()
    val stdout = outFile.readText().trim()
    assertThat(returnCode).isEqualTo(0)
    assertThat(stdout).isEqualTo("--sdk=${rootDir.toAbsolutePath()} sdk list --all")
  }

  @Test
  fun testUnversionedCmdlineToolsBinDoesNotAutoDetectRoot() {
    val rootDir = temporaryFolder.newFolder("legacy_auto_root_sdk").toPath()
    val binDir = rootDir.resolve("cmdline-tools/bin")
    Files.createDirectories(binDir)
    val isolatedBinary = binDir.resolve("sdkmanager")
    sdkManagerBinary.copyTo(isolatedBinary)
    isolatedBinary.toFile().setExecutable(true)

    val mockAndroid = binDir.resolve("android")
    mockAndroid
      .toFile()
      .writeText(
        """
        #!/bin/sh
        echo "$@"
        """
          .trimIndent()
      )
    mockAndroid.toFile().setExecutable(true)

    val outFile = temporaryFolder.newFile()
    val errFile = temporaryFolder.newFile()
    val processBuilder = ProcessBuilder("sh", isolatedBinary.toString(), "--list").redirectOutput(outFile).redirectError(errFile)
    processBuilder.environment().remove("ANDROID_HOME")
    processBuilder.environment().remove("ANDROID_CLI_BIN")

    val returnCode = processBuilder.start().waitFor()
    val stderr = errFile.readText().trim()
    assertThat(returnCode).isEqualTo(1)
    assertThat(stderr).contains("Error: Could not determine SDK root.")
  }

  @Test
  fun testListCommandTranslation() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--list", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=/fake/sdk sdk list --all")
  }

  @Test
  fun testListInstalledCommandTranslation() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--list_installed", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=/fake/sdk sdk list")
  }

  @Test
  fun testUpdateCommandTranslation() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--update", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=/fake/sdk sdk update")
  }

  @Test
  fun testLicensesCommandOutputsDeprecationWarning() {
    val res = runSdkManager("--licenses")
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr.trim()).contains("Warning: The --licenses option is no longer needed")
    assertThat(res.stdout.trim()).isEmpty()
  }

  @Test
  fun testInstallPackageTranslation() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "platforms;android-34",
        "build-tools;34.0.0",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=/fake/sdk sdk install platforms/android-34 build-tools/34.0.0")
  }

  @Test
  fun testUninstallPackageTranslation() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager("--uninstall", "platforms;android-34", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=/fake/sdk sdk remove platforms/android-34")
  }

  @Test
  fun testChannelFlagTranslation() {
    val recorder = createRecorderScript()
    val betaRes =
      runSdkManager("--channel=1", "platforms;android-34", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(betaRes.stdout).isEqualTo("--sdk=/fake/sdk sdk install --beta platforms/android-34")

    val devRes =
      runSdkManager("--channel=2", "platforms;android-34", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(devRes.stdout).isEqualTo("--sdk=/fake/sdk sdk install --canary platforms/android-34")
    assertThat(devRes.stderr).contains("Warning: Channel 2 (Dev) is not discretely supported; mapping to --canary.")

    val canaryRes =
      runSdkManager("--channel=3", "platforms;android-34", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(canaryRes.stdout).isEqualTo("--sdk=/fake/sdk sdk install --canary platforms/android-34")

    val spaceRes =
      runSdkManager(
        "--channel",
        "1",
        "platforms;android-34",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(spaceRes.stdout).isEqualTo("--sdk=/fake/sdk sdk install --beta platforms/android-34")
  }

  @Test
  fun testPackageFileParsingWithCommentsAndCarriageReturns() {
    val sdkRoot = temporaryFolder.newFolder("package_file_sdk").toPath()
    val recorder = createRecorderScript()
    val pkgFile = temporaryFolder.newFile("packages.txt")
    pkgFile.writeText("# Comment line\r\n" + "platforms;android-34\r\n" + "\r\n" + "build-tools;34.0.0\r\n")

    val resEquals =
      runSdkManager(
        "--package_file=${pkgFile.absolutePath}",
        "--sdk_root=${sdkRoot.toAbsolutePath()}",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(resEquals.returnCode).isEqualTo(0)
    assertThat(resEquals.stdout).isEqualTo("--sdk=${sdkRoot.toAbsolutePath()} sdk install platforms/android-34 build-tools/34.0.0")

    val resSpace =
      runSdkManager(
        "--package_file",
        pkgFile.absolutePath,
        "--sdk_root=${sdkRoot.toAbsolutePath()}",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(resSpace.returnCode).isEqualTo(0)
    assertThat(resSpace.stdout).isEqualTo("--sdk=${sdkRoot.toAbsolutePath()} sdk install platforms/android-34 build-tools/34.0.0")
  }

  @Test
  fun testMultiplePackageFilesCombinedWithExplicitPackages() {
    val recorder = createRecorderScript()
    val file1 = temporaryFolder.newFile("file1.txt")
    file1.writeText("# Platforms\n" + "platforms;android-34\n")
    val file2 = temporaryFolder.newFile("file2.txt")
    file2.writeText("# Build tools\n" + "build-tools;34.0.0\n")

    val res =
      runSdkManager(
        "--package_file=${file1.absolutePath}",
        "--package_file=${file2.absolutePath}",
        "system-images;android-34;default;x86_64",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout)
      .isEqualTo("--sdk=/fake/sdk sdk install platforms/android-34 build-tools/34.0.0 system-images/android-34/default/x86_64")
  }

  @Test
  fun testLegacyFlagsEmitGracefulWarningAndAreIgnored() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--include_obsolete",
        "--newer",
        "--no_https",
        "--verbose",
        "platforms;android-34",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr).contains("Warning: Flag --include_obsolete is no longer supported. Ignoring.")
    assertThat(res.stderr).contains("Warning: Flag --newer is no longer supported. Ignoring.")
    assertThat(res.stderr).contains("Warning: Flag --no_https is no longer supported. Ignoring.")
    assertThat(res.stderr).contains("Warning: Flag --verbose is no longer supported. Ignoring.")
    assertThat(res.stdout).isEqualTo("--sdk=/fake/sdk sdk install platforms/android-34")
  }

  @Test
  fun testProxyFlagsEmitWarning() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--proxy_host=proxy.example.com",
        "--proxy_port=8080",
        "platforms;android-34",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr).contains("Warning: Proxy options are no longer needed; using default system proxy configuration.")
    assertThat(res.stdout).contains("--sdk=/fake/sdk sdk install platforms/android-34")
  }

  @Test
  fun testSocksProxyFlagsEmitWarning() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--proxy=socks",
        "--proxy_host=socks.example.com",
        "--proxy_port=1080",
        "platforms;android-34",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr).contains("Warning: Proxy options are no longer needed; using default system proxy configuration.")
    assertThat(res.stdout).contains("--sdk=/fake/sdk sdk install platforms/android-34")
  }

  @Test
  fun testNoProxyFlagIsHandledWithWarning() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager("--no_proxy", "platforms;android-34", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stderr).contains("Warning: Proxy options are no longer needed; using default system proxy configuration.")
    assertThat(res.stdout).contains("--sdk=/fake/sdk sdk install platforms/android-34")
  }

  @Test
  fun testMultiPackageUninstallCommandTranslation() {
    val recorder = createRecorderScript()
    val res =
      runSdkManager(
        "--uninstall",
        "platforms;android-34",
        "build-tools;34.0.0",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout.trim()).isEqualTo("--sdk=/fake/sdk sdk remove platforms/android-34 build-tools/34.0.0")
  }

  @Test
  fun testSpaceSeparatedSdkRoot() {
    val recorder = createRecorderScript()
    val res = runSdkManager("--sdk_root", "/fake/sdk", "platforms;android-34", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).isEqualTo("--sdk=/fake/sdk sdk install platforms/android-34")
  }

  @Test
  fun testSdkRootWithSpaces() {
    val recorder = createRecorderScript()
    val resEquals =
      runSdkManager(
        "--sdk_root=/fake path/with spaces/sdk",
        "platforms;android-34",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(resEquals.returnCode).isEqualTo(0)
    assertThat(resEquals.stdout).isEqualTo("--sdk=/fake path/with spaces/sdk sdk install platforms/android-34")

    val resSpace =
      runSdkManager(
        "--sdk_root",
        "/fake path/with spaces/sdk",
        "platforms;android-34",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(resSpace.returnCode).isEqualTo(0)
    assertThat(resSpace.stdout).isEqualTo("--sdk=/fake path/with spaces/sdk sdk install platforms/android-34")
  }

  @Test
  fun testChannelZeroAndNamedChannels() {
    val recorder = createRecorderScript()
    val stableRes =
      runSdkManager("--channel=0", "platforms;android-34", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(stableRes.stdout).isEqualTo("--sdk=/fake/sdk sdk install platforms/android-34")

    val betaRes =
      runSdkManager(
        "--channel=beta",
        "platforms;android-34",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(betaRes.stdout).isEqualTo("--sdk=/fake/sdk sdk install --beta platforms/android-34")

    val canaryRes =
      runSdkManager(
        "--channel=canary",
        "platforms;android-34",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(canaryRes.stdout).isEqualTo("--sdk=/fake/sdk sdk install --canary platforms/android-34")
  }

  @Test
  fun testUnrecognizedOptionShowsErrorAndHelpAndExits1() {
    val res = runSdkManager("--custom_unrecognized_flag=foo", "platforms;android-34", "--sdk_root=/fake/sdk")
    assertThat(res.returnCode).isEqualTo(1)
    assertThat(res.stderr).contains("Unknown option: '--custom_unrecognized_flag=foo'")
    assertThat(res.stderr).contains(usage)
    assertThat(res.stdout).isEmpty()
  }

  private fun createDetailedRecorderScript(): File {
    val recorder = temporaryFolder.newFile("detailed_recorder.sh")
    recorder.writeText(
      """
      #!/bin/sh
      for arg in "${'$'}@"; do
        printf '[%s]' "${'$'}arg"
      done
      printf '\n'
      """
        .trimIndent()
    )
    recorder.setExecutable(true)
    return recorder
  }

  @Test
  fun testInstallPackageWithSpaces() {
    val recorder = createDetailedRecorderScript()
    val res =
      runSdkManager(
        "platforms;android-34",
        "build-tools;34.0.0 rc1",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath),
      )
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout.trim()).isEqualTo("[--sdk=/fake/sdk][sdk][install][platforms/android-34][build-tools/34.0.0 rc1]")
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
        "platforms;android-34",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "linux"),
      )
    assertThat(installRes.returnCode).isEqualTo(0)
    assertThat(installRes.stdout).isEqualTo("--sdk=/fake/sdk sdk install --platform=linux_$hostArch platforms/android-34")

    val updateRes =
      runSdkManager(
        "--update",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "macosx"),
      )
    assertThat(updateRes.returnCode).isEqualTo(0)
    assertThat(updateRes.stdout).isEqualTo("--sdk=/fake/sdk sdk update --platform=macosx_$hostArch")

    val updateMacShortRes =
      runSdkManager(
        "--update",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "mac"),
      )
    assertThat(updateMacShortRes.returnCode).isEqualTo(0)
    assertThat(updateMacShortRes.stdout).isEqualTo("--sdk=/fake/sdk sdk update --platform=mac_$hostArch")

    val updateWinRes =
      runSdkManager(
        "--update",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "windows"),
      )
    assertThat(updateWinRes.returnCode).isEqualTo(0)
    assertThat(updateWinRes.stdout).isEqualTo("--sdk=/fake/sdk sdk update --platform=windows_$hostArch")

    val explicitArchRes =
      runSdkManager(
        "--update",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "macosx_arm64"),
      )
    assertThat(explicitArchRes.returnCode).isEqualTo(0)
    assertThat(explicitArchRes.stdout).isEqualTo("--sdk=/fake/sdk sdk update --platform=macosx_arm64")

    val listRes =
      runSdkManager(
        "--list",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "linux"),
      )
    assertThat(listRes.returnCode).isEqualTo(0)
    assertThat(listRes.stdout).isEqualTo("--sdk=/fake/sdk sdk list --all")

    val uninstallRes =
      runSdkManager(
        "--uninstall",
        "platforms;android-34",
        "--sdk_root=/fake/sdk",
        env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath, "REPO_OS_OVERRIDE" to "linux"),
      )
    assertThat(uninstallRes.returnCode).isEqualTo(0)
    assertThat(uninstallRes.stdout).isEqualTo("--sdk=/fake/sdk sdk remove platforms/android-34")
  }

  @Test
  fun testLegacySdkmanagerEnvVarIsSet() {
    val recorder = temporaryFolder.newFile("env_recorder.sh")
    recorder.writeText(
      """
      #!/bin/sh
      echo "LEGACY_SDKMANAGER=${'$'}LEGACY_SDKMANAGER"
      echo "$@"
      """
        .trimIndent()
    )
    recorder.setExecutable(true)

    val res = runSdkManager("--list", "--sdk_root=/fake/sdk", env = mapOf("ANDROID_CLI_BIN" to recorder.absolutePath))
    assertThat(res.returnCode).isEqualTo(0)
    assertThat(res.stdout).contains("LEGACY_SDKMANAGER=1")
  }
}
