"""Shared repository rule utilities"""

def default_workspace_file_content(name, rule_name):
    return """# DO NOT EDIT: automatically generated WORKSPACE file by {rule}
workspace(name = \"{name}\")
""".format(name = name, rule = rule_name)

def is_windows(repo_ctx):
    os = repo_ctx.os.name.lower()
    return os.startswith("windows")

def run_command(command, repo_ctx, check = True, **kwargs):
    """Runs a command and returns the execution result.

    Args:
        command: The command to run as a sequence of strings. If the first
          element is a bare executable name, it is then searched for in PATH.
        repo_ctx: The repository context.
        check: When true, raise an error if the command return code is not 0.
        **kwargs: Extra arguments passed to repository_ctx.execute().

    Returns:
        The exec_result as returned by repository_ctx.execute()
    """
    executable = command[0]
    if path_separator(repo_ctx) not in executable:
        executable = repo_ctx.which(executable)
        if not executable:
            fail("Cannot run", command, ":", command[0], "is not in PATH.")
    command = [str(executable)] + command[1:]

    result = repo_ctx.execute(command, **kwargs)
    if check and result.return_code != 0:
        fail(
            "Command failed:",
            " ".join(command),
            ",",
            "returns",
            result.return_code,
            result.stderr,
        )
    return result

def is_abs_path(path_str):
    return path_str[0] == "/" or path_str[1:3] == ":\\" or path_str[0] == "\\"

def path_separator(repo_ctx):
    return "\\" if is_windows(repo_ctx) else "/"

def resolve_workspace_path(path_str, repo_ctx):
    """Resolves path_str relative to the workspace root to an absolute path."""
    if is_abs_path(path_str):
        return repo_ctx.path(path_str)
    return repo_ctx.workspace_root.get_child(path_str)

def relative_path(path_str, root):
    """Returns the path relative to root for path_str.

    Works only if both path_str and root are absolute, and path_str is a subpath of root.
    """
    return path_str.removeprefix(root).lstrip("/\\")

def create_build_file(build_file, repo_ctx, substitutions = None):
    """Create a BUILD.bazel file at root of the repository.

    Args:
        build_file: The BUILD file.
        repo_ctx: The repository context.
        substitutions: If specified, substitutions to make when expanding
            build_file as a template.
    """
    repo_ctx.delete("BUILD.bazel")
    if not substitutions:
        repo_ctx.symlink(build_file, "BUILD.bazel")
    else:
        repo_ctx.template(
            "BUILD.bazel",
            build_file,
            substitutions,
            executable = False,
        )

def create_workspace_file(workspace_file, repo_ctx, default_content = None):
    """Create a WORKSPACE file at root of the repository. At least one of workspace_file and default_content must be passed.

    Args:
        workspace_file: The WORKSPACE file.
        repo_ctx: The repository context.
        default_content: WORKSPACE content if workspace_file is None.
    """
    repo_ctx.delete("WORKSPACE.bazel")
    if workspace_file:
        repo_ctx.symlink(workspace_file, "WORKSPACE.bazel")
    elif default_content:
        repo_ctx.file("WORKSPACE.bazel", default_content, executable = False)
    else:
        fail("Cannot create repository", repo_ctx.name, ": no WORKSPACE file defined.")
