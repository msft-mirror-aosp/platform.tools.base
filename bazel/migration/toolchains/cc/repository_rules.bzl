"""Toolchain repository rules."""

load(
    "//tools/base/bazel/migration/rules:repository_utils.bzl",
    "create_build_file",
    "create_workspace_file",
    "default_workspace_file_content",
    "relative_path",
    "resolve_workspace_path",
    "run_command",
)

def _macos_sdk_repository_impl(repo_ctx):
    """Creates a local repository for macOS SDK from the currently selected Xcode toolchain."""
    versions = repo_ctx.attr.sdk_versions if repo_ctx.attr.sdk_versions else [""]
    result = None
    for version in versions:
        result = run_command([
            "xcrun",
            "--sdk",
            "macosx{}".format(version),
            "--show-sdk-path",
        ], repo_ctx, check = False)
        if result.return_code == 0:
            break
    if result.return_code != 0:
        if versions[-1]:
            fail(
                "None of the following macOS SDK versions are found:",
                versions,
                "Please check your selected xcode path (xcode-select -p).",
            )
        fail(
            "Cannot find any macOS SDK. Please check your selected xcode path",
            "(xcode-select -p). If none is installed, install Xcode Command",
            "Line Tools with 'xcode-select --install'.",
        )
    sdk_path = result.stdout.strip()
    for entry in resolve_workspace_path(sdk_path, repo_ctx).readdir():
        repo_ctx.symlink(entry, relative_path(str(entry), sdk_path))
    create_build_file(repo_ctx.attr.build_file, repo_ctx)
    create_workspace_file(None, repo_ctx, default_workspace_file_content(
        repo_ctx.name,
        "macos_sdk_repository",
    ))

macos_sdk_repository = repository_rule(
    implementation = _macos_sdk_repository_impl,
    local = True,
    doc = "Creates a local repository for macOS SDK from the currently " +
          "selected Xcode toolchain.",
    attrs = {
        "build_file": attr.label(
            doc = "A file to use as a BUILD file for this directory.",
            allow_single_file = True,
            mandatory = True,
        ),
        "sdk_versions": attr.string_list(
            doc = "The SDK versions to look for in the toolchain (e.g. 12.4, " +
                  "13.3). The first version found will be used. An empty " +
                  "string can be added at the end for the default SDK.",
            default = [""],
        ),
    },
)

def _all_vctools_paths(vs_paths, repo_ctx):
    vctools_paths = []
    for vs_path in vs_paths:
        vctools_parent = repo_ctx.path(vs_path).get_child("VC", "Tools", "MSVC")
        for vctools_path in vctools_parent.readdir():
            if vctools_path.get_child("include", "bit").exists:
                vctools_paths.append(vctools_path)
    return vctools_paths

def _version_tuple(numeric_version):
    return [int(seg) for seg in numeric_version.split(".")]

def _max(items, key = None):
    if not key:
        return max(items)
    keys = [key(v) for v in items]
    return items[keys.index(max(keys))]

def _select_version(available_versions, want_versions):
    for want_version in want_versions:
        if want_version:
            if want_version in available_versions:
                return want_version
        elif available_versions:
            return _max(available_versions.keys(), key = _version_tuple)
    return None

