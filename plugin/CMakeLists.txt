cmake_minimum_required(VERSION 3.25)
project(ThorPipBoy VERSION 0.1.0 LANGUAGES CXX RC)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# statische CRT: keine Abhängigkeit von vcruntime-DLLs im Proton-Prefix
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
set(CMAKE_POLICY_DEFAULT_CMP0091 NEW)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(CLIB ${TP}/commonlibf4)
set(CSHARED ${CLIB}/lib/commonlib-shared)

# ---- spdlog (wie commonlib-shared: kompiliert, wchar, std::format) ----
set(SPDLOG_USE_STD_FORMAT ON CACHE BOOL "" FORCE)
set(SPDLOG_WCHAR_SUPPORT ON CACHE BOOL "" FORCE)
set(SPDLOG_WCHAR_FILENAMES OFF CACHE BOOL "" FORCE)
set(SPDLOG_BUILD_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory(${TP}/spdlog spdlog EXCLUDE_FROM_ALL)

set(CLANG_CL_OPTS
    /EHsc /permissive- /utf-8 /bigobj
    -fms-compatibility -fms-extensions
    -Wno-delete-non-abstract-non-virtual-dtor -Wno-deprecated-volatile
    -Wno-ignored-qualifiers -Wno-inconsistent-missing-override -Wno-invalid-offsetof
    -Wno-microsoft-include -Wno-overloaded-virtual -Wno-pragma-system-header-outside-header
    -Wno-reinterpret-base-class -Wno-switch -Wno-unused-local-typedef -Wno-unused-private-field
    -Wno-unknown-pragmas -Wno-nonportable-include-path -Wno-unused-command-line-argument)

# ---- commonlib-shared ----
file(GLOB_RECURSE CSHARED_SRC CONFIGURE_DEPENDS ${CSHARED}/src/*.cpp)
add_library(commonlib-shared STATIC ${CSHARED_SRC})
target_include_directories(commonlib-shared PUBLIC ${CSHARED}/include)
target_link_libraries(commonlib-shared PUBLIC spdlog::spdlog)
target_compile_options(commonlib-shared PUBLIC ${CLANG_CL_OPTS})
target_precompile_headers(commonlib-shared PRIVATE ${CSHARED}/src/REX/PCH.h)
target_link_libraries(commonlib-shared PUBLIC advapi32 bcrypt d3d11 d3dcompiler dbghelp dxgi ole32 shell32 user32 version ws2_32)

# ---- commonlibf4 ----
file(GLOB_RECURSE CLIB_SRC CONFIGURE_DEPENDS ${CLIB}/src/*.cpp)
# Papyrus-Registrierungshelfer werden nicht gebraucht (und kompilieren mit clang-cl nicht: return; in bool-Funktion)
list(FILTER CLIB_SRC EXCLUDE REGEX "F4SE/Registration")
add_library(commonlibf4 STATIC ${CLIB_SRC})
target_include_directories(commonlibf4 PUBLIC ${CLIB}/include)
target_link_libraries(commonlibf4 PUBLIC commonlib-shared)
target_precompile_headers(commonlibf4 PRIVATE ${CLIB}/include/F4SE/Impl/PCH.h)

# ---- Plugin ----
add_library(ThorPipBoy SHARED
    src/main.cpp
    src/Game.cpp
    src/Protocol.cpp
    src/WsServer.cpp
    src/Json.cpp
    src/Log.cpp
    src/version.rc)
target_link_libraries(ThorPipBoy PRIVATE commonlibf4)
target_compile_definitions(ThorPipBoy PRIVATE
    PLUGIN_VERSION_MAJOR=${PROJECT_VERSION_MAJOR}
    PLUGIN_VERSION_MINOR=${PROJECT_VERSION_MINOR}
    PLUGIN_VERSION_PATCH=${PROJECT_VERSION_PATCH})
target_link_options(ThorPipBoy PRIVATE /DEBUG:NONE /OPT:REF /OPT:ICF)
