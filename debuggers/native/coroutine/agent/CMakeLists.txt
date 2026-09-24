cmake_minimum_required(VERSION 3.24)
project(coroutine_debugger_agent)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..")

# Slicer sources
set(SLICER_SOURCES
    ${REPO_ROOT}/external/dexter/slicer/bytecode_encoder.cc
    ${REPO_ROOT}/external/dexter/slicer/code_ir.cc
    ${REPO_ROOT}/external/dexter/slicer/common.cc
    ${REPO_ROOT}/external/dexter/slicer/control_flow_graph.cc
    ${REPO_ROOT}/external/dexter/slicer/debuginfo_encoder.cc
    ${REPO_ROOT}/external/dexter/slicer/dex_bytecode.cc
    ${REPO_ROOT}/external/dexter/slicer/dex_format.cc
    ${REPO_ROOT}/external/dexter/slicer/dex_ir_builder.cc
    ${REPO_ROOT}/external/dexter/slicer/dex_ir.cc
    ${REPO_ROOT}/external/dexter/slicer/dex_utf8.cc
    ${REPO_ROOT}/external/dexter/slicer/instrumentation.cc
    ${REPO_ROOT}/external/dexter/slicer/reader.cc
    ${REPO_ROOT}/external/dexter/slicer/tryblocks_encoder.cc
    ${REPO_ROOT}/external/dexter/slicer/writer.cc
)

# Transport native helpers
set(TRANSPORT_SOURCES
    ${REPO_ROOT}/tools/base/transport/native/jvmti/jvmti_helper.cc
    ${REPO_ROOT}/tools/base/transport/native/utils/log.cc
)

# Target for coroutine debugger agent
add_library(
    coroutine_debugger_agent
    SHARED
    agent.cc
    jni_utils.cc
    jni_utils.h
    ${SLICER_SOURCES}
    ${TRANSPORT_SOURCES}
)

target_include_directories(
    coroutine_debugger_agent
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${REPO_ROOT}
    ${REPO_ROOT}/tools/base/transport/native
    ${REPO_ROOT}/external/dexter/slicer/export
    ${REPO_ROOT}/prebuilts/studio/jdk/jni_headers/jdk8
)

if(APPLE)
    target_include_directories(
        coroutine_debugger_agent
        PRIVATE
        ${REPO_ROOT}/prebuilts/studio/jdk/jni_headers/jdk8/darwin
    )
elseif(WIN32)
    target_include_directories(
        coroutine_debugger_agent
        PRIVATE
        ${REPO_ROOT}/prebuilts/studio/jdk/jni_headers/jdk8/win32
    )
else()
    target_include_directories(
        coroutine_debugger_agent
        PRIVATE
        ${REPO_ROOT}/prebuilts/studio/jdk/jni_headers/jdk8/linux
    )
endif()
