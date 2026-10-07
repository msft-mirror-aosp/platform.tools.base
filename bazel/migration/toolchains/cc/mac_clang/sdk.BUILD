# Exports macOS SDK from Xcode or Command Line Tools directory.

load(
    "@//tools/base/bazel/migration/toolchains/cc:rules.bzl",
    "cc_toolchain_import",
    "sysroot",
)

package(default_visibility = ["@//tools/base/bazel/migration/toolchains/cc:__subpackages__"])

sysroot(
    name = "sdk",
    all_files = glob(
        [
            "usr/include/**",
            "usr/lib/**",
        ],
        exclude = [
            "usr/include/c++/**",
        ],
    ),
)

cc_toolchain_import(
    name = "libcxx",
    include_paths = [
        ":usr/include/c++/v1",
    ],
    lib_search_paths = [
        ":usr/lib",
    ],
    support_files = glob(
        [
            "usr/include/c++/v1/**",
            "usr/lib/libc++.*",
            "usr/lib/libc++abi.*",
        ],
    ),
)

# keep sorted
ALL_FRAMEWORKS = [
    "AVFAudio",
    "AVFoundation",
    "AppKit",
    "ApplicationServices",
    "CFNetwork",
    "Carbon",
    "CloudKit",
    "Cocoa",
    "ColorSync",
    "CoreAudio",
    "CoreAudioTypes",
    "CoreData",
    "CoreFoundation",
    "CoreGraphics",
    "CoreImage",
    "CoreLocation",
    "CoreMedia",
    "CoreServices",
    "CoreText",
    "CoreVideo",
    "DiskArbitration",
    "Foundation",
    "Hypervisor",
    "IOKit",
    "IOSurface",
    "ImageIO",
    "Metal",
    "OpenGL",
    "QuartzCore",
    "Security",
    "Symbols",
    "VideoToolbox",
    "vmnet",
]

cc_toolchain_import(
    name = "frameworks",
    framework_paths = [":System/Library/Frameworks"],
    support_files = glob([
        "System/Library/Frameworks/{}.framework/**".format(f)
        for f in ALL_FRAMEWORKS
    ]),
)
