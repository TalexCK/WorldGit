package com.worldgit.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AiJobManager {

    private static final Duration RETENTION = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, AiJob> jobs = new ConcurrentHashMap<>();

    public AiJob create(UUID playerUuid, String sessionToken, String sourceBranchId, String previewBranchId) {
        purgeExpired();
        String id = UUID.randomUUID().toString();
        AiJob job = new AiJob(id, playerUuid, sessionToken, sourceBranchId, previewBranchId, Instant.now());
        jobs.put(id, job);
        return job;
    }

    public AiJob findForPlayer(String jobId, UUID playerUuid) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        AiJob job = jobs.get(jobId);
        if (job == null || !job.playerUuid().equals(playerUuid)) {
            return null;
        }
        return job;
    }

    public void clear() {
        jobs.clear();
    }

    private void purgeExpired() {
        Instant cutoff = Instant.now().minus(RETENTION);
        Iterator<Map.Entry<String, AiJob>> iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AiJob> entry = iterator.next();
            AiJob job = entry.getValue();
            if (job.status() == AiJob.Status.RUNNING) {
                continue;
            }
            Instant finishedAt = job.finishedAt();
            if (finishedAt != null && finishedAt.isBefore(cutoff)) {
                iterator.remove();
            }
        }
    }
}
