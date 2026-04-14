package com.worldgit.database;

import com.worldgit.model.Branch;
import com.worldgit.model.BranchInvite;
import com.worldgit.model.BranchInviteRequest;
import com.worldgit.model.BranchStatus;
import com.worldgit.model.PlayerMergeLeaderboardEntry;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 分支仓储。
 */
public final class BranchRepository {

    private final DatabaseManager databaseManager;

    public BranchRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "数据库管理器不能为空");
    }

    public void create(Branch branch) throws SQLException {
        databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO branches (
                        id, owner_uuid, owner_name, branch_label, world_name, main_world,
                        min_x, min_y, min_z, max_x, max_y, max_z,
                        status, created_at, submitted_at, reviewed_by, reviewed_at,
                        review_note, merged_by, merge_message, merged_at, closed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """
            )) {
                bindBranch(statement, branch);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void save(Branch branch) throws SQLException {
        databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO branches (
                        id, owner_uuid, owner_name, branch_label, world_name, main_world,
                        min_x, min_y, min_z, max_x, max_y, max_z,
                        status, created_at, submitted_at, reviewed_by, reviewed_at,
                        review_note, merged_by, merge_message, merged_at, closed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        owner_uuid = excluded.owner_uuid,
                        owner_name = excluded.owner_name,
                        branch_label = excluded.branch_label,
                        world_name = excluded.world_name,
                        main_world = excluded.main_world,
                        min_x = excluded.min_x,
                        min_y = excluded.min_y,
                        min_z = excluded.min_z,
                        max_x = excluded.max_x,
                        max_y = excluded.max_y,
                        max_z = excluded.max_z,
                        status = excluded.status,
                        created_at = excluded.created_at,
                        submitted_at = excluded.submitted_at,
                        reviewed_by = excluded.reviewed_by,
                        reviewed_at = excluded.reviewed_at,
                        review_note = excluded.review_note,
                        merged_by = excluded.merged_by,
                        merge_message = excluded.merge_message,
                        merged_at = excluded.merged_at,
                        closed_at = excluded.closed_at
                    """
            )) {
                bindBranch(statement, branch);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Branch> findById(String branchId) throws SQLException {
        return findSingle("SELECT * FROM branches WHERE id = ?", branchId);
    }

    public Optional<Branch> findByWorldName(String worldName) throws SQLException {
        return findSingle("SELECT * FROM branches WHERE world_name = ?", worldName);
    }

    public List<Branch> findByOwner(UUID ownerUuid) throws SQLException {
        return findList(
                """
                SELECT * FROM branches
                WHERE owner_uuid = ?
                ORDER BY created_at DESC
                """,
                ownerUuid.toString()
        );
    }

    public List<Branch> findByStatus(BranchStatus status) throws SQLException {
        return findList(
                """
                SELECT * FROM branches
                WHERE status = ?
                ORDER BY created_at DESC
                """,
                status.dbValue()
        );
    }

    public List<Branch> listAll() throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<Branch> branches = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM branches ORDER BY created_at DESC"
            ); ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    branches.add(mapBranch(resultSet));
                }
            }
            return branches;
        });
    }

    public List<Branch> listRecentCreated(int limit) throws SQLException {
        return listRecentByTimestamp("created_at", false, Math.max(1, limit));
    }

    public List<Branch> listRecentSubmitted(int limit) throws SQLException {
        return listRecentByTimestamp("submitted_at", true, Math.max(1, limit));
    }

    public List<Branch> listRecentMerged(int limit) throws SQLException {
        return listRecentByTimestamp("merged_at", true, Math.max(1, limit));
    }

    public void saveMergeBlockStats(String branchId, long changedBlockCount) throws SQLException {
        databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO branch_merge_stats (branch_id, changed_block_count)
                    VALUES (?, ?)
                    ON CONFLICT(branch_id) DO UPDATE SET
                        changed_block_count = excluded.changed_block_count
                    """
            )) {
                statement.setString(1, branchId);
                statement.setLong(2, Math.max(0L, changedBlockCount));
                statement.executeUpdate();
            }
            return null;
        });
    }

    public List<Branch> listMergedWithoutBlockStats() throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<Branch> branches = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT b.*
                    FROM branches b
                    LEFT JOIN branch_merge_stats stats ON stats.branch_id = b.id
                    WHERE b.status = 'MERGED'
                      AND b.merged_at IS NOT NULL
                      AND stats.branch_id IS NULL
                    ORDER BY b.merged_at ASC
                    """
            ); ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    branches.add(mapBranch(resultSet));
                }
            }
            return branches;
        });
    }

    public List<PlayerMergeLeaderboardEntry> listMergeLeaderboard(int limit) throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<PlayerMergeLeaderboardEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT
                        b.owner_uuid,
                        b.owner_name,
                        COALESCE(SUM(stats.changed_block_count), 0) AS total_changed_blocks,
                        COUNT(*) AS merged_branch_count,
                        MAX(b.merged_at) AS last_merged_at
                    FROM branch_merge_stats stats
                    JOIN branches b ON b.id = stats.branch_id
                    WHERE b.status = 'MERGED'
                      AND b.merged_at IS NOT NULL
                    GROUP BY b.owner_uuid, b.owner_name
                    ORDER BY total_changed_blocks DESC,
                             merged_branch_count DESC,
                             last_merged_at DESC,
                             b.owner_name COLLATE NOCASE ASC
                    LIMIT ?
                    """
            )) {
                statement.setInt(1, Math.max(1, limit));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        entries.add(new PlayerMergeLeaderboardEntry(
                                UUID.fromString(resultSet.getString("owner_uuid")),
                                resultSet.getString("owner_name"),
                                resultSet.getLong("total_changed_blocks"),
                                resultSet.getInt("merged_branch_count"),
                                getNullableInstant(resultSet, "last_merged_at")
                        ));
                    }
                }
            }
            return entries;
        });
    }

    public List<Branch> listActiveByOwner(UUID ownerUuid) throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<Branch> branches = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT * FROM branches
                    WHERE owner_uuid = ? AND status IN ('ACTIVE', 'SUBMITTED', 'APPROVED')
                    ORDER BY created_at DESC
                    """
            )) {
                statement.setString(1, ownerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        branches.add(mapBranch(resultSet));
                    }
                }
            }
            return branches;
        });
    }

    public int countActiveByOwner(UUID ownerUuid) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT COUNT(*) AS total
                    FROM branches
                    WHERE owner_uuid = ? AND status IN ('ACTIVE', 'SUBMITTED', 'APPROVED')
                    """
            )) {
                statement.setString(1, ownerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getInt("total") : 0;
                }
            }
        });
    }

    public boolean markSubmitted(String branchId, Instant submittedAt) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE branches
                    SET status = 'SUBMITTED', submitted_at = ?
                    WHERE id = ?
                    """
            )) {
                statement.setLong(1, submittedAt.toEpochMilli());
                statement.setString(2, branchId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public boolean markReviewed(String branchId, BranchStatus status, UUID reviewedBy, Instant reviewedAt, String reviewNote) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE branches
                    SET status = ?, reviewed_by = ?, reviewed_at = ?, review_note = ?
                    WHERE id = ?
                    """
            )) {
                statement.setString(1, status.dbValue());
                statement.setString(2, reviewedBy == null ? null : reviewedBy.toString());
                statement.setLong(3, reviewedAt.toEpochMilli());
                statement.setString(4, reviewNote);
                statement.setString(5, branchId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public boolean reopenApprovedBranch(String branchId) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE branches
                    SET status = 'ACTIVE',
                        submitted_at = NULL,
                        reviewed_by = NULL,
                        reviewed_at = NULL,
                        review_note = NULL,
                        merged_by = NULL,
                        merge_message = NULL
                    WHERE id = ? AND status = 'APPROVED'
                    """
            )) {
                statement.setString(1, branchId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public boolean resetToActive(String branchId, String note) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE branches
                    SET status = 'ACTIVE',
                        submitted_at = NULL,
                        reviewed_by = NULL,
                        reviewed_at = NULL,
                        review_note = ?,
                        merged_by = NULL,
                        merge_message = NULL
                    WHERE id = ? AND status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'ACTIVE')
                    """
            )) {
                statement.setString(1, note);
                statement.setString(2, branchId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private boolean updateMergeMetadata(String branchId, UUID mergedBy, String mergeMessage) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE branches
                    SET merged_by = ?, merge_message = ?
                    WHERE id = ?
                    """
            )) {
                statement.setString(1, mergedBy == null ? null : mergedBy.toString());
                statement.setString(2, mergeMessage);
                statement.setString(3, branchId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private boolean updateLabel(String branchId, String label) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE branches
                    SET branch_label = ?
                    WHERE id = ?
                    """
            )) {
                statement.setString(1, label);
                statement.setString(2, branchId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public boolean markMerged(String branchId, Instant mergedAt) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE branches
                    SET status = 'MERGED', merged_at = ?
                    WHERE id = ?
                    """
            )) {
                statement.setLong(1, mergedAt.toEpochMilli());
                statement.setString(2, branchId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public boolean markClosed(String branchId, BranchStatus status, Instant closedAt) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE branches
                    SET status = ?, closed_at = ?
                    WHERE id = ?
                    """
            )) {
                statement.setString(1, status.dbValue());
                statement.setLong(2, closedAt.toEpochMilli());
                statement.setString(3, branchId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public int deleteById(String branchId) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM branches WHERE id = ?"
            )) {
                statement.setString(1, branchId);
                return statement.executeUpdate();
            }
        });
    }

    public void insert(Branch branch) {
        try {
            create(branch);
        } catch (SQLException exception) {
            throw new IllegalStateException("创建分支记录失败", exception);
        }
    }

    public List<Branch> findAll() {
        try {
            return listAll();
        } catch (SQLException exception) {
            throw new IllegalStateException("查询全部分支失败", exception);
        }
    }

    public List<Branch> findSubmitted() {
        try {
            return findByStatus(BranchStatus.SUBMITTED);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询待审核分支失败", exception);
        }
    }

    public long countActiveBranches(UUID ownerUuid) {
        try {
            return countActiveByOwner(ownerUuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("统计活跃分支失败", exception);
        }
    }

    public Optional<Branch> findByIdUnchecked(String branchId) {
        try {
            return findById(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询分支失败", exception);
        }
    }

    public Optional<Branch> findByWorldNameUnchecked(String worldName) {
        try {
            return findByWorldName(worldName);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询分支世界失败", exception);
        }
    }

    public List<Branch> findByOwnerUnchecked(UUID ownerUuid) {
        try {
            return findByOwner(ownerUuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询玩家分支失败", exception);
        }
    }

    public List<Branch> listRecentCreatedUnchecked(int limit) {
        try {
            return listRecentCreated(limit);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询最近创建记录失败", exception);
        }
    }

    public List<Branch> listRecentSubmittedUnchecked(int limit) {
        try {
            return listRecentSubmitted(limit);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询最近提交记录失败", exception);
        }
    }

    public List<Branch> listRecentMergedUnchecked(int limit) {
        try {
            return listRecentMerged(limit);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询最近合并记录失败", exception);
        }
    }

    public void saveMergeBlockStatsUnchecked(String branchId, long changedBlockCount) {
        try {
            saveMergeBlockStats(branchId, changedBlockCount);
        } catch (SQLException exception) {
            throw new IllegalStateException("保存合并改块统计失败", exception);
        }
    }

    public List<Branch> listMergedWithoutBlockStatsUnchecked() {
        try {
            return listMergedWithoutBlockStats();
        } catch (SQLException exception) {
            throw new IllegalStateException("查询待回填合并统计失败", exception);
        }
    }

    public List<PlayerMergeLeaderboardEntry> listMergeLeaderboardUnchecked(int limit) {
        try {
            return listMergeLeaderboard(limit);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询合并排行榜失败", exception);
        }
    }

    public Optional<Branch> findLatestOwnedActiveBranch(UUID ownerUuid) {
        try {
            return listActiveByOwner(ownerUuid).stream().findFirst();
        } catch (SQLException exception) {
            throw new IllegalStateException("查询最近活跃分支失败", exception);
        }
    }

    public void markSubmitted(String branchId, long submittedAtEpochSecond) {
        try {
            if (!markSubmitted(branchId, Instant.ofEpochSecond(submittedAtEpochSecond))) {
                throw new IllegalStateException("分支不存在: " + branchId);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("更新提交状态失败", exception);
        }
    }

    public void markReviewed(String branchId, BranchStatus status, UUID reviewedBy, long reviewedAtEpochSecond, String reviewNote) {
        try {
            if (!markReviewed(branchId, status, reviewedBy, Instant.ofEpochSecond(reviewedAtEpochSecond), reviewNote)) {
                throw new IllegalStateException("分支不存在: " + branchId);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("更新审核状态失败", exception);
        }
    }

    public boolean reopenApprovedBranchUnchecked(String branchId) {
        try {
            return reopenApprovedBranch(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("重置审核通过状态失败", exception);
        }
    }

    public boolean resetToActiveUnchecked(String branchId, String note) {
        try {
            return resetToActive(branchId, note);
        } catch (SQLException exception) {
            throw new IllegalStateException("重置分支为编辑状态失败", exception);
        }
    }

    public void saveMergeMetadata(String branchId, UUID mergedBy, String mergeMessage) {
        try {
            if (!updateMergeMetadata(branchId, mergedBy, mergeMessage)) {
                throw new IllegalStateException("分支不存在: " + branchId);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("保存合并信息失败", exception);
        }
    }

    public void saveLabel(String branchId, String label) {
        try {
            if (!updateLabel(branchId, label)) {
                throw new IllegalStateException("分支不存在: " + branchId);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("保存分支标签失败", exception);
        }
    }

    public void markMerged(String branchId, long mergedAtEpochSecond) {
        try {
            if (!markMerged(branchId, Instant.ofEpochSecond(mergedAtEpochSecond))) {
                throw new IllegalStateException("分支不存在: " + branchId);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("更新合并状态失败", exception);
        }
    }

    public void markClosed(String branchId, BranchStatus status, long closedAtEpochSecond, String note) {
        try {
            databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        UPDATE branches
                        SET status = ?, closed_at = ?, review_note = ?
                        WHERE id = ?
                        """
                )) {
                    statement.setString(1, status.dbValue());
                    statement.setLong(2, Instant.ofEpochSecond(closedAtEpochSecond).toEpochMilli());
                    statement.setString(3, note);
                    statement.setString(4, branchId);
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalStateException("分支不存在: " + branchId);
                    }
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("更新关闭状态失败", exception);
        }
    }

    public void addInvite(String branchId, UUID playerUuid, UUID invitedBy, long invitedAtEpochSecond) {
        try {
            databaseManager.upsertBranchInvite(new BranchInvite(
                    branchId,
                    playerUuid,
                    invitedBy,
                    Instant.ofEpochSecond(invitedAtEpochSecond)
            ));
        } catch (SQLException exception) {
            throw new IllegalStateException("保存邀请失败", exception);
        }
    }

    public void addInviteRequest(String branchId, UUID playerUuid, UUID invitedBy, long invitedAtEpochSecond) {
        try {
            databaseManager.upsertBranchInviteRequest(new BranchInviteRequest(
                    branchId,
                    playerUuid,
                    invitedBy,
                    Instant.ofEpochSecond(invitedAtEpochSecond)
            ));
        } catch (SQLException exception) {
            throw new IllegalStateException("保存待接受邀请失败", exception);
        }
    }

    public void removeInvite(String branchId, UUID playerUuid) {
        try {
            databaseManager.deleteBranchInvite(branchId, playerUuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("删除邀请失败", exception);
        }
    }

    public void removeInviteRequest(String branchId, UUID playerUuid) {
        try {
            databaseManager.deleteBranchInviteRequest(branchId, playerUuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("删除待接受邀请失败", exception);
        }
    }

    public void deleteByIdQuietly(String branchId) {
        try {
            deleteById(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("删除分支失败", exception);
        }
    }

    public boolean isInvited(String branchId, UUID playerUuid) {
        try {
            return databaseManager.listBranchInvites(branchId).stream()
                    .anyMatch(invite -> invite.playerUuid().equals(playerUuid));
        } catch (SQLException exception) {
            throw new IllegalStateException("查询邀请失败", exception);
        }
    }

    public boolean hasInviteRequest(String branchId, UUID playerUuid) {
        try {
            return databaseManager.findBranchInviteRequest(branchId, playerUuid).isPresent();
        } catch (SQLException exception) {
            throw new IllegalStateException("查询待接受邀请失败", exception);
        }
    }

    public List<BranchInvite> listInvites(String branchId) {
        try {
            return databaseManager.listBranchInvites(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询邀请列表失败", exception);
        }
    }

    public List<BranchInviteRequest> listInviteRequests(String branchId) {
        try {
            return databaseManager.listBranchInviteRequests(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询待接受邀请列表失败", exception);
        }
    }

    public List<BranchInviteRequest> listInviteRequestsForPlayer(UUID playerUuid) {
        try {
            return databaseManager.listBranchInviteRequestsForPlayer(playerUuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询玩家待接受邀请失败", exception);
        }
    }

    public Optional<BranchInviteRequest> findInviteRequest(String branchId, UUID playerUuid) {
        try {
            return databaseManager.findBranchInviteRequest(branchId, playerUuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询待接受邀请失败", exception);
        }
    }

    public Optional<BranchInviteRequest> acceptInviteRequest(String branchId, UUID playerUuid) {
        try {
            return databaseManager.acceptBranchInviteRequest(branchId, playerUuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("接受邀请失败", exception);
        }
    }

    private Optional<Branch> findSingle(String sql, String value) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapBranch(resultSet)) : Optional.empty();
                }
            }
        });
    }

    private List<Branch> findList(String sql, String value) throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<Branch> branches = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        branches.add(mapBranch(resultSet));
                    }
                }
            }
            return branches;
        });
    }

    private List<Branch> listRecentByTimestamp(String columnName, boolean requireNotNull, int limit) throws SQLException {
        return databaseManager.withConnection(connection -> {
            String sql = requireNotNull
                    ? "SELECT * FROM branches WHERE " + columnName + " IS NOT NULL ORDER BY " + columnName + " DESC LIMIT ?"
                    : "SELECT * FROM branches ORDER BY " + columnName + " DESC LIMIT ?";
            List<Branch> branches = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        branches.add(mapBranch(resultSet));
                    }
                }
            }
            return branches;
        });
    }

    private void bindBranch(PreparedStatement statement, Branch branch) throws SQLException {
        statement.setString(1, branch.id());
        statement.setString(2, branch.ownerUuid().toString());
        statement.setString(3, branch.ownerName());
        statement.setString(4, branch.label());
        statement.setString(5, branch.worldName());
        statement.setString(6, branch.mainWorld());
        setNullableInt(statement, 7, branch.minX());
        setNullableInt(statement, 8, branch.minY());
        setNullableInt(statement, 9, branch.minZ());
        setNullableInt(statement, 10, branch.maxX());
        setNullableInt(statement, 11, branch.maxY());
        setNullableInt(statement, 12, branch.maxZ());
        statement.setString(13, branch.status().dbValue());
        statement.setLong(14, branch.createdAt().toEpochMilli());
        setNullableInstant(statement, 15, branch.submittedAt());
        setNullableUuid(statement, 16, branch.reviewedBy());
        setNullableInstant(statement, 17, branch.reviewedAt());
        statement.setString(18, branch.reviewNote());
        setNullableUuid(statement, 19, branch.mergedBy());
        statement.setString(20, branch.mergeMessage());
        setNullableInstant(statement, 21, branch.mergedAt());
        setNullableInstant(statement, 22, branch.closedAt());
    }

    private Branch mapBranch(ResultSet resultSet) throws SQLException {
        return new Branch(
                resultSet.getString("id"),
                UUID.fromString(resultSet.getString("owner_uuid")),
                resultSet.getString("owner_name"),
                resultSet.getString("branch_label"),
                resultSet.getString("world_name"),
                resultSet.getString("main_world"),
                getNullableInteger(resultSet, "min_x"),
                getNullableInteger(resultSet, "min_y"),
                getNullableInteger(resultSet, "min_z"),
                getNullableInteger(resultSet, "max_x"),
                getNullableInteger(resultSet, "max_y"),
                getNullableInteger(resultSet, "max_z"),
                BranchStatus.fromDbValue(resultSet.getString("status")),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                getNullableInstant(resultSet, "submitted_at"),
                getNullableUuid(resultSet, "reviewed_by"),
                getNullableInstant(resultSet, "reviewed_at"),
                resultSet.getString("review_note"),
                getNullableUuid(resultSet, "merged_by"),
                resultSet.getString("merge_message"),
                getNullableInstant(resultSet, "merged_at"),
                getNullableInstant(resultSet, "closed_at")
        );
    }

    private void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private void setNullableInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setLong(index, value.toEpochMilli());
        }
    }

    private void setNullableUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value.toString());
        }
    }

    private Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Instant getNullableInstant(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private UUID getNullableUuid(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? null : UUID.fromString(value);
    }
}
