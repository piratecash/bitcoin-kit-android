cmake_minimum_required(VERSION 3.20)
project(sqlcipher_jni C)

# Gradle passes native paths; on Windows CMake would read their backslashes as escapes.
file(TO_CMAKE_PATH "${SQLCIPHER_SOURCE_DIR}" SQLCIPHER_SOURCE_DIR)
file(TO_CMAKE_PATH "${LIBTOMCRYPT_SOURCE_DIR}" LIBTOMCRYPT_SOURCE_DIR)
file(TO_CMAKE_PATH "${JNI_HEADER_DIR}" JNI_HEADER_DIR)

if(NOT DEFINED SQLCIPHER_SOURCE_DIR OR NOT EXISTS "${SQLCIPHER_SOURCE_DIR}/sqlite3.c")
    message(FATAL_ERROR "SQLCIPHER_SOURCE_DIR must contain sqlite3.c and sqlite3.h")
endif()
if(NOT DEFINED LIBTOMCRYPT_SOURCE_DIR OR NOT EXISTS "${LIBTOMCRYPT_SOURCE_DIR}/src/headers/tomcrypt.h")
    message(FATAL_ERROR "LIBTOMCRYPT_SOURCE_DIR must be the LibTomCrypt repository root")
endif()
if(NOT DEFINED JNI_HEADER_DIR OR NOT EXISTS "${JNI_HEADER_DIR}/jni.h")
    message(FATAL_ERROR "JNI_HEADER_DIR must contain jni.h")
endif()

file(GLOB_RECURSE LIBTOMCRYPT_SOURCES CONFIGURE_DEPENDS
        "${LIBTOMCRYPT_SOURCE_DIR}/src/*.c")
list(FILTER LIBTOMCRYPT_SOURCES EXCLUDE REGEX "/(demos|notes|tests)/")

add_library(sqlcipher_libtomcrypt STATIC ${LIBTOMCRYPT_SOURCES})
set_target_properties(sqlcipher_libtomcrypt PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(sqlcipher_libtomcrypt PRIVATE "${LIBTOMCRYPT_SOURCE_DIR}/src/headers")
target_compile_definitions(sqlcipher_libtomcrypt PRIVATE LTC_SOURCE)

add_library(sqlcipher_jni SHARED
        sqlcipher_jni.c
        "${SQLCIPHER_SOURCE_DIR}/sqlite3.c")
if(WIN32)
    set_target_properties(sqlcipher_jni PROPERTIES PREFIX "")
endif()

target_include_directories(sqlcipher_jni PRIVATE
        "${SQLCIPHER_SOURCE_DIR}"
        "${LIBTOMCRYPT_SOURCE_DIR}/src/headers"
        "${JNI_HEADER_DIR}")

if(APPLE)
    target_include_directories(sqlcipher_jni PRIVATE "${JNI_HEADER_DIR}/darwin")
elseif(WIN32)
    target_include_directories(sqlcipher_jni PRIVATE "${JNI_HEADER_DIR}/win32")
else()
    target_include_directories(sqlcipher_jni PRIVATE "${JNI_HEADER_DIR}/linux")
endif()

target_compile_definitions(sqlcipher_jni PRIVATE
        NDEBUG
        LTC_SOURCE
        SQLITE_HAS_CODEC
        SQLCIPHER_CRYPTO_LIBTOMCRYPT
        SQLITE_TEMP_STORE=2
        SQLITE_THREADSAFE=1
        SQLITE_ENABLE_COLUMN_METADATA
        SQLITE_ENABLE_FTS3_PARENTHESIS
        SQLITE_ENABLE_FTS4
        SQLITE_ENABLE_FTS4_UNICODE61
        SQLITE_ENABLE_FTS5
        SQLITE_ENABLE_MEMORY_MANAGEMENT
        SQLITE_ENABLE_UNLOCK_NOTIFY
        SQLITE_ENABLE_RTREE
        SQLITE_SOUNDEX
        SQLITE_ENABLE_STAT3
        SQLITE_ENABLE_STAT4
        SQLITE_ENABLE_JSON1
        SQLITE_ENABLE_EXPLAIN_COMMENTS
        SQLITE_DEFAULT_WAL_SYNCHRONOUS=1
        SQLITE_MAX_VARIABLE_NUMBER=99999
        SQLITE_DEFAULT_JOURNAL_SIZE_LIMIT=1048576
        SQLITE_ENABLE_DBSTAT_VTAB
        SQLITE_USE_URI
        SQLITE_EXTRA_INIT=sqlcipher_extra_init
        SQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown)

if(MSVC)
    target_compile_options(sqlcipher_libtomcrypt PRIVATE /O2)
    target_compile_options(sqlcipher_jni PRIVATE /O2)
else()
    target_compile_options(sqlcipher_libtomcrypt PRIVATE -O2 -fvisibility=hidden)
    target_compile_options(sqlcipher_jni PRIVATE -O2 -fvisibility=hidden)
endif()

target_link_libraries(sqlcipher_jni PRIVATE sqlcipher_libtomcrypt)
if(MINGW)
    target_link_options(sqlcipher_jni PRIVATE -static-libgcc)
endif()

if(WIN32)
    target_link_libraries(sqlcipher_jni PRIVATE advapi32)
elseif(NOT APPLE)
    target_link_libraries(sqlcipher_jni PRIVATE dl m pthread)
endif()
