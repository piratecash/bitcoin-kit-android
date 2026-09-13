# SQLCipher JVM driver

This module is the Desktop JVM backend for Bitcoin Kit database encryption. It wraps the official
[SQLCipher 4.17.0](https://github.com/sqlcipher/sqlcipher/tree/v4.17.0) core with the AndroidX
`SQLiteDriver` API and bundles JNI libraries for Linux x64, Windows x64, and macOS ARM64.
The Linux binary targets the `manylinux_2_28` / glibc 2.28 baseline.

The native libraries are built from these exact revisions:

- SQLCipher `810db22f575ee7cf94ea96a3e91622b5fcece3dc`
- LibTomCrypt `7e7eb695d581782f04b24dc444cbfde86af59853`

The binaries are **not in this repository**: the `SQLCipher natives` workflow builds and tests them
on every supported Desktop OS, and `release-natives.yml` attaches the whole set to every `v*` tag.
Locally they are downloaded rather than built, and `test` does it on its own:

```shell
./gradlew :sqlcipher-driver:fetchNatives                                  # newest tag that has them
./gradlew :sqlcipher-driver:fetchNatives -PnativesVersion=v0.1.0-pcash.28  # a specific tag
```

Two boundaries: `fetchNatives` works from tag `v0.1.0-pcash.28` onwards, and versions without a tag
(a snapshot of a branch or a commit) are not published on JitPack at all.

`buildNative` is for working on the sources and expects them checked out. SQLCipher must contain the
generated `sqlite3.c` and `sqlite3.h` amalgamation.

```shell
./gradlew :sqlcipher-driver:buildNative \
  -PsqlcipherSourceDir=/path/to/sqlcipher \
  -PlibtomcryptSourceDir=/path/to/libtomcrypt
./gradlew :sqlcipher-driver:test
```

Every bundled binary has an adjacent SHA-256 file which the loader verifies before `System.load`.
`checkNativesComplete` refuses to publish a set that is incomplete, that changed after it was
fetched, or that was built from sources other than the ones in the tree.

Third-party license texts are in [`third_party`](third_party).
