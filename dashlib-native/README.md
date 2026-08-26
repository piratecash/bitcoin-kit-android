# dashlib-native

Prebuilt `dashjbls` native libraries for desktop JVMs, plus the loader that picks the right one.

Android does not need this module: `dashlib` ships the same native inside its AAR and the platform
loads it. This module exists so a desktop (JVM) application can verify Dash InstantSend BLS
signatures at all.

## Platform contract

A packaged `natives/<os>-<arch>` resource promises a loadable native **on these conditions only** —
not on every system reporting that `os.arch`:

| Tag           | Requirement                                       |
|---------------|---------------------------------------------------|
| `linux-x64`   | glibc >= 2.35                                     |
| `macos-arm64` | macOS >= 12.0                                     |
| `macos-x64`   | macOS >= 12.0                                     |
| `windows-x64` | MinGW build, no external DLLs required            |

Below those thresholds, or on any other platform, the load fails and `DashjBlsLibrary.available`
returns `false`.

## Required startup call

A desktop application **must** touch `DashjBlsLibrary.available` on startup, before constructing any
kit:

```kotlin
if (!DashjBlsLibrary.available) {
    // BLS signatures will not be verified; log it, degrade, or refuse to start
}
```

`BLS` in `dashlib` never calls this loader itself, so without that call signature verification simply
does not work on desktop. It is fail-closed: an unverifiable lock vote is rejected, silently.

## Where the binaries come from

The binaries are **not in this repository**, and JitPack does not compile them — unlike every other
artifact here. The `build-natives.yml` matrix builds each platform and runs the golden-vector smoke
test against the binary it just built; on every `v*` tag `release-natives.yml` attaches the whole set
to that tag, next to `SOURCE.sha256`, the attestation of the sources they were built from.

Locally nothing needs doing by hand — `test` downloads the set on its own:

```
./gradlew :dashlib-native:fetchNatives                                  # newest tag that has them
./gradlew :dashlib-native:fetchNatives -PnativesVersion=v0.1.0-pcash.28  # a specific tag
```

Two boundaries: `fetchNatives` works from tag `v0.1.0-pcash.28` onwards, and versions without a tag
(a snapshot of a branch or a commit) are not published on JitPack at all.

`buildNative` is for working on the C sources; it replaces one platform of the downloaded set:

```
./gradlew :dashlib-native:buildNative                          # host target
./gradlew :dashlib-native:buildNative -PnativeTarget=windows-x64  # Linux host, MinGW cross-build
```

`checkNativesComplete` refuses to publish a set that is incomplete, that changed after it was
fetched, or that was built from sources other than the ones in the tree.

## Licenses

The native links in third-party code; the jar carries their licenses under
`META-INF/licenses/{bls-signatures,relic,winpthreads}/`.
