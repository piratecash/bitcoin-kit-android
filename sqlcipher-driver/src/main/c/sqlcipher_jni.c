#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#include "sqlite3.h"

static void wipe(void *data, size_t size) {
    volatile unsigned char *bytes = data;
    while (size-- > 0) {
        *bytes++ = 0;
    }
}

static void throw_message(JNIEnv *env, const char *class_name, const char *message) {
    jclass exception_class = (*env)->FindClass(env, class_name);
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

static void throw_sqlite(JNIEnv *env, sqlite3 *database, int result) {
    char message[768];
    const char *details = database == NULL ? sqlite3_errstr(result) : sqlite3_errmsg(database);
    snprintf(message, sizeof(message), "SQLCipher error %d: %s", result, details);
    throw_message(env, "androidx/sqlite/SQLiteException", message);
}

static char *copy_utf8_path(JNIEnv *env, jbyteArray path) {
    jsize size = (*env)->GetArrayLength(env, path);
    char *result = malloc((size_t)size + 1);
    if (result == NULL) {
        throw_message(env, "java/lang/OutOfMemoryError", "Unable to allocate a database path");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, path, 0, size, (jbyte *)result);
    if ((*env)->ExceptionCheck(env)) {
        free(result);
        return NULL;
    }
    if (memchr(result, '\0', (size_t)size) != NULL) {
        free(result);
        throw_message(env, "java/lang/IllegalArgumentException", "Database path contains a null byte");
        return NULL;
    }
    result[size] = '\0';
    return result;
}

static void throw_file_error(JNIEnv *env, const char *operation, const char *path) {
    char message[768];
#ifdef _WIN32
    snprintf(message, sizeof(message), "%s failed for %s (Windows error %lu)", operation, path,
             (unsigned long)GetLastError());
#else
    snprintf(message, sizeof(message), "%s failed for %s: %s", operation, path, strerror(errno));
#endif
    throw_message(env, "java/io/IOException", message);
}

#ifdef _WIN32
static wchar_t *wide_path(const char *path) {
    int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);
    if (size <= 0) return NULL;
    wchar_t *result = malloc((size_t)size * sizeof(wchar_t));
    if (result == NULL) return NULL;
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, result, size) == 0) {
        free(result);
        return NULL;
    }
    return result;
}
#endif

static sqlite3 *database_from(jlong handle) {
    return (sqlite3 *)(intptr_t)handle;
}

static sqlite3_stmt *statement_from(jlong handle) {
    return (sqlite3_stmt *)(intptr_t)handle;
}

static int create_raw_key_literal(JNIEnv *env, jbyteArray key, char key_literal[67]) {
    static const char hex[] = "0123456789abcdef";
    unsigned char raw_key[32];
    jsize size = (*env)->GetArrayLength(env, key);
    if (size != (jsize)sizeof(raw_key)) {
        throw_message(env, "java/lang/IllegalArgumentException", "SQLCipher key must contain exactly 32 bytes");
        return SQLITE_MISUSE;
    }

    (*env)->GetByteArrayRegion(env, key, 0, size, (jbyte *)raw_key);
    if ((*env)->ExceptionCheck(env)) {
        wipe(raw_key, sizeof(raw_key));
        return SQLITE_ERROR;
    }

    key_literal[0] = 'x';
    key_literal[1] = '\'';
    for (size_t index = 0; index < sizeof(raw_key); index++) {
        key_literal[2 + index * 2] = hex[raw_key[index] >> 4];
        key_literal[3 + index * 2] = hex[raw_key[index] & 0x0f];
    }
    key_literal[66] = '\'';

    wipe(raw_key, sizeof(raw_key));
    return SQLITE_OK;
}

static int apply_raw_key(JNIEnv *env, sqlite3 *database, jbyteArray key) {
    char key_literal[67];
    int result = create_raw_key_literal(env, key, key_literal);
    if (result == SQLITE_OK) {
        result = sqlite3_key_v2(database, "main", key_literal, (int)sizeof(key_literal));
    }
    wipe(key_literal, sizeof(key_literal));
    return result;
}

