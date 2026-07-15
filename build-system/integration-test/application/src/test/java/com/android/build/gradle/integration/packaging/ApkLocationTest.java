/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.build.gradle.integration.packaging;

import static com.android.build.gradle.integration.common.truth.TruthHelper.assertThat;
import static com.android.testutils.truth.PathSubject.assertThat;

import static org.junit.Assert.assertNotNull;

import com.android.build.api.variant.impl.BuiltArtifactsImpl;
import com.android.build.gradle.integration.common.fixture.GradleTestProject;
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp;
import com.android.build.gradle.integration.common.utils.TestFileUtils;
import com.android.build.gradle.options.StringOption;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

public class ApkLocationTest {

    @Rule
    public GradleTestProject project = GradleTestProject.builder()
            .fromTestApp(HelloWorldApp.forPlugin("com.android.application"))
            .create();

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Test
    public void outputToInjectedLocation() throws Exception {
        project.executor()
                .with(StringOption.IDE_APK_LOCATION, mTemporaryFolder.getRoot().getAbsolutePath())
                .run("assembleDebug");

        File debugApkLocation = new File(mTemporaryFolder.getRoot(), "debug");
        File[] files = debugApkLocation.listFiles();
        assertNotNull(files);

        assertThat(countFiles(files, ".apk")).isEqualTo(1);
        assertThat(countFiles(files, BuiltArtifactsImpl.METADATA_FILE_NAME)).isEqualTo(1);
    }

    @Test
    public void outputToInjectedLocationWithShrinker() throws Exception {
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\n"
                        + "android {\n"
                        + "    buildTypes {\n"
                        + "        release {\n"
                        + "            minifyEnabled true\n"
                        + "            proguardFiles"
                        + " getDefaultProguardFile('proguard-android-optimize.txt'),"
                        + " 'proguard-rules.pro'\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");

        File proguardRulesFile = project.file("proguard-rules.pro");
        Files.writeString(proguardRulesFile.toPath(), "-dontoptimize\n");

        File fooFile = project.file("src/main/java/com/example/helloworld/Foo.java");
        fooFile.getParentFile().mkdirs();
        Files.writeString(
                fooFile.toPath(),
                "package com.example.helloworld;\n"
                        + "public class Foo {\n"
                        + "    public void bar() {\n"
                        + "        System.out.println(\"bar\");\n"
                        + "    }\n"
                        + "}\n");

        File helloWorldFile = project.file("src/main/java/com/example/helloworld/HelloWorld.java");
        String helloWorldContent = Files.readString(helloWorldFile.toPath());
        helloWorldContent =
                helloWorldContent.replace(
                        "setContentView(R.layout.main);",
                        "setContentView(R.layout.main); new Foo().bar();");
        Files.writeString(helloWorldFile.toPath(), helloWorldContent);

        project.executor()
                .with(StringOption.IDE_APK_LOCATION, mTemporaryFolder.getRoot().getAbsolutePath())
                .run("assembleRelease");
        File defaultMapping =
                new File(
                        project.getBuildFile().getParentFile(),
                        "build/outputs/mapping/release/mapping.txt");
        File releaseApkLocation = new File(mTemporaryFolder.getRoot(), "release");

        assertThat(defaultMapping.exists()).isTrue();

        File[] files = releaseApkLocation.listFiles();
        assertNotNull(files);

