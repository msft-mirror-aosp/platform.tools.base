"""Bootstraping binary macros, where bootstraping settings are set to true with transitions."""

load("@with_cfg.bzl", "with_cfg")

cc_bootstrap_binary, _cc_bootstrap_binary_internal = with_cfg(
    native.cc_binary,
).set(
    Label("//tools/base/bazel/migration/toolchains/cc:bootstrap"),
    True,
).build()
