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

package com.android.tools.bazel.model;

import com.android.tools.bazel.parser.ast.CallExpression;
import com.android.tools.bazel.parser.ast.CallStatement;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.*;

public class ImlModule extends BazelRule {

    private List<String> sources = new LinkedList<>();
    private List<String> testSources = new LinkedList<>();
    private List<String> testResources = new LinkedList<>();
    private List<String> resources = new LinkedList<>();
    private List<String> exclude = new LinkedList<>();
    private List<String> imlFiles = new LinkedList<>();
    private String jvmTarget = "";
    private List<String> javacOpts = new LinkedList<>();
    private Map<String, String> prefixes = new LinkedHashMap<>();
    private Set<BazelRule> testDeps = Sets.newLinkedHashSet();
    private Set<BazelRule> runtimeDeps = Sets.newLinkedHashSet();
    private Set<BazelRule> testRuntimeDeps = Sets.newLinkedHashSet();
    private Set<BazelRule> testFriends = Sets.newLinkedHashSet();

    public ImlModule(Package pkg, String name) {
        super(pkg, name);
    }

    @Override
    public void update() throws IOException {
        CallStatement statement = getCallStatement("iml_module", name);
        if (getLoad(statement) == null) {
            addLoad("//tools/base/bazel:bazel.bzl", statement);
        }

        CallExpression call = statement.getCall();
        call.setArgument("srcs", sources);
        call.setArgument("test_srcs", testSources);
        call.setArgument("exclude", exclude);
        call.setArgument("resources", resources);
        call.setArgument("test_resources", testResources);
        call.setArgument("deps", tagDependencies(dependencies, testDeps));
        call.setArgument("exports", exported);
        call.setArgument("iml_files", imlFiles);
        call.setArgument("jvm_target", jvmTarget);
        call.setArgument("javacopts_from_jps", javacOpts);
        call.setArgument("package_prefixes", prefixes);
        call.setArgument("runtime_deps", runtimeDeps);
        call.setArgument("test_runtime_deps", testRuntimeDeps);
        call.setArgument("test_friends", testFriends);

        if (!statement.isFromFile()) {
            call.setArgument("visibility", ImmutableList.of("//visibility:public"));
        }
        String reason = "must match IML order";
        call.setDoNotSort("srcs", reason);
        call.setDoNotSort("resources", reason);
        call.setDoNotSort("exports", reason);
        call.setDoNotSort("deps", reason);
        call.setDoNotSort("runtime_deps", reason);
        call.setDoNotSort("test_runtime_deps", reason);
        call.setDoNotSort("test_friends", reason);

        statement.setIsManaged();
    }

    private List<String> tagDependencies(Set<BazelRule> dependencies, Set<BazelRule> testDeps) {
        List<String> deps = new LinkedList<>();
        for (BazelRule dependency : dependencies) {
            deps.add(dependency.getLabel());
        }
        for (BazelRule dependency : testDeps) {
            deps.add(dependency.getLabel() + "[test]");
        }
        return deps;
    }

    public void addRuntimeDependency(BazelRule rule) {
        runtimeDeps.add(rule);
    }


    public void addTestRuntimeDependency(BazelRule rule) {
        testRuntimeDeps.add(rule);
    }

    public void addTestDependency(BazelRule rule, boolean isExported) {
        testDeps.add(rule);
        if (isExported) {
            exported.add(rule);
        }
    }

    public void addTestFriend(BazelRule rule) {
        testFriends.add(rule);
    }

    public void addPackagePrefix(String src, String prefix) {
        prefixes.put(src, prefix);
    }

    public void addModuleFile(String name) {
        imlFiles.add(name);
    }

    public void addSource(String source) {
        sources.add(source);
    }

    public void addTestSource(String source) {
        testSources.add(source);
    }

    public void addResource(String resource) {
        resources.add(resource);
    }

    public void addTestResource(String resource) {
        testResources.add(resource);
    }

    public void addExclude(String exclude) {
        this.exclude.add(exclude);
    }

    public void setJvmTarget(String jvmTarget) {
        this.jvmTarget = jvmTarget;
    }

    public void addJavacOption(String option) {
        javacOpts.add(option);
    }
}
