package com.worldgit.ai;

import com.google.gson.JsonElement;
import java.time.Instant;

public record AiLogEntry(
        String level,
        String type,
        String message,
        Instant createdAt,
        JsonElement data
) {
}
