cmake_minimum_required(VERSION 3.16)
project(camms-daemon VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Options
option(CAMMS_BUILD_TESTS "Build unit tests" ON)
option(CAMMS_ANDROID "Build for Android" OFF)

# Sources
set(CORE_SOURCES
    android/camms-daemon/src/arc_cache.cpp
    android/camms-daemon/src/psi_monitor.cpp
    android/camms-daemon/src/working_set.cpp
    android/camms-daemon/src/thermal_monitor.cpp
    android/camms-daemon/src/kv_cache_compress.cpp
    android/camms-daemon/src/confidence_gate.cpp
    android/camms-daemon/src/gru_inference.cpp
    android/camms-daemon/src/actions.cpp
)

set(CORE_HEADERS
    android/camms-daemon/include/arc_cache.h
    android/camms-daemon/include/psi_monitor.h
    android/camms-daemon/include/working_set.h
    android/camms-daemon/include/thermal_monitor.h
    android/camms-daemon/include/kv_cache_compress.h
    android/camms-daemon/include/confidence_gate.h
    android/camms-daemon/include/gru_inference.h
)

# Include directories
include_directories(android/camms-daemon/include)

# Static core library
add_library(camms_core STATIC ${CORE_SOURCES} ${CORE_HEADERS})
target_include_directories(camms_core PUBLIC android/camms-daemon/include)

# Linux daemon executable
add_executable(cammsd
    android/camms-daemon/src/camms_daemon.cpp
)
target_link_libraries(cammsd camms_core pthread)

# Android JNI shared library
if(CAMMS_ANDROID)
    find_package(TensorFlowLite REQUIRED)

    add_library(camms_jni SHARED
        android/camms-daemon/src/jni_bridge.cpp
    )
    target_link_libraries(camms_jni
        camms_core
        TensorFlowLite::tensorflowlite
        jnigraphics
        log
    )

    set_target_properties(camms_jni PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/lib/${ANDROID_ABI}
    )
endif()

# Tests
if(CAMMS_BUILD_TESTS)
    enable_testing()

    add_executable(test_arc_cache
        android/camms-daemon/test/test_arc_cache.cpp
    )
    target_link_libraries(test_arc_cache camms_core)
    add_test(NAME test_arc_cache COMMAND test_arc_cache)

    add_executable(test_psi_monitor
        android/camms-daemon/test/test_psi_monitor.cpp
    )
    target_link_libraries(test_psi_monitor camms_core)
    add_test(NAME test_psi_monitor COMMAND test_psi_monitor)

    add_executable(test_confidence_gate
        android/camms-daemon/test/test_confidence_gate.cpp
    )
    target_link_libraries(test_confidence_gate camms_core)
    add_test(NAME test_confidence_gate COMMAND test_confidence_gate)

    add_executable(test_kv_cache
        android/camms-daemon/test/test_kv_cache.cpp
    )
    target_link_libraries(test_kv_cache camms_core)
    add_test(NAME test_kv_cache COMMAND test_kv_cache)

    add_executable(test_working_set
        android/camms-daemon/test/test_working_set.cpp
    )
    target_link_libraries(test_working_set camms_core)
    add_test(NAME test_working_set COMMAND test_working_set)

    add_executable(test_gru_inference
        android/camms-daemon/test/test_gru_inference.cpp
    )
    target_link_libraries(test_gru_inference camms_core)
    add_test(NAME test_gru_inference COMMAND test_gru_inference)
endif()

# Android cross-compilation support
if(CAMMS_ANDROID)
    set(ANDROID_ABI arm64-v8a)
    set(ANDROID_PLATFORM android-28)
    set(ANDROID_STL c++_shared)
endif()

# Install
install(TARGETS cammsd camms_core
    RUNTIME DESTINATION bin
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
)
install(FILES ${CORE_HEADERS} DESTINATION include/camms)
