"""
Bazel repository definitions.
"""

def _impl(repository_ctx):
    s = "bazel_version = \"" + native.bazel_version + "\""
    repository_ctx.file("bazel_version.bzl", s)
    repository_ctx.file("BUILD", "")

# Helper rule for getting the bazel version. Required by com_google_absl.
bazel_version_repository = repository_rule(
    implementation = _impl,
    local = True,
)

def _local_archive_impl(ctx):
    """Implementation of local_archive rule."""

    # Extract archive to the root of the repository.
    path = ctx.path(ctx.attr.archive)
    ctx.extract(path, "", ctx.attr.strip_prefix)

    # Set up WORKSPACE to create @{name}// repository:
    ctx.file("WORKSPACE", 'workspace(name = "{}")\n'.format(ctx.name))

    # Link optional BUILD file:
    if ctx.attr.build_file:
        ctx.delete("BUILD.bazel")
        ctx.symlink(ctx.attr.build_file, "BUILD.bazel")
    elif ctx.attr.build_file_content:
        ctx.file(
            "BUILD.bazel",
            content = ctx.attr.build_file_content,
        )

# We're using a custom repository_rule instead of a regular macro (calling
# http_archive for example) because we need access to the repository_ctx object
# in order to proper resolve the path of the archives we want to extract to
# set up the repos.
#
# http_archive works nicely with absolute paths and urls, but fails to resolve
# path to labels or proper resolve relative paths to the workspace root.
local_archive = repository_rule(
    implementation = _local_archive_impl,
    attrs = {
        "archive": attr.label(
            mandatory = True,
            allow_single_file = True,
            doc = "Label for the archive that contains the target.",
        ),
        "strip_prefix": attr.string(doc = "Optional path prefix to strip from the extracted files."),
        "build_file": attr.label(
            allow_single_file = True,
            doc = "Optional label for a BUILD file to be used when setting the repository.",
        ),
        "build_file_content": attr.string(),
    },
)
