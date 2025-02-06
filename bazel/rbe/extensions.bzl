"""Module extension for RBE exec properties."""

load(
    "@bazel_toolchains//rules/exec_properties:exec_properties.bzl",
    "create_rbe_exec_properties_dict",
    "custom_exec_properties",
)

def _rbe_impl(mctx):
    constants = {}
    for mod in mctx.modules:
        for constant in mod.tags.constant:
            constants[constant.name] = create_rbe_exec_properties_dict(
                labels = constant.labels,
            )

    custom_exec_properties(
        name = "exec_properties",
        constants = constants,
    )

    return mctx.extension_metadata(reproducible = True)

_constant = tag_class(attrs = {
    "name": attr.string(mandatory = True),
    "labels": attr.string_dict(mandatory = True),
})

rbe = module_extension(
    implementation = _rbe_impl,
    tag_classes = {"constant": _constant},
)
