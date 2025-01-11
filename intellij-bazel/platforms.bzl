"""
This file is used to add new intellij platforms to the bazel build.
Here's how to add support:
The URLs have to point to full IDEs, wile there are some versions
of the IDEs inhttps://www.jetbrains.com/intellij-repository
they do not contain the bundled JBRs. These IDEs are used to run
integration tests, so they have to be fully functional. This means are
not able to compile against snapshot builds that have not been released.

To calculate the sha256 download the file and run sha256sum on it.
"""

load("//tools/base/intellij-bazel:intellij.bzl", "local_platform", "remote_platform", "setup_platforms")

def setup_intellij_platforms():
    setup_platforms([
        local_platform(
            name = "studio-sdk",
            target = "@//prebuilts/studio/intellij-sdk:studio-sdk",
            spec = "@//prebuilts/studio/intellij-sdk:AI/spec.bzl",
        ),
        remote_platform(
            name = "intellij_ce_2024_1",
            url = "https://download.jetbrains.com/idea/ideaIC-2024.1.4.tar.gz",
            sha256 = "7d5e4cdb5a7cb1c376ca66957481350571561edadc3f45e6fce422e14af0fc16",
            top_level_dir = "idea-IC-241.18034.62",
        ),
        remote_platform(
            name = "intellij_ce_2024_2",
            url = "https://download.jetbrains.com/idea/ideaIC-2024.2.1.tar.gz",
            sha256 = "781cc03526d5811061c6ffd211942698b3d18ed2f055a04f384956686a7aa0a6",
            top_level_dir = "idea-IC-242.21829.142",
        ),
        remote_platform(
            name = "intellij_ce_2024_3",
            url = "https://download.jetbrains.com/idea/ideaIC-2024.3.1.1.tar.gz",
            sha256 = "b183b126de2cd457475eea184874b5da2fa33ba5ae2ff874bdc8c1d534156428",
            top_level_dir = "idea-IC-243.22562.218",
        ),
    ])
