package app.smspend.data

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Runs AI-learned / user-saved regexes (ignore rules, amount rules, SMS templates)
 * safely: compiled once and cached, input length capped, and every match runs on a
 * time budget so a catastrophically backtracking pattern can't freeze a scan.
 */
object SafeRegex {
    /** Longest pattern we accept from Gemini. */
    const val MAX_PATTERN_LENGTH = 400

    /** SMS are short; anything longer is truncated before matching. */
    const val MAX_INPUT_LENGTH = 2_000

    /** Per-match budget. Normal SMS templates finish in microseconds. */
    const val MATCH_BUDGET_MS = 50L

    private const val CACHE_SIZE = 256

    /** Pattern string → compiled pattern (or [INVALID]) — LRU, shared across scans. */
    private val cache = object : LinkedHashMap<String, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean =
            size > CACHE_SIZE
    }
    private val INVALID = Any()

    /** Cached case-insensitive compile; null for invalid or over-long patterns. */
    fun compile(pattern: String): Pattern? {
        if (pattern.isBlank() || pattern.length > MAX_PATTERN_LENGTH) return null
        synchronized(cache) {
            cache[pattern]?.let { return it as? Pattern }
        }
        val compiled: Any = try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        } catch (_: PatternSyntaxException) {
            INVALID
        } catch (_: StackOverflowError) {
            INVALID
        }
        synchronized(cache) { cache[pattern] = compiled }
        return compiled as? Pattern
    }

    /**
     * Runs [block] on a matcher over [input] with a deadline. Returns null when the
     * pattern is invalid or the budget ran out (treated as "no match").
     */
    fun <T> withMatcher(
        pattern: String,
        input: String,
        budgetMs: Long = MATCH_BUDGET_MS,
        block: (Matcher) -> T,
    ): T? {
        val compiled = compile(pattern) ?: return null
        val text = DeadlineCharSequence(input.take(MAX_INPUT_LENGTH), System.nanoTime() + budgetMs * 1_000_000)
        return try {
            block(compiled.matcher(text))
        } catch (_: MatchTimeoutException) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    fun find(pattern: String, input: String): Boolean =
        withMatcher(pattern, input) { it.find() } ?: false

    /**
     * Gate for saving a learned pattern: compiles, isn't over-long, and finishes quickly
     * on inputs that make nested quantifiers blow up.
     */
    fun isSafe(pattern: String): Boolean {
        if (compile(pattern) == null) return false
        return BACKTRACK_PROBES.all { probe -> withMatcher(pattern, probe) { it.find(); true } == true }
    }

    private val BACKTRACK_PROBES = listOf(
        "a".repeat(64) + "!",
        "1".repeat(64) + "x",
        " ".repeat(64) + "!",
        "Rs " + "1,".repeat(30) + "x",
    )

    private class MatchTimeoutException : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * java.util.regex has no timeout, but it reads input through [CharSequence.get];
     * checking the clock there aborts a runaway match.
     */
    private class DeadlineCharSequence(
        private val inner: CharSequence,
        private val deadlineNanos: Long,
    ) : CharSequence {
        private var reads = 0

        override val length: Int get() = inner.length

        override fun get(index: Int): Char {
            // Only look at the clock every 256 reads; System.nanoTime() isn't free.
            if (++reads and 0xFF == 0 && System.nanoTime() > deadlineNanos) throw MatchTimeoutException()
            return inner[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineCharSequence(inner.subSequence(startIndex, endIndex), deadlineNanos)

        override fun toString(): String = inner.toString()
    }
}
