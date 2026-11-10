package com.meetme.server.coordination.adapter.input.web

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.annotation.DirtiesContext.HierarchyMode
import org.springframework.test.context.TestContextManager
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Class-owned database fixtures must not leave their context or pool alive after class teardown. */
class CorrectionRoundFixtureContextLifecycleTest {
    @Test
    fun `class teardown closes the original context and pool after the database owner stops`() {
        val owner = SyntheticOwner()
        val manager = TestContextManager(SyntheticOwner::class.java)
        try {
            manager.beforeTestClass()
            val originalContext = manager.testContext.applicationContext as ConfigurableApplicationContext
            val originalPool = originalContext.getBean(HikariDataSource::class.java)
            assertTrue(originalContext.isActive)
            assertFalse(originalPool.isClosed)

            // Concrete fixtures stop their container in JUnit @AfterAll, before Spring's afterTestClass callback.
            owner.stopContainer()
            assertTrue(owner.stopped)
            manager.afterTestClass()

            assertAll(
                { assertFalse(originalContext.isActive, "The class-owned context must close at class teardown") },
                { assertTrue(originalPool.isClosed, "Context destruction must close the original Hikari datasource") },
            )
        } finally {
            // Clean up a failing RED without closing resources before the lifecycle assertions.
            manager.testContext.markApplicationContextDirty(HierarchyMode.CURRENT_LEVEL)
        }
    }

    @SpringBootTest(classes = [PoolOnlyConfiguration::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
    class SyntheticOwner : CorrectionRoundPostgresFixture() {
        final var stopped = false
            private set

        fun stopContainer() {
            stopped = true
        }
    }

    @TestComponent
    @Configuration(proxyBeanMethods = false)
    class PoolOnlyConfiguration {
        // The no-argument constructor is lazy: no JDBC URL, connection, database, or provider is started.
        @Bean(destroyMethod = "close")
        fun dataSource(): HikariDataSource = HikariDataSource()
    }
}
