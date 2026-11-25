"""Cc toolchain features that works with clang."""

load("@rules_cc//cc:action_names.bzl", "ACTION_NAMES")
load(
    "@rules_cc//cc:cc_toolchain_config_lib.bzl",
    "feature",
    "feature_set",
    "flag_group",
    "flag_set",
    "variable_with_value",
)
load(
    "//tools/base/bazel/migration/toolchains/cc:actions.bzl",
    "ARCHIVER_ACTIONS",
    "ASSEMBLE_ACTIONS",
    "CPP_CODEGEN_ACTIONS",
    "CPP_COMPILE_ACTIONS",
    "CPP_SOURCE_ACTIONS",
    "C_COMPILE_ACTIONS",
    "LINK_ACTIONS",
    "LTO_BACKEND_ACTIONS",
    "LTO_INDEX_ACTIONS",
    "OBJC_COMPILE_ACTIONS",
)
load(
    "//tools/base/bazel/migration/toolchains/cc:features_common.bzl",
    "dynamic_linking_mode_feature",
    "get_b_prefix_feature",
    "get_sanitizer_feature",
    "get_toolchain_cc_only_features",
    "get_toolchain_compile_flags_feature",
    "get_toolchain_cxx_flags_feature",
    "get_toolchain_link_flags_feature",
    "linkstamps_feature",
    "no_legacy_features",
    "static_link_cpp_runtimes_feature",
    "static_linking_mode_feature",
    "strip_flags_feature",
    "supports_dynamic_linker_feature",
    "supports_pic_feature",
    "supports_start_end_lib_feature",
    "toolchain_import_configs",
    "user_compile_flags_feature",
    "user_link_flags_feature",
)
load(
    "//tools/base/bazel/migration/toolchains/cc:rules.bzl",
    "CcFeatureConfigInfo",
    "CcToolchainImportInfo",
)
load(
    "//tools/base/bazel/migration/toolchains/cc:utils.bzl",
    "check_args",
    "filter_none",
    "flatten",
)

def get_toolchain_include_paths_feature(import_config):
    return feature(
        name = "toolchain_include_paths",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = CPP_SOURCE_ACTIONS + C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + [
                    ACTION_NAMES.preprocess_assemble,
                    ACTION_NAMES.linkstamp_compile,
                ],
                flag_groups = filter_none([
                    check_args(
                        len,
                        flag_group,
                        flags = flatten([
                            ("-isystem", path)
                            for path in import_config.include_paths
                        ]),
                    ),
                    check_args(
                        len,
                        flag_group,
                        flags = flatten([
                            ("-F", path)
                            for path in import_config.framework_paths
                        ]),
                    ),
                ]),
            ),
        ],
    )

def get_toolchain_lib_search_paths_feature(import_config):
    return feature(
        name = "toolchain_library_search_directories",
        enabled = True,
        flag_sets = [
            flag_set(
                actions = LINK_ACTIONS + LTO_INDEX_ACTIONS,
                flag_groups = filter_none([
                    check_args(
                        len,
                        flag_group,
                        flags = ["-L" + p for p in import_config.lib_search_paths],
                    ),
                ]),
            ),
        ],
    )

