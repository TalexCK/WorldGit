package com.worldgit.ai;

import java.time.Instant;
import java.util.UUID;

public record AiSession(
        String token,
        UUID playerUuid,
        String playerName,
        Instant createdAt,
        Instant expiresAt
) {
}
