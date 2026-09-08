cmake_minimum_required (VERSION 3.16.1)
cmake_policy(SET CMP0091 NEW)
project(EntityCollisionOptimizer CXX)

set(CMAKE_CXX_STANDARD 20)
option(ECO_NATIVE_SYMBOLS "Emit Windows PDB symbols without disabling Release optimizations" OFF)
option(ECO_NATIVE_AVX2 "Require AVX2 on x64" ON)

if(ECO_NATIVE_AVX2 AND AR_X64)
    if(AR_WINDOWS)
        add_compile_options(/clang:-mavx2)
    else()
        add_compile_options(-mavx2)
    endif()
endif()

if(NOT AR_TOOLCHAIN)
    message(FATAL_ERROR "AR toolchain not found")
endif()
message("Target: ${AR_SYSTEM}-${AR_ARCH}")
add_compile_definitions(AR_${AR_SYSTEM} AR_${AR_ARCH})

if(AR_WINDOWS)
    if (POLICY CMP0141)
        cmake_policy(SET CMP0141 NEW)
        set(CMAKE_MSVC_DEBUG_INFORMATION_FORMAT "$<IF:$<AND:$<C_COMPILER_ID:MSVC>,$<CXX_COMPILER_ID:MSVC>>,$<$<CONFIG:Debug,RelWithDebInfo>:EditAndContinue>,$<$<CONFIG:Debug,RelWithDebInfo>:ProgramDatabase>>")
    endif()

    if (AR_X64)
        add_compile_options(/clang:-march=x86-64-v2 /clang:-mtune=generic)
    endif()

    add_compile_definitions(UNICODE _UNICODE _SILENCE_CXX20_U8PATH_DEPRECATION_WARNING _CRT_SECURE_NO_WARNINGS WINVER=0x0601 _WIN32_WINNT=0x0601)
    add_compile_options(/MT$<$<CONFIG:Debug>:d> /utf-8 /D_AMD64_ /permissive- /volatile:iso)
    if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
        add_compile_options(/clang:-O3 /EHsc -flto /Ob2 /Gw /GR- /D_HAS_STATIC_RTTI=0)
        add_link_options(-flto /OPT:REF /OPT:ICF)
    else()
        add_compile_options(/EHa)
    endif()
elseif(AR_LINUX)
    SET(CMAKE_CXX_VISIBILITY_PRESET hidden)

    set(CMAKE_SKIP_RPATH ON)
    set(CMAKE_SKIP_BUILD_RPATH ON)
    set(CMAKE_BUILD_WITH_INSTALL_RPATH OFF)

    if (AR_X64)
        add_compile_options(-m64 -march=x86-64-v2 -mtune=generic)
    endif()

    add_compile_options(-fPIC -pthread -Wno-deprecated-declarations)
    add_link_options(-static-libstdc++ -static-libgcc -pthread -Wl,--no-rosegment -Wl,--no-undefined)
    if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
        add_compile_options(-O3 -flto -fdata-sections -ffunction-sections -fno-rtti -fno-addrsig -fomit-frame-pointer)
        add_link_options(-s -flto -Wl,--lto-whole-program-visibility -Wl,--icf=all -Wl,--gc-sections -Wl,--version-script -Wl,${PROJECT_SOURCE_DIR}/version-script)
    endif()
elseif(AR_ANDROID)
    SET(CMAKE_CXX_VISIBILITY_PRESET hidden)

    set(CMAKE_SKIP_RPATH ON)
    set(CMAKE_SKIP_BUILD_RPATH ON)
    set(CMAKE_BUILD_WITH_INSTALL_RPATH OFF)

    if (AR_AARCH64)
        add_compile_options(-march=armv8-a)
    endif()

    add_compile_options(-fPIC -pthread -Wno-deprecated-declarations -fomit-frame-pointer)
    add_link_options(-static-libstdc++ -pthread -Wl,--no-rosegment -Wl,--no-undefined -Wl,--gc-sections -Wl,-z,common-page-size=65536 -Wl,-z,max-page-size=65536 -Wl,--version-script -Wl,${PROJECT_SOURCE_DIR}/version-script)
    if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
        add_compile_options(-O3 -flto -fno-rtti -fno-addrsig)
        add_link_options(-s -flto -Wl,--lto-whole-program-visibility -Wl,--icf=all)
    endif()
elseif(AR_DARWIN)
    SET(CMAKE_CXX_VISIBILITY_PRESET hidden)
    set(CMAKE_VISIBILITY_INLINES_HIDDEN 1)

    set(CMAKE_SKIP_RPATH ON)
    set(CMAKE_SKIP_BUILD_RPATH ON)
    set(CMAKE_BUILD_WITH_INSTALL_RPATH OFF)

    if (AR_X64)
        add_compile_options(-m64 -march=x86-64-v2 -mtune=generic)
    elseif (AR_AARCH64)
        add_compile_options(-mcpu=apple-m1)
    endif()

    add_compile_options(-fPIC -pthread -Wno-deprecated-declarations -fomit-frame-pointer)
    add_link_options(-lc++ -pthread -Wl,-undefined,error -Wl,-dead_strip)
    if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
        add_compile_options(-O3 -flto -fno-rtti -fno-addrsig)
        add_link_options(-flto -fwhole-program-vtables -Wl,-S -Wl,-x)
    endif()
else()
    message(FATAL_ERROR "Unsupported target")
endif()

set(ECO_COMMON_SOURCES
        src/persistent_index.cpp
        src/collision_rules.cpp
        src/context_api.cpp
        src/metadata_api.cpp
        src/query_api.cpp
        src/push_run.cpp
        src/movement_solver.cpp
        src/block_scan.cpp
        src/voxel_geometry.cpp
        src/spatial_index.cpp
)

add_library(EntityCollisionOptimizer SHARED ${ECO_COMMON_SOURCES} src/ordered_candidates.cpp)
add_library(EntityCollisionOptimizerUnordered SHARED ${ECO_COMMON_SOURCES})
target_compile_definitions(EntityCollisionOptimizerUnordered PRIVATE ECO_VANILLA_ORDER=0)

# Batch velocity accumulation must not fuse/reassociate vanilla's individual operations.
if(AR_WINDOWS)
    set_source_files_properties(src/push_run.cpp src/movement_solver.cpp src/voxel_geometry.cpp
            PROPERTIES COMPILE_OPTIONS "/clang:-ffp-contract=off")
else()
    set_source_files_properties(src/push_run.cpp src/movement_solver.cpp src/voxel_geometry.cpp
            PROPERTIES COMPILE_OPTIONS "-ffp-contract=off")
endif()

if(ECO_NATIVE_SYMBOLS AND AR_WINDOWS)
    target_compile_options(EntityCollisionOptimizer PRIVATE /Z7)
    target_link_options(EntityCollisionOptimizer PRIVATE /DEBUG:FULL)
    target_compile_options(EntityCollisionOptimizerUnordered PRIVATE /Z7)
    target_link_options(EntityCollisionOptimizerUnordered PRIVATE /DEBUG:FULL)
endif()
