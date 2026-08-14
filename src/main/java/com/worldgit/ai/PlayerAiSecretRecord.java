package com.worldgit.ai;

import java.time.Instant;
import java.util.UUID;

public record PlayerAiSecretRecord(
        UUID playerUuid,
        String playerName,
        String secretHash,
        String secretSalt,
        int hashIterations,
        boolean aiAllowed,
        Instant createdAt,
        Instant rotatedAt,
        Instant lastUsedAt
) {
}
