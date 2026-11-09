package app.smspend.util

import app.smspend.data.RawSms

/**
 * Pure helpers for SMS delivered through the SMS_RECEIVED broadcast.
 *
 * Kept free of Android types so it can be unit tested on the JVM.
 */
object IncomingSms {
    /** One logical message assembled from the PDUs of a single broadcast. */
    data class Joined(
        val sender: String,
        val body: String,
    )

    /** A single PDU part: originating address and the text it carries. */
    data class Part(
        val sender: String?,
        val body: String?,
    )

    /**
     * A long SMS arrives as several PDUs in one broadcast. Group them by sender
     * (a broadcast is normally one sender, but be defensive) and concatenate the
     * parts in order, the same way the default SMS app stores them in the inbox.
     */
    fun join(parts: List<Part>): List<Joined> {
        val bySender = LinkedHashMap<String, StringBuilder>()
        for (part in parts) {
            val sender = part.sender?.trim().orEmpty()
            val text = part.body ?: continue
            bySender.getOrPut(sender) { StringBuilder() }.append(text)
        }
        return bySender
            .filterValues { it.isNotEmpty() }
            .map { (sender, body) -> Joined(sender, body.toString()) }
    }

    /**
     * Whether [inbox] already holds the broadcast message. The default SMS app
     * writes the inbox row asynchronously, so the scan has to wait for it: the
     * existing scan dedups on the inbox `_id`, which only exists once that row does.
     * [body] may be a truncated prefix (WorkManager input data is size-capped), so
     * the inbox body only has to start with it.
     */
    fun isInInbox(inbox: List<RawSms>, sender: String, body: String): Boolean {
        val wantBody = normalizeBody(body)
        if (wantBody.isEmpty()) return true
        val wantSender = normalizeSender(sender)
        return inbox.any { sms ->
            normalizeBody(sms.body).startsWith(wantBody) &&
                (wantSender.isEmpty() || normalizeSender(sms.sender) == wantSender)
        }
    }

    /** Collapse whitespace; some SMS apps trim or re-wrap line endings on save. */
    fun normalizeBody(body: String): String = body.replace(WHITESPACE, " ").trim()

    /** Compare addresses case-insensitively, ignoring spaces, dashes and a leading '+'. */
    fun normalizeSender(sender: String): String =
        sender.uppercase().replace(SENDER_NOISE, "")

    private val WHITESPACE = Regex("\\s+")
    private val SENDER_NOISE = Regex("[\\s\\-+]")
}
