package com.meetme.server.shared.operations

import com.meetme.server.account.adapter.input.web.AuthEndToEndPostgresTest
import com.meetme.server.shared.adapter.output.persistence.DatabaseMigrationRunner
import com.meetme.server.shared.adapter.output.persistence.DatabaseMigrationSettings
import org.springframework.test.context.DynamicPropertyRegistry
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import kotlin.system.exitProcess

/**
 * Local-only native HTTP/browser verification entrypoint. Reuses #109's isolated PostgreSQL,
 * Redis, OIDC/JWKS server and memory-only synthetic RS256 keys without changing that fixture.
 * The allowlisted identity exists only in this fresh disposable database and JVM configuration.
 */
fun main() {
    val stopFile = Path.of(requireNotNull(System.getProperty("meetme.issue110.native.stop-file")))
    val settings = mutableMapOf<String, String>()
    AuthEndToEndPostgresTest.properties(
        object : DynamicPropertyRegistry {
            override fun add(
                name: String,
                valueSupplier: Supplier<Any>,
            ) {
                settings[name] = valueSupplier.get().toString()
            }
        },
    )
    val stopped = AtomicBoolean()

    fun stopFixture() {
        if (stopped.compareAndSet(false, true)) AuthEndToEndPostgresTest.stop()
    }
    Runtime.getRuntime().addShutdownHook(Thread({ stopFixture() }, "issue110-native-fixture-cleanup"))
    Thread(
        {
            while (!Files.exists(stopFile)) Thread.sleep(250)
            exitProcess(0)
        },
        "issue110-native-stop-file",
    ).apply { isDaemon = true }.start()
    try {
        launchBrowserServer(settings)
    } catch (failure: Throwable) {
        runCatching { stopFixture() }
        throw failure
    }
}

private fun launchBrowserServer(settings: Map<String, String>) {
    val url = requireNotNull(settings["spring.datasource.url"])
    require(URI(url.removePrefix("jdbc:")).host in setOf("localhost", "127.0.0.1", "[::1]")) {
        "Native operations verification requires the isolated loopback PostgreSQL fixture"
    }
    val username = requireNotNull(settings["spring.datasource.username"])
    val password = requireNotNull(settings["spring.datasource.password"])
    DatabaseMigrationRunner.migrate(DatabaseMigrationSettings(url, username, password))
    val account = UUID.fromString("00000000-0000-4000-8000-000000000110")
    DriverManager.getConnection(url, username, password).use { connection ->
        connection.autoCommit = false
        connection
            .prepareStatement("INSERT INTO account_users(id,display_name,created_at) VALUES (?,'Synthetic operator',now())")
            .use { statement ->
                statement.setObject(1, account)
                statement.executeUpdate()
            }
        connection
            .prepareStatement("INSERT INTO account_identities(provider,subject,user_id) VALUES ('GOOGLE','shared-synthetic-subject',?)")
            .use { statement ->
                statement.setObject(1, account)
                statement.executeUpdate()
            }
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO meeting_rooms(id,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                    search_range_source,manual_only,collection_status,created_at,invite_code)
                VALUES ('10000000-0000-4000-8000-000000000110','ISSUE110_NATIVE_PRIVATE_RAW','REMOTE','Asia/Seoul',
                    '2026-10-18','2026-10-20','HOST_SPECIFIED',true,'COLLECTING',now(),'ISSUE110SYNTHETICROOM0');
                INSERT INTO participants(id,room_id,user_id,role,joined_at,display_name)
                VALUES ('70000000-0000-4000-8000-000000000110','10000000-0000-4000-8000-000000000110',
                    '00000000-0000-4000-8000-000000000110','HOST',now(),'Synthetic operator');
                INSERT INTO submission_heads(id,room_id,participant_id)
                VALUES ('80000000-0000-4000-8000-000000000110','10000000-0000-4000-8000-000000000110',
                    '70000000-0000-4000-8000-000000000110');
                INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at)
                VALUES ('90000000-0000-4000-8000-000000000110','80000000-0000-4000-8000-000000000110',
                    1,'ISSUE110_NATIVE_PRIVATE_RAW','ko-KR',now());
                UPDATE submission_heads SET latest_version_id='90000000-0000-4000-8000-000000000110'
                    WHERE id='80000000-0000-4000-8000-000000000110';
                INSERT INTO submission_batches(id,room_id,fixed_at)
                VALUES ('30000000-0000-4000-8000-000000000110','10000000-0000-4000-8000-000000000110',now());
                INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version)
                VALUES ('20000000-0000-4000-8000-000000000110','10000000-0000-4000-8000-000000000110',
                    '30000000-0000-4000-8000-000000000110','ANALYSIS_DELAYED',now(),1);
                UPDATE meeting_rooms SET active_run_id='20000000-0000-4000-8000-000000000110'
                    WHERE id='10000000-0000-4000-8000-000000000110';
                INSERT INTO analysis_invocations(id,coordination_run_id,run_version,started_at,deadline_at,
                    owner_token,gemini_attempts,luna_attempts,finished_at,policy_version)
                VALUES ('40000000-0000-4000-8000-000000000110','20000000-0000-4000-8000-000000000110',1,
                    now(),now()+interval '60 seconds','60000000-0000-4000-8000-000000000110',1,0,
                    now()+interval '1 second','bounded-luna-v1');
                INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,finished_at,
                    provider,model,policy_version,invocation_id,input_tokens,output_tokens,estimated_cost_usd)
                VALUES ('50000000-0000-4000-8000-000000000110','20000000-0000-4000-8000-000000000110',1,
                    now(),now()+interval '1 second','GEMINI','gemini-3.8-flash','bounded-luna-v1',
                    '40000000-0000-4000-8000-000000000110',null,null,null);
                """.trimIndent(),
            )
        }
        connection.commit()
    }
    System.setProperty("meetme.operations.enabled", "true")
    System.setProperty("meetme.operations.operator-user-ids", account.toString())
    System.setProperty("meetme.operations.cleanup-enabled", "false")
    AuthEndToEndPostgresTest.runBrowserServer(apiPort = 18910, webPort = 18911)
    println("ISSUE110_SYNTHETIC_OPERATOR_BROWSER_SERVER_READY")
}
