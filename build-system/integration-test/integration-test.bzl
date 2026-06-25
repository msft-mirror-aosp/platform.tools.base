"""Rules and macros for running Gradle integration tests."""

load("//tools/base/bazel:coverage.bzl", "coverage_java_test")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")
load("//tools/base/bazel/validations:timeout.bzl", "APPROVED_ETERNAL_TESTS")

def gradle_integration_test(
        name,
        srcs,
        deps,
        data,
        friends = [],
        maven_repos = [],
        maven_repo_zips = [],
        resources = [],
        runtime_deps = [],
        tags = [],
        jvm_flags = [],
        timeout = "long",
        lint_baseline = None,
        lint_enabled = True,
        **kwargs):
    """A gradle integration test.

    Args:
        name: The name of the test target.
        srcs: The test source files.
        deps: The test dependencies.
        data: The test data dependencies.
        friends: Friend targets for Kotlin compilation.
        maven_repos: Maven repositories containing the plugin(s) under test.
        maven_repo_zips: Maven repository zips.
        resources: Test resources.
        runtime_deps: Runtime dependencies.
        tags: Tags to add to the target.
        jvm_flags: JVM flags.
        timeout: Test timeout.
        lint_baseline: Lint baseline file.
        lint_enabled: Whether to enable lint.
        **kwargs: Extra arguments.
    """
    lib_name = name + ".testlib"
    kotlin_library(
        name = lib_name,
        srcs = srcs,
        deps = deps,
        friends = friends,
        testonly = True,
        lint_baseline = lint_baseline,
        lint_enabled = lint_enabled,
        lint_is_test_sources = True,
    )

    if not all([maven_repo.startswith("//") for maven_repo in maven_repos + maven_repo_zips]):
        fail("All maven repos should be absolute targets.")

    # Generate the file names for all maven_repos. These will be passed to the
    # test suite in a Java property.
    manifest_file_targets = [manifest + ".manifest" for manifest in maven_repos]
    zip_file_targets = [maven_repo + ".zip" for maven_repo in maven_repo_zips]
    repo_file_names = ",".join([target[2:].replace(":", "/") for target in manifest_file_targets + zip_file_targets])

    # Depend on the manifest targets in addition to the manifest file targets so
    # that runfiles are included.
    maven_repo_dependencies = maven_repos + manifest_file_targets + zip_file_targets

    coverage_java_test(
        name = name,
        timeout = timeout,
        data = data + maven_repo_dependencies,
        jvm_flags = jvm_flags + [
            "-Dtest.suite.jar=" + lib_name + ".jar",
            "-Dfile.encoding=UTF-8",
            "-Dsun.jnu.encoding=UTF-8",
            "-Dmaven.repo.local=/tmp/localMavenRepo",  # For gradle publishing, writing to ~/.m2
            "-Dtest.android.build.gradle.integration.repos=" + repo_file_names,
        ],
        resources = resources,
        tags = [
            "block-network",
            "cpu:3",
            "gradle_integration",
            "slow",
        ] + tags,
        test_class = "com.android.build.gradle.integration.BazelIntegrationTestsSuite",
        runtime_deps = runtime_deps + [lib_name],
        **kwargs
    )

def single_gradle_integration_test(name, deps, data, maven_repos, srcs = "", runtime_deps = [], tags = [], **kwargs):
    gradle_integration_test(
        name = name,
        srcs = native.glob([srcs + name + ".java", srcs + name + ".kt"]),
        deps = deps,
        data = data,
        maven_repos = maven_repos,
        runtime_deps = runtime_deps,
        tags = tags,
        **kwargs
    )

def single_gradle_integration_test_per_source(
        name,
        deps,
        data,
        maven_repos,
        package_name,
        srcs,
        friends = [],
        non_target_srcs = [],
        runtime_deps = [],
        flaky_targets = [],
        tags = [],
        **kwargs):
    """Creates a separate integration test target for each source file in the glob.

    Args:
        name: The base name of the test suite.
        deps: Dependencies for the test library.
        data: Test data files.
        maven_repos: Maven repositories under test.
        package_name: The package name of the test targets.
        srcs: List of source files.
        friends: Friend targets for Kotlin compilation.
        non_target_srcs: Extra sources to compile but not run as standalone tests.
        runtime_deps: Runtime dependencies for the test execution.
        flaky_targets: List of targets to mark as flaky.
        tags: Tags to add to the test targets.
        **kwargs: Extra arguments passed to gradle_integration_test.
    """

    # List of target names approved to use an eternal timeout.
    eternal_target_names = []
    eternal_target_prefix = "//" + package_name + ":"
    for target in APPROVED_ETERNAL_TESTS:
        if target.startswith(eternal_target_prefix):
            eternal_target_names.append(target[len(eternal_target_prefix):])

    split_targets = []

    # need case-insensitive target names because of case-insensitive FS e.g. on Windows
    lowercase_split_targets = []
    num_flaky_applied = 0
    for src in srcs:
        start_index = src.rfind("/")
        end_index = src.rfind(".")
        class_name = src[start_index + 1:end_index]
        if class_name.lower() in lowercase_split_targets:
            # prepend part of package name to make unique
            test_name = src.split("/")[-2] + "." + class_name
        else:
            test_name = class_name
        lowercase_split_targets.append(test_name.lower())
        is_flaky = test_name in flaky_targets
        if is_flaky:
            num_flaky_applied += 1

        # For coverage to work with the test suite, test targets need a <suite>__ prefix
        target_name = name + "__" + test_name
        split_targets.append(target_name)

        timeout = kwargs.pop("timeout", "long")
        if target_name in eternal_target_names:
            timeout = "eternal"
        flaky_tags = []
        if test_name in flaky_targets:
            flaky_tags = ["noci:studio-linux", "ci:studio-linux_flaky"]
        gradle_integration_test(
            name = target_name,
            srcs = [src] + non_target_srcs,
            deps = deps,
            friends = friends,
            flaky = is_flaky,
            data = data,
            shard_count = None,
            maven_repos = maven_repos,
            runtime_deps = runtime_deps,
            lint_enabled = False,
            tags = tags + flaky_tags,
            timeout = timeout,
            **kwargs
        )
    if num_flaky_applied != len(flaky_targets):
        fail("mismatch between flaky_targets given and targets found.")

    native.test_suite(
        name = name,
        tests = split_targets,
    )
