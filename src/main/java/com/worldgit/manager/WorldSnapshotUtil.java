package com.worldgit.manager;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;

final class WorldSnapshotUtil {

    private WorldSnapshotUtil() {
    }

    static void copyWorldSnapshot(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (shouldSkip(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (shouldSkip(relative)) {
                    return FileVisitResult.CONTINUE;
                }

                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                try {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                } catch (NoSuchFileException ex) {
                    if (!shouldIgnoreMissing(file)) {
                        throw ex;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (exc instanceof NoSuchFileException && shouldIgnoreMissing(file)) {
                    return FileVisitResult.CONTINUE;
                }
                throw exc;
            }
        });
    }

    static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean shouldSkip(Path relativePath) {
        if (relativePath == null || relativePath.toString().isBlank()) {
            return false;
        }
        return "session.lock".equals(relativePath.toString());
    }

    private static boolean shouldIgnoreMissing(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }

        String fileName = path.getFileName().toString();
        if ("session.lock".equals(fileName)) {
            return true;
        }

        // Minecraft 保存世界时会轮换 level.dat / level.dat_old / 临时 level*.dat。
        // 这些文件在遍历过程中短暂消失是正常现象，不应让整个快照失败。
        return "level.dat_old".equals(fileName)
                || (fileName.startsWith("level")
                && fileName.endsWith(".dat")
                && !"level.dat".equals(fileName));
    }
}
