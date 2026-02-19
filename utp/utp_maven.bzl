"""Provides the `maven_to_java` rule to wrap Maven artifacts as Java targets.
"""

load("@rules_java//java:defs.bzl", "JavaInfo")
load("//tools/base/bazel:maven.bzl", "MavenInfo")

visibility("private")

def _maven_to_java_impl(ctx):
    maven_info = ctx.attr.artifact[MavenInfo]

    # MavenInfo.files is a list of tuples: (repo_path_string, File object)
    # We unpack this to get the raw File objects.
    artifact_files = [f[1] for f in maven_info.files]

    jar = None
    source_jar = None

    # Scan the files to find the classes Jar and Sources Jar
    for f in artifact_files:
        if f.extension == "jar":
            if f.basename.endswith("-sources.jar"):
                source_jar = f
            elif not f.basename.endswith("-javadoc.jar"):
                # Use the first valid jar found as the binary jar
                jar = f

    if not jar:
        fail("No suitable class jar found in maven_artifact: %s" % ctx.attr.artifact.label)

    # Collect dependencies provided to this rule
    dep_java_infos = [d[JavaInfo] for d in ctx.attr.deps]

    # Construct the JavaInfo provider
    java_info = JavaInfo(
        output_jar = jar,
        compile_jar = jar,
        source_jar = source_jar,
        exports = dep_java_infos,
        deps = dep_java_infos,
    )

    return [
        DefaultInfo(files = depset([jar])),
        java_info,
        maven_info,
    ]

maven_to_java = rule(
    implementation = _maven_to_java_impl,
    attrs = {
        "artifact": attr.label(
            providers = [MavenInfo],
            mandatory = True,
            doc = "The maven_artifact target to wrap.",
        ),
        "deps": attr.label_list(
            providers = [JavaInfo],
            doc = "Dependencies to export to consumers (both compile-time and runtime).",
        ),
    },
    doc = "Wraps a maven_artifact to produce JavaInfo and forwards MavenInfo.",
)
