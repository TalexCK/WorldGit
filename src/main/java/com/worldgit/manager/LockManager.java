package com.worldgit.manager;

import com.worldgit.database.LockRepository;
import com.worldgit.model.RegionLock;
import java.time.Instant;
import java.util.List;

public final class LockManager {

    private final LockRepository lockRepository;

    public LockManager(LockRepository lockRepository) {
        this.lockRepository = lockRepository;
    }

    public List<RegionLock> findConflicts(String mainWorld, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return lockRepository.findConflictsUnchecked(mainWorld, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public RegionLock createLock(String branchId, String mainWorld, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        RegionLock lock = new RegionLock(
                0L,
                branchId,
                mainWorld,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                Instant.now()
        );
        lockRepository.insert(lock);
        return lock;
    }

    public void unlockBranch(String branchId) {
        lockRepository.deleteByBranchId(branchId);
    }

    public List<RegionLock> findAll() {
        return lockRepository.findAllUnchecked();
    }
}
