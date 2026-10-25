// Copyright (C) 2026 Toit contributors.
package org.toit.lumascan

/** Owns at most one camera buffer until its tagged result proves an exact sensor timestamp. */
internal class CameraFrameMatcher<I : AutoCloseable, R : Any>(
    private val imageTimestamp: (I) -> Long,
    private val resultTimestamp: (R) -> Long?
) : AutoCloseable {
    private var image: I? = null
    private var result: R? = null
    val hasResult: Boolean get() = result != null
    val candidateTimestamp: Long? get() = image?.let(imageTimestamp)
    val expectedTimestamp: Long? get() = result?.let(resultTimestamp)

    fun receiveImage(incoming: I) {
        val retained = image
        if (retained != null && imageTimestamp(incoming) < imageTimestamp(retained)) {
            // A timed-out request can deliver an old buffer after the next request's image.
            incoming.close()
            return
        }
        image?.close()
        image = incoming
    }

    fun receiveResult(incoming: R) { result = incoming }

    /** Transfers buffer ownership only on an exact match; a stale buffer is closed, not admitted. */
    fun takeMatched(): Pair<I, R>? {
        val buffer = image ?: return null
        val metadata = result ?: return null
        image = null
        if (imageTimestamp(buffer) != resultTimestamp(metadata)) {
            buffer.close()
            return null
        }
        return buffer to metadata
    }

    override fun close() {
        image?.close()
        image = null
    }
}

internal object CameraTimeoutPolicy {
    enum class Action { RETRY, DRAIN, FAIL }
    fun action(consecutiveTimeouts: Int, resultCompleted: Boolean) = when {
        consecutiveTimeouts >= 3 -> Action.FAIL
        resultCompleted -> Action.RETRY
        else -> Action.DRAIN
    }
}
