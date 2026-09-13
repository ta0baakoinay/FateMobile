cmake_minimum_required(VERSION 3.22.1)
project(fatemmo_native)

# Phase 0/1 stub: proves the NDK/CMake toolchain is wired end-to-end from
# android/app/build.gradle.kts through to a loadable .so. Real engine code
# (networking, protocol, renderer, world, entities, ...) lands starting
# Phase 3 per docs/FATE_MMO_MOBILE_ROADMAP.md — see the README in each
# subdirectory here for what belongs where and when.
add_library(fatemmo_native SHARED
    src/native_lib.cpp
)

find_library(log-lib log)

target_include_directories(fatemmo_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(fatemmo_native
    ${log-lib}
)

target_compile_features(fatemmo_native PRIVATE cxx_std_17)
