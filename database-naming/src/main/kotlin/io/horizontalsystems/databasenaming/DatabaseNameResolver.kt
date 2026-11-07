package io.horizontalsystems.databasenaming

/** Maps a kit's default file, directory or namespace name to the name it actually uses on disk. */
fun interface DatabaseNameResolver {
    fun resolve(defaultName: String): String

    object Identity : DatabaseNameResolver {
        override fun resolve(defaultName: String): String = defaultName
    }
}
