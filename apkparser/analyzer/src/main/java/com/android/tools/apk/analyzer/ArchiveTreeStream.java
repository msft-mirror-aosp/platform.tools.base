/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tools.apk.analyzer;

import org.jetbrains.annotations.NotNull;

import java.util.stream.Stream;

public class ArchiveTreeStream {
    @NotNull
    public static Stream<ArchiveNode> preOrderStreamNoInnerArchiveExpansion(
            @NotNull ArchiveNode node) {
        return Stream.concat(
                Stream.of(node),
                node.getChildren().stream()
                        .flatMap(
                                child -> {
                                    if (child.getData() instanceof InnerArchiveEntry) {
                                        return Stream.of(child);
                                    } else {
                                        return preOrderStreamNoInnerArchiveExpansion(child);
                                    }
                                }));
    }

    @NotNull
    public static Stream<ArchiveNode> preOrderStream(@NotNull ArchiveNode node) {
        return Stream.concat(
                Stream.of(node),
                node.getChildren().stream().flatMap(ArchiveTreeStream::preOrderStream));
    }

    @NotNull
    public static <T> Stream<ArchiveNode> postOrderStream(@NotNull ArchiveNode node) {
        return Stream.concat(
                node.getChildren().stream().flatMap(ArchiveTreeStream::postOrderStream),
                Stream.of(node));
    }
}
