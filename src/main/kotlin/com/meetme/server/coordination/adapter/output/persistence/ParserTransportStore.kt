package com.meetme.server.coordination.adapter.output.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.DisposableBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Component
import javax.sql.DataSource

/** Private bounded pool; it never replaces or changes the business datasource. */
@Component
class ParserTransportStore(
    main: DataSource,
) : DisposableBean {
    internal final val source: HikariDataSource
    final val jdbc: JdbcTemplate
    final val transactionManager: DataSourceTransactionManager

    init {
        val configured = main.unwrap(HikariDataSource::class.java)
        source =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = configured.jdbcUrl
                    username = configured.username
                    password = configured.password
                    configured.dataSourceProperties.forEach { key, value -> addDataSourceProperty(key.toString(), value) }
                    addDataSourceProperty("connectTimeout", "1")
                    addDataSourceProperty("socketTimeout", "2")
                    maximumPoolSize = 2
                    minimumIdle = 0
                    connectionTimeout = 250
                    validationTimeout = 250
                    initializationFailTimeout = -1
                    poolName = "parser-transport"
                },
            )
        jdbc = JdbcTemplate(source).apply { queryTimeout = 1 }
        transactionManager = DataSourceTransactionManager(source)
    }

    override fun destroy() = source.close()
}