        assertThat(countFiles(files, ".apk")).isEqualTo(1);
        assertThat(countFiles(files, "output-metadata.json")).isEqualTo(1);
        assertThat(countFiles(files, "mapping.txt")).isEqualTo(1);
    }

    @Test
    public void verifyTaskDecoupling() throws Exception {
        File apkRedirectDir = mTemporaryFolder.newFolder("apk_redirect");
        com.android.build.gradle.integration.common.fixture.GradleBuildResult resultApk =
                project.executor()
                        .with(StringOption.IDE_APK_LOCATION, apkRedirectDir.getAbsolutePath())
                        .run("assembleDebug");

        assertThat(resultApk.getTasks()).contains(":packageDebug");
        assertThat(resultApk.getTasks()).doesNotContain(":signDebugBundle");
        assertThat(resultApk.getTasks()).doesNotContain(":bundleDebug");

        File bundleRedirectDir = mTemporaryFolder.newFolder("bundle_redirect");
        com.android.build.gradle.integration.common.fixture.GradleBuildResult resultBundle =
                project.executor()
                        .with(StringOption.IDE_APK_LOCATION, bundleRedirectDir.getAbsolutePath())
                        .run("bundleDebug");

        assertThat(resultBundle.getTasks()).contains(":bundleDebug");
        assertThat(resultBundle.getTasks()).doesNotContain(":packageDebug");
        assertThat(resultBundle.getTasks()).doesNotContain(":assembleDebug");
    }

    @Test
    public void bundleRedirection() throws Exception {
        File bundleRedirectDir = mTemporaryFolder.newFolder("bundle_redirect");
        project.executor()
                .with(StringOption.IDE_APK_LOCATION, bundleRedirectDir.getAbsolutePath())
                .run("bundleDebug");

        File debugBundleLocation = new File(bundleRedirectDir, "debug");
        File[] files = debugBundleLocation.listFiles();
        assertNotNull(files);

        assertThat(countFiles(files, ".aab")).isEqualTo(1);
    }

    @Test
    public void buildCacheHitRedirection() throws Exception {
        File cacheDir = mTemporaryFolder.newFolder("cache");
        File redirectDir1 = mTemporaryFolder.newFolder("redirect1");
        File redirectDir2 = mTemporaryFolder.newFolder("redirect2");

        // Build 1: populate cache
        project.executor()
                .with(StringOption.IDE_APK_LOCATION, redirectDir1.getAbsolutePath())
                .withArgument("--build-cache")
                .withArgument("-Dgradle.cache.local.directory=" + cacheDir.getAbsolutePath())
                .run("assembleDebug");

        // Clean local build directory to force cache hit
        project.execute("clean");

        // Build 2: should hit cache and still redirect outputs
        com.android.build.gradle.integration.common.fixture.GradleBuildResult result =
                project.executor()
                        .with(StringOption.IDE_APK_LOCATION, redirectDir2.getAbsolutePath())
                        .withArgument("--build-cache")
                        .withArgument(
                                "-Dgradle.cache.local.directory=" + cacheDir.getAbsolutePath())
                        .run("assembleDebug");

        // Verify task was loaded from cache
        assertThat(result.getFromCacheTasks()).contains(":compileDebugJavaWithJavac");

        // Verify APK was still copied to the second redirect directory
        File debugApkLocation = new File(redirectDir2, "debug");
        File[] files = debugApkLocation.listFiles();
        assertNotNull(files);
        assertThat(countFiles(files, ".apk")).isEqualTo(1);
    }

    @Test
    public void outputLocationChangedWithoutClean() throws Exception {
        File redirectDir1 = mTemporaryFolder.newFolder("redirect1");
        File redirectDir2 = mTemporaryFolder.newFolder("redirect2");

        // Build 1 - to redirect1
        project.executor()
                .with(StringOption.IDE_APK_LOCATION, redirectDir1.getAbsolutePath())
                .run("assembleDebug");

        File debugApkLocation1 = new File(redirectDir1, "debug");
        assertThat(debugApkLocation1.listFiles()).isNotNull();

        // Build 2 - immediately to redirect2, without clean
        project.executor()
                .with(StringOption.IDE_APK_LOCATION, redirectDir2.getAbsolutePath())
                .run("assembleDebug");

        File debugApkLocation2 = new File(redirectDir2, "debug");
        File[] files = debugApkLocation2.listFiles();
        assertNotNull(files);
        assertThat(countFiles(files, ".apk")).isEqualTo(1);
    }

    @Test
    public void outputRestoredWhenDeleted() throws Exception {
        File redirectDir = mTemporaryFolder.newFolder("redirect");

        // Build 1
        project.executor()
                .with(StringOption.IDE_APK_LOCATION, redirectDir.getAbsolutePath())
                .run("assembleDebug");

        File debugApkLocation = new File(redirectDir, "debug");
        File[] files = debugApkLocation.listFiles();
        assertNotNull(files);
        File apkFile = findFile(files, ".apk");
        assertNotNull(apkFile);

        // Simulate manual deletion of APK by user or IDE
        assertThat(apkFile.delete()).isTrue();

        // Build 2, without clean
        project.executor()
                .with(StringOption.IDE_APK_LOCATION, redirectDir.getAbsolutePath())
                .run("assembleDebug");

        // Verify APK was correctly restored
        File[] filesAfter = debugApkLocation.listFiles();
        assertNotNull(filesAfter);
        assertThat(countFiles(filesAfter, ".apk")).isEqualTo(1);
    }

    private static long countFiles(File[] files, String suffixOrName) {
        if (files == null) return 0;
        return Arrays.stream(files)
                .filter(
                        file ->
                                file.isFile()
                                        && (file.getName().endsWith(suffixOrName)
                                                || file.getName().equals(suffixOrName)))
                .count();
    }

    private static File findFile(File[] files, String suffixOrName) {
        if (files == null) return null;
        return Arrays.stream(files)
                .filter(
                        file ->
                                file.isFile()
                                        && (file.getName().endsWith(suffixOrName)
                                                || file.getName().equals(suffixOrName)))
                .findFirst()
                .orElse(null);
    }
}
