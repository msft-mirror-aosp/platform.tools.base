/*
 * Copyright (C) 2018 The Android Open Source Project
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

@file:Suppress("RedundantSuppression", "rawtypes", "RedundantOperationOnEmptyContainer")

package com.android.tools.lint.checks

import com.android.testutils.TestUtils
import com.android.tools.lint.LintCliFlags
import com.android.tools.lint.MainTest
import com.android.tools.lint.detector.api.Desugaring
import com.android.tools.lint.detector.api.Detector
import java.io.File

@Suppress("PrivatePropertyName")
class ApiDetectorDesugaringTest : AbstractCheckTest() {
  fun testTryWithResources() {
    // No desugaring
    val expected =
      """
            src/main/java/test/pkg/MultiCatch.java:10: Error: Multi-catch with these reflection exceptions requires API level 19 (current min is 1) because they get compiled to the common but new super type ReflectiveOperationException. As a workaround either create individual catch statements, or catch Exception. [NewApi]
                    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                             ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            src/main/java/test/pkg/TryWithResources.java:9: Error: Try-with-resources requires API level 19 (current min is 1) [NewApi]
                    try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            2 errors, 0 warnings
            """
    lint().files(manifest().minSdk(1), tryWithResources, multiCatch, gradleVersion231).run().expect(expected)
  }

  fun testTryWithResourcesOkDueToCompileSdk() {
    lint().files(manifest().minSdk(19), tryWithResources, multiCatch, gradleVersion231).run().expectClean()
  }

  fun testTryWithResourcesOkDueToDesugar() {
    lint().files(manifest().minSdk(19), tryWithResources, multiCatch, gradleVersion24_language18).run().expectClean()
  }

  fun testTryWithResourcesOutsideAndroid() {
    lint().files(manifest().minSdk(1), tryWithResources, multiCatch, gradle("apply plugin: 'java'\n")).run().expectClean()
  }

  fun testTryWithResourcesOldGradlePlugin() {
    val expected =
      """
            src/main/java/test/pkg/TryWithResources.java:9: Error: Try-with-resources requires API level 19 (current min is 1) [NewApi]
                    try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            1 errors, 0 warnings
            """
    lint().files(manifest().minSdk(1), gradleVersion231, tryWithResources).run().expect(expected)
  }

  fun testTryWithResourcesNewPluginLanguage17() {
    val expected =
      """
            src/main/java/test/pkg/TryWithResources.java:9: Error: Try-with-resources requires API level 19 (current min is 1) [NewApi]
                    try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            1 errors, 0 warnings
            """
    lint().files(manifest().minSdk(1), gradleVersion24_language17, tryWithResources).run().expect(expected)
  }

  fun testTryWithResourcesDesugar() {
    lint().files(manifest().minSdk(1), gradleVersion24_language18, tryWithResources).run().expectClean()
  }

  fun testDesugarMethods() {
    // Desugar inlines Objects.requireNonNull(foo) so don't flag this if using Desugar
    // Ditto for Throwable.addSuppressed.

    lint()
      .files(
        java(
            """
                package test.pkg;

                import java.util.Objects;

                @SuppressWarnings({"ClassNameDiffersFromFileName", "MethodMayBeStatic"})
                public class DesugarTest {
                    public void testRequireNull(Object foo) {
                        Objects.requireNonNull(foo); // OK 1
                        Objects.requireNonNull(foo, "message"); // OK 2
                    }

                    public void addThrowable(Throwable t1, Throwable t2) {
                        t1.addSuppressed(t2); // OK 3
                        // Regression test for b/177353340
                        Throwable[] suppressed = t1.getSuppressed();// OK 4
                    }
                }
                """
          )
          .indented(),
        gradleVersion24_language18,
      )
      .run()
      .expectClean()
  }

  fun testDefaultMethodsDesugar() {
    // Default methods require minSdkVersion=N

    lint()
      .files(
        manifest().minSdk(15),
        java(
            "src/test/pkg/InterfaceMethodTest.java",
            """
                package test.pkg;

                @SuppressWarnings({"ClassNameDiffersFromFileName", "MethodMayBeStatic"})
                public interface InterfaceMethodTest {
                    void someMethod();
                    default void method2() {
                        System.out.println("test");
                    }
                    static void method3() {
                        System.out.println("test");
                    }
                }""",
          )
          .indented(),
        gradleVersion24_language18,
      )
      .run()
      .expectClean()
  }

  fun testDesugarCompare() {
    lint()
      .files(
        manifest().minSdk(1),
        java(
            """
                package test.pkg;

                // Desugar rewrites these
                @SuppressWarnings({"ClassNameDiffersFromFileName", "MethodMayBeStatic"})
                public class CompareTest {
                    public void testLong(long value1, long value2) {
                        int result3 = Long.compare(value1, value2);
                    }

                    public int testFloat(float value1, float value2) {
                        return Float.compare(value1, value2); // OK
                    }

                    public int testBoolean(boolean value1, boolean value2) {
                        return Boolean.compare(value1, value2);
                    }

                    public int testDouble(double value1, double value2) {
                        return Double.compare(value1, value2); // OK
                    }

                    public int testByte(byte value1, byte value2) {
                        return Byte.compare(value1, value2);
                    }

                    public int testChar(char value1, char value2) {
                        return Character.compare(value1, value2);
                    }

                    public int testInt(int value1, int value2) {
                        return Integer.compare(value1, value2);
                    }

                    public int testShort(short value1, short value2) {
                        return Short.compare(value1, value2);
                    }
                }
                """
          )
          .indented(),
        gradleVersion24_language18,
      )
      .run()
      .expectClean()
  }

  fun testDesugarJava8LibsKotlin() {
    lint()
      .files(
        manifest().minSdk(1),
        kotlin(
            """
                @file:Suppress("unused", "UNUSED_VARIABLE")

                package test.pkg

                import java.time.Duration
                import java.util.*
                import java.util.function.Consumer
                import java.util.function.Function

                class Test {
                    fun time(duration: java.time.Duration) {
                        val negative = duration.isNegative
                        val duration2 = Duration.ofMillis(1000L)
                   }

                    fun streams(list: ArrayList<String>) {
                        list.stream().forEach { it -> Consumer<String> { println(it) }  }
                    }

                    fun functions(func: Function<String, String>) {
                        func.apply("hello")
                    }


                    // Type use annotations
                    @Target(AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
                    annotation class MyTypeUse
                }
                """
          )
          .indented(),
      )
      .desugaring(Desugaring.FULL)
      .run()
      .expectClean()
  }

  fun testDesugarJava8LibsJavaAndroid() {
    lint()
      .files(
        manifest().minSdk(1),
        java(
            """
                package test.pkg;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Repeatable;
                import java.lang.annotation.Target;
                import java.util.ArrayList;
                import java.util.Arrays;
                import java.util.Iterator;
                import java.util.Optional;
                import java.util.stream.BaseStream;
                import java.util.stream.Stream;

                @SuppressWarnings({"unused", "SimplifyStreamApiCallChains", "OptionalGetWithoutIsPresent", "OptionalUsedAsFieldOrParameterType", "ClassNameDiffersFromFileName", "MethodMayBeStatic"})
                public class Test {

                    public void utils(java.util.Collection<String> collection) {
                        collection.removeIf(s -> s.length() > 5);
                    }

                    public void streams(ArrayList<String> list, String[] array) {
                        list.stream().forEach(s -> System.out.println(s.length()));
                        Stream<String> stream = Arrays.stream(array);
                    }

                    public void otherUtils(Optional<String> optional, Iterator<String> iterator,
                                           java.util.concurrent.atomic.AtomicInteger integer) {
                        double max = java.lang.Double.max(5, 6);
                        int exact = java.lang.Math.toIntExact(5L);
                        String got = optional.get();
                        iterator.forEachRemaining(s -> System.out.println(s.length()));
                        integer.addAndGet(5);
                    }

                    public void bannedMembers(java.util.Collection collection, @SuppressWarnings("rawtypes") java.util.stream.BaseStream base) {
                        Stream stream = collection.parallelStream();
                        BaseStream parallel = base.parallel();
                    }

                    // Type use annotations

                    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
                    @interface MyInner {
                    }

                    // Repeatable annotations

                    public @interface Schedules {
                        Schedule[] value();
                    }

                    @Repeatable(Schedules.class)
                    public @interface Schedule {
                        String dayOfMonth() default "first";
                        String dayOfWeek() default "Mon";
                        int hour() default 12;
                    }
                }
                """
          )
          .indented(),
      )
      .desugaring(Desugaring.FULL)
      .run()
      .expect(
        """
            src/test/pkg/Test.java:35: Error: Call requires API level 24 (current min is 1): java.util.Collection#parallelStream [NewApi]
                    Stream stream = collection.parallelStream();
                                               ~~~~~~~~~~~~~~
            src/test/pkg/Test.java:36: Error: Call requires API level 24 (current min is 1): java.util.stream.BaseStream#parallel [NewApi]
                    BaseStream parallel = base.parallel();
                                               ~~~~~~~~
            2 errors, 0 warnings
            """
      )
  }

  fun testDesugarJava8LibsJavaLib() {
    val lib =
      project(
        java(
            """
                package test.pkg.lib;

                import java.util.ArrayList;
                import java.util.function.IntBinaryOperator;

                @SuppressWarnings({"unused", "ClassNameDiffersFromFileName", "MethodMayBeStatic", "SimplifyStreamApiCallChains"})
                public class Test {
                    public @interface Something {
                        javax.lang.model.type.TypeKind value();
                    }

                    @Something(javax.lang.model.type.TypeKind.PACKAGE)
                    public void usingTypeKind() {
                    }

                    public void otherUtils(java.util.concurrent.atomic.AtomicInteger integer, IntBinaryOperator operator) {
                        new ArrayList<String>().stream().forEach(s -> System.out.println(s.length()));
                        integer.addAndGet(5);
                        integer.accumulateAndGet(5, operator);
                    }
                }
                """
          )
          .indented(),
        // Make sure it's treated as a plain library
        gradle(
            """
          apply plugin: 'java'
          """
          )
          .indented(),
      )

    val main =
      project(
          manifest().minSdk(1),
          gradle(
            """
        android.compileOptions.coreLibraryDesugaringEnabled = true
        """
          ),
        )
        .dependsOn(lib)

    lint().projects(lib, main).run().expectClean()
  }

  fun testDesugarInheritedMethods() {
    // Regression test for https://issuetracker.google.com/327670482
    lint()
      .files(
        java(
            """
            package test.pkg;

            import java.util.ArrayList;

            public class DesugaringJavaTestClass {
                DesugaringJavaTestClass() {
                    ArrayList<String> list = new ArrayList<>();
                    list.removeIf(s -> true);
                }
            }
            """
          )
          .indented(),
        gradle(
          """
          android.compileOptions.coreLibraryDesugaringEnabled = true
          """
        ),
      )
      .run()
      .expectClean()
  }

  fun testDesugarInheritedMethodsInLibrary() {
    // Like testDesugarInheritedMethods, but here the code is in a library
    // where core library desugaring has not been turned on, and we're
    // generating a report for a downstream app module which turns it on only there.
    // Regression test for https://issuetracker.google.com/327670482
    val lib =
      project(
        java(
            """
            package test.pkg;

            import java.util.ArrayList;

            public class DesugaringJavaTestClass {
                DesugaringJavaTestClass() {
                    ArrayList<String> list = new ArrayList<>();
                    list.removeIf(s -> true);
                }
            }
            """
          )
          .indented(),
        // Make sure it's treated as a plain library
        gradle(
            """
            apply plugin: 'java'
            android.compileOptions.coreLibraryDesugaringEnabled = false
            """
          )
          .indented(),
      )

    val main =
      project(
          manifest().minSdk(1),
          gradle(
              """
              android.compileOptions.coreLibraryDesugaringEnabled = true
              """
            )
            .indented(),
        )
        .dependsOn(lib)

    lint().projects(lib, main).run().expectClean()
  }

  fun testLibraryDesugaringNioFields() {
    // 267449090: Lint information for library desugaring is missing fields
    lint()
      .files(
        manifest().minSdk(1),
        kotlin(
            """
                package test.pkg

                import java.nio.file.Path
                import kotlin.io.path.writeLines

                fun test(tempFile: Path) {
                    tempFile.writeLines(listOf("Hello"), options = arrayOf(java.nio.file.StandardOpenOption.APPEND))
                }
                """
          )
          .indented(),
        java(
            """
                package test.pkg;

                import static java.nio.charset.StandardCharsets.UTF_8;

                import java.io.IOException;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.List;

                public class FieldTest {
                    public void test(Path tempFile) throws IOException {
                        Files.write(tempFile, List.of("Hello"), UTF_8, java.nio.file.StandardOpenOption.APPEND);
                    }
                }
                """
          )
          .indented(),
      )
      .desugaring(Desugaring.FULL)
      .run()
      .expectClean()
  }

  fun testInstantSource() {
    // Regression test for b/374282903
    lint()
      .files(
        manifest().minSdk(1),
        java(
            """
            package test.pkg;

            import java.time.InstantSource;

            public class Foo {
              void useSource(InstantSource source) {
                var unused = source.instant();
              }
            }
          """
          )
          .indented(),
      )
      .desugaring(Desugaring.FULL)
      .run()
      .expect(
        """
        src/test/pkg/Foo.java:7: Error: Call requires API level 34 (current min is 1): java.time.InstantSource#instant [NewApi]
            var unused = source.instant();
                                ~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testLibraryDesugaredCasts() {
    // Regression test for b/347167978
    lint()
      .files(
        manifest().minSdk(1),
        kotlin(
            """
            package test.pkg

            import java.time.chrono.IsoChronology
            import java.time.format.DateTimeFormatter
            import java.time.format.DateTimeFormatterBuilder
            import java.util.Locale

            fun test(
                locale: Locale,
                pattern: String
            ): DateTimeFormatter {
                return DateTimeFormatterBuilder()
                    .appendPattern(pattern)
                    .toFormatter(locale)
                    .withChronology(IsoChronology.INSTANCE)
            }
            """
          )
          .indented(),
      )
      .desugaring(Desugaring.FULL)
      .run()
      .expectClean()
  }

  fun testLibraryDesugaredCasts2() {
    // Regression test for b/441076971
    lint()
      .files(
        manifest().minSdk(21),
        java(
            """
            package test.pkg;

            import java.util.Comparator;
            import java.util.Map;
            import java.util.stream.Stream;

            public abstract class BiStream<K, V> implements AutoCloseable {
                @SuppressWarnings("unchecked") // Immutable Map.Entry<> is covariant.
                private BiStream<K, V> sorted(Comparator<? super Map.Entry<K, V>> entryComparator) {
                    return fromEntries(((Stream<Map.Entry<K, V>>) mapToEntry()).sorted(entryComparator));
                }

                static <K, V, E extends Map.Entry<? extends K, ? extends V>> BiStream<K, V> fromEntries(
                        Stream<E> entryStream) {
                    throw new UnsupportedOperationException("TODO");
                }

                Stream<? extends Map.Entry<? extends K, ? extends V>> mapToEntry() {
                    throw new UnsupportedOperationException("TODO");
                }
            }
            """
          )
          .indented(),
      )
      .desugaring(Desugaring.FULL)
      .run()
      .expectClean()
  }

  fun testNioCompatWarnings() {
    // Regression test for b/381126163
    val testFiles =
      arrayOf(
        java(
            """
            package test.pkg;

            import java.io.File;
            import java.io.IOException;
            import java.nio.ByteBuffer;
            import java.nio.channels.AsynchronousFileChannel;
            import java.nio.file.FileSystem;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.WatchService;
            import java.nio.file.attribute.BasicFileAttributes;

            public class NioTest {
                public void test() {
                    Path path = new File("").toPath();
                    try(AsynchronousFileChannel open = AsynchronousFileChannel.open(path)) { // ERROR 1
                        open.read(ByteBuffer.allocate(1024), 1024); // ERROR 2
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                public void watchService(FileSystem fileSystem, Path path, WatchService service) throws IOException {
                    fileSystem.newWatchService().poll(); // ERROR 3
                }

                public void lookup(FileSystem fileSystem) throws IOException {
                    fileSystem.getUserPrincipalLookupService(); // ERROR 4
                }

                public void links(FileSystem fileSystem, Path p1, Path p2) throws IOException {
                    fileSystem.provider().createLink(p1, p2); // ERROR 5
                    fileSystem.provider().createSymbolicLink(p1, p2); // ERROR 6
                    BasicFileAttributes attributes = Files.readAttributes(p1, BasicFileAttributes.class);
                    if (attributes.isSymbolicLink()) { // OK
                        System.out.println(p1 + " is a symbolic link.");
                    }
                }

                public void tryCatch(Path p1) throws IOException {
                    try {
                        Files.readAttributes(p1, BasicFileAttributes.class);
                    } catch (java.nio.file.NoSuchFileException e) { // ERROR 7
                    }
                    try {
                        Files.readAttributes(p1, BasicFileAttributes.class);
                    } catch (java.nio.file.NoSuchFileException e) { // OK - also handling IOException
                    } catch (java.io.IOException e2) { // OK
                       throw RuntimeException(e2);
                    }
                }
            }
            """
          )
          .indented(),
        kotlin(
            """
            package test.pkg

            import java.nio.channels.AsynchronousFileChannel
            import java.nio.channels.FileChannel
            import java.nio.file.Files
            import java.nio.file.Path
            import java.nio.file.Paths
            import java.nio.file.StandardCopyOption
            import java.nio.file.StandardOpenOption.DELETE_ON_CLOSE
            import java.nio.file.StandardOpenOption.READ
            import java.nio.file.StandardOpenOption.SPARSE
            import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            import java.nio.file.StandardOpenOption.WRITE
            import java.nio.file.attribute.GroupPrincipal
            import java.nio.file.attribute.PosixFilePermission.GROUP_READ
            import java.nio.file.attribute.PosixFilePermission.OTHERS_READ
            import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
            import java.nio.file.attribute.PosixFilePermission.OWNER_READ
            import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            import java.nio.file.attribute.PosixFilePermissions
            import java.nio.file.attribute.UserPrincipalLookupService

            fun testPosix(path: Path) {
                // Set permissions
                val newPermissions = PosixFilePermissions.fromString("rwxr-xr-x") // ERROR 8
                Files.setPosixFilePermissions(path, newPermissions) // ERROR 9

                // Set owner (requires appropriate privileges)
                val lookupService = path.fileSystem.userPrincipalLookupService // ERROR 10
                val newOwner = lookupService.lookupPrincipalByName("new_owner_username") // ERROR 11
                Files.setOwner(path, newOwner)
            }

            fun testGroup(path: Path, lookupService: UserPrincipalLookupService) {
                // Set group (requires appropriate privileges)
                val newGroup: GroupPrincipal = lookupService.lookupPrincipalByGroupName("new_group_name") // ERROR 12
                val owner = setGroup(path, newGroup)
            }

            fun setGroup(
                path: Path,
                newGroup: GroupPrincipal
            ): Path? = Files.setOwner(path, newGroup)

            fun testFileAttributes(directoryPath: Path) {
                val permissions = PosixFilePermissions.asFileAttribute( // ERROR 13
                    setOf(
                        OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, OTHERS_READ,
                    )
                )
                Files.createDirectory(directoryPath, permissions)
            }

            fun testCopy(path: Path, destination: Path) {
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING) // OK
                Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES) // WARNING 1
                Files.copy(path, destination, StandardCopyOption.ATOMIC_MOVE) // WARNING 2
            }

            fun channels() {
                val path = Paths.get("my_file.txt")
                val channel = FileChannel.open(path, READ, WRITE, TRUNCATE_EXISTING) // OK
                val channel2 = FileChannel.open(path, SPARSE) // ERROR 14
                val channel3 = FileChannel.open(path, DELETE_ON_CLOSE) // WARNING 3
                val channel4 = AsynchronousFileChannel.open(path, SPARSE) // ERROR 15
            }
            """
          )
          .indented(),
      )

    // No warnings after API level 26 with full library desugaring
    lint().files(manifest().minSdk(26), *testFiles).desugaring(Desugaring.FULL).run().expectClean()

    // Special warnings with library desugaring prior to API level 26
    lint()
      .files(manifest().minSdk(25), *testFiles)
      .desugaring(Desugaring.FULL)
      .run()
      .expect(
        """
        src/test/pkg/NioTest.java:16: Error: Using AsynchronousFileChannel is not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
                try(AsynchronousFileChannel open = AsynchronousFileChannel.open(path)) { // ERROR 1
                                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/NioTest.java:17: Error: Using AsynchronousFileChannel is not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
                    open.read(ByteBuffer.allocate(1024), 1024); // ERROR 2
                    ~~~~~~~~~
        src/test/pkg/NioTest.java:24: Error: Using a WatchService is not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
                fileSystem.newWatchService().poll(); // ERROR 3
                ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/NioTest.java:28: Error: POSIX file features are not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
                fileSystem.getUserPrincipalLookupService(); // ERROR 4
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/NioTest.java:32: Error: Creating links is not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
                fileSystem.provider().createLink(p1, p2); // ERROR 5
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/NioTest.java:33: Error: Creating links is not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
                fileSystem.provider().createSymbolicLink(p1, p2); // ERROR 6
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/NioTest.java:43: Error: When using core library desugaring on API levels lower than 26, file operations sometimes raise java.io.FileNotFoundException instead of java.nio.file.NoSuchFileException when a file is not found. They are both subclasses of java.io.IOException, so the issue can be mitigated by catching java.io.IOException instead. [NioDesugaring]
                } catch (java.nio.file.NoSuchFileException e) { // ERROR 7
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:25: Error: POSIX file features are not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
            val newPermissions = PosixFilePermissions.fromString("rwxr-xr-x") // ERROR 8
                                 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:26: Error: POSIX file features are not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
            Files.setPosixFilePermissions(path, newPermissions) // ERROR 9
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:29: Error: POSIX file features are not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
            val lookupService = path.fileSystem.userPrincipalLookupService // ERROR 10
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:30: Error: POSIX file features are not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
            val newOwner = lookupService.lookupPrincipalByName("new_owner_username") // ERROR 11
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:36: Error: POSIX file features are not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
            val newGroup: GroupPrincipal = lookupService.lookupPrincipalByGroupName("new_group_name") // ERROR 12
                                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:46: Error: POSIX file features are not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
            val permissions = PosixFilePermissions.asFileAttribute( // ERROR 13
                              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:63: Error: Option SPARSE is ignored by core library desugaring on API levels lower than 26 [NioDesugaring]
            val channel2 = FileChannel.open(path, SPARSE) // ERROR 14
                                                  ~~~~~~
        src/test/pkg/test.kt:65: Error: Using AsynchronousFileChannel is not supported by core library desugaring on API levels lower than 26 [NioDesugaring]
            val channel4 = AsynchronousFileChannel.open(path, SPARSE) // ERROR 15
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:56: Warning: With core library desugaring on API levels lower than 26, file copy with the COPY_ATTRIBUTES option does not copy attributes, though it is usually not important to copy basic attributes [NioDesugaring]
            Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES) // WARNING 1
                                          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:57: Warning: With core library desugaring on API levels lower than 26, file move with the ATOMIC_MOVE option uses file renaming. Linux does not guarantee renaming to be atomic, though it usually is, and the desugaring implementation will have the semantics of the OS. [NioDesugaring]
            Files.copy(path, destination, StandardCopyOption.ATOMIC_MOVE) // WARNING 2
                                          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:64: Warning: With core library desugaring on API levels lower than 26, DELETE_ON_CLOSE is only partially supported; the file is only closed when FileChannel is closed [NioDesugaring]
            val channel3 = FileChannel.open(path, DELETE_ON_CLOSE) // WARNING 3
                                                  ~~~~~~~~~~~~~~~
        15 errors, 3 warnings
        """
      )
  }

  fun testLibraryDesugaringFields() {
    try {
      val project =
        getProjectDir(
          null,
          manifest().minSdk(1),
          kotlin(
              """
            package test.pkg

            import java.nio.file.Path
            import kotlin.io.path.writeLines

            fun test(tempFile: Path) {
                tempFile.writeLines(listOf("Hello"), options = arrayOf(java.nio.file.StandardOpenOption.APPEND))
            }
            """
            )
            .indented(),
          java(
              """
            package test.pkg;

            import static java.nio.charset.StandardCharsets.UTF_8;

            import java.io.IOException;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.List;

            public class FieldTest {
                public void test(Path tempFile) throws IOException {
                    Files.write(tempFile, List.of("Hello"), UTF_8, java.nio.file.StandardOpenOption.APPEND);
                }
                public void test(Path tempFile, List<CharSequence> bytes, java.nio.charset.Charset cs) throws IOException {
                    Files.write(tempFile, bytes, cs, java.nio.file.StandardOpenOption.APPEND);
                }
            }
            """
            )
            .indented(),
        )

      val desugaringFile = File(project, "desugaring.xml")
      desugaringFile.writeText(
        """
        java/net/URLDecoder
        java/nio/charset/StandardCharsets
        java/nio/file/Files
        java/nio/file/SimpleFileVisitor
        java/nio/file/StandardCopyOption
        java/nio/file/StandardOpenOption
        java/time/DateTimeException
        java/util/List#of(Ljava/lang/Object;)Ljava/util/List;
        """
          .trimIndent()
      )
      MainTest.checkDriver(
        "No issues found.",
        "",
        LintCliFlags.ERRNO_SUCCESS,
        arrayOf(
          "-q",
          "--check",
          "NewApi",
          "--sdk-home",
          TestUtils.getSdk().toString(),
          "--text",
          "stdout",
          "--disable",
          "LintError",
          "--Xdesugared-methods",
          desugaringFile.path,
          project.path,
        ),
        null,
        null,
      )
    } finally {
      DesugaredMethodLookup.reset()
    }
  }

  private val gradleVersion24_language18 =
    gradle(
        """
        buildscript {
            repositories {
                mavenCentral()
            }
            dependencies {
                classpath 'com.android.tools.build:gradle:2.4.0-alpha8'
            }
        }
        android {
            compileOptions {
                sourceCompatibility JavaVersion.VERSION_1_8
                targetCompatibility JavaVersion.VERSION_1_8
            }
        }"""
      )
      .indented()

  private val gradleVersion24_language17 =
    gradle(
        """
        buildscript {
            repositories {
                mavenCentral()
            }
            dependencies {
                classpath 'com.android.tools.build:gradle:2.4.0-alpha8'
            }
        }
        android {
            compileOptions {
                sourceCompatibility JavaVersion.VERSION_1_7
                targetCompatibility JavaVersion.VERSION_1_7
            }
        }"""
      )
      .indented()

  private val gradleVersion231 =
    gradle(
        """
        buildscript {
            repositories {
                mavenCentral()
            }
            dependencies {
                classpath 'com.android.tools.build:gradle:2.3.1'
            }
        }"""
      )
      .indented()

  private val tryWithResources =
    java(
        """
        package test.pkg;

        import java.io.BufferedReader;
        import java.io.FileReader;
        import java.io.IOException;
        @SuppressWarnings({"ClassNameDiffersFromFileName", "MethodMayBeStatic"})
        public class TryWithResources {
            public String testTryWithResources(String path) throws IOException {
                try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                    return br.readLine();
                }
            }
        }
        """
      )
      .indented()

  private val multiCatch =
    java(
        """
        package test.pkg;

        import java.lang.reflect.InvocationTargetException;

        @SuppressWarnings({"ClassNameDiffersFromFileName", "MethodMayBeStatic", "TryWithIdenticalCatches"})
        public class MultiCatch {
            public void testMultiCatch() {
                try {
                    Class.forName("java.lang.Integer").getMethod("toString").invoke(null);
                } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
        """
      )
      .indented()

  override fun getDetector(): Detector = ApiDetector()
}
