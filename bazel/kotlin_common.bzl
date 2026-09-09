""" Kotlin common module for dealing with java toolchains and different target JVM versions

kotlin_compile in kotlin.bzl uses next parameters for compiling kotlin code to target jvm version
1) `-jdk-home` https://kotlinlang.org/docs/compiler-reference.html#jdk-home-path
   kotlinc extracts bootclasspath from it.
2) `-jvm-target` https://kotlinlang.org/docs/compiler-reference.html#jvm-target-version
   to specify the target version of the generated JVM bytecode.

Alternative equivalent would be to use single -Xjdk-release parameter. Which is simpler, but
slightly worse as it relies on public APIs surface hardcoded in ct.sym. That makes `-Xjdk-release`
and corresponding `--release` from javac incompatible with options that modifies set of system
classes, such as -bootclasspath (e.g. when targeting Android) or --add-exports

See also JEP for `--release` https://openjdk.org/jeps/247
See also discussion in https://youtrack.jetbrains.com/issue/KT-29974
"""

LEGACY_JVM_TARGETS = ["8"]

_PROHIBITED_JVM_TARGET_FLAGS = [
    "--release",
    "-release",
    "--target",
    "-target",
    "--source",
    "-source",
    "-jvm-target",
    "-Xjdk-release",
]

def _is_jvm_target_opt(opt):
    """Returns True if the given compiler option configures a JVM target or release level."""
    for token in opt.split(" "):
        if not token:
            continue
        flag = token.split("=")[0].split(":")[0]
        if flag in _PROHIBITED_JVM_TARGET_FLAGS:
            return True
    return False

def validate_jvm_target_opts_error(opts, label = None):
    """Validates that compiler options do not configure JVM target directly.

    Args:
        opts: ([str]) A list of compiler options (javacopts or kotlinc_opts).
        label: (Label, optional) Target label for error reporting.

    Returns:
        (str or None) An error message string if invalid options are found, or None.
    """
    for opt in opts:
        if _is_jvm_target_opt(opt):
            return "%sConfiguring '--release', '--target', '-target', '--source', '-source', '-jvm-target' or '-Xjdk-release' directly is not supported. Use 'jvm_target' instead." % (
                ("In " + str(label) + ": ") if label else "",
            )
    return None

def default_javac_opts(toolchain_info, jvm_target):
    """Get default javac options for jvm_target

    Args:
        toolchain_info: KtJvmToolchainInfo with all available toolchains.
        jvm_target: The target JVM version.

    Returns:
        ([str]) A list of javac options
    """
    toolchain = select_java_compile_toolchain(toolchain_info, jvm_target)

    # buildifier: disable=native-java-common
    return java_common.default_javac_opts(java_toolchain = toolchain)

def add_jvm_target_opts(toolchain_info, jvm_target, javac_opts, kotlinc_opts, label = None):
    """Configures JVM target and release options for javac and kotlinc.

    Args:
        toolchain_info: KtJvmToolchainInfo with all available toolchains (or None in tests).
        jvm_target: (str) Target JVM version (e.g., "8", "17", "21").
        javac_opts: ([str]) User-provided list of javac options.
        kotlinc_opts: ([str]) User-provided list of kotlinc options.
        label: (Label, optional) Target label for error reporting.

    Returns:
        ([str], [str]) A tuple of updated (javac_opts, kotlinc_opts).
    """
    error = validate_jvm_target_opts_error(javac_opts + kotlinc_opts, label)
    if error:
        fail(error)

    if toolchain_info:
        javac_opts = default_javac_opts(toolchain_info, jvm_target) + javac_opts

    # Two way to compile:
    #
    # - for jvm_target that we have corresponding jdk checkout - use javac from that checkout and
    #   kotlic with -jvm-targe option. This check bytecode compatibility and bootclasspath compatibility
    #   is verified against actual Java stdlib. This allows access internal JDK APIs via --add-exports
    #
    # - jvm_target without jdk checkout are compiled with `--release` /  `-Xjdk-release=` that also verifies
    #   bytecode, but only allows public JDK APIs usage

    if jvm_target in LEGACY_JVM_TARGETS:
        kotlinc_target_ver = "1.8" if jvm_target == "8" else jvm_target
        javac_opts = javac_opts + ["--release", jvm_target]
        kotlinc_opts = kotlinc_opts + ["-Xjdk-release=" + kotlinc_target_ver]
    elif jvm_target == "11":
        kotlinc_opts = kotlinc_opts + ["-jvm-target", "11"]
    elif jvm_target == "17":
        kotlinc_opts = kotlinc_opts + ["-jvm-target", "17"]
    elif jvm_target == "21":
        kotlinc_opts = kotlinc_opts + ["-jvm-target", "21"]
    elif jvm_target == "25":
        kotlinc_opts = kotlinc_opts + ["-jvm-target", "25"]
    else:
        fail("JVM target " + jvm_target + " is not supported")

    return javac_opts, kotlinc_opts

