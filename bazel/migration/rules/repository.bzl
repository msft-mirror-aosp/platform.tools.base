"""Custom repository rules.

Requires bazel 6.0.0+ due to usage of repository_ctx.workspace_root
"""

load(
    ":repository_utils.bzl",
    "create_build_file",
    "create_workspace_file",
    "default_workspace_file_content",
    "is_windows",
    "path_separator",
    "relative_path",
    "resolve_workspace_path",
    "run_command",
)

def _list_files_recursive(path, repo_ctx):
    """Lists all the files / symlinks under path, recursively.

    Directories are recursed into but not included in the list. It does not
    follow symlinks.

    Args:
        path: The path to list. The behavior of path not being a directory is
          undefined.
        repo_ctx: A repository_ctx object.

    Returns:
        A list of strings, each item is a path for each file / symlink,
          relative to the list path.
    """
    if is_windows(repo_ctx):
        path = path.replace("/", "\\")
        command = ["cmd.exe", "/c", "dir \"{}\" /a-d /s /b".format(path)]
    else:
        command = ["find", path, "-type", "f,l"]
    result = run_command(command, repo_ctx, working_directory = path)
    return [relative_path(f, path) for f in result.stdout.splitlines()]

def _file_is_ignored(path_seg, ignored_basenames):
    return path_seg[-1] in ignored_basenames

def _path_ancestors(path_seg):
    return [path_seg[:i] for i in range(1, len(path_seg))]

def _find_collapsable_ancestor(path_seg, non_collapsable_directories):
    for i in range(1, len(path_seg)):
        if path_seg[:i] not in non_collapsable_directories:
            return path_seg[:i]
    return path_seg

def _filter_and_collapse(files, ignored_basenames, path_separator):
    """This function processes a list of files and returns a list of paths for symlinking.

    It filters out all the file entries to ignore, and uses that information
    to find out the minimum set of symlinks to create provided that the ignored
    entries are excluded from the generated file tree.
    """
    filepaths_in_segments = [tuple(f.split(path_separator)) for f in files]
    non_collapsable_directories = {}
    keep_files = []
    for file_seg in filepaths_in_segments:
        if _file_is_ignored(file_seg, ignored_basenames):
            non_collapsable_directories.update([(p, None) for p in _path_ancestors(file_seg)])
        else:
            keep_files.append(file_seg)

    link_paths = {_find_collapsable_ancestor(p, non_collapsable_directories): None for p in keep_files}
    return [path_separator.join(p) for p in link_paths]

def _selective_local_repository_impl(repo_ctx):
    # Create shadow directory in the repository path.
    src_root = resolve_workspace_path(repo_ctx.attr.path, repo_ctx)
    if not src_root.exists:
        fail("Cannot create repository", repo_ctx.name, ": path", src_root, "is not found.")
    src_root = str(src_root.realpath)

    files = _list_files_recursive(src_root, repo_ctx)
    path_sep = path_separator(repo_ctx)
    link_paths = _filter_and_collapse(files, repo_ctx.attr.ignore_filenames, path_sep)

    for dest in link_paths:
        src = path_sep.join([src_root, dest])
        repo_ctx.symlink(src, dest)

    create_build_file(repo_ctx.attr.build_file, repo_ctx)
    create_workspace_file(
        repo_ctx.attr.workspace_file,
        repo_ctx,
        default_workspace_file_content(
            repo_ctx.name,
            "selective_local_repository",
        ),
    )

selective_local_repository = repository_rule(
    implementation = _selective_local_repository_impl,
    local = True,
    doc = "A repository rule similar to new_local_repository, but allows to ignore certain files.",
    attrs = {
        "build_file": attr.label(
            doc = "A file to use as a BUILD file for this directory.",
            allow_single_file = True,
            mandatory = True,
        ),
        "ignore_filenames": attr.string_list(
            doc = "Base filenames to ignore.",
            default = [],
        ),
        "path": attr.string(
            doc = "A path on the local filesystem. This can be either " +
                  "absolute or relative to the main workspace.",
            mandatory = True,
        ),
        "workspace_file": attr.label(
            doc = "The file to use as the WORKSPACE file for this repository.",
            allow_single_file = True,
        ),
    },
)

def _json2bzl_repository_impl(repo_ctx):
    starlark_content = []
    for json_label, target_variable in repo_ctx.attr.config_mapping.items():
        json_data = json.decode(repo_ctx.read(json_label))
        starlark_content.append(
            "{} = {}\n".format(target_variable, repr(json_data)),
        )
    repo_ctx.file(
        repo_ctx.attr.output_file,
        "\n".join(starlark_content),
        executable = False,
    )
    repo_ctx.file("BUILD.bazel", "", executable = False)
    create_workspace_file(None, repo_ctx, default_workspace_file_content(
        repo_ctx.name,
        "json2bzl_repository",
    ))

json2bzl_repository = repository_rule(
    implementation = _json2bzl_repository_impl,
    local = True,
    doc = "A repository transforming json config files to starlark for usage " +
          "in BUILD files.",
    attrs = {
        "config_mapping": attr.label_keyed_string_dict(
            doc = "A mapping of input files (as labels) to the variable name " +
                  "that stores the output structure.",
            allow_empty = False,
            allow_files = [".json"],
            mandatory = True,
        ),
        "output_file": attr.string(
            doc = "The file in the repository to store outputs.",
            mandatory = True,
        ),
    },
)
