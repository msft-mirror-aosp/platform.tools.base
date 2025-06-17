load("//tools/base/bazel/sdk:sdk_utils.bzl", "calculate_jar_name_for_sdk_package", "tool_start_script")
load("@rules_license//rules_gathering:gather_metadata.bzl", "gather_metadata_info")
load("@rules_license//rules_gathering:gathering_providers.bzl", "TransitiveMetadataInfo")

platforms = ["win", "linux", "mac"]

def _generate_classpath_jar_impl(ctx):
    stamp = ctx.actions.declare_file(ctx.label.name + ".stamp.txt")
    ctx.actions.run(
        inputs = [ctx.info_file],
        outputs = [stamp],
        executable = ctx.executable._status_reader,
        arguments = ["--src", ctx.info_file.path, "--dst", stamp.path, "--key", "BUILD_EMBED_LABEL"],
        progress_message = "Extracting label...",
        mnemonic = "status",
    )

    runtime_jars = depset(transitive = [java_lib[JavaInfo].transitive_runtime_jars for java_lib in [ctx.attr.java_binary]])
    jars = [calculate_jar_name_for_sdk_package(jar.short_path) for jar in runtime_jars.to_list()]
    mffile = ctx.actions.declare_file(ctx.attr.java_binary.label.name + "-manifest")
    ctx.actions.write(output = mffile, content = "Class-Path: \n " + " \n ".join(jars) + " \n")
    arguments = ["c", ctx.outputs.classpath_jar.path, "META-INF/MANIFEST.MF=" + mffile.path]
    arguments += ["resources/stamp.txt=" + stamp.path]
    outputs = [ctx.outputs.classpath_jar]
    ctx.actions.run(
        inputs = [mffile, stamp],
        outputs = outputs,
        arguments = arguments,
        executable = ctx.executable._zipper,
    )

generate_classpath_jar = rule(
    implementation = _generate_classpath_jar_impl,
    attrs = {
        "java_binary": attr.label(allow_single_file = True, mandatory = True),
        "_zipper": attr.label(default = Label("@bazel_tools//tools/zip:zipper"), cfg = "host", executable = True),
        "_status_reader": attr.label(
            default = Label("//tools/base/bazel:status_reader"),
            cfg = "host",
            executable = True,
        ),
        "classpath_jar": attr.output(),
    },
)

def sdk_java_binary(name, command_name = None, main_class = None, runtime_deps = [], default_jvm_opts = {}, visibility = None):
    command_name = command_name if command_name else name
    native.java_library(
        name = command_name,
        runtime_deps = runtime_deps,
        javacopts = ["--release", "8"],
        visibility = visibility,
    )
    native.java_binary(
        # Convenience for running through bazel during testing. Not used in release.
        name = command_name + "_binary",
        runtime_deps = [":" + command_name],
        main_class = main_class,
        visibility = visibility,
    )
    classpath_jar = command_name + "-classpath.jar"
    generate_classpath_jar(java_binary = command_name, name = command_name + "-classpath", classpath_jar = classpath_jar, visibility = ["//visibility:public"])
    for platform in platforms:
        tool_start_script(
            name = name + "_wrapper_" + platform,
            platform = platform,
            command_name = command_name,
            default_jvm_opts = default_jvm_opts.get(platform) or "",
            main_class_name = main_class,
            classpath_jar = classpath_jar,
            visibility = visibility,
        )

def _combine_licenses_impl(ctx):
    inputs = []
    license_infos = set([])
    for dep in ctx.attr.deps:
        if not TransitiveMetadataInfo in dep:
          continue
        metadata = dep[TransitiveMetadataInfo]
        for license_info in metadata.licenses.to_list():
          if license_info in license_infos:
            continue
          license_infos.add(license_info)
          notice_link = ctx.actions.declare_file(license_info.label.name + ".NOTICE")
          ctx.actions.symlink(output=notice_link, target_file=license_info.license_text)
          inputs.append(notice_link)

    ctx.actions.run(
        inputs = inputs,
        outputs = [ctx.outputs.out],
        arguments = [ctx.outputs.out.path] + [f.path for f in inputs],
        executable = ctx.executable._combine_notices,
    )

combine_licenses = rule(
    implementation = _combine_licenses_impl,
    attrs = {
        "deps": attr.label_list(aspects = [gather_metadata_info]),
        "out": attr.output(mandatory = True),
        "_combine_notices": attr.label(executable = True, cfg = "host", default = Label("//tools/base/bazel/sdk:combine_notices")),
    },
)

def _package_component_impl(ctx):
    inputs = []
    args = ["c", ctx.outputs.out.path]
    for bin in ctx.attr.bins:
        file = bin.files.to_list()[0]
        args.append("cmdline-tools/bin/%s=%s" % (file.basename, file.path))
        inputs += [file]

    runtime_jars = depset(transitive = [java_lib[JavaInfo].transitive_runtime_jars for java_lib in ctx.attr.java_libs])
    runtime_jar_names = {}
    for jar in runtime_jars.to_list() + [j.files.to_list()[0] for j in ctx.attr.other_libs]:
        name = calculate_jar_name_for_sdk_package(jar.short_path)
        existing = runtime_jar_names.get(name)
        if existing:
            fail("Multiple jars have same name for SDK component with the same name! name= " + name + " jars= " + existing.path + "       " + jar.path)
        runtime_jar_names[name] = jar
        args.append("cmdline-tools/lib/%s=%s" % (name, jar.path))
        inputs += [jar]
    for other_file, other_location in ctx.attr.others.items():
        args.append(other_location + "=" + other_file.files.to_list()[0].path)
        inputs += other_file.files.to_list()
    ctx.actions.run(
        inputs = inputs,
        outputs = [ctx.outputs.out],
        executable = ctx.executable._zipper,
        arguments = args,
        progress_message = "Creating archive...",
        mnemonic = "archiver",
    )

package_component = rule(
    implementation = _package_component_impl,
    attrs = {
        "bins": attr.label_list(),
        "classpaths": attr.label_list(),
        "java_libs": attr.label_list(),
        "other_libs": attr.label_list(allow_files = True),
        "others": attr.label_keyed_string_dict(allow_files = True),
        "_zipper": attr.label(
            default = Label("@bazel_tools//tools/zip:zipper"),
            cfg = "host",
            executable = True,
        ),
    },
    outputs = {"out": "%{name}.zip"},
)

def sdk_package(name, binaries, sourceprops, visibility):
    combine_licenses(name = name + "_combined_licenses", out = "NOTICE.txt", deps = binaries)
    for platform in platforms:
        others = {
            sourceprops: "cmdline-tools/source.properties",
            name + "_combined_licenses": "cmdline-tools/NOTICE.txt",
            "README.libs": "cmdline-tools/lib/README",
        }

        if platform == "mac":
            others["macos_codesign_filelist.txt"] = "_codesign/filelist"

        package_component(
            name = "%s_%s" % (name, platform),
            bins = [bin + "_wrapper_" + platform for bin in binaries],
            java_libs = binaries,
            other_libs = [bin + "-classpath.jar" for bin in binaries],
            others = others,
            visibility = visibility,
        )
    native.filegroup(
        name = name,
        srcs = ["%s_%s.zip" % (name, platform) for platform in platforms],
        visibility = visibility,
    )
