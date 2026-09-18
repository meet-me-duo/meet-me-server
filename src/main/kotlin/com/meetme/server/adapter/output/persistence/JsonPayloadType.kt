package com.meetme.server.adapter.output.persistence

import org.komapper.jdbc.spi.JdbcUserDefinedDataType
import org.postgresql.util.PGobject
import tools.jackson.databind.json.JsonMapper
import java.sql.JDBCType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLType
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class JsonPayload private constructor(
    val value: String,
) {
    companion object {
        private val jsonMapper = JsonMapper.builder().build()

        fun from(rawValue: String): JsonPayload = JsonPayload(jsonMapper.readTree(rawValue).toString())
    }
}

class JsonPayloadType : JdbcUserDefinedDataType<JsonPayload> {
    override val name: String = "jsonb"
    override val type: KType = typeOf<JsonPayload>()
    override val sqlType: SQLType = JDBCType.OTHER

    override fun getValue(
        rs: ResultSet,
        index: Int,
    ): JsonPayload = JsonPayload.from(requireNotNull(rs.getObject(index, PGobject::class.java).value))

    override fun getValue(
        rs: ResultSet,
        columnLabel: String,
    ): JsonPayload = JsonPayload.from(requireNotNull(rs.getObject(columnLabel, PGobject::class.java).value))

    override fun setValue(
        ps: PreparedStatement,
        index: Int,
        value: JsonPayload,
    ) {
        val jsonb =
            PGobject().apply {
                type = name
                this.value = value.value
            }
        ps.setObject(index, jsonb)
    }

    override fun toString(value: JsonPayload): String = value.value
}
