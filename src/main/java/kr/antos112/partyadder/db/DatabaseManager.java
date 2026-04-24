package kr.antos112.partyadder.db;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public final class DatabaseManager implements AutoCloseable {

    private final JavaPlugin plugin;
    private final String jdbcUrl;
    private final ExecutorService executor;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;

        File dbFile = new File(plugin.getDataFolder(), "partyadder.db");
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }

        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "PartyAdder-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public void init() {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("PRAGMA journal_mode=WAL;");
            statement.execute("PRAGMA synchronous=NORMAL;");
            statement.execute("PRAGMA foreign_keys=ON;");

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS party (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    leader_uuid TEXT NOT NULL,
                    pvp_enabled INTEGER NOT NULL DEFAULT 0,
                    public_party INTEGER NOT NULL DEFAULT 1,
                    chat_enabled INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
            """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS party_member (
                    party_id INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    role TEXT NOT NULL,
                    joined_at INTEGER NOT NULL,
                    PRIMARY KEY (party_id, player_uuid)
                )
            """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS party_invite (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    party_id INTEGER NOT NULL,
                    inviter_uuid TEXT NOT NULL,
                    target_uuid TEXT NOT NULL,
                    expires_at INTEGER NOT NULL
                )
            """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS party_request (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    party_id INTEGER NOT NULL,
                    requester_uuid TEXT NOT NULL,
                    expires_at INTEGER NOT NULL
                )
            """);

            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_party_member_uuid ON party_member(player_uuid)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_invite_target ON party_invite(target_uuid)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_invite_party ON party_invite(party_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_request_party ON party_request(party_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_request_requester ON party_request(requester_uuid)");

            plugin.getLogger().info("SQLite DB 초기화 완료: partyadder.db");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "DB 초기화 실패", e);
            throw new IllegalStateException("Database init failed", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public void runAsync(SqlRunnable runnable) {
        executor.execute(() -> {
            try (Connection connection = getConnection()) {
                runnable.run(connection);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "비동기 DB 작업 실패", e);
            }
        });
    }

    public <T> java.util.concurrent.CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (Connection connection = getConnection()) {
                return supplier.get(connection);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "비동기 DB 조회 실패", e);
                return null;
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlSupplier<T> {
        T get(Connection connection) throws SQLException;
    }
}