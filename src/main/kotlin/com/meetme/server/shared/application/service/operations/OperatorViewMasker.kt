package com.meetme.server.shared.application.service.operations

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Presentation-only privacy policy. Stored evidence and model input must remain unchanged. */
class OperatorViewMasker {
    fun name(value: String?): String {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return "이름 없음"
        return text.substring(0, text.offsetByCodePoints(0, 1)) + "*".repeat(text.codePointCount(0, text.length) - 1)
    }

    fun text(value: String): String {
        val known = phones.replace(emails.replace(value, "[이메일 숨김]"), "[전화번호 숨김]")
        return internationalPhones.replace(known) { match ->
            // E.164 bounds apply to the contact, not subsequent schedule number groups.
            var end = match.value.length
            while (match.value.take(end).count(Char::isDigit) > 15) {
                val boundary = match.value.take(end).indexOfLast { it == ' ' || it == '-' }
                if (boundary < 0) break
                end = boundary
            }
            val contact = match.value.take(end)
            if (contact.count(Char::isDigit) in 8..15) "[전화번호 숨김]" + match.value.drop(end) else match.value
        }
    }

    fun participantAlias(
        roomId: UUID,
        participantId: UUID,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$roomId:$participantId".toByteArray(StandardCharsets.US_ASCII))
        return "참가자 " + digest.take(4).joinToString("") { "%02X".format(it) }
    }

    companion object {
        private val emails = Regex("[A-Za-z0-9.!#%&'*+/=?^_`{|}~-]+@(?:[\\p{L}0-9](?:[\\p{L}0-9-]*[\\p{L}0-9])?\\.)+[\\p{L}]{2,63}")
        private const val SCHEDULE_NUMBER_GROUP =
            "(?:" +
                "[0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}(?![0-9])|" +
                "[0-9]{1,2}[/.][0-9]{1,2}(?![0-9])|" +
                "[0-9]{1,4}[ ]*[-~–][ ]*[0-9]{1,4}[ ]*(?:시간|분|시|초)|" +
                "[0-9]{1,4}(?:년|월|일|시|분|초|:))"
        private val internationalPhones =
            Regex(
                """(?<![0-9])\+[1-9][0-9]{0,2}(?:[- ](?!""" + SCHEDULE_NUMBER_GROUP +
                    """)(?:\([0-9]{1,4}\)|[0-9]{1,4})){2,6}(?![0-9:]|시|분|초|년|월|일|부터|까지|[~–])""",
            )
        private val phones =
            Regex(
                "(?<![0-9])(?:" +
                    "\\+82[- ]?1[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}|" +
                    "\\+1[ -]?(?:\\([0-9]{3}\\)|[0-9]{3})[ -]?[0-9]{3}[- ]?[0-9]{4}|" +
                    "\\+44[- ]?[0-9]{2,4}[- ]?[0-9]{3,4}[- ]?[0-9]{4}|" +
                    "\\+[1-9][0-9]{7,14}|" +
                    "01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}|" +
                    "0(?:2|[3-6][0-9])[- ][0-9]{3,4}[- ][0-9]{4}" +
                    ")(?![0-9])",
            )
    }
}
