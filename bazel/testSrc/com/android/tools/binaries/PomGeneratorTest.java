/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tools.binaries;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class PomGeneratorTest {

    @Test
    @SuppressWarnings("DoubleBraceInitialization")
    public void testGenerator() throws Exception {
        PomGenerator generator = new PomGenerator();

        File outputPom = new File("output.pom");

        Path testDataDir = Paths.get("tools/base/bazel/test/pom_generator/");
        List<File> pomDependencies =
                new ArrayList<File>() {
                    {
                        add(testDataDir.resolve("groovy-all-3.0.7.pom").toFile());
                    }
                };
        List<File> pomExports =
                new ArrayList<File>() {
                    {
                        add(testDataDir.resolve("guava-30.1-jre.pom").toFile());
                    }
                };

        generator.generatePom(
                null,
                outputPom,
                pomDependencies,
                null,
                pomExports,
                "group",
                "artifact",
                "version",
                "description",
                "name",
                false);

        String goldenFileContents = Files.readString(testDataDir.resolve("golden.pom"));
        String generatedFileContents = Files.readString(outputPom.toPath());
        if (!goldenFileContents.equals(generatedFileContents)) {
            System.err.println("=== Start of generated file contents ===");
            System.err.println(generatedFileContents);
            System.err.println("=== End of generated file contents ===");
            fail("Generated file does not match golden file.");
        }
    }

    @Test
    public void testPomPackagingRoutesToDependencyManagement() throws Exception {
        PomGenerator generator = new PomGenerator();
        File outputPom = new File("output_dm.pom");

        Path testDataDir = Paths.get("tools/base/bazel/test/pom_generator/");
        List<File> pomDependencies = List.of(testDataDir.resolve("sample-bom-1.0.0.pom").toFile());
        List<File> pomExports = List.of(testDataDir.resolve("guava-30.1-jre.pom").toFile());

        generator.generatePom(
                null,
                outputPom,
                pomDependencies,
                null,
                pomExports,
                "com.example",
                "test-artifact",
                "1.0.0",
                null,
                null,
                false);

        Model generated = PomGenerator.pomToModel(outputPom.getAbsolutePath());

        // Assert BOM is in dependencyManagement
        assertNotNull(generated.getDependencyManagement());
        Dependency bomDep = generated.getDependencyManagement().getDependencies().get(0);
        assertEquals("org.example", bomDep.getGroupId());
        assertEquals("sample-bom", bomDep.getArtifactId());
        assertEquals("pom", bomDep.getType());
        assertEquals("import", bomDep.getScope());

        // Assert standard library is in dependencies
        Dependency libDep = generated.getDependencies().get(0);
        assertEquals("com.google.guava", libDep.getGroupId());
        assertEquals("guava", libDep.getArtifactId());
        assertEquals("compile", libDep.getScope());
    }
}
