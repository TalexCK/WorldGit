package com.worldgit.database;

import com.worldgit.model.RegionLock;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 区域锁仓储。
 */
public final class LockRepository {

    private final DatabaseManager databaseManager;

    public LockRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "数据库管理器不能为空");
    }

    public boolean acquire(RegionLock lock) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT OR IGNORE INTO region_locks (
                        branch_id, main_world, min_x, min_y, min_z, max_x, max_y, max_z, locked_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """
            )) {
                bindLock(statement, lock);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public Optional<RegionLock> findByBranchId(String branchId) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, branch_id, main_world, min_x, min_y, min_z, max_x, max_y, max_z, locked_at
                    FROM region_locks
                    WHERE branch_id = ?
                    """
            )) {
                statement.setString(1, branchId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapLock(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public List<RegionLock> listAll() throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<RegionLock> locks = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, branch_id, main_world, min_x, min_y, min_z, max_x, max_y, max_z, locked_at
                    FROM region_locks
                    ORDER BY locked_at ASC, id ASC
                    """
            ); ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    locks.add(mapLock(resultSet));
                }
            }
            return locks;
        });
    }

    public List<RegionLock> findConflicts(String mainWorld, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<RegionLock> locks = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, branch_id, main_world, min_x, min_y, min_z, max_x, max_y, max_z, locked_at
                    FROM region_locks
                    WHERE main_world = ?
                      AND NOT (max_x < ? OR min_x > ? OR max_y < ? OR min_y > ? OR max_z < ? OR min_z > ?)
                    ORDER BY locked_at ASC, id ASC
                    """
            )) {
                statement.setString(1, mainWorld);
                statement.setInt(2, minX);
                statement.setInt(3, maxX);
                statement.setInt(4, minY);
                statement.setInt(5, maxY);
                statement.setInt(6, minZ);
                statement.setInt(7, maxZ);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        locks.add(mapLock(resultSet));
                    }
                }
            }
            return locks;
        });
    }

    public int releaseByBranchId(String branchId) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM region_locks WHERE branch_id = ?"
            )) {
                statement.setString(1, branchId);
                return statement.executeUpdate();
            }
        });
    }

    public int releaseByRegion(String mainWorld, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    DELETE FROM region_locks
                    WHERE main_world = ?
                      AND min_x = ?
                      AND min_y = ?
                      AND min_z = ?
                      AND max_x = ?
                      AND max_y = ?
                      AND max_z = ?
                    """
            )) {
                statement.setString(1, mainWorld);
                statement.setInt(2, minX);
                statement.setInt(3, minY);
                statement.setInt(4, minZ);
                statement.setInt(5, maxX);
                statement.setInt(6, maxY);
                statement.setInt(7, maxZ);
                return statement.executeUpdate();
            }
        });
    }

    public boolean isLocked(String mainWorld, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws SQLException {
        return !findConflicts(mainWorld, minX, minY, minZ, maxX, maxY, maxZ).isEmpty();
    }

    public long countByMainWorld(String mainWorld) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) AS total FROM region_locks WHERE main_world = ?"
            )) {
                statement.setString(1, mainWorld);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong("total") : 0L;
                }
            }
        });
    }

    public void insert(RegionLock lock) {
        try {
            if (!acquire(lock)) {
                throw new IllegalStateException("区域已被其他分支锁定");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("创建区域锁失败", exception);
        }
    }

    public void deleteByBranchId(String branchId) {
        try {
            releaseByBranchId(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("释放区域锁失败", exception);
        }
    }

    public List<RegionLock> findAllUnchecked() {
        try {
            return listAll();
        } catch (SQLException exception) {
            throw new IllegalStateException("查询区域锁失败", exception);
        }
    }

    public List<RegionLock> findConflictsUnchecked(String mainWorld, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        try {
            return findConflicts(mainWorld, minX, minY, minZ, maxX, maxY, maxZ);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询区域冲突失败", exception);
        }
    }

    private void bindLock(PreparedStatement statement, RegionLock lock) throws SQLException {
        statement.setString(1, lock.branchId());
        statement.setString(2, lock.mainWorld());
        statement.setInt(3, requireCoordinate(lock.minX(), "minX"));
        statement.setInt(4, requireCoordinate(lock.minY(), "minY"));
        statement.setInt(5, requireCoordinate(lock.minZ(), "minZ"));
        statement.setInt(6, requireCoordinate(lock.maxX(), "maxX"));
        statement.setInt(7, requireCoordinate(lock.maxY(), "maxY"));
        statement.setInt(8, requireCoordinate(lock.maxZ(), "maxZ"));
        statement.setLong(9, lock.lockedAt().toEpochMilli());
    }

    private RegionLock mapLock(ResultSet resultSet) throws SQLException {
        return new RegionLock(
                resultSet.getLong("id"),
                resultSet.getString("branch_id"),
                resultSet.getString("main_world"),
                getNullableInteger(resultSet, "min_x"),
                getNullableInteger(resultSet, "min_y"),
                getNullableInteger(resultSet, "min_z"),
                getNullableInteger(resultSet, "max_x"),
                getNullableInteger(resultSet, "max_y"),
                getNullableInteger(resultSet, "max_z"),
                Instant.ofEpochMilli(resultSet.getLong("locked_at"))
        );
    }

    private int requireCoordinate(Integer value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("锁定区域坐标不能为空: " + name);
        }
        return value;
    }

    private Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}
