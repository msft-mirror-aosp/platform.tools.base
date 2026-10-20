/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.jdwptracer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JDWPTracerTest {

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void packetHeaderParsing() {
        AssertableLog logs = new AssertableLog();
        JDWPTracer tracer = new JDWPTracer(true, logs);
        ByteBuffer badPacket = ByteBuffer.allocate(0);

        try {
            tracer.addUpstreamPacket(badPacket);
        } catch (Exception e) {
            Assert.fail();
        }

        Assert.assertEquals(1, logs.getWarnings().size());
    }

    @Test
    public void closeWritesUniqueTempFileWithSanitizedName() throws Exception {
        Path outputDir = tempFolder.newFolder().toPath();
        Path predictableFile = outputDir.resolve("perfetto-trace-com.example.app.json");
        Files.write(predictableFile, "sentinel".getBytes(StandardCharsets.UTF_8));

        JDWPTracer tracer = new JDWPTracer(true, outputDir);
        tracer.setName("../../com.example.app/evil");
        tracer.close();

        // Pre-existing predictable file must not be overwritten.
        Assert.assertEquals(
                "sentinel",
                new String(Files.readAllBytes(predictableFile), StandardCharsets.UTF_8));

        List<Path> traceFiles;
        try (Stream<Path> stream = Files.list(outputDir)) {
            traceFiles =
                    stream.filter(p -> !p.equals(predictableFile)).collect(Collectors.toList());
        }
        Assert.assertEquals(1, traceFiles.size());
        Path created = traceFiles.get(0);
        Assert.assertEquals(outputDir, created.getParent());
        String fileName = created.getFileName().toString();
        Assert.assertTrue(fileName.startsWith("perfetto-trace-____com.example.app_evil-"));
        Assert.assertTrue(fileName.endsWith(".json"));
        Assert.assertTrue(Files.size(created) > 0);
    }
}
