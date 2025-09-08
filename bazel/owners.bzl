"""This module defines a rule that creates a zip containing all OWNERS files."""

def _owners_zip_impl(ctx):
    ctx.actions.run(
        inputs = [ctx.info_file],  # Consumes stable-status.txt as an input to force rebuilds.
        outputs = [ctx.outputs.out],
        arguments = [ctx.outputs.out.path],
        executable = ctx.executable._create_owners_zip,
        execution_requirements = {
            "local": "1",  # So that the workspace tree is intact.
        },
    )

owners_zip = rule(
    implementation = _owners_zip_impl,
    attrs = {
        "out": attr.output(mandatory = True),
        "_create_owners_zip": attr.label(
            executable = True,
            cfg = "exec",
            default = "//tools/base/bazel:create_owners_zip",
        ),
    },
)