static int validate_database(sqlite3 *database) {
    sqlite3_stmt *statement = NULL;
    int result = sqlite3_prepare_v2(database, "SELECT count(*) FROM sqlite_schema", -1, &statement, NULL);
    if (result == SQLITE_OK) {
        result = sqlite3_step(statement);
        if (result == SQLITE_ROW) {
            result = SQLITE_OK;
        }
    }
    sqlite3_finalize(statement);
    return result;
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_open(JNIEnv *env, jobject receiver,
                                                          jbyteArray path, jbyteArray key) {
    (void)receiver;
    char *utf_path = copy_utf8_path(env, path);
    if (utf_path == NULL) {
        return 0;
    }

    sqlite3 *database = NULL;
    int flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX | SQLITE_OPEN_URI;
    int result = sqlite3_open_v2(utf_path, &database, flags, NULL);
    free(utf_path);
    if (result == SQLITE_OK) {
        sqlite3_extended_result_codes(database, 1);
        sqlite3_busy_timeout(database, 5000);
        result = apply_raw_key(env, database, key);
    }
    if (result == SQLITE_OK && !(*env)->ExceptionCheck(env)) {
        result = validate_database(database);
    }
    if (result != SQLITE_OK || (*env)->ExceptionCheck(env)) {
        if (!(*env)->ExceptionCheck(env)) {
            throw_sqlite(env, database, result);
        }
        sqlite3_close_v2(database);
        return 0;
    }
    return (jlong)(intptr_t)database;
}

static int read_user_version(sqlite3 *database, int *version) {
    sqlite3_stmt *statement = NULL;
    int result = sqlite3_prepare_v2(database, "PRAGMA user_version", -1, &statement, NULL);
    if (result == SQLITE_OK) {
        result = sqlite3_step(statement);
        if (result == SQLITE_ROW) {
            *version = sqlite3_column_int(statement, 0);
            result = SQLITE_OK;
        }
    }
    sqlite3_finalize(statement);
    return result;
}

static int attach_encrypted(sqlite3 *database, const char *path, const char *key_literal) {
    sqlite3_stmt *statement = NULL;
    int result = sqlite3_prepare_v2(database, "ATTACH DATABASE ? AS encrypted KEY ?", -1, &statement, NULL);
    if (result == SQLITE_OK) {
        result = sqlite3_bind_text(statement, 1, path, -1, SQLITE_TRANSIENT);
    }
    if (result == SQLITE_OK) {
        result = sqlite3_bind_text(statement, 2, key_literal, 67, SQLITE_TRANSIENT);
    }
    if (result == SQLITE_OK) {
        result = sqlite3_step(statement);
        if (result == SQLITE_DONE) {
            result = SQLITE_OK;
        }
    }
    int finalize_result = sqlite3_finalize(statement);
    return result == SQLITE_OK ? finalize_result : result;
}

static int export_encrypted(sqlite3 *database, int user_version) {
    int result = sqlite3_exec(database, "SELECT sqlcipher_export('encrypted')", NULL, NULL, NULL);
    if (result == SQLITE_OK) {
        char pragma[64];
        snprintf(pragma, sizeof(pragma), "PRAGMA encrypted.user_version=%d", user_version);
        result = sqlite3_exec(database, pragma, NULL, NULL, NULL);
    }
    if (result == SQLITE_OK) {
        result = sqlite3_exec(database, "DETACH DATABASE encrypted", NULL, NULL, NULL);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_exportPlaintext(JNIEnv *env, jobject receiver,
                                                                    jbyteArray source_path,
                                                                    jbyteArray target_path,
                                                                    jbyteArray key) {
    (void)receiver;
    char *source = copy_utf8_path(env, source_path);
    char *target = copy_utf8_path(env, target_path);
    if (source == NULL || target == NULL) {
        free(source);
        free(target);
        return;
    }

    sqlite3 *database = NULL;
    int flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX | SQLITE_OPEN_URI;
    int result = sqlite3_open_v2(source, &database, flags, NULL);
    if (result == SQLITE_OK) {
        sqlite3_extended_result_codes(database, 1);
        sqlite3_busy_timeout(database, 5000);
        result = sqlite3_exec(database, "PRAGMA wal_checkpoint(TRUNCATE)", NULL, NULL, NULL);
    }

    int user_version = 0;
    if (result == SQLITE_OK) {
        result = read_user_version(database, &user_version);
    }

    char key_literal[67];
    if (result == SQLITE_OK) {
        result = create_raw_key_literal(env, key, key_literal);
    }
    if (result == SQLITE_OK && !(*env)->ExceptionCheck(env)) {
        result = attach_encrypted(database, target, key_literal);
    }
    wipe(key_literal, sizeof(key_literal));
    if (result == SQLITE_OK && !(*env)->ExceptionCheck(env)) {
        result = export_encrypted(database, user_version);
    }

    free(source);
    free(target);
    if (result != SQLITE_OK && !(*env)->ExceptionCheck(env)) {
        throw_sqlite(env, database, result);
    }
    sqlite3_close_v2(database);
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_atomicMove(JNIEnv *env, jobject receiver,
                                                               jbyteArray source_path,
                                                               jbyteArray target_path,
                                                               jboolean replace) {
    (void)receiver;
    char *source = copy_utf8_path(env, source_path);
    char *target = copy_utf8_path(env, target_path);
    if (source == NULL || target == NULL) {
        free(source);
        free(target);
        return;
    }

#ifdef _WIN32
    wchar_t *wide_source = wide_path(source);
    wchar_t *wide_target = wide_path(target);
    DWORD flags = MOVEFILE_WRITE_THROUGH | (replace ? MOVEFILE_REPLACE_EXISTING : 0);
    if (wide_source == NULL || wide_target == NULL || !MoveFileExW(wide_source, wide_target, flags)) {
        throw_file_error(env, "Atomic move", target);
    }
    free(wide_source);
    free(wide_target);
#else
    if ((!replace && access(target, F_OK) == 0) || rename(source, target) != 0) {
        if (!replace && access(target, F_OK) == 0) errno = EEXIST;
        throw_file_error(env, "Atomic move", target);
    }
#endif
    free(source);
    free(target);
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_forceDirectory(JNIEnv *env, jobject receiver,
                                                                    jbyteArray directory_path) {
    (void)receiver;
    char *directory = copy_utf8_path(env, directory_path);
    if (directory == NULL) return;

#ifdef _WIN32
    wchar_t *wide_directory = wide_path(directory);
    wchar_t source[MAX_PATH];
    wchar_t target[MAX_PATH];
    if (wide_directory == NULL ||
        swprintf(source, MAX_PATH, L"%ls\\.sqlcipher-sync-%lu.tmp", wide_directory,
                 (unsigned long)GetCurrentProcessId()) < 0 ||
        swprintf(target, MAX_PATH, L"%ls\\.sqlcipher-sync-%lu.done", wide_directory,
                 (unsigned long)GetCurrentProcessId()) < 0) {
        SetLastError(ERROR_INVALID_NAME);
        throw_file_error(env, "Directory sync", directory);
    } else {
        HANDLE marker = CreateFileW(source, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                                    FILE_ATTRIBUTE_HIDDEN | FILE_FLAG_WRITE_THROUGH, NULL);
        char value = 0;
        DWORD written = 0;
        BOOL success = marker != INVALID_HANDLE_VALUE &&
                       WriteFile(marker, &value, 1, &written, NULL) && written == 1 &&
                       FlushFileBuffers(marker);
        if (marker != INVALID_HANDLE_VALUE) CloseHandle(marker);
        if (success) {
            DeleteFileW(target);
            success = MoveFileExW(source, target, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
        }
        if (!success) {
            DeleteFileW(source);
            DeleteFileW(target);
            throw_file_error(env, "Directory sync", directory);
        } else {
            DeleteFileW(target);
        }
    }
    free(wide_directory);
#else
    int flags = O_RDONLY;
#ifdef O_DIRECTORY
    flags |= O_DIRECTORY;
#endif
    int descriptor = open(directory, flags);
    if (descriptor < 0 || fsync(descriptor) != 0) {
        if (descriptor >= 0) close(descriptor);
        throw_file_error(env, "Directory sync", directory);
    } else {
        close(descriptor);
    }
#endif
    free(directory);
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_closeConnection(JNIEnv *env, jobject receiver,
                                                                     jlong handle) {
    (void)receiver;
    sqlite3 *database = database_from(handle);
    int result = sqlite3_close_v2(database);
    if (result != SQLITE_OK) {
        throw_sqlite(env, database, result);
    }
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_inTransaction(JNIEnv *env, jobject receiver,
                                                                   jlong handle) {
    (void)env;
    (void)receiver;
    return sqlite3_get_autocommit(database_from(handle)) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_prepare(JNIEnv *env, jobject receiver,
                                                             jlong connection_handle, jstring sql) {
    (void)receiver;
    sqlite3 *database = database_from(connection_handle);
    const jchar *text = (*env)->GetStringChars(env, sql, NULL);
    if (text == NULL) {
        return 0;
    }
    sqlite3_stmt *statement = NULL;
    int length = (*env)->GetStringLength(env, sql) * (int)sizeof(jchar);
    int result = sqlite3_prepare16_v2(database, text, length, &statement, NULL);
    (*env)->ReleaseStringChars(env, sql, text);
    if (result != SQLITE_OK) {
        throw_sqlite(env, database, result);
        return 0;
    }
    return (jlong)(intptr_t)statement;
}

static void check_statement_result(JNIEnv *env, sqlite3_stmt *statement, int result) {
    if (result != SQLITE_OK) {
        throw_sqlite(env, sqlite3_db_handle(statement), result);
    }
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_bindBlob(JNIEnv *env, jobject receiver,
                                                              jlong handle, jint index, jbyteArray value) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    jsize size = (*env)->GetArrayLength(env, value);
    jbyte *bytes = (*env)->GetByteArrayElements(env, value, NULL);
    if (bytes == NULL) {
        return;
    }
    int result = sqlite3_bind_blob(statement, index, bytes, size, SQLITE_TRANSIENT);
    (*env)->ReleaseByteArrayElements(env, value, bytes, JNI_ABORT);
    check_statement_result(env, statement, result);
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_bindDouble(JNIEnv *env, jobject receiver,
                                                                jlong handle, jint index, jdouble value) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    check_statement_result(env, statement, sqlite3_bind_double(statement, index, value));
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_bindLong(JNIEnv *env, jobject receiver,
                                                              jlong handle, jint index, jlong value) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    check_statement_result(env, statement, sqlite3_bind_int64(statement, index, value));
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_bindText(JNIEnv *env, jobject receiver,
                                                              jlong handle, jint index, jstring value) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    const jchar *text = (*env)->GetStringChars(env, value, NULL);
    if (text == NULL) {
        return;
    }
    int length = (*env)->GetStringLength(env, value) * (int)sizeof(jchar);
    int result = sqlite3_bind_text16(statement, index, text, length, SQLITE_TRANSIENT);
    (*env)->ReleaseStringChars(env, value, text);
    check_statement_result(env, statement, result);
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_bindNull(JNIEnv *env, jobject receiver,
                                                              jlong handle, jint index) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    check_statement_result(env, statement, sqlite3_bind_null(statement, index));
}

JNIEXPORT jbyteArray JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_getBlob(JNIEnv *env, jobject receiver,
                                                             jlong handle, jint index) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    int size = sqlite3_column_bytes(statement, index);
    const void *bytes = sqlite3_column_blob(statement, index);
    jbyteArray value = (*env)->NewByteArray(env, size);
    if (value != NULL && size > 0) {
        (*env)->SetByteArrayRegion(env, value, 0, size, bytes);
    }
    return value;
}

JNIEXPORT jdouble JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_getDouble(JNIEnv *env, jobject receiver,
                                                               jlong handle, jint index) {
    (void)env;
    (void)receiver;
    return sqlite3_column_double(statement_from(handle), index);
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_getLong(JNIEnv *env, jobject receiver,
                                                             jlong handle, jint index) {
    (void)env;
    (void)receiver;
    return sqlite3_column_int64(statement_from(handle), index);
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_getText(JNIEnv *env, jobject receiver,
                                                             jlong handle, jint index) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    const jchar *text = sqlite3_column_text16(statement, index);
    int length = sqlite3_column_bytes16(statement, index) / (int)sizeof(jchar);
    return text == NULL ? NULL : (*env)->NewString(env, text, length);
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_isNull(JNIEnv *env, jobject receiver,
                                                            jlong handle, jint index) {
    (void)env;
    (void)receiver;
    return sqlite3_column_type(statement_from(handle), index) == SQLITE_NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_getColumnCount(JNIEnv *env, jobject receiver,
                                                                    jlong handle) {
    (void)env;
    (void)receiver;
    return sqlite3_column_count(statement_from(handle));
}

static jsize utf16_length(const jchar *text) {
    jsize length = 0;
    while (text[length] != 0) {
        length++;
    }
    return length;
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_getColumnName(JNIEnv *env, jobject receiver,
                                                                   jlong handle, jint index) {
    (void)receiver;
    const jchar *name = sqlite3_column_name16(statement_from(handle), index);
    if (name == NULL) {
        throw_message(env, "androidx/sqlite/SQLiteException", "SQLCipher returned no column name");
        return NULL;
    }
    return (*env)->NewString(env, name, utf16_length(name));
}

JNIEXPORT jint JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_getColumnType(JNIEnv *env, jobject receiver,
                                                                   jlong handle, jint index) {
    (void)env;
    (void)receiver;
    return sqlite3_column_type(statement_from(handle), index);
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_step(JNIEnv *env, jobject receiver, jlong handle) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    int result = sqlite3_step(statement);
    if (result == SQLITE_ROW) {
        return JNI_TRUE;
    }
    if (result == SQLITE_DONE) {
        return JNI_FALSE;
    }
    throw_sqlite(env, sqlite3_db_handle(statement), result);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_reset(JNIEnv *env, jobject receiver, jlong handle) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    check_statement_result(env, statement, sqlite3_reset(statement));
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_clearBindings(JNIEnv *env, jobject receiver,
                                                                   jlong handle) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    check_statement_result(env, statement, sqlite3_clear_bindings(statement));
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_sqlcipher_SqlCipherNative_closeStatement(JNIEnv *env, jobject receiver,
                                                                    jlong handle) {
    (void)receiver;
    sqlite3_stmt *statement = statement_from(handle);
    int result = sqlite3_finalize(statement);
    if (result != SQLITE_OK) {
        throw_sqlite(env, NULL, result);
    }
}
