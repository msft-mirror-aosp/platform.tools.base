"""Module extension IntelliJ platforms."""

load("//tools/base/intellij-bazel:intellij.bzl", "local_platform", "remote_platform", "setup_platforms")

def _intellij_impl(mctx):
    platforms = []
    for mod in mctx.modules:
        for platform in mod.tags.local_platform:
            platforms.append(local_platform(
                name = platform.name,
                target = platform.target,
                spec = platform.spec,
            ))
        for platform in mod.tags.remote_platform:
            platforms.append(remote_platform(
                name = platform.name,
                url = platform.url,
                sha256 = platform.sha256,
                top_level_dir = platform.top_level_dir,
                plugins = platform.plugins,
                export_plugins = platform.export_plugins,
            ))
    setup_platforms(platforms)
    return mctx.extension_metadata(
        root_module_direct_deps = "all",
        root_module_direct_dev_deps = [],
        reproducible = True,
    )

_local_platform = tag_class(attrs = {
    "name": attr.string(mandatory = True),
    "target": attr.string(mandatory = True),
    "spec": attr.string(mandatory = True),
})

_remote_platform = tag_class(attrs = {
    "name": attr.string(mandatory = True),
    "url": attr.string(mandatory = True),
    "sha256": attr.string(mandatory = True),
    "top_level_dir": attr.string(mandatory = True),
    "plugins": attr.string_list(mandatory = False, default = []),
    "export_plugins": attr.bool(mandatory = False, default = False),
})

intellij = module_extension(
    implementation = _intellij_impl,
    tag_classes = {
        "local_platform": _local_platform,
        "remote_platform": _remote_platform,
    },
)
