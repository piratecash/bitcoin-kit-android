#
# dashj-bls
#
cmake_minimum_required(VERSION 3.4.1)
set(CMAKE_CXX_STANDARD 11)

if(ANDROID)
    set(OPSYS "DROID")
else()
    if(NOT JNI_HEADER_DIR AND DEFINED ENV{JAVA_HOME})
        set(JNI_HEADER_DIR "$ENV{JAVA_HOME}/include")
    endif()
    if(NOT JNI_HEADER_DIR)
        find_program(JAVA_EXECUTABLE java)
        if(JAVA_EXECUTABLE)
            get_filename_component(JAVA_REAL "${JAVA_EXECUTABLE}" REALPATH)
            get_filename_component(JAVA_BIN "${JAVA_REAL}" DIRECTORY)
            get_filename_component(JAVA_ROOT "${JAVA_BIN}" DIRECTORY)
            set(JNI_HEADER_DIR "${JAVA_ROOT}/include")
        endif()
    endif()
    if(WIN32)
        set(JNI_MD_SUBDIR win32)
    elseif(APPLE)
        set(JNI_MD_SUBDIR darwin)
    else()
        set(JNI_MD_SUBDIR linux)
    endif()
    if(NOT EXISTS "${JNI_HEADER_DIR}/jni.h")
        message(FATAL_ERROR "jni.h not found (looked in '${JNI_HEADER_DIR}'); pass -DJNI_HEADER_DIR=<jdk>/include")
    endif()
    # Cross-building for Windows on Linux: jni.h resolves from the host JDK, include/win32 does not.
    if(NOT EXISTS "${JNI_HEADER_DIR}/${JNI_MD_SUBDIR}/jni_md.h")
        message(FATAL_ERROR "jni_md.h not found in '${JNI_HEADER_DIR}/${JNI_MD_SUBDIR}': these JDK headers are for a different target OS; pass -DJNI_HEADER_DIR=<jdk-for-target-os>/include")
    endif()
    include_directories("${JNI_HEADER_DIR}" "${JNI_HEADER_DIR}/${JNI_MD_SUBDIR}")
endif()

add_library( # Sets the name of the library.
        dashjbls
        # Sets the library as a shared library.
       SHARED
        # Provides a relative path to your source file(s).
        dashj-bls-signature-wrapper.cpp
        stdio.cpp)

# System.mapLibraryName("dashjbls") is "dashjbls.dll"; MinGW would emit "libdashjbls.dll".
if(WIN32)
    set_target_properties(dashjbls PROPERTIES PREFIX "")
endif()

# Ship no runtime side-by-side: fold libgcc/libstdc++/winpthread into the library itself.
if(MINGW)
    set(CMAKE_SHARED_LINKER_FLAGS
        "${CMAKE_SHARED_LINKER_FLAGS} -static-libgcc -static-libstdc++ -Wl,-Bstatic,-lwinpthread")
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND NOT ANDROID)
    set(CMAKE_SHARED_LINKER_FLAGS
        "${CMAKE_SHARED_LINKER_FLAGS} -static-libgcc -static-libstdc++")
endif()

# Find Java

#find_package(JNI)
#if (JNI_FOUND)
#    message (STATUS "JNI_INCLUDE_DIRS=${JNI_INCLUDE_DIRS}")
#    message (STATUS "JNI_LIBRARIES=${JNI_LIBRARIES}")
#endif()

include_directories(#${JNI_INCLUDE_DIRS}
					${CMAKE_SOURCE_DIR}/bls-signatures/src
					${CMAKE_BINARY_DIR}/dashj-bls/bls-signatures/contrib/relic/include
					${CMAKE_SOURCE_DIR}/bls-signatures/contrib/relic/include
)
message(STATUS "binary dir = ${CMAKE_BINARY_DIR}")

if ("${ANDROID_ABI}" STREQUAL "x86_64")
	include_directories(${ANDROID_SYSROOT}/usr/include/x86_64-linux-android)
elseif ("${ANDROID_ABI}" STREQUAL "x86")
	include_directories(${ANDROID_SYSROOT}/usr/include/i686-linux-android)
elseif ("${ANDROID_ABI}" STREQUAL "arm64-v8a")
	include_directories(${ANDROID_SYSROOT}/usr/include/aarch64-linux-android)
elseif ("${ANDROID_ABI}" STREQUAL "armeabi-v7a")
	include_directories(${ANDROID_SYSROOT}/usr/include/arm-linux-androideabi)
endif()

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
        dashjbls

        # Links the target library to the bls library
        bls blstmp relic_s)

#add_subdirectory(src/main/cpp)
include(bls-signatures.cmake)

if(ANDROID)
    add_library(pthread SHARED pthread.c)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
    set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
    set(CMAKE_EXE_LINKER_FLAGS    "${CMAKE_EXE_LINKER_FLAGS}    -Wl,-z,max-page-size=16384")
endif()
