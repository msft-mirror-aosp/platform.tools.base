#load("@rules_pkg//pkg:pkg.bzl", "pkg_zip")

XjcInfo = provider(
    "The result of running xjc on an xsd.",
    fields = {
        "episode": "the episode file produced by running xjc",
        "xsd": "the xsd file being processed",
    },
)

def remove_package_info(f):
    return None if "com/android/repository/impl/meta/package-info" in f.path else f.path

def _xsd_to_java_impl(ctx):
    inputs = [ctx.file.xsd]
    if ctx.files.bindings:
        inputs += ctx.files.bindings

    episode = ctx.actions.declare_file(ctx.attr.name + "-episode.xjb")
    cmd = ctx.actions.args()
    cmd.add("-episode", episode.path)

    for binding in ctx.files.bindings:
        cmd.add("-b", binding.path)
    inputs += ctx.files.bindings

    for dep in ctx.attr.deps:
        if JavaInfo in dep and dep[JavaInfo].java_outputs:
            for java_out in dep[JavaInfo].runtime_output_jars:
                cmd.add("-cp", java_out)
                inputs.append(java_out)
        if XjcInfo in dep:
            if dep[XjcInfo].episode:
                cmd.add("-b", dep[XjcInfo].episode.path)
                inputs.append(dep[XjcInfo].episode)
            inputs.append(dep[XjcInfo].xsd)
            cmd.add(dep[XjcInfo].xsd.path)

    cmd.add("-p", ctx.attr.package)
    cmd.add("-extension", "-Xandroid-inheritance")
    cmd.add("-no-header")

    out = ctx.actions.declare_directory(ctx.attr.name + "-java")
    cmd.add("-d", out.path)
    cmd.add(ctx.file.xsd)
    srcjar = ctx.actions.declare_file(ctx.attr.name + ".srcjar")

    ctx.actions.run(
        mnemonic = "RunXjc",
        inputs = inputs,
        outputs = [out, episode],
        executable = ctx.executable._xjc,
        arguments = [cmd],
    )
    zipargs = ctx.actions.args()
    zipargs.add("c", srcjar.path)

    # Sometimes xjc generates unnecessary package-infos for other packages. Filter them out.
    zipargs.add_all([out], map_each = remove_package_info)
    ctx.actions.run(
        mnemonic = "ZipSrc",
        inputs = [out],
        outputs = [srcjar],
        executable = ctx.executable._zipper,
        arguments = [zipargs],
    )

    return [
        DefaultInfo(files = depset([srcjar])),
        XjcInfo(episode = episode, xsd = ctx.file.xsd),
    ]

xsd_to_java = rule(
    attrs = {
        "package": attr.string(
            mandatory = True,
        ),
        "xsd": attr.label(
            allow_single_file = True,
            mandatory = True,
        ),
        "deps": attr.label_list(
            providers = [[JavaInfo], [XjcInfo]],
        ),
        "bindings": attr.label_list(
            allow_files = [".xjb"],
        ),
        "_xjc": attr.label(
            default = Label("//tools/base/repository:xjc"),
            cfg = "exec",
            executable = True,
        ),
        "_zipper": attr.label(
            default = Label("@bazel_tools//tools/zip:zipper"),
            cfg = "exec",
            executable = True,
        ),
    },
    implementation = _xsd_to_java_impl,
)

def xsd_java_library(
        name,
        package,
        xsd,
        java_deps = [],
        binding = None,
        xsd_deps = [],
        **kwargs):
    """Runs xjc on a xsd file and compiles the generated java.

    Args:
        name: The name of the xjc-running target. The resulting java library will be :name.lib
        package: The java package to use for the generated code
        xsd: The xsd file to compile
        java_deps: Java library dependencies, e.g. containing superclasses for the generated code.
        binding: The .xjb file to specify additional customizations, if necessary.
        xsd_deps: Other xsd_java_library targets that this one depends on.
        **kwargs: Arguments that will be passed along to xjc and java_library.

    Returns:
        XsdInfo for the resulting xjc run, and JavaInfo (named :name.lib) for the resulting java library.
    """
    java_deps += [d + ".lib" for d in xsd_deps]

    bindings = []
    if binding:
        bindings.append(binding)

    xsd_to_java(
        name = name,
        package = package,
        xsd = xsd,
        deps = java_deps + xsd_deps,
        bindings = bindings + ["//tools/base/repository:src/main/resources/xsd/global.xjb"],
        **kwargs
    )

    native.java_library(
        name = name + ".lib",
        javacopts = ["-source 17", "-target 17"],
        srcs = [":" + name],
        deps = java_deps + ["@maven//:org.glassfish.jaxb.jaxb-runtime"],
        **kwargs
    )
