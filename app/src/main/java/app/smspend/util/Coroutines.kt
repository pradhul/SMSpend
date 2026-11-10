package app.smspend.util

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for coroutine code: identical, except [CancellationException] is
 * rethrown. Plain `runCatching` swallows it, so a cancelled scope (screen closed,
 * work stopped) would keep running the rest of the block as if nothing happened.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
