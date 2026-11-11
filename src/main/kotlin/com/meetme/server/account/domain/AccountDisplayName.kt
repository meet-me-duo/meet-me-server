package com.meetme.server.account.domain

@JvmInline
value class AccountDisplayName private constructor(
    val value: String,
) {
    companion object {
        fun of(value: String): AccountDisplayName {
            require(value.codePoints().noneMatch { Character.isISOControl(it) || it in 0xD800..0xDFFF }) {
                "Account display name must not contain control characters or invalid Unicode"
            }
            val normalized = value.trim()
            require(normalized.codePointCount(0, normalized.length) in 1..50) {
                "Account display name must contain between 1 and 50 characters"
            }
            return AccountDisplayName(normalized)
        }
    }
}
