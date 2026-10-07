import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/** Standalone JDBC metadata reader; never starts Spring or runs Flyway. */
public final class ReadOnlyFlywayProbe {
    private static String quote(String text) {
        if (text == null) return "null";
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("", "02000");
            return rows.getString(1);
        }
    }
    public static void main(String[] args) {
        String output = "{\"status\":\"unavailable\",\"reason\":\"HELPER_FAILED\"}";
        Connection connection = null;
        try {
            String url = System.getenv("DATABASE_URL");
            String user = System.getenv("DATABASE_USERNAME");
            String password = System.getenv("DATABASE_PASSWORD");
            if (url == null || user == null || password == null || !url.startsWith("jdbc:postgresql:")) {
                System.out.println("{\"status\":\"unavailable\",\"reason\":\"MISSING_CONNECTION\"}");
                return;
            }
            Class.forName("org.postgresql.Driver");
            Properties properties = new Properties();
            properties.setProperty("user", user);
            properties.setProperty("password", password);
            properties.setProperty("connectTimeout", "5");
            properties.setProperty("socketTimeout", "5");
            properties.setProperty("readOnlyMode", "transaction");
            DriverManager.setLoginTimeout(5);
            connection = DriverManager.getConnection(url, properties);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("SET TRANSACTION READ ONLY");
                if (!"on".equals(scalar(statement, "SHOW transaction_read_only"))) {
                    throw new SQLException("", "25006");
                }
                statement.execute("SET LOCAL statement_timeout = 5000");
                statement.execute("SET LOCAL lock_timeout = 2000");
                String database = scalar(statement, "SELECT current_database()");
                String version = scalar(statement, "SELECT current_setting('server_version')");
                StringBuilder history = new StringBuilder();
                try (ResultSet rows = statement.executeQuery(
                    "SELECT version, script, checksum, success FROM public.flyway_schema_history ORDER BY installed_rank")) {
                    int count = 0;
                    while (rows.next()) {
                        if (++count > 100) throw new SQLException("", "54000");
                        if (history.length() > 0) history.append(',');
                        String migrationVersion = rows.getString("version");
                        String script = rows.getString("script");
                        int checksum = rows.getInt("checksum");
                        boolean nullChecksum = rows.wasNull();
                        history.append("{\"version\":").append(quote(migrationVersion))
                            .append(",\"script\":").append(quote(script))
                            .append(",\"checksum\":").append(nullChecksum ? "null" : Integer.toString(checksum))
                            .append(",\"success\":").append(rows.getBoolean("success")).append('}');
                    }
                }
                output = "{\"status\":\"success\",\"database\":" + quote(database)
                    + ",\"serverVersion\":" + quote(version) + ",\"history\":[" + history + "]}";
            }
        } catch (SQLException failure) {
            String state = failure.getSQLState();
            output = "{\"status\":\"unavailable\",\"reason\":\"SQL_"
                + (state != null && state.matches("[A-Z0-9]{5}") ? state : "UNKNOWN") + "\"}";
        } catch (Exception failure) {
            output = "{\"status\":\"unavailable\",\"reason\":\"HELPER_FAILED\"}";
        } finally {
            if (connection != null) {
                try { connection.rollback(); }
                catch (SQLException failure) { output = "{\"status\":\"unavailable\",\"reason\":\"ROLLBACK_FAILED\"}"; }
                try { connection.close(); }
                catch (SQLException failure) { output = "{\"status\":\"unavailable\",\"reason\":\"CLOSE_FAILED\"}"; }
            }
        }
        System.out.println(output);
    }
}
