package com.worldgit.database;

import com.worldgit.model.BranchInvite;
import com.worldgit.model.MergeJournalEntry;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SQLite 数据库管理器。
 */
public final class DatabaseManager implements Closeable {

    private static final int SCHEMA_VERSION = 3;

    private final Path databaseFile;
    private final String jdbcUrl;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("无法加载 SQLite JDBC 驱动", exception);
        }
    }

    public DatabaseManager(Path databaseFile) throws SQLException {
        this.databaseFile = Objects.requireNonNull(databaseFile, "数据库文件不能为空").toAbsolutePath();
        this.jdbcUrl = "jdbc:sqlite:" + this.databaseFile;
        initialize();
    }

    public DatabaseManager(String databaseFile) throws SQLException {
        this(Path.of(databaseFile));
    }

    public Path getDatabaseFile() {
        return databaseFile;
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        configureConnection(connection);
        return connection;
    }

    public <T> T withConnection(SqlFunction<Connection, T> action) throws SQLException {
        try (Connection connection = openConnection()) {
            return action.apply(connection);
        }
    }

    public void withTransaction(SqlConsumer<Connection> action) throws SQLException {
        withConnection(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                action.accept(connection);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
            return null;
        });
    }

    public <T> T withTransactionResult(SqlFunction<Connection, T> action) throws SQLException {
        return withConnection(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = action.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    public void upsertMergeJournal(String branchId, String phase, Instant updatedAt) throws SQLException {
        withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO merge_journal (branch_id, phase, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(branch_id) DO UPDATE SET
                        phase = excluded.phase,
                        updated_at = excluded.updated_at
                    """
            )) {
                statement.setString(1, branchId);
                statement.setString(2, phase);
                statement.setLong(3, updatedAt.toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public Optional<MergeJournalEntry> findMergeJournal(String branchId) throws SQLException {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, branch_id, phase, updated_at
                    FROM merge_journal
                    WHERE branch_id = ?
                    """
            )) {
                statement.setString(1, branchId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new MergeJournalEntry(
                            resultSet.getLong("id"),
                            resultSet.getString("branch_id"),
                            resultSet.getString("phase"),
                            Instant.ofEpochMilli(resultSet.getLong("updated_at"))
                    ));
                }
            }
        });
    }

    public List<MergeJournalEntry> listMergeJournals() throws SQLException {
        return withConnection(connection -> {
            List<MergeJournalEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, branch_id, phase, updated_at
                    FROM merge_journal
                    ORDER BY updated_at ASC, id ASC
                    """
            ); ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new MergeJournalEntry(
                            resultSet.getLong("id"),
                            resultSet.getString("branch_id"),
                            resultSet.getString("phase"),
                            Instant.ofEpochMilli(resultSet.getLong("updated_at"))
                    ));
                }
            }
            return entries;
        });
    }

    public List<MergeJournalEntry> listMergeJournalsByPhase(String phase) throws SQLException {
        return withConnection(connection -> {
            List<MergeJournalEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, branch_id, phase, updated_at
                    FROM merge_journal
                    WHERE phase = ?
                    ORDER BY updated_at ASC, id ASC
                    """
            )) {
                statement.setString(1, phase);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        entries.add(new MergeJournalEntry(
                                resultSet.getLong("id"),
                                resultSet.getString("branch_id"),
                                resultSet.getString("phase"),
                                Instant.ofEpochMilli(resultSet.getLong("updated_at"))
                        ));
                    }
                }
            }
            return entries;
        });
    }

    public int deleteMergeJournal(String branchId) throws SQLException {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM merge_journal WHERE branch_id = ?"
            )) {
                statement.setString(1, branchId);
                return statement.executeUpdate();
            }
        });
    }

    public void upsertBranchInvite(BranchInvite invite) throws SQLException {
        withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO branch_invites (branch_id, player_uuid, invited_by, invited_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(branch_id, player_uuid) DO UPDATE SET
                        invited_by = excluded.invited_by,
                        invited_at = excluded.invited_at
                    """
            )) {
                statement.setString(1, invite.branchId());
                statement.setString(2, invite.playerUuid().toString());
                statement.setString(3, invite.invitedBy() == null ? null : invite.invitedBy().toString());
                statement.setLong(4, invite.invitedAt().toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public List<BranchInvite> listBranchInvites(String branchId) throws SQLException {
        return withConnection(connection -> {
            List<BranchInvite> invites = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT branch_id, player_uuid, invited_by, invited_at
                    FROM branch_invites
                    WHERE branch_id = ?
                    ORDER BY invited_at ASC
                    """
            )) {
                statement.setString(1, branchId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        invites.add(new BranchInvite(
                                resultSet.getString("branch_id"),
                                UUID.fromString(resultSet.getString("player_uuid")),
                                parseUuid(resultSet.getString("invited_by")),
                                Instant.ofEpochMilli(resultSet.getLong("invited_at"))
                        ));
                    }
                }
            }
            return invites;
        });
    }

    public int deleteBranchInvite(String branchId, UUID playerUuid) throws SQLException {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM branch_invites WHERE branch_id = ? AND player_uuid = ?"
            )) {
                statement.setString(1, branchId);
                statement.setString(2, playerUuid.toString());
                return statement.executeUpdate();
            }
        });
    }

    public void initialize() throws SQLException {
        try {
            Files.createDirectories(databaseFile.getParent() == null ? Path.of(".") : databaseFile.getParent());
        } catch (IOException exception) {
            throw new SQLException("无法创建数据库目录", exception);
        }
        try (Connection connection = openConnection()) {
            ensurePragmas(connection);
            ensureMetadataTable(connection);
            int currentVersion = readSchemaVersion(connection);
            if (currentVersion > SCHEMA_VERSION) {
                throw new SQLException("数据库版本高于当前程序支持的版本: " + currentVersion);
            }
            for (Migration migration : migrations()) {
                if (migration.version() > currentVersion) {
                    applyMigration(connection, migration);
                    currentVersion = migration.version();
                }
            }
        }
    }

    public void upsertMergePhase(String branchId, String phase) {
        try {
            upsertMergeJournal(branchId, phase, Instant.now());
        } catch (SQLException exception) {
            throw new IllegalStateException("写入合并日志失败", exception);
        }
    }

    public Optional<String> getMergePhase(String branchId) {
        try {
            return findMergeJournal(branchId).map(MergeJournalEntry::phase);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取合并日志失败", exception);
        }
    }

    public List<String> listIncompleteMergeBranchIds() {
        try {
            return listMergeJournals().stream()
                    .map(MergeJournalEntry::branchId)
                    .collect(Collectors.toList());
        } catch (SQLException exception) {
            throw new IllegalStateException("读取待恢复合并列表失败", exception);
        }
    }

    public void deleteMergeJournalQuietly(String branchId) {
        try {
            deleteMergeJournal(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("删除合并日志失败", exception);
        }
    }

    @Override
    public void close() throws IOException {
        // 当前实现按需创建连接，不持有长连接，因此这里无需额外清理。
    }

    private void applyMigration(Connection connection, Migration migration) throws SQLException {
        try {
            connection.setAutoCommit(false);
            for (String statementSql : migration.statements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(statementSql);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)"
            )) {
                statement.setInt(1, migration.version());
                statement.setLong(2, Instant.now().toEpochMilli());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private int readSchemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COALESCE(MAX(version), 0) AS version FROM schema_migrations")) {
            return resultSet.next() ? resultSet.getInt("version") : 0;
        }
    }

    private void ensureMetadataTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        applied_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    private void ensurePragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private void configureConnection(Connection connection) throws SQLException {
        ensurePragmas(connection);
    }

    private List<Migration> migrations() {
        return List.of(
                new Migration(1, List.of(
                        """
                        CREATE TABLE IF NOT EXISTS branches (
                            id TEXT PRIMARY KEY,
                            owner_uuid TEXT NOT NULL,
                            owner_name TEXT NOT NULL,
                            world_name TEXT NOT NULL UNIQUE,
                            main_world TEXT NOT NULL,
                            min_x INTEGER,
                            min_y INTEGER,
                            min_z INTEGER,
                            max_x INTEGER,
                            max_y INTEGER,
                            max_z INTEGER,
                            status TEXT NOT NULL DEFAULT 'ACTIVE',
                            created_at INTEGER NOT NULL,
                            submitted_at INTEGER,
                            reviewed_by TEXT,
                            reviewed_at INTEGER,
                            review_note TEXT,
                            merged_at INTEGER,
                            closed_at INTEGER
                        )
                        """,
                        """
                        CREATE TABLE IF NOT EXISTS region_locks (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            branch_id TEXT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
                            main_world TEXT NOT NULL,
                            min_x INTEGER NOT NULL,
                            min_y INTEGER NOT NULL,
                            min_z INTEGER NOT NULL,
                            max_x INTEGER NOT NULL,
                            max_y INTEGER NOT NULL,
                            max_z INTEGER NOT NULL,
                            locked_at INTEGER NOT NULL,
                            UNIQUE(main_world, min_x, min_y, min_z, max_x, max_y, max_z)
                        )
                        """,
                        """
                        CREATE TABLE IF NOT EXISTS queue_entries (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            player_uuid TEXT NOT NULL,
                            player_name TEXT NOT NULL,
                            main_world TEXT NOT NULL,
                            min_x INTEGER NOT NULL,
                            min_y INTEGER NOT NULL,
                            min_z INTEGER NOT NULL,
                            max_x INTEGER NOT NULL,
                            max_y INTEGER NOT NULL,
                            max_z INTEGER NOT NULL,
                            queued_at INTEGER NOT NULL
                        )
                        """,
                        """
                        CREATE TABLE IF NOT EXISTS branch_invites (
                            branch_id TEXT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
                            player_uuid TEXT NOT NULL,
                            invited_by TEXT,
                            invited_at INTEGER NOT NULL,
                            PRIMARY KEY (branch_id, player_uuid)
                        )
                        """,
                        """
                        CREATE TABLE IF NOT EXISTS merge_journal (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            branch_id TEXT NOT NULL UNIQUE REFERENCES branches(id) ON DELETE CASCADE,
                            phase TEXT NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """,
                        "CREATE INDEX IF NOT EXISTS idx_branches_owner_uuid ON branches(owner_uuid)",
                        "CREATE INDEX IF NOT EXISTS idx_branches_status ON branches(status)",
                        "CREATE INDEX IF NOT EXISTS idx_branches_main_world ON branches(main_world)",
                        "CREATE INDEX IF NOT EXISTS idx_region_locks_branch_id ON region_locks(branch_id)",
                        "CREATE INDEX IF NOT EXISTS idx_region_locks_main_world ON region_locks(main_world)",
                        "CREATE UNIQUE INDEX IF NOT EXISTS idx_queue_entries_player_uuid ON queue_entries(player_uuid)",
                        "CREATE INDEX IF NOT EXISTS idx_queue_entries_main_world ON queue_entries(main_world)",
                        "CREATE INDEX IF NOT EXISTS idx_merge_journal_phase ON merge_journal(phase)"
                )),
                new Migration(2, List.of(
                        "ALTER TABLE branches ADD COLUMN merged_by TEXT",
                        "ALTER TABLE branches ADD COLUMN merge_message TEXT"
                )),
                new Migration(3, List.of(
                        """
                        CREATE TABLE IF NOT EXISTS branch_sync_meta (
                            branch_id TEXT PRIMARY KEY REFERENCES branches(id) ON DELETE CASCADE,
                            base_revision INTEGER NOT NULL DEFAULT 0,
                            last_rebased_revision INTEGER,
                            last_reviewed_revision INTEGER,
                            sync_state TEXT NOT NULL DEFAULT 'CLEAN',
                            snapshot_path TEXT NOT NULL DEFAULT '',
                            pending_rebase_revision INTEGER,
                            pending_snapshot_path TEXT,
                            working_snapshot_path TEXT,
                            unresolved_group_count INTEGER NOT NULL DEFAULT 0,
                            stale_reason TEXT
                        )
                        """,
                        """
                        CREATE TABLE IF NOT EXISTS world_heads (
                            main_world TEXT PRIMARY KEY,
                            head_revision INTEGER NOT NULL DEFAULT 0
                        )
                        """,
                        """
                        CREATE TABLE IF NOT EXISTS world_commits (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            main_world TEXT NOT NULL,
                            revision INTEGER NOT NULL,
                            branch_id TEXT UNIQUE,
                            commit_kind TEXT NOT NULL,
                            min_x INTEGER NOT NULL,
                            min_y INTEGER NOT NULL,
                            min_z INTEGER NOT NULL,
                            max_x INTEGER NOT NULL,
                            max_y INTEGER NOT NULL,
                            max_z INTEGER NOT NULL,
                            author_uuid TEXT,
                            author_name TEXT,
                            message TEXT,
                            created_at INTEGER NOT NULL,
                            UNIQUE(main_world, revision)
                        )
                        """,
                        """
                        CREATE TABLE IF NOT EXISTS rebase_journal (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            branch_id TEXT NOT NULL UNIQUE REFERENCES branches(id) ON DELETE CASCADE,
                            phase TEXT NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """,
                        """
                        CREATE TABLE IF NOT EXISTS conflict_groups (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            branch_id TEXT NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
                            group_index INTEGER NOT NULL,
                            min_x INTEGER NOT NULL,
                            min_y INTEGER NOT NULL,
                            min_z INTEGER NOT NULL,
                            max_x INTEGER NOT NULL,
                            max_y INTEGER NOT NULL,
                            max_z INTEGER NOT NULL,
                            block_count INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            resolution TEXT,
                            detail_path TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            resolved_at INTEGER,
                            UNIQUE(branch_id, group_index)
                        )
                        """,
                        "CREATE INDEX IF NOT EXISTS idx_branch_sync_meta_state ON branch_sync_meta(sync_state)",
                        "CREATE INDEX IF NOT EXISTS idx_world_commits_world_revision ON world_commits(main_world, revision)",
                        "CREATE INDEX IF NOT EXISTS idx_world_commits_branch_id ON world_commits(branch_id)",
                        "CREATE INDEX IF NOT EXISTS idx_conflict_groups_branch_id ON conflict_groups(branch_id)",
                        "CREATE INDEX IF NOT EXISTS idx_conflict_groups_status ON conflict_groups(status)",
                        "CREATE INDEX IF NOT EXISTS idx_rebase_journal_phase ON rebase_journal(phase)"
                ))
        );
    }

    private UUID parseUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    @FunctionalInterface
    public interface SqlFunction<T, R> {
        R apply(T value) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer<T> {
        void accept(T value) throws SQLException;
    }

    private record Migration(int version, List<String> statements) {
    }
}