def select_java_compile_toolchain(toolchain_info, jvm_target):
    """ Selects the Java toolchain for jvm_target

    Args:
      toolchain_info: KtJvmToolchainInfo with all available toolchains.
      jvm_target: The target JVM version.

    Returns:
      A JavaToolchainInfo.

    """
    if jvm_target in LEGACY_JVM_TARGETS:
        # see add_jvm_target_opts for how it works
        return toolchain_info[KtJvmToolchainInfo].java_compile_toolchain_25
    elif jvm_target == "11":
        return toolchain_info[KtJvmToolchainInfo].java_compile_toolchain_11
    elif jvm_target == "17":
        return toolchain_info[KtJvmToolchainInfo].java_compile_toolchain_17
    elif jvm_target == "21":
        return toolchain_info[KtJvmToolchainInfo].java_compile_toolchain_21
    elif jvm_target == "25":
        return toolchain_info[KtJvmToolchainInfo].java_compile_toolchain_25
    else:
        fail("JVM target " + jvm_target + " is not supported")

def select_java_runtime(toolchain_info, jvm_target):
    """ Selects the Java Runtime for jvm_target

    Args:
      toolchain_info: KtJvmToolchainInfo with all available toolchains.
      jvm_target: The target JVM version.

    Returns:
      A JavaRuntimeInfo.

    """
    if jvm_target in LEGACY_JVM_TARGETS:
        # see add_jvm_target_opts for how it works
        return toolchain_info[KtJvmToolchainInfo].java_runtime_25
    elif jvm_target == "11":
        return toolchain_info[KtJvmToolchainInfo].java_runtime_11
    elif jvm_target == "17":
        return toolchain_info[KtJvmToolchainInfo].java_runtime_17
    elif jvm_target == "21":
        return toolchain_info[KtJvmToolchainInfo].java_runtime_21
    elif jvm_target == "25":
        return toolchain_info[KtJvmToolchainInfo].java_runtime_25
    else:
        fail("JVM target " + jvm_target + " is not supported")

KtJvmToolchainInfo = provider(
    doc = "Info about java runtimes used for compiling to different `jvm_target`.",
    fields = [
        "java_runtime_11",
        "java_runtime_17",
        "java_runtime_21",
        "java_runtime_25",
        "java_compile_toolchain_11",
        "java_compile_toolchain_17",
        "java_compile_toolchain_21",
        "java_compile_toolchain_25",
    ],
)

# buildifier: disable=native-java-common
def _kt_java_toolchain_bundle_impl(ctx):
    return [
        KtJvmToolchainInfo(
            java_runtime_11 = ctx.attr.kt_java_runtime_11[java_common.JavaRuntimeInfo],
            java_runtime_17 = ctx.attr.kt_java_runtime_17[java_common.JavaRuntimeInfo],
            java_runtime_21 = ctx.attr.kt_java_runtime_21[java_common.JavaRuntimeInfo],
            java_runtime_25 = ctx.attr.kt_java_runtime_25[java_common.JavaRuntimeInfo],
            java_compile_toolchain_11 = ctx.attr.kt_java_compile_toolchain_11[java_common.JavaToolchainInfo],
            java_compile_toolchain_17 = ctx.attr.kt_java_compile_toolchain_17[java_common.JavaToolchainInfo],
            java_compile_toolchain_21 = ctx.attr.kt_java_compile_toolchain_21[java_common.JavaToolchainInfo],
            java_compile_toolchain_25 = ctx.attr.kt_java_compile_toolchain_25[java_common.JavaToolchainInfo],
        ),
    ]

# buildifier: disable=native-java-common
kt_java_toolchain_bundle = rule(
    implementation = _kt_java_toolchain_bundle_impl,
    doc = """List java toolchains for Kotlin compilation

    kt_java_runtime_N used for `-jdk-home` kotlinc option
    kt_java_compile_toolchain_N for compiling java sources.
    """,
    # There are two sets because for same version it could be different toolchains.
    # could be simplified if kotlinc support --bootclasspath option directly,
    # instead of extracting it from jdk location
    attrs = {
        "kt_java_runtime_11": attr.label(
            default = Label("//prebuilts/studio/jdk/jdk11:java_runtime"),
            providers = [java_common.JavaRuntimeInfo],
        ),
        "kt_java_runtime_17": attr.label(
            default = Label("//prebuilts/studio/jdk/jdk17:java_runtime"),
            providers = [java_common.JavaRuntimeInfo],
        ),
        "kt_java_runtime_21": attr.label(
            default = Label("//prebuilts/studio/jdk/jbr-next:java_runtime"),
            providers = [java_common.JavaRuntimeInfo],
        ),
        "kt_java_runtime_25": attr.label(
            default = Label("//prebuilts/studio/jdk/jbr25:java_runtime"),
            providers = [java_common.JavaRuntimeInfo],
        ),
        "kt_java_compile_toolchain_11": attr.label(
            default = Label("//prebuilts/studio/jdk:java11_compile_toolchain"),
            providers = [java_common.JavaToolchainInfo],
        ),
        "kt_java_compile_toolchain_17": attr.label(
            default = Label("//prebuilts/studio/jdk:java17_compile_toolchain"),
            providers = [java_common.JavaToolchainInfo],
        ),
        "kt_java_compile_toolchain_21": attr.label(
            default = Label("//prebuilts/studio/jdk:java21_compile_toolchain"),
            providers = [java_common.JavaToolchainInfo],
        ),
        "kt_java_compile_toolchain_25": attr.label(
            default = Label("//prebuilts/studio/jdk:java25_compile_toolchain"),
            providers = [java_common.JavaToolchainInfo],
        ),
    },
    provides = [KtJvmToolchainInfo],
)
