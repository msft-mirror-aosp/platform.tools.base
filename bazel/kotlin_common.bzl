""" Kotlin common module for dealing with java toolchains and different target JVM versions

kotlin_compile in kotlin.bzl uses next parameters for compiling kotlin code to target jvm version
1) `-jdk-home` https://kotlinlang.org/docs/compiler-reference.html#jdk-home-path
   kotlinc extracts bootclasspath from it.
2) `-jvm-target` https://kotlinlang.org/docs/compiler-reference.html#jvm-target-version
   to specify the target version of the generated JVM bytecode.

Alternative equivalent would be to use single -Xjdk-release parameter. Whitch is simpler, but
slighty worse as it relly on public APIs surface hardcoded in ct.sym. That makes `-Xjdk-release`
and corresponding `--release` from javac incompatible with options that modifies set of system
classes, such as -bootclasspath (e.g. when targeting Android) or --add-exports

See also JEP for `--release` rhttps://openjdk.org/jeps/247
See also disscussion in https://youtrack.jetbrains.com/issue/KT-29974
"""

def default_javac_opts(toolchain_info, jvm_target):
    """Get default kotlic options for jvm_target

    Args:
        toolchain_info: KtJvmToolchainInfo with all available toolchains.
        jvm_target: The target JVM version.

    Returns:
        ([str]) A list of javac options
    """
    toolchain = select_java_compile_toolchain(toolchain_info, jvm_target)

    # buildifier: disable=native-java-common
    return java_common.default_javac_opts(java_toolchain = toolchain)

# buildifier: disable=unused-variable
def default_kotlinc_opts(toolchain_info, jvm_target):
    """Get default kotlic options for jvm_target

    Args:
        toolchain_info: KtJvmToolchainInfo with all available toolchains.
        jvm_target: The target JVM version.

    Returns:
        ([str]) A list of kotlinc options
    """
    if jvm_target == "8":
        return ["-jvm-target", "1.8"]
    elif jvm_target == "11":
        return ["-jvm-target", "11"]
    elif jvm_target == "17":
        return ["-jvm-target", "17"]
    elif jvm_target == "21":
        return ["-jvm-target", "21"]
    else:
        fail("JVM target " + jvm_target + " is not supported")

def select_java_compile_toolchain(toolchain_info, jvm_target):
    """ Selects the Java toolchain for jvm_target

    Args:
      toolchain_info: KtJvmToolchainInfo with all available toolchains.
      jvm_target: The target JVM version.

    Returns:
      A JavaToolchainInfo.

    """
    if jvm_target == "8":
        return toolchain_info[KtJvmToolchainInfo].java_compile_toolchain_8
    elif jvm_target == "11":
        return toolchain_info[KtJvmToolchainInfo].java_compile_toolchain_11
    elif jvm_target == "17":
        return toolchain_info[KtJvmToolchainInfo].java_compile_toolchain_17
    elif jvm_target == "21":
        return toolchain_info[KtJvmToolchainInfo].java_compile_toolchain_21
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
    if jvm_target == "8":
        return toolchain_info[KtJvmToolchainInfo].java_runtime_8
    elif jvm_target == "11":
        return toolchain_info[KtJvmToolchainInfo].java_runtime_11
    elif jvm_target == "17":
        return toolchain_info[KtJvmToolchainInfo].java_runtime_17
    elif jvm_target == "21":
        return toolchain_info[KtJvmToolchainInfo].java_runtime_21
    else:
        fail("JVM target " + jvm_target + " is not supported")

KtJvmToolchainInfo = provider(
    doc = "Info about java runtimes used for compiling to different `jvm_target`.",
    fields = [
        "java_runtime_8",
        "java_runtime_11",
        "java_runtime_17",
        "java_runtime_21",
        "java_compile_toolchain_8",
        "java_compile_toolchain_11",
        "java_compile_toolchain_17",
        "java_compile_toolchain_21",
    ],
)

# buildifier: disable=native-java-common
def _kt_java_toolchain_bundle_impl(ctx):
    return [
        KtJvmToolchainInfo(
            java_runtime_8 = ctx.attr.kt_java_runtime_8[java_common.JavaRuntimeInfo],
            java_runtime_11 = ctx.attr.kt_java_runtime_11[java_common.JavaRuntimeInfo],
            java_runtime_17 = ctx.attr.kt_java_runtime_17[java_common.JavaRuntimeInfo],
            java_runtime_21 = ctx.attr.kt_java_runtime_21[java_common.JavaRuntimeInfo],
            java_compile_toolchain_8 = ctx.attr.kt_java_compile_toolchain_8[java_common.JavaToolchainInfo],
            java_compile_toolchain_11 = ctx.attr.kt_java_compile_toolchain_11[java_common.JavaToolchainInfo],
            java_compile_toolchain_17 = ctx.attr.kt_java_compile_toolchain_17[java_common.JavaToolchainInfo],
            java_compile_toolchain_21 = ctx.attr.kt_java_compile_toolchain_21[java_common.JavaToolchainInfo],
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
        "kt_java_runtime_8": attr.label(
            default = Label("//prebuilts/studio/jdk/jdk8:java_runtime"),
            providers = [java_common.JavaRuntimeInfo],
        ),
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
        "kt_java_compile_toolchain_8": attr.label(
            default = Label("//prebuilts/studio/jdk:java8_compile_toolchain"),
            providers = [java_common.JavaToolchainInfo],
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
    },
    provides = [KtJvmToolchainInfo],
)
