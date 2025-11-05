/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.internal.plugins;


import static com.google.common.truth.Truth.assertThat;

import com.android.SdkConstants;
import com.android.Version;
import com.android.build.api.dsl.ApplicationBuildType;
import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.variant.ApplicationVariantBuilder;
import com.android.build.api.variant.Component;
import com.android.build.api.variant.ComponentIdentity;
import com.android.build.api.variant.impl.VariantImpl;
import com.android.build.gradle.internal.component.ApplicationCreationConfig;
import com.android.build.gradle.internal.component.TestComponentCreationConfig;
import com.android.build.gradle.internal.component.VariantCreationConfig;
import com.android.build.gradle.internal.errors.SyncIssueReporterImpl;
import com.android.build.gradle.internal.fixture.AppVariantCreationConfigChecker;
import com.android.build.gradle.internal.fixture.TestConstants;
import com.android.build.gradle.internal.fixture.TestProjects;
import com.android.build.gradle.internal.fixture.VariantCheckers;
import com.android.build.gradle.internal.fixture.VariantCreationConfigChecker;
import com.android.build.gradle.internal.services.BuildServicesKt;
import com.android.build.gradle.internal.variant.ComponentInfo;
import com.android.build.gradle.options.BooleanOption;
import com.android.builder.core.ToolsRevisionUtils;
import com.android.builder.model.SyncIssue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.truth.Truth;

import groovy.util.Eval;

import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;
import org.gradle.api.tasks.compile.JavaCompile;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Tests for checking the "application" and "atom" DSLs. */
public class PluginDslTest {

    @Rule public TemporaryFolder projectDirectory = new TemporaryFolder();
    private AppPlugin plugin;
    private ApplicationExtension android;
    private Project project;

    @Before
    public void setUp() throws Exception {
        project =
                TestProjects.builder(projectDirectory.newFolder("project").toPath())
                        .withPlugin(TestProjects.Plugin.APP)
                        .withProperty(BooleanOption.IDE_BUILD_MODEL_ONLY_ADVANCED, true)
                        // turns this on to disable unsafe read checks in properties.
                        .withProperty("_agp_internal_test_mode_", "true")
                        .build();
        android = project.getExtensions().getByType(ApplicationExtension.class);
        android.setCompileSdk(TestConstants.COMPILE_SDK_VERSION);
        android.setBuildToolsVersion(TestConstants.BUILD_TOOL_VERSION);
        android.setNamespace("com.example.namespace");
        android.getBuildFeatures().setAidl(true);
        plugin = project.getPlugins().getPlugin(AppPlugin.class);
    }

    @Test
    public void testMultiRes() {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.android {\n"
                        + "\n"
                        + "    sourceSets.main.res.srcDirs 'src/main/res1', 'src/main/res2'\n"
                        + "}\n");

        // nothing to be done here. If the DSL fails, it'll throw an exception
    }


    @Test
    public void testAdb() {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.android {\n"
                        + "    adbOptions {\n"
                        + "        timeOutInMs 180000 \n"
                        + "        installOptions = ['-d','-t'] \n"
                        + "    }\n"
                        + "    installation {\n"
                        + "        timeOutInMs 180000 \n"
                        + "        installOptions = ['-d','-t'] \n"
                        + "    }\n"
                        + "}\n");

        assertThat(android.getAdbOptions().getTimeOutInMs()).isEqualTo(180000);
        assertThat(android.getAdbOptions().getInstallOptions()).isEqualTo(List.of("-d", "-t"));
        assertThat(android.getInstallation().getTimeOutInMs()).isEqualTo(180000);
        assertThat(android.getInstallation().getInstallOptions()).isEqualTo(List.of("-d", "-t"));
    }

    @Test
    public void testSourceSetsApi() {
        // query the sourceSets, will throw if missing
        Eval.me(
                "project",
                project,
                "    println project.android.sourceSets.main.java.srcDirs\n"
                        + "println project.android.sourceSets.main.resources.srcDirs\n"
                        + "println project.android.sourceSets.main.manifest.srcFile\n"
                        + "println project.android.sourceSets.main.res.srcDirs\n"
                        + "println project.android.sourceSets.main.assets.srcDirs");
    }

