package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

public final class GitHubSyncManager {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long COMMAND_TIMEOUT_SECONDS = 300L;
    private static final String MARKER_FILE_NAME = ".worldgit-sync";

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);
    private BukkitTask task;

    public GitHubSyncManager(WorldGitPlugin plugin, PluginConfig pluginConfig) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
    }

    public void start() {
        String invalidReason = getInvalidReason();
        if (invalidReason != null) {
            if (pluginConfig.githubSyncEnabled()) {
                plugin.getLogger().warning("GitHub 同步未启动: " + invalidReason);
            }
            return;
        }
        stop();
        long intervalTicks = pluginConfig.githubSyncIntervalMinutes() * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> syncNowSafe(), intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean syncNowSafe() {
        String invalidReason = getInvalidReason();
        if (invalidReason != null) {
            plugin.getLogger().warning("GitHub 同步未执行: " + invalidReason);
            return false;
        }
        if (!syncRunning.compareAndSet(false, true)) {
            plugin.getLogger().info("已有 GitHub 同步任务在执行，跳过本次触发");
            return false;
        }
        try {
            syncNow();
            return true;
        } catch (Exception ex) {
            syncRunning.set(false);
            plugin.getLogger().warning("准备 GitHub 同步任务失败: " + ex.getMessage());
            return false;
        }
    }

    public void syncNow() {
        World world = Bukkit.getWorld(pluginConfig.mainWorld());
        if (world == null) {
            throw new IllegalStateException("主世界不存在，无法同步到 GitHub");
        }

        if (!world.isAutoSave()) {
            world.save();
        }

        Path source = world.getWorldFolder().toPath();
        String worldName = world.getName();
        Path repoRoot = pluginConfig.githubSyncDirectoryPath(plugin);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                runSync(source, worldName, repoRoot);
            } catch (Exception ex) {
                plugin.getLogger().warning("GitHub 同步失败: " + ex.getMessage());
            } finally {
                syncRunning.set(false);
            }
        });
    }

    private void runSync(Path source, String worldName, Path repoRoot) throws IOException {
        Files.createDirectories(plugin.getDataFolder().toPath());
        Path snapshotRoot = Files.createTempDirectory(plugin.getDataFolder().toPath(), "github-sync-");
        Path snapshotWorld = snapshotRoot.resolve(worldName);
        try {
            WorldSnapshotUtil.copyWorldSnapshot(source, snapshotWorld);
            Files.createDirectories(repoRoot);
            Map<String, String> environment = buildGitEnvironment();
            prepareRepository(repoRoot, environment);

            Path repoWorld = repoRoot.resolve(worldName);
            WorldSnapshotUtil.deleteDirectory(repoWorld);
            WorldSnapshotUtil.copyWorldSnapshot(snapshotWorld, repoWorld);

            runGit(repoRoot, environment, "add", "-A", "--", worldName);
            String status = runGitCapture(repoRoot, environment, true, "status", "--porcelain", "--", worldName).trim();
            if (status.isEmpty()) {
                plugin.getLogger().info("GitHub 同步跳过：世界没有变化");
                return;
            }

            String timestamp = FORMATTER.format(LocalDateTime.now());
            runGit(
                    repoRoot,
                    environment,
                    "commit",
                    "-m",
                    "同步世界 " + worldName + " @ " + timestamp
            );
            runGit(repoRoot, environment, "push", "-u", "origin", pluginConfig.githubSyncBranch());
            plugin.getLogger().info("GitHub 同步完成: " + pluginConfig.githubSyncRepository());
        } finally {
            WorldSnapshotUtil.deleteDirectory(snapshotRoot);
        }
    }

    public String getInvalidReason() {
        try {
            if (!pluginConfig.githubSyncEnabled()) {
                return "github-sync.enabled=false";
            }
            if (pluginConfig.githubSyncRepository().isBlank() || pluginConfig.githubSyncPrivateKeyPath().isBlank()) {
                return "repository 或 private-key-path 未配置";
            }
            if (!pluginConfig.githubSyncRepository().contains("/")) {
                return "repository 格式错误，应为 owner/repo: " + pluginConfig.githubSyncRepository();
            }
            Path privateKeyPath = pluginConfig.githubSyncPrivateKeyPath(plugin);
            if (!Files.isRegularFile(privateKeyPath) || !Files.isReadable(privateKeyPath)) {
                return "SSH 私钥文件不存在或不可读: " + privateKeyPath;
            }
            pluginConfig.githubSyncDirectoryPath(plugin);
            return null;
        } catch (Exception ex) {
            return ex.getMessage();
        }
    }

    private void prepareRepository(Path repoRoot, Map<String, String> environment) throws IOException {
        ensureManagedDirectory(repoRoot, environment);
        runGit(repoRoot, environment, "init");
        runGit(repoRoot, environment, "config", "user.name", pluginConfig.githubSyncAuthorName());
        runGit(repoRoot, environment, "config", "user.email", pluginConfig.githubSyncAuthorEmail());
        runGitIgnoringExitCode(repoRoot, environment, "remote", "remove", "origin");
        runGit(repoRoot, environment, "remote", "add", "origin", buildRemoteUrl());
        writeMarkerFile(repoRoot);

        String branch = pluginConfig.githubSyncBranch();
        boolean remoteBranchExists = runGitExitCode(repoRoot, environment, "ls-remote", "--exit-code", "--heads", "origin", branch) == 0;
        if (remoteBranchExists) {
            runGit(repoRoot, environment, "fetch", "origin", branch);
        }

        boolean localBranchExists = runGitExitCode(
                repoRoot,
                environment,
                "show-ref",
                "--verify",
                "--quiet",
                "refs/heads/" + branch
        ) == 0;

        if (localBranchExists) {
            runGit(repoRoot, environment, "checkout", branch);
        } else if (remoteBranchExists) {
            runGit(repoRoot, environment, "checkout", "-b", branch, "--track", "origin/" + branch);
        } else {
            runGit(repoRoot, environment, "checkout", "-B", branch);
        }

        // 同步目录由插件独占管理，进入同步前先清掉上次异常留下的未提交状态。
        runGit(repoRoot, environment, "reset", "--hard");
        runGit(repoRoot, environment, "clean", "-fd", "-e", MARKER_FILE_NAME);

        if (remoteBranchExists) {
            runGit(repoRoot, environment, "pull", "--rebase", "origin", branch);
        }
    }

    private void ensureManagedDirectory(Path repoRoot, Map<String, String> environment) throws IOException {
        Files.createDirectories(repoRoot);
        Path markerPath = markerFilePath(repoRoot);
        boolean hasMarker = Files.isRegularFile(markerPath);
        boolean hasGitDirectory = Files.isDirectory(repoRoot.resolve(".git"));

        if (hasMarker) {
            validateMarkerFile(markerPath);
            return;
        }

        if (!hasGitDirectory && !directoryHasNonMarkerEntries(repoRoot)) {
            return;
        }

        if (hasGitDirectory && isCurrentRemoteSafe(repoRoot, environment)) {
            writeMarkerFile(repoRoot);
            return;
        }

        throw new IOException("GitHub 同步目录不是 WorldGit 管理的专用目录，请清理后重试: " + repoRoot);
    }

    private boolean directoryHasNonMarkerEntries(Path repoRoot) throws IOException {
        try (var stream = Files.list(repoRoot)) {
            return stream.anyMatch(path -> !MARKER_FILE_NAME.equals(path.getFileName().toString()));
        }
    }

    private boolean isCurrentRemoteSafe(Path repoRoot, Map<String, String> environment) throws IOException {
        CommandResult result = runCommand(repoRoot, environment, "config", "--get", "remote.origin.url");
        if (result.exitCode() != 0) {
            return false;
        }
        return buildRemoteUrl().equals(result.output().trim());
    }

    private void validateMarkerFile(Path markerPath) throws IOException {
        Properties properties = new Properties();
        try (var inputStream = Files.newInputStream(markerPath)) {
            properties.load(inputStream);
        }

        String repository = properties.getProperty("repository", "");
        String branch = properties.getProperty("branch", "");
        if (!pluginConfig.githubSyncRepository().trim().equals(repository)
                || !pluginConfig.githubSyncBranch().trim().equals(branch)) {
            throw new IOException("同步目录已绑定到其他 GitHub 仓库或分支，请清理目录后再切换配置");
        }
    }

    private void writeMarkerFile(Path repoRoot) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("repository", pluginConfig.githubSyncRepository().trim());
        properties.setProperty("branch", pluginConfig.githubSyncBranch().trim());
        try (var outputStream = Files.newOutputStream(markerFilePath(repoRoot))) {
            properties.store(outputStream, "WorldGit managed sync directory");
        }
    }

    private Path markerFilePath(Path repoRoot) {
        return repoRoot.resolve(MARKER_FILE_NAME);
    }

    private Map<String, String> buildGitEnvironment() throws IOException {
        Path privateKeyPath = pluginConfig.githubSyncPrivateKeyPath(plugin).toAbsolutePath().normalize();
        Path knownHostsPath = plugin.getDataFolder().toPath().resolve("known_hosts").toAbsolutePath().normalize();
        Files.createDirectories(knownHostsPath.getParent());
        String sshCommand = "ssh -i " + shellQuote(privateKeyPath)
                + " -o IdentitiesOnly=yes"
                + " -o StrictHostKeyChecking=accept-new"
                + " -o UserKnownHostsFile=" + shellQuote(knownHostsPath);
        return Map.of(
                "GIT_SSH_COMMAND", sshCommand,
                "GIT_TERMINAL_PROMPT", "0"
        );
    }

    private void runGit(Path repoRoot, Map<String, String> environment, String... args) throws IOException {
        CommandResult result = runCommand(repoRoot, environment, args);
        if (result.exitCode() != 0) {
            throw new IOException("git " + String.join(" ", args) + " 失败: " + result.summary());
        }
    }

    private String runGitCapture(Path repoRoot, Map<String, String> environment, boolean trimOutput, String... args)
            throws IOException {
        CommandResult result = runCommand(repoRoot, environment, args);
        if (result.exitCode() != 0) {
            throw new IOException("git " + String.join(" ", args) + " 失败: " + result.summary());
        }
        return trimOutput ? result.output().trim() : result.output();
    }

    private int runGitExitCode(Path repoRoot, Map<String, String> environment, String... args) throws IOException {
        return runCommand(repoRoot, environment, args).exitCode();
    }

    private void runGitIgnoringExitCode(Path repoRoot, Map<String, String> environment, String... args) throws IOException {
        runCommand(repoRoot, environment, args);
    }

    private CommandResult runCommand(Path repoRoot, Map<String, String> environment, String... gitArgs) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(gitArgs));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (repoRoot != null) {
            processBuilder.directory(repoRoot.toFile());
        }
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().putAll(environment);

        Process process = processBuilder.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!builder.isEmpty()) {
                    builder.append(System.lineSeparator());
                }
                builder.append(line);
            }
            output = builder.toString();
        }

        boolean finished;
        try {
            finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 git 命令完成时被中断", ex);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git " + String.join(" ", gitArgs) + " 超时");
        }
        return new CommandResult(process.exitValue(), output);
    }

    private String shellQuote(Path path) {
        return shellQuote(path.toString());
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String buildRemoteUrl() {
        String normalized = pluginConfig.githubSyncRepository().trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return "git@github.com:" + normalized + ".git";
    }

    private record CommandResult(int exitCode, String output) {

        private String summary() {
            if (output == null || output.isBlank()) {
                return "无输出";
            }
            return output;
        }
    }
}
