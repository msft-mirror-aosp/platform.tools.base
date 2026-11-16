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

import com.android.tools.uicheck.ProjectSourceFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.jar.JarFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Lists the source files of a project module: the name, package and path on disk of each one.
 *
 * A component tree only records a file name and a package hash for each call site. With this list, UI Check can tell the user's code from
 * library code and report the full path of a file.
 */
object ProjectSourceFiles {
  // Part of the name of a class that kotlinc creates when it inlines a function into another file, such as
  // `CallerKt$show$$inlined$map$1`. That class keeps the inlined function's file name but sits in the caller's package, so it is skipped.
  private const val INLINED_CLASS_MARKER = "\$inlined\$"

  /** A source file found on disk. [isGuess] is true when it was found with package directories left out. */
  private class FoundFile(val path: String, val isGuess: Boolean)

  /**
   * Returns the source files compiled into [projectClassPath], which holds class directories and JARs.
   *
   * [projectClassPath] must hold only the project's own compiled output, not its dependencies: every file in it counts as user code.
   *
   * [sourceFolders] are the absolute paths of the folders that package directories start from, such as `src/main/kotlin` or google3's
   * `java`. A Gradle module's `src` folder also works. A module or workspace folder does not, because source roots inside it are not looked
   * for.
   *
   * A file gets just its file name as [ProjectSourceFile.filePath] when it is not found, is found more than once, or is only found with
   * package directories left out at a path where another file was also found. A file that is in two source sets, such as `debug` and
   * `release`, is found twice; pass only the source roots that were compiled to avoid that.
   */
  @JvmStatic
  fun fromClassPath(projectClassPath: List<String>, sourceFolders: List<String>): List<ProjectSourceFile> {
    // 1. Read which files the module was compiled from, and the package of each.
    val compiledFiles = readCompiledFiles(projectClassPath)
    // 2. Find the source roots: the folders that package directories start from.
    val sourceRoots =
      sourceFolders
        .filter { it.isNotBlank() } // `""` would become the working directory.
        .map { File(it).absoluteFile.normalize() }
        .filter { it.isDirectory }
        .flatMap(::sourceRootsOf)
        // The same folder can arrive under two spellings, such as through a symbolic link. Keep it once, or every file is found twice.
        .distinctBy(::canonicalPathOf)
    // 3. Look for each file in the directory of its package. No folder is walked, so this is fast even in a huge source tree.
    val found = compiledFiles.associateWith { findFile(it, sourceRoots) }
    // A guess is only kept when no other compiled file was found at the same path.
    val filesAtPath = found.values.filterNotNull().groupingBy { it.path }.eachCount()
    return compiledFiles.map { file ->
      val match = found[file]
      if (match == null || (match.isGuess && filesAtPath.getValue(match.path) > 1)) file else file.copy(filePath = match.path)
    }
  }

  /** Returns where [file] is under [sourceRoots], or `null` when it is not found or is found more than once. */
  private fun findFile(file: ProjectSourceFile, sourceRoots: List<File>): FoundFile? {
    val packageDir = file.packageName.replace('.', '/')

    // Usual case: the directory matches the package, so `com.example.ui` is in `com/example/ui`. If two source sets, such as `main`
    // and `debug`, both have the file, we cannot tell which one was compiled, so we return nothing.
    val inPackageDir = filesIn(sourceRoots, packageDir, file.fileName)
    if (inPackageDir.isNotEmpty()) return inPackageDir.singleOrNull()?.let { FoundFile(it.path, isGuess = false) }

    // Kotlin allows leaving out the first package directories, so `com.example.ui` may be in `example/ui`, in `ui`, or in the source
    // root. Java does not. This match is less certain, so it is marked as a guess. A file found in two of these directories is not used.
    if (!file.fileName.endsWith(".kt")) return null
    val shorterDirs = generateSequence(packageDir) { if (it.isEmpty()) null else it.substringAfter('/', "") }.drop(1)
    return shorterDirs
      .flatMap { filesIn(sourceRoots, it, file.fileName) }
      .toList()
      .singleOrNull()
      ?.let { FoundFile(it.path, isGuess = true) }
  }

