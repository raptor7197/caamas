cmake_minimum_required(VERSION 3.22.1)
project(agent_native LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── llama.cpp ────────────────────────────────────────────────────────────────
# Expects: app/src/main/cpp/third_party/llama.cpp
# Run scripts/setup.sh to clone it.
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_DIR}\n"
        "Run: bash scripts/setup.sh  (from the main/ directory)")
endif()

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_DIR} llama_build)

# ─── whisper.cpp ──────────────────────────────────────────────────────────────
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/third_party/whisper.cpp)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_DIR} whisper_build)

# ─── Agent native library ─────────────────────────────────────────────────────
add_library(agent_native SHARED
    src/main/cpp/llama_bridge.cpp
    src/main/cpp/embedding_bridge.cpp
    src/main/cpp/whisper_bridge.cpp
)

target_include_directories(agent_native PRIVATE
    ${LLAMA_DIR}
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
    ${WHISPER_DIR}/include
)

target_link_libraries(agent_native
    llama
    whisper
    android
    log
)

target_compile_options(agent_native PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_options(agent_native PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
)
