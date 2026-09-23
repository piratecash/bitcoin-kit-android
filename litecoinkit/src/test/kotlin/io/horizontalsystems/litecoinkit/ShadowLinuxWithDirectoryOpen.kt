package io.horizontalsystems.litecoinkit

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLinux
import java.io.File
import java.io.FileDescriptor

/**
 * Robolectric opens paths through RandomAccessFile, which cannot open a directory, so a directory
 * fsync fails with EIO. Os.rename/fsync/close are already no-ops there; make the open one too.
 */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
class ShadowLinuxWithDirectoryOpen : ShadowLinux() {

    @Implementation
    override fun open(path: String, flags: Int, mode: Int): FileDescriptor =
        if (File(path).isDirectory) FileDescriptor() else super.open(path, flags, mode)
}
