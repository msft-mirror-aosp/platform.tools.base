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

package com.android.tools.uicheck.checks

import com.android.testutils.OsType
import com.android.tools.uicheck.ProjectSourceFile
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ProjectSourceFilesTest {

  @get:Rule val tempDir = TemporaryFolder()

  /** Returns the bytes of a small class named [internalClassName] that was compiled from [sourceFile]. */
  private fun classBytes(internalClassName: String, sourceFile: String?): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalClassName, null, "java/lang/Object", null)
    if (sourceFile != null) {
      writer.visitSource(sourceFile, null)
    }
    writer.visitEnd()
    return writer.toByteArray()
  }

  /** Writes [bytes] to [relativePath] under [root] and returns the file. */
  private fun writeClassFile(root: File, relativePath: String, bytes: ByteArray): File {
    val file = File(root, relativePath)
    file.parentFile.mkdirs()
    file.writeBytes(bytes)
    return file
  }

  /** Creates a source file at [relativePath] under [root] and returns its path. */
  private fun createSourceFile(root: File, relativePath: String): String {
    val file = File(root, relativePath)
    file.parentFile.mkdirs()
    file.writeText("// source\n")
    return file.absoluteFile.normalize().path
  }

  /** Verifies that classes are read from a directory and a JAR. With no source folders, each path is just the file name. */
  @Test
  fun extractsSourceFilesFromClassDirectoriesAndJarsWithoutSourceFolders() {
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/home/HomeScreenKt.class", classBytes("com/example/home/HomeScreenKt", "HomeScreen.kt"))
    writeClassFile(
      classesDir,
      "com/example/home/HomeScreenKt\$Inner.class",
      classBytes("com/example/home/HomeScreenKt\$Inner", "HomeScreen.kt"),
    )
    writeClassFile(classesDir, "RootPreviewKt.class", classBytes("RootPreviewKt", "RootPreview.kt"))
    writeClassFile(classesDir, "com/example/NoSource.class", classBytes("com/example/NoSource", null))

    val jarFile = tempDir.newFile("module.jar")
    JarOutputStream(jarFile.outputStream()).use { jar ->
      jar.putNextEntry(JarEntry("com/example/card/CardKt.class"))
      jar.write(classBytes("com/example/card/CardKt", "Card.kt"))
      jar.closeEntry()
    }

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path, jarFile.path, "", "/missing/classes"), emptyList())
    assertEquals(
      setOf(
        ProjectSourceFile("HomeScreen.kt", "com.example.home"),
        ProjectSourceFile("RootPreview.kt", ""),
        ProjectSourceFile("Card.kt", "com.example.card"),
      ),
      entries.toSet(),
    )
    assertTrue(ProjectSourceFiles.fromClassPath(emptyList(), listOf(classesDir.path)).isEmpty())
  }

  /** Verifies the main cases: one file name in several packages, Kotlin package directories left out, and one file in two source sets. */
  @Test
  fun resolvesDiskPathsAcrossSubpackagesOmittedPrefixesAndSamePackageTies() {
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/alpha/ScreenKt.class", classBytes("com/example/alpha/ScreenKt", "Screen.kt"))
    writeClassFile(classesDir, "com/example/beta/ScreenKt.class", classBytes("com/example/beta/ScreenKt", "Screen.kt"))
    writeClassFile(classesDir, "com/example/flat/FlatCardKt.class", classBytes("com/example/flat/FlatCardKt", "FlatCard.kt"))
    writeClassFile(classesDir, "com/example/app/auth/LoginKt.class", classBytes("com/example/app/auth/LoginKt", "Login.kt"))
    writeClassFile(classesDir, "com/example/app/ui/detail/DetailKt.class", classBytes("com/example/app/ui/detail/DetailKt", "Detail.kt"))
    writeClassFile(classesDir, "com/example/debug/DebugPanel.class", classBytes("com/example/debug/DebugPanel", "DebugPanel.java"))
    writeClassFile(classesDir, "com/example/tied/TiedKt.class", classBytes("com/example/tied/TiedKt", "Tied.kt"))
    // A file in the default package, and a file with the same name in another package.
    writeClassFile(classesDir, "RootKt.class", classBytes("RootKt", "Root.kt"))
    writeClassFile(classesDir, "com/example/other/RootKt.class", classBytes("com/example/other/RootKt", "Root.kt"))
    // kotlinc copies an inlined lambda into the caller's package but keeps the inline function's file name, `Login.kt`. Skipped.
    val inlinedClass = "com/example/caller/CallerKt\$use\$\$inlined\$login\$1"
    writeClassFile(classesDir, "$inlinedClass.class", classBytes(inlinedClass, "Login.kt"))
    // The same happens for a `fun interface` wrapper. Its class name has a single `$` before `inlined`.
    val inlinedSamClass = "com/example/caller/CallerKt\$inlined\$sam\$i\$com_example_app_auth_OnLogin\$0"
    writeClassFile(classesDir, "$inlinedSamClass.class", classBytes(inlinedSamClass, "Login.kt"))

    val srcMain = tempDir.newFolder("app", "src", "main", "kotlin")
    val srcDebug = tempDir.newFolder("app", "src", "debug", "kotlin")
    val alphaScreen = createSourceFile(srcMain, "com/example/alpha/Screen.kt")
    val betaScreen = createSourceFile(srcMain, "com/example/beta/Screen.kt")
    val flatCard = createSourceFile(srcMain, "FlatCard.kt")
    val prefixOmittedLogin = createSourceFile(srcMain, "auth/Login.kt")
    // Another `Login.kt` on disk that no class was compiled from. It must not get in the way.
    createSourceFile(srcMain, "com/unrelated/Login.kt")
    val multiSegmentPrefixOmittedDetail = createSourceFile(srcMain, "ui/detail/Detail.kt")
    // `debug/java` is a source root only because the whole `app/src` folder is passed.
    val debugJavaPanel = createSourceFile(File(tempDir.root, "app/src/debug/java"), "com/example/debug/DebugPanel.java")
    val defaultPackageRoot = createSourceFile(srcMain, "Root.kt")
    val otherPackageRoot = createSourceFile(srcMain, "com/example/other/Root.kt")
    createSourceFile(srcMain, "com/example/tied/Tied.kt")
    createSourceFile(srcDebug, "com/example/tied/Tied.kt")

    val appSrc = File(tempDir.root, "app/src")
    // `app/src` and `app/src/main/kotlin` overlap. The shared source root must count once, or every file would look duplicated.
    val entries =
      ProjectSourceFiles.fromClassPath(
        projectClassPath = listOf(classesDir.path),
        sourceFolders = listOf(appSrc.path, srcMain.path),
      )

    assertEquals(
      setOf(
        ProjectSourceFile("Screen.kt", "com.example.alpha", alphaScreen),
        ProjectSourceFile("Screen.kt", "com.example.beta", betaScreen),
        ProjectSourceFile("FlatCard.kt", "com.example.flat", flatCard),
        ProjectSourceFile("Login.kt", "com.example.app.auth", prefixOmittedLogin),
        ProjectSourceFile("Detail.kt", "com.example.app.ui.detail", multiSegmentPrefixOmittedDetail),
        ProjectSourceFile("DebugPanel.java", "com.example.debug", debugJavaPanel),
        ProjectSourceFile("Root.kt", "", defaultPackageRoot),
        ProjectSourceFile("Root.kt", "com.example.other", otherPackageRoot),
        // The same file is in two source sets, so we cannot tell which one was compiled: just the file name.
        ProjectSourceFile("Tied.kt", "com.example.tied", "Tied.kt"),
      ),
      entries.toSet(),
    )
  }

  /** Verifies that a file in another package's directory is never matched. */
  @Test
  fun rejectsUnrelatedSiblingPackage() {
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/foo/GeneratedKt.class", classBytes("com/example/foo/GeneratedKt", "Generated.kt"))
    writeClassFile(classesDir, "com/example/build/BuildWidgetKt.class", classBytes("com/example/build/BuildWidgetKt", "BuildWidget.kt"))
    writeClassFile(classesDir, "DefaultPkgKt.class", classBytes("DefaultPkgKt", "DefaultPkg.kt"))

    val srcDir = tempDir.newFolder("src", "main", "kotlin")
    // `com/other` is not the directory of `com.example.foo` or of the default package.
    createSourceFile(srcDir, "com/other/Generated.kt")
    createSourceFile(srcDir, "com/other/DefaultPkg.kt")
    // A package named `build` is a normal package.
    val userBuildWidget = createSourceFile(srcDir, "com/example/build/BuildWidget.kt")

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf("", "   ", srcDir.path))
    assertEquals(
      setOf(
        ProjectSourceFile("Generated.kt", "com.example.foo", "Generated.kt"),
        ProjectSourceFile("DefaultPkg.kt", "", "DefaultPkg.kt"),
        ProjectSourceFile("BuildWidget.kt", "com.example.build", userBuildWidget),
      ),
      entries.toSet(),
    )
  }

  /** Verifies that a directory that only looks like the package's directory is never matched. */
  @Test
  fun rejectsLookAlikeDirectoriesOfOtherPackages() {
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/foo/LookalikeKt.class", classBytes("com/example/foo/LookalikeKt", "Lookalike.kt"))
    writeClassFile(classesDir, "auth/NestedKt.class", classBytes("auth/NestedKt", "Nested.kt"))
    writeClassFile(classesDir, "com/b/c/TwiceKt.class", classBytes("com/b/c/TwiceKt", "Twice.kt"))
    writeClassFile(classesDir, "com/b/util/Strings.class", classBytes("com/b/util/Strings", "Strings.java"))

    val srcDir = tempDir.newFolder("src", "main", "kotlin")
    // The last directory is `foo`, as in `com.example.foo`, but the package is different.
    createSourceFile(srcDir, "com/other/foo/Lookalike.kt")
    // The path ends in `auth`, but this file belongs to `com.example.auth`, not `auth`.
    createSourceFile(srcDir, "com/example/auth/Nested.kt")
    // Either file could be `com.b.c`'s `Twice.kt` with package directories left out, so we do not guess.
    createSourceFile(srcDir, "c/Twice.kt")
    createSourceFile(srcDir, "Twice.kt")
    // Java files cannot leave out package directories, so this is not `com.b.util`'s file.
    createSourceFile(srcDir, "util/Strings.java")

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf(srcDir.path))
    assertEquals(
      setOf(
        ProjectSourceFile("Lookalike.kt", "com.example.foo", "Lookalike.kt"),
        ProjectSourceFile("Nested.kt", "auth", "Nested.kt"),
        ProjectSourceFile("Twice.kt", "com.b.c", "Twice.kt"),
        ProjectSourceFile("Strings.java", "com.b.util", "Strings.java"),
      ),
      entries.toSet(),
    )
  }

  /** Verifies that files with the same name in several packages are each found when the common package directories are left out. */
  @Test
  fun resolvesSameNamedFilesWithPackageDirectoriesLeftOut() {
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/app/a/ScreenKt.class", classBytes("com/example/app/a/ScreenKt", "Screen.kt"))
    writeClassFile(classesDir, "com/example/app/b/ScreenKt.class", classBytes("com/example/app/b/ScreenKt", "Screen.kt"))
    writeClassFile(classesDir, "ui/SharedKt.class", classBytes("ui/SharedKt", "Shared.kt"))
    writeClassFile(classesDir, "com/a/ui/SharedKt.class", classBytes("com/a/ui/SharedKt", "Shared.kt"))

    val srcDir = tempDir.newFolder("src", "main", "kotlin")
    val screenA = createSourceFile(srcDir, "a/Screen.kt")
    val screenB = createSourceFile(srcDir, "b/Screen.kt")
    // `ui`'s `Shared.kt` with its directory left out. `com.a.ui` has its own file, so this one is not in doubt.
    val rootShared = createSourceFile(srcDir, "Shared.kt")
    val nestedUiShared = createSourceFile(srcDir, "com/a/ui/Shared.kt")

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf(srcDir.path))
    assertEquals(
      setOf(
        ProjectSourceFile("Screen.kt", "com.example.app.a", screenA),
        ProjectSourceFile("Screen.kt", "com.example.app.b", screenB),
        ProjectSourceFile("Shared.kt", "ui", rootShared),
        ProjectSourceFile("Shared.kt", "com.a.ui", nestedUiShared),
      ),
      entries.toSet(),
    )
  }

  /** Verifies that a file found with package directories left out is not used when another compiled file is found at the same path. */
  @Test
  fun rejectsGuessedPathThatAnotherFileIsFoundAt() {
    val classesDir = tempDir.newFolder("classes")
    // `ui/Owned.kt` is the file of package `ui`, so it cannot also be `com.x.ui`'s file.
    writeClassFile(classesDir, "ui/OwnedKt.class", classBytes("ui/OwnedKt", "Owned.kt"))
    writeClassFile(classesDir, "com/x/ui/OwnedKt.class", classBytes("com/x/ui/OwnedKt", "Owned.kt"))
    // `ui/Either.kt` could be the file of `com.a.ui` or of `com.b.ui`, so we do not guess.
    writeClassFile(classesDir, "com/a/ui/EitherKt.class", classBytes("com/a/ui/EitherKt", "Either.kt"))
    writeClassFile(classesDir, "com/b/ui/EitherKt.class", classBytes("com/b/ui/EitherKt", "Either.kt"))

    val srcDir = tempDir.newFolder("src", "main", "kotlin")
    val owned = createSourceFile(srcDir, "ui/Owned.kt")
    createSourceFile(srcDir, "ui/Either.kt")

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf(srcDir.path))
    assertEquals(
      setOf(
        ProjectSourceFile("Owned.kt", "ui", owned),
        ProjectSourceFile("Owned.kt", "com.x.ui", "Owned.kt"),
        ProjectSourceFile("Either.kt", "com.a.ui", "Either.kt"),
        ProjectSourceFile("Either.kt", "com.b.ui", "Either.kt"),
      ),
      entries.toSet(),
    )
  }

  /** Verifies that a source folder passed twice, once through a symbolic link, is used once, so its files are not found twice. */
  @Test
  fun usesSourceFolderOnceWhenAlsoPassedThroughSymbolicLink() {
    assumeTrue(OsType.getHostOs() != OsType.WINDOWS)
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/home/HomeScreenKt.class", classBytes("com/example/home/HomeScreenKt", "HomeScreen.kt"))

    val srcDir = tempDir.newFolder("sources")
    val homeScreen = createSourceFile(srcDir, "com/example/home/HomeScreen.kt")
    val link = File(tempDir.root, "linked-sources")
    Files.createSymbolicLink(link.toPath(), srcDir.toPath())

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf(srcDir.path, link.path))
    assertEquals(listOf(ProjectSourceFile("HomeScreen.kt", "com.example.home", homeScreen)), entries)
  }

  /**
   * Verifies that in a `src` folder only `<source set>/kotlin` and `<source set>/java` are source roots, not deeper `kotlin` or `src`
   * directories.
   */
  @Test
  fun doesNotTakePackageDirectoriesNamedLikeSourceRootsForSourceRoots() {
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/kotlin/ui/WidgetKt.class", classBytes("com/example/kotlin/ui/WidgetKt", "Widget.kt"))
    // Their source files are not on disk. They would be found only if `com/example/kotlin` or `com/example/src` were source roots.
    writeClassFile(classesDir, "ui/WidgetKt.class", classBytes("ui/WidgetKt", "Widget.kt"))
    writeClassFile(classesDir, "HelperKt.class", classBytes("HelperKt", "Helper.kt"))

    val srcDir = tempDir.newFolder("app", "src")
    val widget = createSourceFile(srcDir, "main/kotlin/com/example/kotlin/ui/Widget.kt")
    createSourceFile(srcDir, "main/kotlin/com/example/src/Helper.kt")

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf(srcDir.path))
    assertEquals(
      setOf(
        ProjectSourceFile("Widget.kt", "com.example.kotlin.ui", widget),
        ProjectSourceFile("Widget.kt", "ui", "Widget.kt"),
        ProjectSourceFile("Helper.kt", "", "Helper.kt"),
      ),
      entries.toSet(),
    )
  }

  /** Verifies that a `src` folder with no source sets is a source root itself. */
  @Test
  fun takesFolderWithoutSourceSetsAsSourceRoot() {
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/home/HomeScreenKt.class", classBytes("com/example/home/HomeScreenKt", "HomeScreen.kt"))

    val srcDir = tempDir.newFolder("lib", "src")
    val homeScreen = createSourceFile(srcDir, "com/example/home/HomeScreen.kt")

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf(srcDir.path))
    assertEquals(listOf(ProjectSourceFile("HomeScreen.kt", "com.example.home", homeScreen)), entries)
  }

  /** Verifies that a folder above the source roots, such as a module directory, finds nothing. */
  @Test
  fun doesNotSearchBroaderFolderForSourceRoots() {
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/home/HomeScreenKt.class", classBytes("com/example/home/HomeScreenKt", "HomeScreen.kt"))

    val moduleDir = tempDir.newFolder("app")
    createSourceFile(moduleDir, "src/main/kotlin/com/example/home/HomeScreen.kt")

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf(moduleDir.path))
    assertEquals(listOf(ProjectSourceFile("HomeScreen.kt", "com.example.home")), entries)
  }

  /** Verifies that plain source roots, such as google3's `java` and `javatests`, work without a Gradle layout. */
  @Test
  fun resolvesFilesUnderPlainSourceRoots() {
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/google/app/home/HomeScreenKt.class", classBytes("com/google/app/home/HomeScreenKt", "HomeScreen.kt"))
    writeClassFile(classesDir, "com/google/app/home/HomeTest.class", classBytes("com/google/app/home/HomeTest", "HomeTest.java"))

    val workspace = tempDir.newFolder("google3")
    val homeScreen = createSourceFile(workspace, "java/com/google/app/home/HomeScreen.kt")
    val homeTest = createSourceFile(workspace, "javatests/com/google/app/home/HomeTest.java")
    // A file with the same name in another package must not get in the way.
    createSourceFile(workspace, "java/com/google/other/HomeScreen.kt")

    val entries =
      ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf(File(workspace, "java").path, File(workspace, "javatests").path))
    assertEquals(
      setOf(
        ProjectSourceFile("HomeScreen.kt", "com.google.app.home", homeScreen),
        ProjectSourceFile("HomeTest.java", "com.google.app.home", homeTest),
      ),
      entries.toSet(),
    )
  }

  /** Verifies that directories in a class's `SourceFile` name cannot point the lookup outside the package directory. */
  @Test
  fun doesNotFollowDirectoriesInSourceFileAttribute() {
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/AKt.class", classBytes("com/example/AKt", "../../../outside/Secret.kt"))

    val srcDir = tempDir.newFolder("sources")
    File(srcDir, "com/example").mkdirs()
    // This file is at `sources/com/example/../../../outside/Secret.kt`, which is outside the source folder.
    createSourceFile(tempDir.newFolder("outside"), "Secret.kt")

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf(srcDir.path))
    assertEquals(listOf(ProjectSourceFile("Secret.kt", "com.example")), entries)
  }

  /** Verifies that a source file that is a symbolic link, as in a Bazel sandbox, is found at the link's path. */
  @Test
  fun resolvesSourceFilesThatAreSymbolicLinks() {
    assumeTrue(OsType.getHostOs() != OsType.WINDOWS)
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "com/example/home/HomeScreenKt.class", classBytes("com/example/home/HomeScreenKt", "HomeScreen.kt"))

    val target = File(createSourceFile(tempDir.newFolder("outside"), "HomeScreen.kt"))
    val link = File(tempDir.newFolder("sources"), "com/example/home/HomeScreen.kt")
    link.parentFile.mkdirs()
    Files.createSymbolicLink(link.toPath(), target.toPath())

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path), listOf(File(tempDir.root, "sources").path))
    assertEquals(listOf(ProjectSourceFile("HomeScreen.kt", "com.example.home", link.absoluteFile.normalize().path)), entries)
  }

  /** Verifies that a class that cannot be read is skipped, and the classes after it are still read. */
  @Test
  fun skipsUnreadableClassesWithoutDroppingLaterOnes() {
    val corrupt = byteArrayOf(1, 2, 3)
    val classesDir = tempDir.newFolder("classes")
    writeClassFile(classesDir, "a/Bad.class", corrupt)
    writeClassFile(classesDir, "com/example/home/HomeScreenKt.class", classBytes("com/example/home/HomeScreenKt", "HomeScreen.kt"))

    val jarFile = tempDir.newFile("module.jar")
    JarOutputStream(jarFile.outputStream()).use { jar ->
      jar.putNextEntry(JarEntry("a/Bad.class"))
      jar.write(corrupt)
      jar.closeEntry()
      jar.putNextEntry(JarEntry("com/example/card/CardKt.class"))
      jar.write(classBytes("com/example/card/CardKt", "Card.kt"))
      jar.closeEntry()
    }
    val notAJar = tempDir.newFile("broken.jar").apply { writeBytes(corrupt) }

    val entries = ProjectSourceFiles.fromClassPath(listOf(classesDir.path, notAJar.path, jarFile.path), emptyList())
    assertEquals(
      setOf(ProjectSourceFile("HomeScreen.kt", "com.example.home"), ProjectSourceFile("Card.kt", "com.example.card")),
      entries.toSet(),
    )
  }
}
