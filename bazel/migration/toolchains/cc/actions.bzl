"""Cc toolchain actions and configs."""

load("@rules_cc//cc:action_names.bzl", "ACTION_NAMES")
load(
    "@rules_cc//cc:cc_toolchain_config_lib.bzl",
    "action_config",
    "tool",
    "with_feature_set",
)

C_COMPILE_ACTIONS = [
    ACTION_NAMES.c_compile,
]

OBJC_COMPILE_ACTIONS = [
    ACTION_NAMES.objc_compile,
]

# C++ actions that directly reads the source
CPP_SOURCE_ACTIONS = [
    ACTION_NAMES.cpp_compile,
    ACTION_NAMES.cpp_header_parsing,
    ACTION_NAMES.cpp_module_compile,
    ACTION_NAMES.objcpp_compile,
]

# C++ actions that generate machine code
CPP_CODEGEN_ACTIONS = [
    ACTION_NAMES.cpp_compile,
    ACTION_NAMES.cpp_module_codegen,
    ACTION_NAMES.objcpp_compile,
]

CPP_COMPILE_ACTIONS = CPP_SOURCE_ACTIONS + [
    ACTION_NAMES.linkstamp_compile,
    ACTION_NAMES.cpp_module_codegen,
]

# Assembler actions for .s and .S files.
ASSEMBLE_ACTIONS = [
    ACTION_NAMES.assemble,
    ACTION_NAMES.preprocess_assemble,
]

LINK_ACTIONS = [
    ACTION_NAMES.cpp_link_executable,
    ACTION_NAMES.cpp_link_dynamic_library,
    ACTION_NAMES.cpp_link_nodeps_dynamic_library,
    ACTION_NAMES.objc_executable,
    ACTION_NAMES.objc_fully_link,
]

ARCHIVER_ACTIONS = [
    ACTION_NAMES.cpp_link_static_library,
]

LTO_BACKEND_ACTIONS = [
    ACTION_NAMES.lto_backend,
]

LTO_INDEX_ACTIONS = [
    ACTION_NAMES.lto_index_for_executable,
    ACTION_NAMES.lto_index_for_dynamic_library,
    ACTION_NAMES.lto_index_for_nodeps_dynamic_library,
]

def create_action_configs(tool_label_and_configs):
    """Creates a list of action configs to specify cc tools to each action.

    Args:
        tool_label_and_configs: A list of (Label, CcToolInfo). We do not support
            having multiple tools for the same action due to the added
            complexity of tool selection strategy.

    Returns:
        A list of action configs
    """
    tools_by_action = {}
    for label, tool_config in tool_label_and_configs:
        for action in tool_config.applied_actions:
            if action in tools_by_action:
                fail(
                    "cannot associate tool",
                    label,
                    "with action",
                    action,
                    ": action already associated with",
                    tools_by_action[action],
                )
            tools_by_action[action] = tool_config

    action_configs = []
    for action, tool_config in tools_by_action.items():
        tools = [tool(
            tool = tool_config.tool,
            with_features = [
                with_feature_set(
                    features = tool_config.with_features,
                    not_features = tool_config.with_no_features,
                ),
            ],
        )]
        action_configs.append(
            action_config(
                action_name = action,
                enabled = True,
                tools = tools,
            ),
        )

    return action_configs
