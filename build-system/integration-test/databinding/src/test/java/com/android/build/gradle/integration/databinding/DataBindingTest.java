/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.build.gradle.integration.databinding;

import static com.android.build.gradle.integration.common.truth.ApkSubject.assertThat;
import static com.android.testutils.truth.DexSubject.assertThat;
import static com.android.testutils.truth.PathSubject.assertThat;

import com.android.build.gradle.integration.common.fixture.GradleTestProject;
import com.android.build.gradle.integration.common.runner.FilterableParameterized;
import com.android.build.gradle.options.BooleanOption;
import com.android.testutils.apk.Apk;
import com.android.testutils.apk.Dex;
import com.android.utils.FileUtils;

import com.google.common.base.Joiner;
import com.google.common.truth.Truth8;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RunWith(FilterableParameterized.class)
public class DataBindingTest {

    @Parameterized.Parameters(name = "library={0},withoutAdapters={1},useAndroidX={2}")
    public static Collection<Object[]> getParameters() {
        List<Object[]> options = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            options.add(new Object[] {(i & 1) != 0, (i & 2) != 0, (i & 4) != 0});
        }
        return options;
    }
    private final boolean myWithoutAdapters;
    private final boolean myLibrary;
    private final String myDbPkg;

    public DataBindingTest(boolean library, boolean withoutAdapters, boolean useAndroidX) {
        myWithoutAdapters = withoutAdapters;
        myLibrary = library;
        myDbPkg = useAndroidX ? "Landroidx/databinding/" : "Landroid/databinding/";

        project =
                GradleTestProject.builder()
                        .fromTestProject("databinding")
                        .addGradleProperties(
                                BooleanOption.USE_ANDROID_X.getPropertyName() + "=" + useAndroidX)
                        .create();
    }

    @Rule
    public final GradleTestProject project;

    @Before
    public void setUp() throws IOException {
        List<String> options = new ArrayList<>();
        if (myLibrary) {
            options.add("library");
        }
        if (myWithoutAdapters) {
            options.add("withoutadapters");
        }
        if (!options.isEmpty()) {
            String customBuildFile = "build." + Joiner.on('-').join(options) + ".gradle";
            FileUtils.copyFile(
                    new File(project.getBuildFile().getParentFile(), customBuildFile),
                    project.getBuildFile());
        }
    }

    @Test
    public void checkApkContainsDataBindingClasses() throws Exception {
        project.executor().run("assembleDebug");

        // FIXME remove when migrating to new ApkSubject
        String bindingClassInAar = "android/databinding/testapp/databinding/ActivityMainBinding";
        String bindingClassinApk = "L" + bindingClassInAar + ";";
        // only in v2
        String implClassInAar = "android/databinding/testapp/databinding/ActivityMainBindingImpl";
        String implClassInApk = "L" + implClassInAar + ";";
        final Apk apk;
        if (myLibrary) {
            project.testAar(
                    "debug",
                    it -> {
                        it.allJars(
                                jar -> {
                                    jar.containsClass(bindingClassInAar);
                                    jar.containsClass(implClassInAar);

                                    jar.doesNotContainClass(myDbPkg + "adapters/Converters;");
                                    jar.doesNotContainClass(myDbPkg + "DataBindingComponent;");
                                });
                    });

            // also builds the test app
            project.executor().run("assembleDebugAndroidTest");

            Apk testApk = project.getApk(GradleTestProject.ApkType.ANDROIDTEST_DEBUG);
            assertThat(testApk.getFile()).isFile();
            Optional<Dex> dexOptional = testApk.getMainDexFile();
            Truth8.assertThat(dexOptional).isPresent();
            assertThat(dexOptional.get()).containsClass(bindingClassinApk);
            assertThat(dexOptional.get()).containsClass(implClassInApk);
            apk = testApk;
        } else {
            apk = project.getApk("debug");
        }
        assertThat(apk).containsClass(bindingClassinApk);
        assertThat(apk).containsClass(implClassInApk);
        assertThat(apk).containsClass(myDbPkg + "DataBindingComponent;");
        if (myWithoutAdapters) {
            assertThat(apk).doesNotContainClass(myDbPkg + "adapters/Converters;");
        } else {
            assertThat(apk).containsClass(myDbPkg + "adapters/Converters;");
        }
    }
}
