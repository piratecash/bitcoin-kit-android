package io.horizontalsystems.bitcoincore.managers

internal inline fun String.parsePublicKeyPath(invalidPathError: () -> Throwable): List<Int> =
    split("/").map { it.toIntOrNull() ?: throw invalidPathError() }