    @Test
    public void testProguardFiles_oldDsl() throws Exception {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.android {\n"
                        + "    buildTypes {\n"
                        + "        release {\n"
                        + "            proguardFile 'file1.1'\n"
                        + "            proguardFiles 'file1.2', 'file1.3'\n"
                        + "        }\n"
                        + "\n"
                        + "        custom {\n"
                        + "            proguardFile 'file3.1'\n"
                        + "            proguardFiles 'file3.2', 'file3.3'\n"
                        + "            proguardFiles = ['file3.1']\n"
                        + "        }\n"
                        + "    }\n"
                        + "\n"
                        + "    flavorDimensions 'foo'\n"
                        + "    productFlavors {\n"
                        + "        f1 {\n"
                        + "            proguardFile 'file2.1'\n"
                        + "            proguardFiles 'file2.2', 'file2.3'\n"
                        + "        }\n"
                        + "\n"
                        + "        f2  {\n"
                        + "\n"
                        + "        }\n"
                        + "\n"
                        + "        f3 {\n"
                        + "            proguardFile 'file4.1'\n"
                        + "            proguardFiles 'file4.2', 'file4.3'\n"
                        + "            proguardFiles = ['file4.1']\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
        plugin.createAndroidTasks(project);

        Map<String, List<String>> expected = new TreeMap<>();
        expected.put(
                "f1Release",
                ImmutableList.of("file1.1", "file1.2", "file1.3", "file2.1", "file2.2", "file2.3"));
        expected.put("f1Debug", ImmutableList.of("file2.1", "file2.2", "file2.3"));
        expected.put("f2Release", ImmutableList.of("file1.1", "file1.2", "file1.3"));
        expected.put("f2Custom", ImmutableList.of("file3.1"));
        expected.put("f3Custom", ImmutableList.of("file3.1", "file4.1"));

        checkProguardFiles(expected);
    }

    @Test
    public void testBuildTypes() {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.android {\n"
                        + "    testBuildType 'staging'\n"
                        + "\n"
                        + "    buildTypes {\n"
                        + "        staging {\n"
                        + "            signingConfig = signingConfigs.debug\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");

        plugin.createAndroidTasks(project);
        LinkedHashMap<String, Integer> map = new LinkedHashMap<>(3);
        map.put("appVariants", 3);
        map.put("unitTest", 1);
        map.put("androidTests", 1);
        VariantCreationConfigChecker checker = new AppVariantCreationConfigChecker(plugin);

        assertThat(VariantCheckers.countVariants(map))
                .isEqualTo(
                        plugin.getVariantManager().getMainComponents().size()
                                + plugin.getVariantManager().getTestComponents().size());

        assertThat(
                        checker.getTestComponents().stream()
                                .map(ComponentIdentity::getName)
                                .collect(Collectors.toList()))
                .named("test variant list")
                .containsExactly("stagingAndroidTest", "stagingUnitTest");

        checker.checkTestedVariant("staging", "stagingAndroidTest", null, null);
        checker.checkNonTestedVariant("debug", null);
        checker.checkNonTestedVariant("release", null);
    }

    @Test
    public void testFlavors() {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.android {\n"
                        + "    flavorDimensions 'foo'\n"
                        + "    productFlavors {\n"
                        + "        flavor1 {\n"
                        + "\n"
                        + "        }\n"
                        + "        flavor2 {\n"
                        + "\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");

        plugin.createAndroidTasks(project);
        LinkedHashMap<String, Integer> map = new LinkedHashMap<>(3);
        map.put("appVariants", 4);
        map.put("unitTest", 2);
        map.put("androidTests", 2);
        VariantCreationConfigChecker checker = new AppVariantCreationConfigChecker(plugin);

        assertThat(VariantCheckers.countVariants(map))
                .isEqualTo(
                        plugin.getVariantManager().getMainComponents().size()
                                + plugin.getVariantManager().getTestComponents().size());

        // we can now call this since the variants/tasks have been created

        // does not include tests
        Set<VariantCreationConfig> variants = checker.getMainVariants();
        assertThat(variants).named("variant list").hasSize(4);

        Set<TestComponentCreationConfig> testVariants = checker.getTestComponents();
        assertThat(testVariants).named("test variant list").hasSize(4);

        checker.checkTestedVariant("flavor1Debug", "flavor1DebugAndroidTest", null, null);
        checker.checkTestedVariant("flavor2Debug", "flavor2DebugAndroidTest", null, null);

        checker.checkNonTestedVariant("flavor1Release", null);
        checker.checkNonTestedVariant("flavor2Release", null);
    }

    @Test
    public void testMultiFlavors() {
        Eval.me(
                "project",
                project,
                "\n"
                    + "project.android {\n"
                    + "    flavorDimensions   'dimension1', 'dimension2'\n"
                    + "\n"
                    + "    productFlavors {\n"
                    + "        f1 {\n"
                    + "            dimension   'dimension1'\n"
                    + "            javaCompileOptions.annotationProcessorOptions.className 'f1'\n"
                    + "        }\n"
                    + "        f2 {\n"
                    + "            dimension   'dimension1'\n"
                    + "            javaCompileOptions.annotationProcessorOptions.className 'f2'\n"
                    + "        }\n"
                    + "\n"
                    + "        fa {\n"
                    + "            dimension   'dimension2'\n"
                    + "            javaCompileOptions.annotationProcessorOptions.className 'fa'\n"
                    + "        }\n"
                    + "        fb {\n"
                    + "            dimension   'dimension2'\n"
                    + "            javaCompileOptions.annotationProcessorOptions.className 'fb'\n"
                    + "        }\n"
                    + "        fc {\n"
                    + "            dimension   'dimension2'\n"
                    + "            javaCompileOptions.annotationProcessorOptions.className 'fc'\n"
                    + "        }\n"
                    + "    }\n"
                    + "}\n");

        plugin.createAndroidTasks(project);
        VariantCreationConfigChecker checker = new AppVariantCreationConfigChecker(plugin);

        ImmutableMap<String, Integer> map =
                ImmutableMap.of("appVariants", 12, "unitTests", 6, "androidTests", 6);
        assertThat(VariantCheckers.countVariants(map))
                .isEqualTo(checker.getMainVariants().size() + checker.getTestComponents().size());

        // we can now call this since the variants/tasks have been created

        // does not include tests
        Set<VariantCreationConfig> variants = checker.getMainVariants();
        Truth.assertThat(variants).named("variant list").hasSize(12);

        Set<TestComponentCreationConfig> testVariants = checker.getTestComponents();
        Truth.assertThat(testVariants).named("test variant list").hasSize(12);

        checker.checkTestedVariant("f1FaDebug", "f1FaDebugAndroidTest", null, null);
        checker.checkTestedVariant("f1FbDebug", "f1FbDebugAndroidTest", null, null);
        checker.checkTestedVariant("f1FcDebug", "f1FcDebugAndroidTest", null, null);
        checker.checkTestedVariant("f2FaDebug", "f2FaDebugAndroidTest", null, null);
        checker.checkTestedVariant("f2FbDebug", "f2FbDebugAndroidTest", null, null);
        checker.checkTestedVariant("f2FcDebug", "f2FcDebugAndroidTest", null, null);

        checker.checkNonTestedVariant("f1FaRelease", null);
        checker.checkNonTestedVariant("f1FbRelease", null);
        checker.checkNonTestedVariant("f1FcRelease", null);
        checker.checkNonTestedVariant("f2FaRelease", null);
        checker.checkNonTestedVariant("f2FbRelease", null);
        checker.checkNonTestedVariant("f2FcRelease", null);
    }

    @Test
    public void testAdbExe() throws Exception {
        VariantCreationConfigChecker checker = new AppVariantCreationConfigChecker(plugin);

        for (VariantCreationConfig variant : checker.getMainVariants()) {
            assertThat(variant.getGlobal().getVersionedSdkLoader().get().getAdbExecutableProvider())
                    .isNotNull();
            assertThat(
                            variant.getGlobal()
                                    .getVersionedSdkLoader()
                                    .get()
                                    .getAdbHelper()
                                    .get()
                                    .getAdbExecutable())
                    .isNotNull();
        }
    }

    @Test
    public void testProguardFiles_newDsl() throws Exception {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.android {\n"
                        + "\n"
                        + "    flavorDimensions 'foo'\n"
                        + "    productFlavors {\n"
                        + "        f1 {\n"
                        + "            proguardFile 'file2.1'\n"
                        + "            proguardFiles 'file2.2', 'file2.3'\n"
                        + "        }\n"
                        + "\n"
                        + "        f3 {\n"
                        + "            proguardFile 'file4.1'\n"
                        + "            proguardFiles 'file4.2', 'file4.3'\n"
                        + "            proguardFiles = ['file4.1']\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
        plugin.createAndroidTasks(project);

        Map<String, List<String>> expected = new TreeMap<>();
        expected.put(
                "f1Release",
                ImmutableList.of(
                        "file2.1",
                        "file2.2",
                        "file2.3"));

        // The custom build type uses setProguardFiles, so the default file will not be there.
        expected.put("f3Debug", ImmutableList.of("file4.1"));

        checkProguardFiles(expected);
    }

    @Test
    public void testSettingLanguageLevelFromCompileSdk_doNotOverride() {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.android {\n"
                        + "    compileOptions {\n"
                        + "        sourceCompatibility '1.6'\n"
                        + "        targetCompatibility '1.6'\n"
                        + "    }\n"
                        + "}\n");
        plugin.createAndroidTasks(project);

        JavaCompile compileReleaseJavaWithJavac =
                (JavaCompile) project.getTasks().getByName("compileReleaseJavaWithJavac");

        assertThat(compileReleaseJavaWithJavac.getTargetCompatibility())
                .named("target compat")
                .isEqualTo(JavaVersion.VERSION_1_6.toString());
        assertThat(compileReleaseJavaWithJavac.getSourceCompatibility())
                .named("source compat")
                .isEqualTo(JavaVersion.VERSION_1_6.toString());
    }

    @Test
    public void testSettingLanguageLevelFromCompileSdkWithJavaVersion_doNotOverride() {
        Eval.me(
                "project",
                project,
                "\n"
                        + "import org.gradle.api.JavaVersion\n"
                        + "project.android {\n"
                        + "    compileOptions {\n"
                        + "        sourceCompatibility = JavaVersion.VERSION_1_8\n"
                        + "        targetCompatibility = JavaVersion.VERSION_1_8\n"
                        + "    }\n"
                        + "}\n");
        plugin.createAndroidTasks(project);

        JavaCompile compileReleaseJavaWithJavac =
                (JavaCompile) project.getTasks().getByName("compileReleaseJavaWithJavac");

        assertThat(compileReleaseJavaWithJavac.getTargetCompatibility())
                .named("target compat")
                .isEqualTo(JavaVersion.VERSION_1_8.toString());
        assertThat(compileReleaseJavaWithJavac.getSourceCompatibility())
                .named("source compat")
                .isEqualTo(JavaVersion.VERSION_1_8.toString());
    }

    @Test
    public void testMockableJarName() {
        android.compileSdkAddon(
                "Google Inc.", "Google APIs", TestConstants.COMPILE_SDK_VERSION_WITH_GOOGLE_APIS);
        plugin.createAndroidTasks(project);
        Map<String, VariantCreationConfig> componentMap = getComponentMap();
        Map.Entry<String, VariantCreationConfig> vsentry =
                componentMap.entrySet().iterator().next();

        File mockableJarFile =
                vsentry.getValue().getGlobal().getMockableJarArtifact().getSingleFile();
        assertThat(mockableJarFile).isNotNull();

        if (SdkConstants.CURRENT_PLATFORM != SdkConstants.PLATFORM_WINDOWS) {
            // Windows paths contain : to identify drives.
            assertThat(mockableJarFile.getAbsolutePath())
                    .named("Mockable jar Path")
                    .doesNotContain(":");
        }

        assertThat(mockableJarFile.getName()).named("Mockable jar name").isEqualTo("android.jar");
    }

    @Test
    public void testEncoding() {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.android {\n"
                        + "\n"
                        + "    compileOptions {\n"
                        + "       encoding 'foo'\n"
                        + "    }\n"
                        + "}\n");
        plugin.createAndroidTasks(project);

        JavaCompile compileReleaseJavaWithJavac =
                (JavaCompile) project.getTasks().getByName("compileReleaseJavaWithJavac");

        assertThat(compileReleaseJavaWithJavac.getOptions().getEncoding())
                .named("source encoding")
                .isEqualTo("foo");
    }

    /** Make sure DSL objects don't need "=" everywhere. */
    @Test
    public void testSetters() throws Exception {

        Eval.me(
                "project",
                project,
                "\n"
                        + "project.android {\n"
                        + "\n"
                        + "\n"
                        + "    buildTypes {\n"
                        + "        debug {\n"
                        + "            shrinkResources = true\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n"
                        + "\n");
        ApplicationBuildType debug = android.getBuildTypes().getByName("debug");
        assertThat(debug.isShrinkResources()).isTrue();
    }

    @Test
    public void testTestOptionsExecution() throws Exception {
        android.getTestOptions().setExecution("android_test_orchestrator");
        assertThat(android.getTestOptions().getExecution()).isEqualTo("android_test_orchestrator");
    }

    @Ignore("b/192070233")
    @Test
    public void testSetOlderBuildToolsVersion() {
        android.setBuildToolsVersion("19.0.0");
        plugin.createAndroidTasks(project);

        assertThat(
                        plugin.getVersionedSdkLoaderService()
                                .getVersionedSdkLoader()
                                .get()
                                .getBuildToolsRevisionProvider()
                                .get())
                .isEqualTo(ToolsRevisionUtils.DEFAULT_BUILD_TOOLS_REVISION);
        // FIXME once we get rid of the component model, we can make this better.
        SyncIssueReporterImpl.GlobalSyncIssueService issueReporter =
                BuildServicesKt.getBuildService(
                                project.getGradle().getSharedServices(),
                                SyncIssueReporterImpl.GlobalSyncIssueService.class)
                        .get();
        Collection<SyncIssue> syncIssues = issueReporter.getAllIssuesAndClear();
        assertThat(syncIssues).hasSize(1);
        SyncIssue issue = Iterables.getOnlyElement(syncIssues);
        assertThat(issue.getType()).isEqualTo(SyncIssue.TYPE_BUILD_TOOLS_TOO_LOW);
        assertThat(issue.getSeverity()).isEqualTo(SyncIssue.SEVERITY_WARNING);
        assertThat(issue.getMessage())
                .isEqualTo(
                        "The specified Android SDK Build Tools version (19.0.0) is "
                                + "ignored, as it is below the minimum supported version ("
                                + ToolsRevisionUtils.MIN_BUILD_TOOLS_REV
                                + ") for Android Gradle Plugin "
                                + Version.ANDROID_GRADLE_PLUGIN_VERSION
                                + ".\n"
                                + "Android SDK Build Tools "
                                + ToolsRevisionUtils.DEFAULT_BUILD_TOOLS_REVISION
                                + " will be used.\n"
                                + "To suppress this warning, remove \"buildToolsVersion '19.0.0'\""
                                + " from your build.gradle file, as each version of the Android"
                                + " Gradle Plugin now has a default version of the build tools.");
    }

    private void checkNestedComponents(
            VariantCreationConfig variant,
            boolean unitTestsEnabled,
            boolean androidTestsEnabled,
            boolean testFixturesEnabled) {
        List<String> expected = Lists.newArrayList();
        if (unitTestsEnabled) {
            expected.add(variant.getName() + "UnitTest");
        }
        if (androidTestsEnabled) {
            expected.add(variant.getName() + "AndroidTest");
        }
        if (testFixturesEnabled) {
            expected.add(variant.getName() + "TestFixtures");
        }
        assertThat(
                        Lists.transform(
                                ((VariantImpl) variant).getNestedComponents(), Component::getName))
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void testNestedComponents() {
        plugin.createAndroidTasks(project);
        List<VariantCreationConfig> variants =
                plugin.getVariantManager().getMainComponents().stream()
                        .map(ComponentInfo::getVariant)
                        .collect(Collectors.toList());

        assertThat(variants.size()).isEqualTo(2);

        VariantCreationConfig debugVariant =
                variants.stream().filter(it -> it.getName().equals("debug")).findFirst().get();
        VariantCreationConfig releaseVariant =
                variants.stream().filter(it -> it.getName().equals("release")).findFirst().get();

        checkNestedComponents(debugVariant, true, true, false);
        checkNestedComponents(releaseVariant, false, false, false);
    }

    @Test
    public void testNestedComponentsWithTestFixturesEnabled() {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.android {\n"
                        + "\n"
                        + "\n"
                        + "    testFixtures {\n"
                        + "        enable = true\n"
                        + "    }\n"
                        + "}\n"
                        + "\n");

        plugin.createAndroidTasks(project);
        List<VariantCreationConfig> variants =
                plugin.getVariantManager().getMainComponents().stream()
                        .map(ComponentInfo::getVariant)
                        .collect(Collectors.toList());

        assertThat(variants.size()).isEqualTo(2);

        VariantCreationConfig debugVariant =
                variants.stream().filter(it -> it.getName().equals("debug")).findFirst().get();
        VariantCreationConfig releaseVariant =
                variants.stream().filter(it -> it.getName().equals("release")).findFirst().get();

        checkNestedComponents(debugVariant, true, true, true);
        checkNestedComponents(releaseVariant, false, false, true);
    }

    @Test
    public void testNestedComponentsWithReleaseUnitTestsDisabled() {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.androidComponents {\n"
                        + "\n"
                        + "\n"
                        + "    beforeVariants(selector().withBuildType(\"release\")) {\n"
                        + "        enableUnitTest false\n"
                        + "    }\n"
                        + "}\n"
                        + "\n");

        plugin.createAndroidTasks(project);
        List<VariantCreationConfig> variants =
                plugin.getVariantManager().getMainComponents().stream()
                        .map(ComponentInfo::getVariant)
                        .collect(Collectors.toList());

        assertThat(variants.size()).isEqualTo(2);

        VariantCreationConfig debugVariant =
                variants.stream().filter(it -> it.getName().equals("debug")).findFirst().get();
        VariantCreationConfig releaseVariant =
                variants.stream().filter(it -> it.getName().equals("release")).findFirst().get();

        checkNestedComponents(debugVariant, true, true, false);
        checkNestedComponents(releaseVariant, false, false, false);
    }

    @Test
    public void testNestedComponentsWithAndroidTestsDisabled() {
        Eval.me(
                "project",
                project,
                "\n"
                        + "project.androidComponents {\n"
                        + "\n"
                        + "\n"
                        + "    beforeVariants(selector().withBuildType(\"debug\")) {\n"
                        + "        enableAndroidTest false\n"
                        + "    }\n"
                        + "}\n"
                        + "\n");

        plugin.createAndroidTasks(project);
        List<VariantCreationConfig> variants =
                plugin.getVariantManager().getMainComponents().stream()
                        .map(ComponentInfo::getVariant)
                        .collect(Collectors.toList());

        assertThat(variants.size()).isEqualTo(2);

        VariantCreationConfig debugVariant =
                variants.stream().filter(it -> it.getName().equals("debug")).findFirst().get();
        VariantCreationConfig releaseVariant =
                variants.stream().filter(it -> it.getName().equals("release")).findFirst().get();

        checkNestedComponents(debugVariant, true, false, false);
        checkNestedComponents(releaseVariant, false, false, false);
    }

    public void checkProguardFiles(Map<String, List<String>> expected) {
        Map<String, VariantCreationConfig> componentMap = getComponentMap();
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String variantName = entry.getKey();
            Set<File> proguardFiles =
                    componentMap.get(variantName).getOptimizationCreationConfig().getProguardFiles()
                            .get().stream()
                            .map(RegularFile::getAsFile)
                            .map(File::getAbsoluteFile)
                            .collect(Collectors.toSet());
            Set<File> expectedFiles =
                    entry.getValue().stream().map(project::file).collect(Collectors.toSet());
            assertThat(proguardFiles)
                    .named("Proguard files for " + variantName)
                    .containsExactlyElementsIn(expectedFiles);
        }
    }

    public Map<String, VariantCreationConfig> getComponentMap() {
        Map<String, VariantCreationConfig> result = new HashMap<>();
        for (ComponentInfo<ApplicationVariantBuilder, ApplicationCreationConfig> variant :
                plugin.getVariantManager().getMainComponents()) {
            result.put(variant.getVariant().getName(), variant.getVariant());
        }
        return result;
    }
}
