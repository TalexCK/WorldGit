package com.worldgit.ai;

import java.util.List;

public record AiConversationResult(
        String provider,
        String model,
        String sessionId,
        String branchId,
        String branchWorld,
        String finalText,
        int toolRounds,
        int totalBlockChanges,
        List<AiLogEntry> logs
) {

    public AiConversationResult {
        logs = logs == null ? List.of() : List.copyOf(logs);
    }
}
