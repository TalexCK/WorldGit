package com.worldgit.database;

import com.worldgit.model.RealshotMedia;
import com.worldgit.model.RealshotRequest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RealshotRepository {

    private final DatabaseManager databaseManager;

    public RealshotRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "数据库管理器不能为空");
    }

    public void saveRequest(RealshotRequest request) {
        try {
            databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO realshot_requests (
                            id, branch_id, requester_uuid, requester_name, question, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """
                )) {
                    statement.setString(1, request.id());
                    statement.setString(2, request.branchId());
                    statement.setString(3, request.requesterUuid().toString());
                    statement.setString(4, request.requesterName());
                    statement.setString(5, request.question());
                    statement.setLong(6, request.createdAt().toEpochMilli());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("保存实景图片请求失败", exception);
        }
    }

    public Optional<RealshotRequest> findRequest(String requestId) {
        try {
            return databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT id, branch_id, requester_uuid, requester_name, question, created_at
                        FROM realshot_requests
                        WHERE id = ?
                        """
                )) {
                    statement.setString(1, requestId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(mapRequest(resultSet));
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("读取实景图片请求失败", exception);
        }
    }

    public List<RealshotRequest> listRequestsByBranchIds(List<String> branchIds) {
        if (branchIds == null || branchIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", branchIds.stream().map(ignored -> "?").toList());
        try {
            return databaseManager.withConnection(connection -> {
                List<RealshotRequest> requests = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT id, branch_id, requester_uuid, requester_name, question, created_at
                        FROM realshot_requests
                        WHERE branch_id IN (
                        """ + placeholders + """
                        )
                        ORDER BY created_at DESC
                        """
                )) {
                    for (int index = 0; index < branchIds.size(); index++) {
                        statement.setString(index + 1, branchIds.get(index));
                    }
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            requests.add(mapRequest(resultSet));
                        }
                    }
                }
                return requests;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("读取实景图片请求列表失败", exception);
        }
    }

    public void saveMedia(RealshotMedia media) {
        try {
            databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO realshot_media (
                            id, request_id, branch_id, uploader_uuid, uploader_name,
                            file_name, mime_type, file_path, file_size, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """
                )) {
                    statement.setString(1, media.id());
                    statement.setString(2, media.requestId());
                    statement.setString(3, media.branchId());
                    statement.setString(4, media.uploaderUuid().toString());
                    statement.setString(5, media.uploaderName());
                    statement.setString(6, media.fileName());
                    statement.setString(7, media.mimeType());
                    statement.setString(8, media.filePath());
                    statement.setLong(9, media.fileSize());
                    statement.setLong(10, media.createdAt().toEpochMilli());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("保存实景图片素材失败", exception);
        }
    }

    public Optional<RealshotMedia> findMedia(String mediaId) {
        try {
            return databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT id, request_id, branch_id, uploader_uuid, uploader_name,
                               file_name, mime_type, file_path, file_size, created_at
                        FROM realshot_media
                        WHERE id = ?
                        """
                )) {
                    statement.setString(1, mediaId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(mapMedia(resultSet));
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("读取实景图片素材失败", exception);
        }
    }

    public List<RealshotMedia> listMediaByRequestIds(List<String> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", requestIds.stream().map(ignored -> "?").toList());
        try {
            return databaseManager.withConnection(connection -> {
                List<RealshotMedia> media = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT id, request_id, branch_id, uploader_uuid, uploader_name,
                               file_name, mime_type, file_path, file_size, created_at
                        FROM realshot_media
                        WHERE request_id IN (
                        """ + placeholders + """
                        )
                        ORDER BY created_at ASC
                        """
                )) {
                    for (int index = 0; index < requestIds.size(); index++) {
                        statement.setString(index + 1, requestIds.get(index));
                    }
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            media.add(mapMedia(resultSet));
                        }
                    }
                }
                return media;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("读取实景图片素材列表失败", exception);
        }
    }

    private RealshotRequest mapRequest(ResultSet resultSet) throws SQLException {
        return new RealshotRequest(
                resultSet.getString("id"),
                resultSet.getString("branch_id"),
                UUID.fromString(resultSet.getString("requester_uuid")),
                resultSet.getString("requester_name"),
                resultSet.getString("question"),
                Instant.ofEpochMilli(resultSet.getLong("created_at"))
        );
    }

    private RealshotMedia mapMedia(ResultSet resultSet) throws SQLException {
        return new RealshotMedia(
                resultSet.getString("id"),
                resultSet.getString("request_id"),
                resultSet.getString("branch_id"),
                UUID.fromString(resultSet.getString("uploader_uuid")),
                resultSet.getString("uploader_name"),
                resultSet.getString("file_name"),
                resultSet.getString("mime_type"),
                resultSet.getString("file_path"),
                resultSet.getLong("file_size"),
                Instant.ofEpochMilli(resultSet.getLong("created_at"))
        );
    }
}
