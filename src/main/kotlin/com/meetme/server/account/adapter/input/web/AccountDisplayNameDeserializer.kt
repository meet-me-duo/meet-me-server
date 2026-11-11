package com.meetme.server.account.adapter.input.web

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer

/** Profile updates accept JSON strings only, without changing coercion for other APIs. */
class AccountDisplayNameDeserializer : StdDeserializer<String>(String::class.java) {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): String {
        require(parser.currentToken() == JsonToken.VALUE_STRING) { "Account display name must be a JSON string" }
        return parser.string
    }
}
