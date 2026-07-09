cmake_minimum_required(VERSION 3.22.1)
project("gguf_lib")

# Force Release optimizations
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

option(GGML_VULKAN "Use Vulkan backend" OFF)
option(GGML_OPENCL "Use OpenCL backend" OFF)
option(GGML_QNN "Use Qualcomm QNN NPU backend" OFF)

if(GGML_VULKAN)
    add_compile_definitions(GGML_USE_VULKAN)
elseif(GGML_OPENCL)
    add_compile_definitions(GGML_USE_OPENCL)
elseif(GGML_QNN)
    add_compile_definitions(GGML_USE_QNN)
else()
    add_compile_definitions(GGML_USE_CPU)
endif()

add_compile_definitions(_GNU_SOURCE)
add_compile_definitions(GGML_VERSION="1.2.0")
add_compile_definitions(GGML_COMMIT="v1.2.0")

# Global optimization flags
add_compile_options(
    -ffp-contract=fast
    -fno-math-errno
    -fno-signed-zeros
    -fno-trapping-math
    -fvisibility=hidden
    -fomit-frame-pointer
    -ffunction-sections
    -fdata-sections
    -O3
    -DNDEBUG
)

# Architecture specific flags for Android ARM (v8.6-a for dotprod/i8mm/fp16)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
    add_compile_options(-march=armv8.6-a+dotprod+i8mm+fp16)
endif()

# Linker optimizations
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,--icf=safe -Wl,-z,max-page-size=16384")

# GGML Core
file(GLOB GGML_CORE_SOURCES ggml/src/*.c ggml/src/*.cpp)

set(GGML_SOURCES
    ${GGML_CORE_SOURCES}
    
    # CPU Backend (Corrected paths based on ggml-cpu directory)
    ggml/src/ggml-cpu/ggml-cpu.c
    ggml/src/ggml-cpu/ggml-cpu.cpp
    ggml/src/ggml-cpu/quants.c
    ggml/src/ggml-cpu/traits.cpp
    ggml/src/ggml-cpu/repack.cpp
    ggml/src/ggml-cpu/ops.cpp
    ggml/src/ggml-cpu/vec.cpp
    ggml/src/ggml-cpu/binary-ops.cpp
    ggml/src/ggml-cpu/unary-ops.cpp
    ggml/src/ggml-cpu/hbm.cpp
)

# Architecture specific CPU optimizations
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64" OR CMAKE_SYSTEM_PROCESSOR MATCHES "arm")
    list(APPEND GGML_SOURCES
        ggml/src/ggml-cpu/arch/arm/quants.c
        ggml/src/ggml-cpu/arch/arm/repack.cpp
    )
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64" OR CMAKE_SYSTEM_PROCESSOR MATCHES "i686" OR CMAKE_SYSTEM_PROCESSOR MATCHES "AMD64")
    list(APPEND GGML_SOURCES
        ggml/src/ggml-cpu/arch/x86/quants.c
        ggml/src/ggml-cpu/arch/x86/repack.cpp
        ggml/src/ggml-cpu/arch/x86/cpu-feats.cpp
    )
endif()

if(GGML_VULKAN)
    list(APPEND GGML_SOURCES ggml/src/ggml-vulkan/ggml-vulkan.cpp)
    find_library(VULKAN_LIB vulkan REQUIRED)
endif()

if(GGML_OPENCL)
    list(APPEND GGML_SOURCES ggml/src/ggml-opencl.cpp)
    find_library(OPENCL_LIB OpenCL REQUIRED)
endif()

if(GGML_QNN)
    list(APPEND GGML_SOURCES ggml/src/ggml-qnn.cpp)
endif()

# Llama Core
file(GLOB LLAMA_SOURCES src/*.cpp)

# Model implementations
file(GLOB LLAMA_MODELS src/models/*.cpp)

# Common Utilities
file(GLOB_RECURSE COMMON_SOURCES common/*.cpp)

# Engine components
set(ENGINE_SOURCES
    engine/tool-manager.cpp
    engine/character-engine.cpp
    engine/rag-engine.cpp
    engine/ggml-engine.cpp
    engine/ggml-engine-vlm.cpp
)

# Vision Language Model (CLIP/mtmd multimodal)
file(GLOB VLM_SOURCES engine/vlm/*.cpp)
file(GLOB VLM_MODEL_SOURCES engine/vlm/models/*.cpp)

# JNI bridge
set(JNI_SOURCES
    gguf_lib.cpp
)

add_library(${CMAKE_PROJECT_NAME} SHARED
    ${GGML_SOURCES}
    ${LLAMA_SOURCES}
    ${LLAMA_MODELS}
    ${COMMON_SOURCES}
    ${ENGINE_SOURCES}
    ${VLM_SOURCES}
    ${VLM_MODEL_SOURCES}
    ${JNI_SOURCES}
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    include
    common
    ggml/include
    ggml/src
    ggml/src/ggml-cpu
    vendor
    engine
    engine/vlm
    src
    .
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    m
    dl
)

if(GGML_VULKAN)
    target_link_libraries(${CMAKE_PROJECT_NAME} ${VULKAN_LIB})
endif()

if(GGML_OPENCL)
    target_link_libraries(${CMAKE_PROJECT_NAME} ${OPENCL_LIB})
endif()
