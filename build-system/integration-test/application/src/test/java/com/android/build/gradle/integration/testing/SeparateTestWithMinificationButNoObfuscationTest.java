package com.android.build.gradle.integration.testing;

import com.android.build.gradle.integration.common.fixture.GradleTestProject;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

/**
 * Test for a separate test module that has minification turned on but no obfuscation (no
 * mapping.txt file produced)
 */
public class SeparateTestWithMinificationButNoObfuscationTest {

    @Rule
    public GradleTestProject project =
            GradleTestProject.builder()
                    .fromTestProject("separateTestWithMinificationButNoObfuscation")
                    .create();

    @Test
    public void testBuilding() {
        // just building fine is enough to test the regression.
        project.executor().run("clean");
        project.executor().run("assemble");
    }
}