# TODO(zachyu) remove when host stdlibs are no longer needed.
no_implicit_libs_feature = feature(
    name = "no_implicit_libs",
    enabled = True,
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=98;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
dependency_file_feature = feature(
    name = "dependency_file",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_SOURCE_ACTIONS + ASSEMBLE_ACTIONS,
            flag_groups = [
                flag_group(
                    expand_if_available = "dependency_file",
                    flags = [
                        "-MD",
                        "-MF",
                        "%{dependency_file}",
                    ],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;drc=6d03a2ecf25ad596446c296ef1e881b60c379812;l=129
random_seed_feature = feature(
    name = "random_seed",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_CODEGEN_ACTIONS + [ACTION_NAMES.cpp_module_compile],
            flag_groups = [
                flag_group(
                    expand_if_available = "output_file",
                    flags = [
                        "-frandom-seed=%{output_file}",
                    ],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;drc=feea781b30788997c0b97ad9103a13fdc3f627c8;l=147
pic_feature = feature(
    name = "pic",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + ASSEMBLE_ACTIONS + CPP_CODEGEN_ACTIONS + [
                ACTION_NAMES.cpp_module_compile,
                ACTION_NAMES.linkstamp_compile,
            ],
            flag_groups = [
                flag_group(
                    expand_if_available = "pic",
                    flags = ["-fPIC"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=186;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
preprocessor_defines_feature = feature(
    name = "preprocessor_defines",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_COMPILE_ACTIONS + ASSEMBLE_ACTIONS,
            flag_groups = [
                flag_group(
                    iterate_over = "preprocessor_defines",
                    flags = ["-D%{preprocessor_defines}"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=207;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
includes_feature = feature(
    name = "includes",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = CPP_SOURCE_ACTIONS + C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + [
                ACTION_NAMES.preprocess_assemble,
                ACTION_NAMES.linkstamp_compile,
            ],
            flag_groups = [
                flag_group(
                    expand_if_available = "includes",
                    iterate_over = "includes",
                    flags = ["-include", "%{includes}"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=232;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
include_paths_feature = feature(
    name = "include_paths",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = CPP_SOURCE_ACTIONS + C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + [
                ACTION_NAMES.preprocess_assemble,
                ACTION_NAMES.linkstamp_compile,
            ],
            flag_groups = [
                flag_group(
                    iterate_over = "quote_include_paths",
                    flags = ["-iquote", "%{quote_include_paths}"],
                ),
                flag_group(
                    iterate_over = "include_paths",
                    flags = ["-I", "%{include_paths}"],
                ),
                flag_group(
                    iterate_over = "system_include_paths",
                    flags = ["-isystem", "%{system_include_paths}"],
                ),
                flag_group(
                    iterate_over = "framework_include_paths",
                    flags = ["-F%{framework_include_paths}"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=476;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
shared_flag_feature = feature(
    name = "shared_flag",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = [
                ACTION_NAMES.cpp_link_dynamic_library,
                ACTION_NAMES.cpp_link_nodeps_dynamic_library,
                ACTION_NAMES.objc_fully_link,
                ACTION_NAMES.lto_index_for_dynamic_library,
                ACTION_NAMES.lto_index_for_nodeps_dynamic_library,
            ],
            flag_groups = [
                flag_group(
                    flags = [
                        "-shared",
                    ],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=512;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
output_execpath_feature = feature(
    name = "output_execpath_flags",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = LINK_ACTIONS + LTO_INDEX_ACTIONS,
            flag_groups = [
                flag_group(
                    expand_if_available = "output_execpath",
                    flags = ["-o", "%{output_execpath}"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;drc=feea781b30788997c0b97ad9103a13fdc3f627c8;l=537
rpath_feature = feature(
    name = "runtime_library_search_directories",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = LINK_ACTIONS + LTO_INDEX_ACTIONS,
            flag_groups = [
                flag_group(
                    iterate_over = "runtime_library_search_directories",
                    flag_groups = [
                        flag_group(
                            flags = [
                                "-Wl,-rpath,$ORIGIN/%{runtime_library_search_directories}",
                            ],
                        ),
                    ],
                    expand_if_available =
                        "runtime_library_search_directories",
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=592;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
lib_search_paths_feature = feature(
    name = "library_search_directories",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = LINK_ACTIONS + LTO_INDEX_ACTIONS,
            flag_groups = [
                flag_group(
                    expand_if_available = "library_search_directories",
                    iterate_over = "library_search_directories",
                    flags = ["-L%{library_search_directories}"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=612;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
archiver_flags_feature = feature(
    name = "archiver_flags",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = ARCHIVER_ACTIONS,
            flag_groups = [
                flag_group(
                    flags = ["rcsD"],
                ),
                flag_group(
                    expand_if_available = "output_execpath",
                    flags = ["%{output_execpath}"],
                ),
            ],
        ),
        flag_set(
            actions = ARCHIVER_ACTIONS,
            flag_groups = [
                flag_group(
                    expand_if_available = "libraries_to_link",
                    iterate_over = "libraries_to_link",
                    flag_groups = [
                        flag_group(
                            expand_if_equal = variable_with_value(
                                name = "libraries_to_link.type",
                                value = "object_file",
                            ),
                            flags = ["%{libraries_to_link.name}"],
                        ),
                    ],
                ),
                flag_group(
                    expand_if_equal = variable_with_value(
                        name = "libraries_to_link.type",
                        value = "object_file_group",
                    ),
                    iterate_over = "libraries_to_link.object_files",
                    flags = ["%{libraries_to_link.object_files}"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=653;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
libraries_to_link_feature = feature(
    name = "libraries_to_link",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = LINK_ACTIONS + LTO_INDEX_ACTIONS,
            flag_groups = ([
                flag_group(
                    expand_if_true = "thinlto_param_file",
                    flags = ["-Wl,@%{thinlto_param_file}"],
                ),
                flag_group(
                    expand_if_available = "libraries_to_link",
                    iterate_over = "libraries_to_link",
                    flag_groups = (
                        [
                            flag_group(
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "object_file_group",
                                ),
                                expand_if_false = "libraries_to_link.is_whole_archive",
                                flags = ["-Wl,--start-lib"],
                            ),
                            flag_group(
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "static_library",
                                ),
                                expand_if_true = "libraries_to_link.is_whole_archive",
                                flags = ["-Wl,-whole-archive"],
                            ),
                            flag_group(
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "object_file_group",
                                ),
                                iterate_over = "libraries_to_link.object_files",
                                flags = ["%{libraries_to_link.object_files}"],
                            ),
                            flag_group(
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "object_file",
                                ),
                                flags = ["%{libraries_to_link.name}"],
                            ),
                            flag_group(
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "interface_library",
                                ),
                                flags = ["%{libraries_to_link.name}"],
                            ),
                            flag_group(
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "static_library",
                                ),
                                flags = ["%{libraries_to_link.name}"],
                            ),
                            flag_group(
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "dynamic_library",
                                ),
                                flags = ["-l%{libraries_to_link.name}"],
                            ),
                            flag_group(
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "versioned_dynamic_library",
                                ),
                                flags = ["-l:%{libraries_to_link.name}"],
                            ),
                            flag_group(
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "static_library",
                                ),
                                expand_if_true = "libraries_to_link.is_whole_archive",
                                flags = ["-Wl,-no-whole-archive"],
                            ),
                            flag_group(
                                expand_if_equal = variable_with_value(
                                    name = "libraries_to_link.type",
                                    value = "object_file_group",
                                ),
                                expand_if_false = "libraries_to_link.is_whole_archive",
                                flags = ["-Wl,--end-lib"],
                            ),
                        ]
                    ),
                ),
            ]),
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;drc=feea781b30788997c0b97ad9103a13fdc3f627c8;l=831
force_pic_feature = feature(
    name = "force_pic_flags",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = [
                ACTION_NAMES.cpp_link_executable,
                ACTION_NAMES.objc_executable,
                ACTION_NAMES.lto_index_for_executable,
            ],
            flag_groups = [
                flag_group(
                    expand_if_available = "force_pic",
                    iterate_over = "user_link_flags",
                    flags = ["-pie"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;drc=feea781b30788997c0b97ad9103a13fdc3f627c8;l=924
strip_debug_symbols_feature = feature(
    name = "strip_debug_symbols",
    flag_sets = [
        flag_set(
            actions = LINK_ACTIONS + LTO_INDEX_ACTIONS,
            flag_groups = [
                flag_group(
                    expand_if_available = "strip_debug_symbols",
                    flags = ["-Wl,-S"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;drc=feea781b30788997c0b97ad9103a13fdc3f627c8;l=1432
sysroot_feature = feature(
    name = "sysroot",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_SOURCE_ACTIONS + LINK_ACTIONS + [
                ACTION_NAMES.preprocess_assemble,
                ACTION_NAMES.linkstamp_compile,
            ] + LTO_BACKEND_ACTIONS + LTO_INDEX_ACTIONS,
            flag_groups = [
                flag_group(
                    expand_if_available = "sysroot",
                    flags = ["--sysroot=%{sysroot}"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;drc=feea781b30788997c0b97ad9103a13fdc3f627c8;l=1490
linker_param_file_feature = feature(
    name = "linker_param_file",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = LINK_ACTIONS + LTO_INDEX_ACTIONS + ARCHIVER_ACTIONS,
            flag_groups = [
                flag_group(
                    expand_if_available = "linker_param_file",
                    flags = ["@%{linker_param_file}"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=1511;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
compiler_input_feature = feature(
    name = "compiler_input_flags",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_COMPILE_ACTIONS + ASSEMBLE_ACTIONS,
            flag_groups = [
                flag_group(
                    expand_if_available = "source_file",
                    flags = ["-c", "%{source_file}"],
                ),
            ],
        ),
    ],
)

# https://cs.opensource.google/bazel/bazel/+/master:src/main/java/com/google/devtools/build/lib/rules/cpp/CppActionConfigs.java;l=1538;drc=6d03a2ecf25ad596446c296ef1e881b60c379812
compiler_output_feature = feature(
    name = "compiler_output_flags",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_COMPILE_ACTIONS + ASSEMBLE_ACTIONS,
            flag_groups = [
                flag_group(
                    expand_if_available = "output_assembly_file",
                    flags = ["-S"],
                ),
                flag_group(
                    expand_if_available = "output_preprocess_file",
                    flags = ["-E"],
                ),
                flag_group(
                    expand_if_available = "output_file",
                    flags = ["-o", "%{output_file}"],
                ),
            ],
        ),
    ],
)

generate_debug_symbols_feature = feature(
    name = "generate_debug_symbols",
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_COMPILE_ACTIONS + LTO_BACKEND_ACTIONS,
            flag_groups = [
                flag_group(flags = ["-g"]),
            ],
        ),
    ],
)

thinlto_feature = feature(
    name = "thin_lto",
    enabled = True,
    flag_sets = [
        flag_set(
            actions = [
                ACTION_NAMES.c_compile,
                ACTION_NAMES.objc_compile,
                ACTION_NAMES.cpp_compile,
                ACTION_NAMES.objcpp_compile,
            ] + LINK_ACTIONS + LTO_INDEX_ACTIONS,
            flag_groups = [
                flag_group(flags = ["-flto=thin", "-fwhole-program-vtables"]),
                flag_group(
                    expand_if_available = "lto_indexing_bitcode_file",
                    flags = [
                        "-fthin-link-bitcode=%{lto_indexing_bitcode_file}",
                    ],
                ),
            ],
        ),
        flag_set(
            actions = [ACTION_NAMES.linkstamp_compile],
            flag_groups = [flag_group(flags = ["-DBUILD_LTO_TYPE=thin"])],
        ),
        flag_set(
            actions = LTO_INDEX_ACTIONS,
            flag_groups = [
                flag_group(
                    expand_if_available = "thinlto_indexing_param_file",
                    flags = [
                        "-Wl,--thinlto-index-only=%{thinlto_indexing_param_file}",
                    ],
                ),
                flag_group(
                    expand_if_not_available = "thinlto_indexing_param_file",
                    flags = [
                        "-Wl,--thinlto-index-only",
                    ],
                ),
                flag_group(flags = [
                    "-Wl,--thinlto-emit-imports-files",
                    "-Wl,--thinlto-prefix-replace=%{thinlto_prefix_replace}",
                ]),
                flag_group(
                    expand_if_available = "thinlto_object_suffix_replace",
                    flags = [
                        "-Wl,--thinlto-object-suffix-replace=%{thinlto_object_suffix_replace}",
                    ],
                ),
                flag_group(
                    expand_if_available = "thinlto_merged_object_file",
                    flags = [
                        "-Wl,--lto-obj-path=%{thinlto_merged_object_file}",
                    ],
                ),
            ],
        ),
        flag_set(
            actions = LTO_BACKEND_ACTIONS,
            flag_groups = [
                flag_group(flags = [
                    "-c",
                    "-fthinlto-index=%{thinlto_index}",
                    "-o",
                    "%{thinlto_output_object_file}",
                    "-x",
                    "ir",
                    "%{thinlto_input_bitcode_file}",
                ]),
            ],
        ),
    ],
    requires = [feature_set(features = ["opt"])],
)

opt_feature = feature(
    name = "opt",
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_COMPILE_ACTIONS + LTO_BACKEND_ACTIONS,
            flag_groups = [
                flag_group(flags = [
                    # Let's go very aggressive
                    "-O3",
                    # No debug symbols.
                    "-g0",
                    # Buffer overrun detection.
                    "-D_FORTIFY_SOURCE=1",
                    # Allow removal of unused sections and code folding at link
                    # time.
                    "-ffunction-sections",
                    "-fdata-sections",
                    # Needed by --icf=safe
                    "-faddrsig",
                ]),
            ],
        ),
        flag_set(
            actions = LINK_ACTIONS + LTO_INDEX_ACTIONS,
            flag_groups = [
                flag_group(flags = [
                    "-Wl,--gc-sections",
                    "-Wl,--icf=safe",
                ]),
            ],
        ),
    ],
)

dbg_feature = feature(
    name = "dbg",
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_COMPILE_ACTIONS,
            flag_groups = [
                flag_group(flags = [
                    "-O0",
                ]),
            ],
        ),
    ],
    implies = ["generate_debug_symbols"],
)

fastbuild_feature = feature(
    name = "fastbuild",
    flag_sets = [
        flag_set(
            actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_COMPILE_ACTIONS,
            flag_groups = [
                flag_group(flags = [
                    "-O1",
                ]),
            ],
        ),
    ],
)

asan_feature = get_sanitizer_feature(
    name = "asan",
    compile_flags = [
        "-fsanitize=address",
        "-fno-optimize-sibling-calls",
        "-fsanitize-address-use-after-scope",
        "-fno-common",
    ],
    link_flags = [
        "-fsanitize=address",
    ],
)

tsan_feature = get_sanitizer_feature(
    name = "tsan",
    compile_flags = [
        "-fsanitize=thread",
    ],
    link_flags = [
        "-fsanitize=thread",
    ],
)

msan_feature = get_sanitizer_feature(
    name = "msan",
    compile_flags = [
        "-fsanitize=memory",
        "-fno-optimize-sibling-calls",
        "-fsanitize-memory-track-origins",
        "-fsanitize-memory-use-after-dtor",
    ],
    link_flags = [
        "-fsanitize=memory",
    ],
)

def _cc_features_impl(ctx):
    import_config = toolchain_import_configs(ctx.attr.toolchain_imports)
    all_features = flatten([
        # features set / consumed by bazel
        no_legacy_features,
        dynamic_linking_mode_feature,
        static_linking_mode_feature,
        supports_start_end_lib_feature,
        supports_dynamic_linker_feature,
        supports_pic_feature,
        static_link_cpp_runtimes_feature,
        # features for tool invocations
        no_implicit_libs_feature,
        dependency_file_feature,
        random_seed_feature,
        pic_feature,
        preprocessor_defines_feature,
        includes_feature,
        include_paths_feature,
        get_toolchain_include_paths_feature(import_config),
        thinlto_feature,
        shared_flag_feature,
        linkstamps_feature,
        output_execpath_feature,
        rpath_feature,
        lib_search_paths_feature,
        get_toolchain_lib_search_paths_feature(import_config),
        archiver_flags_feature,
        strip_flags_feature,
        generate_debug_symbols_feature,
        # Start flag ordering: the order of following features impacts how
        # flags override each other.
        opt_feature,
        dbg_feature,
        fastbuild_feature,
        libraries_to_link_feature,
        asan_feature,
        tsan_feature,
        msan_feature,
        get_toolchain_link_flags_feature(ctx.attr.link_flags),
        get_toolchain_cc_only_features([]),
        user_link_flags_feature,
        force_pic_feature,
        strip_debug_symbols_feature,
        get_toolchain_compile_flags_feature(ctx.attr.compile_flags),
        get_toolchain_cxx_flags_feature(ctx.attr.cxx_flags),
        user_compile_flags_feature,
        ### End flag ordering ##
        sysroot_feature,
        get_b_prefix_feature(ctx.file.b_prefix),
        linker_param_file_feature,
        compiler_input_feature,
        compiler_output_feature,
    ])
    return CcFeatureConfigInfo(features = all_features)

cc_features = rule(
    implementation = _cc_features_impl,
    doc = "A rule to create features for cc toolchain config.",
    attrs = {
        "b_prefix": attr.label(
            doc = "Specifies where to find the executables, libraries, include " +
                  "files and data files of the compiler itself (-B flag).",
            allow_single_file = True,
        ),
        "compile_flags": attr.string_list(
            doc = "Flags always added to compile actions.",
            default = [],
        ),
        "cxx_flags": attr.string_list(
            doc = "Flags always added to c++ actions.",
            default = [],
        ),
        "link_flags": attr.string_list(
            doc = "Flags always added to link actions.",
            default = [],
        ),
        "toolchain_imports": attr.label_list(
            doc = "A list of cc_toolchain_import targets.",
            providers = [CcToolchainImportInfo],
            default = [],
        ),
    },
    provides = [CcFeatureConfigInfo],
)
