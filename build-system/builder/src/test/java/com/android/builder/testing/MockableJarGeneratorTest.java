/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.builder.testing;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.android.SdkConstants;
import com.android.testutils.TestResources;
import com.android.testutils.TestUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MockableJarGeneratorTest {

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Test
    public void testJarRewriting() throws Exception {
        MockableJarGenerator generator = new MockableJarGenerator(true);

        File inputJar =
                TestResources.getFile(
                        MockableJarGenerator.class, "/testData/testing/non-mockable.jar");
        File outputJar = new File(mTemporaryFolder.newFolder(),"mockable.jar");

        generator.createMockableJar(inputJar, outputJar);

        assertTrue(outputJar.exists());

        try (JarFile jarFile = new JarFile(outputJar)) {
            Set<String> names =
                    Collections.list(jarFile.entries())
                            .stream()
                            .map(JarEntry::getName)
                            .collect(Collectors.toSet());
            assertThat(names)
                    .containsExactly(
                            "META-INF/MANIFEST.MF",
                            "SomeResource",
                            "NonFinalClass.class",
                            "FinalClass.class");
        }
    }

    @Test
    public void realAndroidJar_returnDefaultValues() throws Exception {
        File output = generate(androidJar(), true);

        try (JarFile jarFile = new JarFile(output)) {
            List<String> javaEntries =
                    jarFile.stream()
                            .map(ZipEntry::getName)
                            .filter(name -> name.startsWith("java/"))
                            .collect(Collectors.toList());
            assertThat(javaEntries).isEmpty();
        }
        try (URLClassLoader loader = newClassLoader(output)) {
            // The stub constructor passes arguments to its super constructor.
            Class<?> context = loader.loadClass("android.content.Context");
            Object textView =
                    loader.loadClass("android.widget.TextView")
                            .getConstructor(context)
                            .newInstance((Object) null);
            assertThat(invoke(textView, "getText")).isNull();

            Object bundle = loader.loadClass("android.os.Bundle").getConstructor().newInstance();
            assertThat(invoke(bundle, "size")).isEqualTo(0);
            assertThat(invoke(bundle, "isEmpty")).isEqualTo(false);

            // Enums keep their static initializer, values() and valueOf().
            Class<?> config = loader.loadClass("android.graphics.Bitmap$Config");
            Object argb8888 = config.getMethod("valueOf", String.class).invoke(null, "ARGB_8888");
            assertThat(((Enum<?>) argb8888).name()).isEqualTo("ARGB_8888");
        }
    }

    @Test
    public void realAndroidJar_throwsNotMockedException() throws Exception {
        File output = generate(androidJar(), false);

        try (URLClassLoader loader = newClassLoader(output)) {
            Object bundle = loader.loadClass("android.os.Bundle").getConstructor().newInstance();
            InvocationTargetException e =
                    assertThrows(InvocationTargetException.class, () -> invoke(bundle, "size"));
            assertThat(e.getCause())
                    .hasMessageThat()
                    .isEqualTo(
                            "Method size in android.os.BaseBundle not mocked. See"
                                    + " https://developer.android.com/r/studio-ui/build/not-mocked"
                                    + " for details.");
        }
    }

    @Test
    public void outputIsDeterministic() throws Exception {
        File first;
        File second;
        TimeZone defaultTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            first = generate(nonMockableJar(), true);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
            second = generate(nonMockableJar(), true);
        } finally {
            TimeZone.setDefault(defaultTimeZone);
        }

        assertThat(Files.readAllBytes(second.toPath()))
                .isEqualTo(Files.readAllBytes(first.toPath()));
        try (JarFile jarFile = new JarFile(first)) {
            Set<FileTime> timestamps =
                    jarFile.stream().map(ZipEntry::getLastModifiedTime).collect(Collectors.toSet());
            assertThat(timestamps).containsExactly(FileTime.fromMillis(0));
        }
    }

    @Test
    public void overwritesExistingOutput() throws Exception {
        File expected = generate(nonMockableJar(), true);
        File output = new File(mTemporaryFolder.newFolder(), "mockable.jar");
        Files.write(output.toPath(), "stale".getBytes(StandardCharsets.UTF_8));

        new MockableJarGenerator(true).createMockableJar(nonMockableJar(), output);

        assertThat(Files.readAllBytes(output.toPath()))
                .isEqualTo(Files.readAllBytes(expected.toPath()));
    }

    @Test
    public void outputHasDefaultFilePermissions() throws Exception {
        File outputDir = mTemporaryFolder.newFolder();
        assumeTrue(Files.getFileStore(outputDir.toPath()).supportsFileAttributeView("posix"));
        File output = new File(outputDir, "mockable.jar");
        Path reference = Files.createFile(outputDir.toPath().resolve("reference"));

        new MockableJarGenerator(true).createMockableJar(nonMockableJar(), output);

        assertThat(Files.getPosixFilePermissions(output.toPath()))
                .isEqualTo(Files.getPosixFilePermissions(reference));
    }

    @Test
    public void rejectsOutputThatIsTheInput() throws Exception {
        File dir = mTemporaryFolder.newFolder();
        File jar = new File(dir, "android.jar");
        byte[] original = Files.readAllBytes(nonMockableJar().toPath());
        Files.write(jar.toPath(), original);
        // A different path to the same file.
        File sameJar = new File(new File(dir, "."), "android.jar");

        assertThrows(
                IllegalArgumentException.class,
                () -> new MockableJarGenerator(true).createMockableJar(jar, sameJar));
        assertThat(Files.readAllBytes(jar.toPath())).isEqualTo(original);
    }

    @Test
    public void unreadableInputFailsAndKeepsExistingOutput() throws Exception {
        File dir = mTemporaryFolder.newFolder();
        assumeTrue(Files.getFileStore(dir.toPath()).supportsFileAttributeView("posix"));
        Path input = dir.toPath().resolve("android.jar");
        Files.write(input, Files.readAllBytes(nonMockableJar().toPath()));
        File output = new File(dir, "mockable.jar");
        byte[] previous = "previous".getBytes(StandardCharsets.UTF_8);
        Files.write(output.toPath(), previous);
        Files.setPosixFilePermissions(input, Set.of());
        try {
            // Permissions are not enforced for root.
            assumeFalse(Files.isReadable(input));

            assertThrows(
                    IOException.class,
                    () -> new MockableJarGenerator(true).createMockableJar(input.toFile(), output));
            assertThat(dir.list()).asList().containsExactly("android.jar", "mockable.jar");
            assertThat(Files.readAllBytes(output.toPath())).isEqualTo(previous);
        } finally {
            Files.setPosixFilePermissions(input, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    public void failureKeepsExistingOutputAndLeavesNoPartialJar() throws Exception {
        File input = new File(mTemporaryFolder.newFolder(), "corrupt.jar");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(input))) {
            out.putNextEntry(new ZipEntry("Corrupt.class"));
            out.write("not a class file".getBytes(StandardCharsets.UTF_8));
        }
        File outputDir = mTemporaryFolder.newFolder();
        File output = new File(outputDir, "mockable.jar");
        MockableJarGenerator generator = new MockableJarGenerator(true);

        assertThrows(
                IllegalArgumentException.class, () -> generator.createMockableJar(input, output));
        assertThat(outputDir.list()).isEmpty();

        byte[] previous = "previous".getBytes(StandardCharsets.UTF_8);
        Files.write(output.toPath(), previous);
        assertThrows(
                IllegalArgumentException.class, () -> generator.createMockableJar(input, output));
        assertThat(outputDir.list()).asList().containsExactly("mockable.jar");
        assertThat(Files.readAllBytes(output.toPath())).isEqualTo(previous);
    }

    private File generate(File input, boolean returnDefaultValues) throws Exception {
        File output = new File(mTemporaryFolder.newFolder(), "mockable.jar");
        new MockableJarGenerator(returnDefaultValues).createMockableJar(input, output);
        return output;
    }

    private static File nonMockableJar() {
        return TestResources.getFile(
                MockableJarGenerator.class, "/testData/testing/non-mockable.jar");
    }

    private static File androidJar() {
        return TestUtils.resolvePlatformPath(
                        SdkConstants.FN_FRAMEWORK_LIBRARY, TestUtils.TestType.AGP)
                .toFile();
    }

    private static URLClassLoader newClassLoader(File jar) throws Exception {
        return new URLClassLoader(
                new URL[] {jar.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        return target.getClass().getMethod(methodName).invoke(target);
    }
}