def _msvc_tools_repository_impl(repo_ctx):
    """Creates a local repository for host installed MSVC tools."""
    environ = {k.upper(): v for k, v in repo_ctx.os.environ.items()}
    vswhere_path = environ["PROGRAMFILES(X86)"] + "\\Microsoft Visual Studio\\Installer\\vswhere.exe"
    vswhere_result = run_command([
        vswhere_path,
        "-products",
        "*",
        "-requires",
        "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
        "-property",
        "installationPath",
        "-format",
        "value",
    ], repo_ctx)
    vs_paths = vswhere_result.stdout.strip().splitlines()
    vctools_paths = _all_vctools_paths(vs_paths, repo_ctx)
    if not vctools_paths:
        fail(
            "Cannot find any Visual Studio installation with component",
            "'Microsoft.VisualStudio.Component.VC.Tools.x86.x64'. Please install",
            "'C++ x64/x86 build tools (Latest)' from Visual Studio installer",
            "(https://visualstudio.microsoft.com/visual-cpp-build-tools/)",
        )
    vctools_by_version = {p.basename: p for p in vctools_paths}
    want_versions = repo_ctx.attr.tool_versions if repo_ctx.attr.tool_versions else [""]
    selected_version = _select_version(vctools_by_version, want_versions)
    if not selected_version:
        fail(
            "None of the following VC Tools versions are found:",
            want_versions,
            "; available versions are:",
            vctools_by_version.keys(),
        )
    selected_vctools = vctools_by_version[selected_version]
    for entry in selected_vctools.readdir():
        repo_ctx.symlink(entry, relative_path(str(entry), str(selected_vctools)))
    create_build_file(repo_ctx.attr.build_file, repo_ctx)
    create_workspace_file(None, repo_ctx, default_workspace_file_content(
        repo_ctx.name,
        "msvc_tools_repository",
    ))

msvc_tools_repository = repository_rule(
    implementation = _msvc_tools_repository_impl,
    local = True,
    doc = "Creates a local repository for host installed MSVC tools.",
    attrs = {
        "build_file": attr.label(
            doc = "A file to use as a BUILD file for this directory.",
            allow_single_file = True,
            mandatory = True,
        ),
        "tool_versions": attr.string_list(
            doc = "The tool versions to look for (e.g. 14.29.30133). " +
                  "The first version found will be used. An empty " +
                  "string can be added at the end for the latest version.",
            default = [""],
        ),
    },
)

def _get_all_win_sdk_versions(sdk_path):
    sdk_versions = []
    for versioned_sdk_include in sdk_path.get_child("Include").readdir():
        if versioned_sdk_include.get_child("um", "winsdkver.h").exists:
            sdk_versions.append(versioned_sdk_include.basename)
    return sdk_versions

def _windows_sdk_repository_impl(repo_ctx):
    """Creates a local repository for a Windows SDK."""
    sdk_path = repo_ctx.path(repo_ctx.attr.sdk_path)
    all_versions = {v: None for v in _get_all_win_sdk_versions(sdk_path)}
    if not all_versions:
        fail("Cannot find any Windows SDK installation in path", sdk_path)
    want_versions = repo_ctx.attr.sdk_versions if repo_ctx.attr.sdk_versions else [""]
    selected_version = _select_version(all_versions, want_versions)
    if not selected_version:
        fail(
            "None of the following Windows SDK versions are found in",
            str(sdk_path),
            ":",
            want_versions,
            "; available versions are:",
            all_versions.keys(),
        )
    for entry in sdk_path.readdir():
        repo_ctx.symlink(entry, relative_path(str(entry), str(sdk_path)))
    create_build_file(
        repo_ctx.attr.build_file_template,
        repo_ctx,
        substitutions = {"%{sdk_version}": selected_version},
    )
    create_workspace_file(None, repo_ctx, default_workspace_file_content(
        repo_ctx.name,
        "windows_sdk_repository",
    ))

windows_sdk_repository = repository_rule(
    implementation = _windows_sdk_repository_impl,
    local = True,
    doc = "Creates a local repository for host installed Windows SDK.",
    attrs = {
        "build_file_template": attr.label(
            doc = "A file to be expanded as a BUILD file for this directory." +
                  "The template can contain '%{sdk_version}' tags that will " +
                  "be replaced with exact SDK version.",
            allow_single_file = True,
            mandatory = True,
        ),
        "sdk_path": attr.string(
            doc = "The installation path of Windows SDKs.",
            mandatory = True,
        ),
        "sdk_versions": attr.string_list(
            doc = "The SDK versions to look for (e.g. 10.0.19041.0). " +
                  "The first version found will be used. An empty " +
                  "string can be added at the end for the latest version.",
            default = [""],
        ),
    },
)
