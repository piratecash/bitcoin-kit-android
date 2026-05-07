package io.horizontalsystems.litecoinkit.mweb

import java.util.concurrent.TimeoutException

internal object MwebDaemonErrorMapper {
    fun <T> map(block: () -> T): T {
        return try {
            block()
        } catch (error: Throwable) {
            throwMapped(error)
        }
    }

    suspend fun <T> mapSuspend(block: suspend () -> T): T {
        return try {
            block()
        } catch (error: Throwable) {
            throwMapped(error)
        }
    }

    private fun throwMapped(error: Throwable): Nothing {
        when (error) {
            is MwebError -> throw error
            is UnsatisfiedLinkError,
            is NoClassDefFoundError -> throw MwebError.NativeUnavailable(error)
            is TimeoutException -> throw MwebError.NativeUnavailable(error)
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                throw MwebError.DaemonCrashed(error)
            }
            is Exception -> throw MwebError.DaemonCrashed(error)
            else -> throw error
        }
    }
}