  /** Returns the path of [file] with symbolic links resolved, or its path as given when that fails. */
  private fun canonicalPathOf(file: File): String =
    try {
      file.canonicalPath
    } catch (_: IOException) {
      file.path
    }

  /** Returns the files named [fileName] found in directory [dir] of each source root. */
  private fun filesIn(sourceRoots: List<File>, dir: String, fileName: String): List<File> =
    sourceRoots.map { File(File(it, dir), fileName) }.filter { it.isFile }

  /** Reads the file name and package of every class in [projectClassPath]. The path of each result is just the file name. */
  private fun readCompiledFiles(projectClassPath: List<String>): Set<ProjectSourceFile> {
    val result = LinkedHashSet<ProjectSourceFile>()
    for (entry in projectClassPath.map(::File)) {
      if (entry.isDirectory) {
        entry.walk().filter { it.extension == "class" }.forEach { skipUnreadable { readClass(it.inputStream(), result) } }
      } else if (entry.extension == "jar") {
        skipUnreadable { readJar(entry, result) }
      }
    }
    return result
  }

  /** Reads every class in [jarFile] and adds its source file to [out]. */
  private fun readJar(jarFile: File, out: MutableSet<ProjectSourceFile>) {
    JarFile(jarFile).use { jar ->
      for (entry in jar.entries()) {
        if (entry.name.endsWith(".class")) skipUnreadable { readClass(jar.getInputStream(entry), out) }
      }
    }
  }

  /**
   * Runs [read] and ignores a class or JAR that cannot be read, so one bad file does not hide the rest. ASM throws a `RuntimeException` for
   * a corrupt class and for a class version it does not know.
   */
  private inline fun skipUnreadable(read: () -> Unit) {
    try {
      read()
    } catch (_: IOException) {} catch (_: RuntimeException) {}
  }

  /** Reads the class in [stream], adds its source file name and package to [out], and closes [stream]. */
  private fun readClass(stream: InputStream, out: MutableSet<ProjectSourceFile>) {
    val reader = stream.use(::ClassReader)
    if (INLINED_CLASS_MARKER in reader.className) return
    // The package is the class name up to the last `/`: `com/example/ui/LoginScreenKt` is in `com.example.ui`.
    val packageName = reader.className.substringBeforeLast('/', "").replace('/', '.')
    val visitor =
      object : ClassVisitor(Opcodes.ASM9) {
        /** Called by ASM with the name of the file the class was compiled from. */
        override fun visitSource(source: String?, debug: String?) {
          // Keep only the file name, so a name with directories in it cannot point outside the package directory.
          val fileName = source?.let { File(it).name }
          if (!fileName.isNullOrBlank()) out.add(ProjectSourceFile(fileName, packageName))
        }
      }
    // SKIP_CODE skips method bodies, which are not needed.
    reader.accept(visitor, ClassReader.SKIP_CODE)
  }

  /**
   * Returns the source roots in [folder]. For a Gradle module's `src` these are `src/<source set>/kotlin` and `src/<source set>/java`. Any
   * other folder, or a `src` without those, is a source root itself. This assumes that a `src` holding packages directly has no package
   * whose second directory is named `kotlin` or `java`, such as `src/org/kotlin`.
   */
  private fun sourceRootsOf(folder: File): List<File> {
    if (folder.name != "src") return listOf(folder)
    val sourceSets = folder.listFiles().orEmpty()
    val sourceSetRoots = sourceSets.flatMap { listOf(File(it, "kotlin"), File(it, "java")) }.filter { it.isDirectory }
    return sourceSetRoots.ifEmpty { listOf(folder) }
  }
}
