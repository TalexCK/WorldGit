package com.worldgit.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.AiAuditRepository;
import com.worldgit.database.AiPreviewSessionRepository;
import com.worldgit.database.PlayerAiSecretRepository;
import com.worldgit.manager.BranchManager;
import com.worldgit.manager.ProtectionManager;
import com.worldgit.manager.WorldManager;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchStatus;
import com.worldgit.util.BranchDisplayUtil;
import com.worldgit.util.MessageUtil;
import java.net.http.HttpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class WorldGitAiService {

    private static final int SECRET_SALT_BYTES = 16;
    private static final int SECRET_HASH_BYTES = 32;
    private static final int SECRET_HASH_ITERATIONS = 120_000;
    private static final List<String> SUPPORTED_IMAGE_TYPES = List.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp"
    );

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final BranchManager branchManager;
    private final WorldManager worldManager;
    private final ProtectionManager protectionManager;
    private final PlayerAiSecretRepository playerAiSecretRepository;
    private final AiAuditRepository aiAuditRepository;
    private final AiPreviewSessionRepository aiPreviewSessionRepository;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, AiPreviewSession> previewSessions = new ConcurrentHashMap<>();
    private final AiJobManager jobManager = new AiJobManager();

    private ExecutorService executor;
    private HttpClient httpClient;
    private AiSessionManager sessionManager;
    private AiToolRegistry toolRegistry;
    private AiProvider aiProvider;
    private MainThreadBridge mainThreadBridge;

    public WorldGitAiService(
            WorldGitPlugin plugin,
            PluginConfig pluginConfig,
            BranchManager branchManager,
            WorldManager worldManager,
            ProtectionManager protectionManager,
            PlayerAiSecretRepository playerAiSecretRepository,
            AiAuditRepository aiAuditRepository,
            AiPreviewSessionRepository aiPreviewSessionRepository
    ) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.pluginConfig = Objects.requireNonNull(pluginConfig, "插件配置不能为空");
        this.branchManager = Objects.requireNonNull(branchManager, "分支管理器不能为空");
        this.worldManager = Objects.requireNonNull(worldManager, "世界管理器不能为空");
        this.protectionManager = Objects.requireNonNull(protectionManager, "保护管理器不能为空");
        this.playerAiSecretRepository = Objects.requireNonNull(playerAiSecretRepository, "Secret 仓储不能为空");
        this.aiAuditRepository = Objects.requireNonNull(aiAuditRepository, "审计仓储不能为空");
        this.aiPreviewSessionRepository = Objects.requireNonNull(aiPreviewSessionRepository, "AI 预览仓储不能为空");
    }

    public void start() {
        executor = Executors.newFixedThreadPool(4, new AiThreadFactory());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(pluginConfig.aiRequestTimeoutSeconds()))
                .executor(executor)
                .build();
        sessionManager = new AiSessionManager(
                Duration.ofMinutes(pluginConfig.aiSessionTtlMinutes()),
                loadJwtSigningKey(),
                gson
        );
        mainThreadBridge = new MainThreadBridge(plugin, Duration.ofSeconds(30));
        toolRegistry = new AiBranchToolService(
                branchManager,
                worldManager,
                protectionManager,
                mainThreadBridge
        ).createRegistry();
        aiProvider = createProvider();
        loadPersistedPreviewSessions();
        int purged = branchManager.purgeLegacyAbandonedAiPreviews();
        if (purged > 0) {
            plugin.getLogger().info("已清理 " + purged + " 条遗留的 AI 预览分支记录");
        }
    }

    public void stop() {
        if (sessionManager != null) {
            sessionManager.clear();
            sessionManager = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        httpClient = null;
        aiProvider = null;
        toolRegistry = null;
        mainThreadBridge = null;
        previewSessions.clear();
        jobManager.clear();
    }

    public Gson gson() {
        return gson;
    }

    public String rotateSecret(Player player, boolean ignoredAiAllowed) {
        Objects.requireNonNull(player, "玩家不能为空");
        ensureStarted();
        String secret = generateSecret(player.getUniqueId());
        byte[] salt = new byte[SECRET_SALT_BYTES];
        secureRandom.nextBytes(salt);
        Instant now = Instant.now();
        Instant createdAt = playerAiSecretRepository.findByPlayerUuid(player.getUniqueId())
                .map(PlayerAiSecretRecord::createdAt)
                .orElse(now);
        playerAiSecretRepository.save(new PlayerAiSecretRecord(
                player.getUniqueId(),
                player.getName(),
                hashSecret(secret, salt, SECRET_HASH_ITERATIONS),
                Base64.getEncoder().encodeToString(salt),
                SECRET_HASH_ITERATIONS,
                true,
                createdAt,
                now,
                null
        ));
        sessionManager.invalidatePlayerSessions(player.getUniqueId());
        return secret;
    }

    public String rotateSecret(Player player) {
        return rotateSecret(player, true);
    }

    public JsonObject publicStatus() {
        JsonObject payload = new JsonObject();
        payload.addProperty("enabled", pluginConfig.aiEnabled());
        payload.addProperty("configured", pluginConfig.aiConfigured());
        payload.addProperty("runtimeAvailable", pluginConfig.aiConfigured());
        payload.addProperty("provider", pluginConfig.aiProvider());
        payload.addProperty("model", pluginConfig.aiModel());
        payload.addProperty("openaiMode", pluginConfig.aiOpenAiMode());
        payload.addProperty("blueMapUrl", pluginConfig.webBlueMapUrl());
        payload.addProperty("pointCloudUrl", pluginConfig.webPointCloudUrl());
        payload.addProperty("runtimeMessage", runtimeMessage());
        payload.add("limits", limitsJson());
        return payload;
    }

    public JsonObject login(String secret) {
        ensureStarted();
        UUID playerUuid = parsePlayerUuidFromSecret(secret);
        PlayerAiSecretRecord record = playerAiSecretRepository.findByPlayerUuid(playerUuid)
                .orElseThrow(() -> new IllegalStateException("Secret 不存在或已失效"));
        if (!verifySecret(secret, record)) {
            throw new IllegalStateException("Secret 不正确");
        }
        playerAiSecretRepository.touchLastUsed(playerUuid, Instant.now());
        AiSession session = sessionManager.createSession(record.playerUuid(), record.playerName(), Duration.ofDays(7));
        return sessionPayload(session);
    }

    public JsonObject createWebSession(Player player, Duration sessionTtl) {
        Objects.requireNonNull(player, "玩家不能为空");
        ensureStarted();
        AiSession session = sessionManager.createSession(player.getUniqueId(), player.getName(), sessionTtl);
        return sessionPayload(session);
    }

    public AiSession requireSession(String sessionToken) {
        ensureStarted();
        return sessionManager.requireSession(sessionToken);
    }

    public JsonObject sessionState(String sessionToken) {
        ensureStarted();
        AiSession session = sessionManager.requireSession(sessionToken);
        return sessionPayload(session);
    }

    public JsonObject listTools(String sessionToken) {
        ensureStarted();
        AiSession session = sessionManager.requireSession(sessionToken);
        JsonObject payload = sessionPayload(session);
        if (hasAiPermission(session.playerUuid())) {
            payload.add("tools", toolsJson());
        } else {
            payload.add("tools", new JsonArray());
            payload.addProperty("aiDisabledReason", "当前玩家没有 worldgit.ai.use 权限");
        }
        return payload;
    }

    public JsonObject callTool(String sessionToken, String branchId, String toolName, JsonObject arguments) {
        ensureStarted();
        AiSession session = sessionManager.requireSession(sessionToken);
        ensureAiAllowed(session);
        Branch branch = requireEditableBranch(session, branchId);
        AiRunLogger logger = new AiRunLogger(
                gson,
                aiAuditRepository,
                session.token(),
                session.playerUuid(),
                branch.id(),
                pluginConfig.aiProvider(),
                pluginConfig.aiModel(),
                pluginConfig.aiAuditPayloadMaxLength()
        );
        AiExecutionContext context = new AiExecutionContext(
                session.token(),
                session.playerUuid(),
                session.playerName(),
                branch,
                pluginConfig.aiMaxBoxBlocks(),
                pluginConfig.aiMaxTotalBlockChanges(),
                logger
        );
        JsonObject result = toolRegistry.execute(toolName, arguments == null ? new JsonObject() : arguments, context);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", result.has("ok") && result.get("ok").getAsBoolean());
        payload.add("session", sessionSummaryJson(session));
        payload.add("branch", branchJson(branch, session.playerUuid()));
        payload.add("toolResult", result);
        payload.add("logs", logsJson(logger.snapshot()));
        return payload;
    }

    public JsonObject startConversation(
            String sessionToken,
            String branchId,
            String prompt,
            AiImageInput imageInput
    ) {
        ensureStarted();

        AiSession session = sessionManager.requireSession(sessionToken);
        ensureAiAllowed(session);
        ensureRuntimeAvailable();
        Branch sourceBranch = requireEditableBranch(session, branchId);
        String normalizedPrompt = normalizePrompt(prompt);
        AiImageInput normalizedImage = normalizeImage(imageInput);
        requireOnlinePlayer(session.playerUuid());
        cleanupSupersededPreviewSessions(session.playerUuid(), sourceBranch.id());

        PreviewCreation previewCreation = createPreviewBranch(session, sourceBranch);
        Branch previewBranch = previewCreation.previewBranch();
        AiPreviewSession previewSession = new AiPreviewSession(
                previewBranch.id(),
                sourceBranch.id(),
                session.playerUuid(),
                previewCreation.sourceAnchor(),
                Instant.now()
        );
        registerPreviewSession(previewSession);

        AiJob job = jobManager.create(session.playerUuid(), session.token(), sourceBranch.id(), previewBranch.id());

        AiRunLogger logger = new AiRunLogger(
                gson,
                aiAuditRepository,
                session.token(),
                session.playerUuid(),
                previewBranch.id(),
                pluginConfig.aiProvider(),
                pluginConfig.aiModel(),
                pluginConfig.aiAuditPayloadMaxLength()
        );
        logger.setListener(job::appendLog);
        logger.info("preview_created", "已创建 AI 预览分支", previewJson(sourceBranch, previewBranch));
        logger.info("session_start", "开始执行 AI 建造任务", branchJson(previewBranch, session.playerUuid()));

        AiExecutionContext context = new AiExecutionContext(
                session.token(),
                session.playerUuid(),
                session.playerName(),
                previewBranch,
                pluginConfig.aiMaxBoxBlocks(),
                pluginConfig.aiMaxTotalBlockChanges(),
                logger
        );

        String systemPrompt = buildSystemPrompt(previewBranch);
        UUID playerUuid = session.playerUuid();

        executor.submit(() -> runConversationAsync(
                job,
                context,
                logger,
                sourceBranch,
                previewBranch,
                previewSession,
                systemPrompt,
                normalizedPrompt,
                normalizedImage,
                playerUuid
        ));

        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("jobId", job.id());
        payload.add("session", sessionSummaryJson(session));
        payload.add("branch", branchJson(previewBranch, session.playerUuid()));
        payload.addProperty("sourceBranchId", sourceBranch.id());
        payload.addProperty("previewBranchId", previewBranch.id());
        return payload;
    }

    public JsonObject jobStatus(String sessionToken, String jobId, int sinceLogIndex) {
        ensureStarted();
        AiSession session = sessionManager.requireSession(sessionToken);
        AiJob job = jobManager.findForPlayer(jobId, session.playerUuid());
        if (job == null) {
            throw new IllegalStateException("找不到该 AI 任务");
        }
        int cursor = Math.max(0, sinceLogIndex);
        List<AiLogEntry> newLogs = job.logsSince(cursor);
        int nextCursor = cursor + newLogs.size();

        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("jobId", job.id());
        payload.addProperty("status", job.status().name().toLowerCase(Locale.ROOT));
        payload.addProperty("previewBranchId", job.previewBranchId());
        payload.addProperty("sourceBranchId", job.sourceBranchId());
        payload.addProperty("nextLogCursor", nextCursor);
        payload.add("logs", logsJson(newLogs));
        AiConversationResult result = job.result();
        if (result != null) {
            payload.addProperty("provider", result.provider());
            payload.addProperty("model", result.model());
            payload.addProperty("finalText", result.finalText() == null ? "" : result.finalText());
            payload.addProperty("toolRounds", result.toolRounds());
            payload.addProperty("totalBlockChanges", result.totalBlockChanges());
        }
        if (job.errorMessage() != null) {
            payload.addProperty("errorMessage", job.errorMessage());
        }
        return payload;
    }

    private void runConversationAsync(
            AiJob job,
            AiExecutionContext context,
            AiRunLogger logger,
            Branch sourceBranch,
            Branch previewBranch,
            AiPreviewSession previewSession,
            String systemPrompt,
            String normalizedPrompt,
            AiImageInput normalizedImage,
            UUID playerUuid
    ) {
        try {
            String terrainSnapshot = captureTerrainSnapshot(previewBranch);
            String userPrompt = buildUserPrompt(previewBranch, normalizedPrompt, normalizedImage, terrainSnapshot);
            AiConversationResult result = aiProvider.runConversation(
                    context,
                    systemPrompt,
                    userPrompt,
                    normalizedImage,
                    logger
            );
            job.markDone(result);
            notifyPreviewReady(playerUuid, sourceBranch, previewBranch, previewSession, true, null);
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            logger.error("provider_error", "AI 执行失败", errorPayload(exception));
            job.markError(message);
            notifyPreviewReady(
                    playerUuid,
                    sourceBranch,
                    previewBranch,
                    previewSession,
                    false,
                    "AI 执行失败，但预览分支已保留"
            );
            plugin.getLogger().log(Level.WARNING, "AI 执行失败", cause);
        }
    }

    public void keepPreview(Player player, String previewBranchId) {
        Objects.requireNonNull(player, "玩家不能为空");
        ensureStarted();
        AiPreviewSession previewSession = resolvePreviewSession(player, previewBranchId);
        KeepPreviewResult result = mainThreadBridge.call(() -> {
            Branch previewBranch = branchManager.requireBranch(previewSession.previewBranchId());
            Branch sourceBranch = branchManager.requireBranch(previewSession.sourceBranchId());
            if (!branchManager.canModifyBranch(player, sourceBranch)) {
                throw new IllegalStateException("你无权回写该源分支");
            }
            if (!branchManager.isAiEditableBranch(sourceBranch)) {
                throw new IllegalStateException("源分支当前不是可编辑状态，无法回写 AI 预览");
            }
            int changedBlocks = branchManager.copyEditableRegion(previewBranch, sourceBranch);
            branchManager.teleportToCorrespondingBranch(player, previewBranch, sourceBranch, player.getLocation());
            branchManager.forceDeleteBranch(previewBranch.id(), "AI 预览已保留并回写源分支");
            return new KeepPreviewResult(sourceBranch, previewBranch, changedBlocks);
        });
        unregisterPreviewSession(previewSession.previewBranchId());
        MessageUtil.sendSuccess(
                player,
                "已保留 AI 预览并回写到源分支 ["
                        + BranchDisplayUtil.shortId(result.sourceBranch().id())
                        + "]，同步 "
                        + result.changedBlocks()
                        + " 个方块差异。"
        );
    }

    public void dropPreview(Player player, String previewBranchId) {
        Objects.requireNonNull(player, "玩家不能为空");
        ensureStarted();
        AiPreviewSession previewSession = resolvePreviewSession(player, previewBranchId);
        DropPreviewResult result = mainThreadBridge.call(() -> {
            Branch previewBranch = branchManager.requireBranch(previewSession.previewBranchId());
            Branch sourceBranch = null;
            try {
                sourceBranch = branchManager.requireBranch(previewSession.sourceBranchId());
            } catch (IllegalStateException ignored) {
                // 源分支缺失时退回主世界。
            }
            if (sourceBranch != null && sourceBranch.hasRegion() && worldManager.isBranchWorld(sourceBranch.worldName())) {
                branchManager.teleportToCorrespondingBranch(player, previewBranch, sourceBranch, player.getLocation());
            } else {
                player.teleport(worldManager.getMainWorld().getSpawnLocation());
            }
            branchManager.forceDeleteBranch(previewBranch.id(), "已丢弃 AI 预览分支");
            return new DropPreviewResult(sourceBranch, previewBranch);
        });
        unregisterPreviewSession(previewSession.previewBranchId());
        MessageUtil.sendSuccess(
                player,
                result.sourceBranch() == null
                        ? "已丢弃 AI 预览 [" + BranchDisplayUtil.shortId(result.previewBranch().id()) + "]，并返回主世界。"
                        : "已丢弃 AI 预览 ["
                                + BranchDisplayUtil.shortId(result.previewBranch().id())
                                + "]，并返回源分支 ["
                                + BranchDisplayUtil.shortId(result.sourceBranch().id())
                                + "]。"
        );
    }

    public List<String> suggestPreviewBranchIds(Player player, String prefix) {
        ensureStarted();
        if (player == null) {
            return List.of();
        }
        String normalizedPrefix = prefix == null ? "" : prefix.trim();
        return previewSessions.values().stream()
                .filter(session -> session.playerUuid().equals(player.getUniqueId()))
                .sorted(Comparator.comparing(AiPreviewSession::createdAt).reversed())
                .map(AiPreviewSession::previewBranchId)
                .filter(id -> normalizedPrefix.isEmpty() || id.startsWith(normalizedPrefix))
                .toList();
    }

    public void enterPreview(Player player, String previewBranchId) {
        Objects.requireNonNull(player, "玩家不能为空");
        ensureStarted();
        AiPreviewSession previewSession = resolvePreviewSession(player, previewBranchId);
        mainThreadBridge.run(() -> {
            Branch previewBranch = branchManager.requireBranch(previewSession.previewBranchId());
            Branch sourceBranch = resolveOptionalBranch(previewSession.sourceBranchId());
            Location anchorLocation = previewSession.sourceAnchor() == null ? null : previewSession.sourceAnchor().toLocation();
            if (sourceBranch != null) {
                branchManager.teleportToCorrespondingBranch(player, sourceBranch, previewBranch, anchorLocation);
            } else {
                player.teleport(worldManager.createBranchSpawn(
                        worldManager.createBranchWorld(previewBranch.worldName()),
                        previewBranch.minX(),
                        previewBranch.maxX(),
                        previewBranch.minY(),
                        previewBranch.maxY(),
                        previewBranch.minZ(),
                        previewBranch.maxZ()
                ));
            }
            MessageUtil.sendInfo(player, "已进入 AI 预览分支 [" + BranchDisplayUtil.shortId(previewBranch.id()) + "]。");
        });
    }

    public void handlePlayerJoin(Player player) {
        if (player == null) {
            return;
        }
        mainThreadBridge.run(() -> handlePlayerContextRefresh(player, true));
    }

    public void handlePlayerChangedWorld(Player player) {
        if (player == null) {
            return;
        }
        mainThreadBridge.run(() -> handlePlayerContextRefresh(player, false));
    }

    private void ensureStarted() {
        if (sessionManager == null || toolRegistry == null || aiProvider == null || mainThreadBridge == null) {
            throw new IllegalStateException("AI 服务尚未初始化");
        }
    }

    private void loadPersistedPreviewSessions() {
        previewSessions.clear();
        for (AiPreviewSessionRecord record : aiPreviewSessionRepository.findAll()) {
            try {
                Branch previewBranch = branchManager.requireBranch(record.previewBranchId());
                if (!previewBranch.hasRegion() || !worldManager.isBranchWorld(previewBranch.worldName())) {
                    aiPreviewSessionRepository.delete(record.previewBranchId());
                    continue;
                }
                previewSessions.put(record.previewBranchId(), sessionFromRecord(record));
            } catch (IllegalStateException exception) {
                aiPreviewSessionRepository.delete(record.previewBranchId());
            }
        }
    }

    private void registerPreviewSession(AiPreviewSession previewSession) {
        previewSessions.put(previewSession.previewBranchId(), previewSession);
        aiPreviewSessionRepository.save(recordFromSession(previewSession));
    }

    private void unregisterPreviewSession(String previewBranchId) {
        previewSessions.remove(previewBranchId);
        aiPreviewSessionRepository.delete(previewBranchId);
    }

    private void handlePlayerContextRefresh(Player player, boolean fromJoin) {
        if (player == null || !player.isOnline() || player.getWorld() == null) {
            return;
        }

        AiPreviewSession activePreview = previewSessionForWorld(player.getWorld().getName(), player.getUniqueId());
        if (activePreview != null) {
            try {
                Branch previewBranch = branchManager.requireBranch(activePreview.previewBranchId());
                Branch sourceBranch = resolveOptionalBranch(activePreview.sourceBranchId());
                player.sendMessage(previewDecisionMessage(sourceBranch, previewBranch));
            } catch (IllegalStateException exception) {
                unregisterPreviewSession(activePreview.previewBranchId());
            }
            return;
        }

        if (!fromJoin || !pluginConfig.mainWorld().equalsIgnoreCase(player.getWorld().getName())) {
            return;
        }
        for (AiPreviewSession previewSession : pendingPreviewSessions(player.getUniqueId())) {
            try {
                Branch previewBranch = branchManager.requireBranch(previewSession.previewBranchId());
                Branch sourceBranch = resolveOptionalBranch(previewSession.sourceBranchId());
                player.sendMessage(previewEntryMessage(sourceBranch, previewBranch));
            } catch (IllegalStateException exception) {
                unregisterPreviewSession(previewSession.previewBranchId());
            }
        }
    }

    private List<AiPreviewSession> pendingPreviewSessions(UUID playerUuid) {
        return previewSessions.values().stream()
                .filter(session -> session.playerUuid().equals(playerUuid))
                .sorted(Comparator.comparing(AiPreviewSession::createdAt).reversed())
                .toList();
    }

    private AiPreviewSession previewSessionForWorld(String worldName, UUID playerUuid) {
        if (worldName == null || worldName.isBlank() || playerUuid == null) {
            return null;
        }
        Branch branch = branchManager.findByWorld(worldName).orElse(null);
        if (branch == null) {
            return null;
        }
        AiPreviewSession previewSession = previewSessions.get(branch.id());
        if (previewSession == null || !previewSession.playerUuid().equals(playerUuid)) {
            return null;
        }
        return previewSession;
    }

    private Branch resolveOptionalBranch(String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return null;
        }
        try {
            return branchManager.requireBranch(branchId);
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private void requireOnlinePlayer(UUID playerUuid) {
        mainThreadBridge.call(() -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                throw new IllegalStateException("执行 AI 前玩家必须在线，便于进入预览分支确认结果");
            }
            return player;
        });
    }

    private void ensureRuntimeAvailable() {
        if (!pluginConfig.aiEnabled()) {
            throw new IllegalStateException("AI 功能已在配置中禁用");
        }
        if (pluginConfig.aiApiKey().isBlank()) {
            throw new IllegalStateException("AI 功能缺少 api-key，无法发起模型请求");
        }
    }

    private String runtimeMessage() {
        if (!pluginConfig.aiEnabled()) {
            return "AI 功能已禁用";
        }
        if (pluginConfig.aiApiKey().isBlank()) {
            return "AI 已启用，但尚未配置 api-key";
        }
        return "AI 功能可用";
    }

    private AiProvider createProvider() {
        if ("anthropic".equalsIgnoreCase(pluginConfig.aiProvider())) {
            return new AnthropicAiProvider(httpClient, gson, pluginConfig, toolRegistry);
        }
        return new OpenAiProvider(httpClient, gson, pluginConfig, toolRegistry);
    }

    private byte[] loadJwtSigningKey() {
        Path keyFile = plugin.getDataFolder().toPath().resolve("web-jwt.key").toAbsolutePath().normalize();
        try {
            Files.createDirectories(keyFile.getParent());
            if (Files.exists(keyFile)) {
                byte[] key = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
                if (key.length >= 32) {
                    return key;
                }
                plugin.getLogger().warning("Web JWT 签名密钥长度不足，将重新生成。");
            }
            byte[] key = new byte[64];
            secureRandom.nextBytes(key);
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key), StandardCharsets.UTF_8);
            return key;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("初始化 Web JWT 签名密钥失败", exception);
        }
    }

    private JsonObject sessionPayload(AiSession session) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.add("session", sessionSummaryJson(session));
        payload.add("runtime", publicStatus());
        payload.add("branches", hasAiPermission(session.playerUuid())
                ? branchesJson(visibleBranches(session.playerUuid()), session.playerUuid())
                : new JsonArray());
        payload.add("realshotBranches", branchesJson(realshotBranches(session.playerUuid()), session.playerUuid()));
        return payload;
    }

    private List<Branch> visibleBranches(UUID playerUuid) {
        return branchManager.listAiEditableBranches(playerUuid).stream()
                .filter(branch -> !previewSessions.containsKey(branch.id()))
                .toList();
    }

    private List<Branch> realshotBranches(UUID playerUuid) {
        return branchManager.listEditableBranches(playerUuid).stream()
                .filter(Branch::hasRegion)
                .filter(branch -> branch.status() == BranchStatus.ACTIVE)
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .toList();
    }

    private JsonObject sessionSummaryJson(AiSession session) {
        JsonObject payload = new JsonObject();
        payload.addProperty("token", session.token());
        payload.addProperty("playerUuid", session.playerUuid().toString());
        payload.addProperty("playerName", session.playerName());
        payload.addProperty("aiAllowed", hasAiPermission(session.playerUuid()));
        payload.addProperty("expiresAt", session.expiresAt().toString());
        return payload;
    }

    private void ensureAiAllowed(AiSession session) {
        if (!hasAiPermission(session.playerUuid())) {
            throw new IllegalStateException("当前玩家没有 worldgit.ai.use 权限，不能用于 AI");
        }
    }

    private boolean hasAiPermission(UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        return player != null && player.isOnline() && player.hasPermission("worldgit.ai.use");
    }

    private JsonArray branchesJson(List<Branch> branches, UUID playerUuid) {
        JsonArray array = new JsonArray();
        for (Branch branch : branches) {
            array.add(branchJson(branch, playerUuid));
        }
        return array;
    }

    private JsonObject branchJson(Branch branch, UUID playerUuid) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", branch.id());
        payload.addProperty("label", branch.label() == null || branch.label().isBlank() ? branch.id() : branch.label());
        payload.addProperty("worldName", branch.worldName());
        payload.addProperty("status", branch.status().dbValue());
        payload.addProperty("ownerUuid", branch.ownerUuid().toString());
        payload.addProperty("ownerName", branch.ownerName());
        payload.addProperty("role", branch.ownerUuid().equals(playerUuid) ? "owner" : "collaborator");
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", branch.minX());
        bounds.addProperty("minY", branch.minY());
        bounds.addProperty("minZ", branch.minZ());
        bounds.addProperty("maxX", branch.maxX());
        bounds.addProperty("maxY", branch.maxY());
        bounds.addProperty("maxZ", branch.maxZ());
        payload.add("bounds", bounds);
        return payload;
    }

    private JsonArray toolsJson() {
        JsonArray tools = new JsonArray();
        for (AiToolDefinition definition : toolRegistry.list()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", definition.name());
            tool.addProperty("description", definition.description());
            tool.add("parametersSchema", definition.parametersSchema());
            tools.add(tool);
        }
        return tools;
    }

    private JsonObject limitsJson() {
        JsonObject payload = new JsonObject();
        payload.addProperty("maxToolRounds", pluginConfig.aiMaxToolRounds());
        payload.addProperty("maxBoxBlocks", pluginConfig.aiMaxBoxBlocks());
        payload.addProperty("maxTotalBlockChanges", pluginConfig.aiMaxTotalBlockChanges());
        payload.addProperty("maxPromptCharacters", pluginConfig.aiMaxPromptCharacters());
        payload.addProperty("maxImageBytes", pluginConfig.aiMaxImageBytes());
        payload.addProperty("sessionTtlMinutes", pluginConfig.aiSessionTtlMinutes());
        return payload;
    }

    private JsonArray logsJson(List<AiLogEntry> logs) {
        JsonArray array = new JsonArray();
        for (AiLogEntry log : logs) {
            JsonObject payload = new JsonObject();
            payload.addProperty("level", log.level());
            payload.addProperty("type", log.type());
            payload.addProperty("message", log.message());
            payload.addProperty("createdAt", log.createdAt().toString());
            payload.add("data", AiProviderSupport.nonNull(log.data()));
            array.add(payload);
        }
        return array;
    }

    private Branch requireEditableBranch(AiSession session, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            throw new IllegalStateException("必须明确选择一个可编辑分支");
        }
        Branch branch = branchManager.requireBranch(branchId);
        if (previewSessions.containsKey(branch.id())) {
            throw new IllegalStateException("不能直接对 AI 预览分支再次发起任务，请回到源分支");
        }
        if (!branch.hasRegion()) {
            throw new IllegalStateException("该分支没有有效编辑区域");
        }
        if (!worldManager.isBranchWorld(branch.worldName())) {
            throw new IllegalStateException("目标世界不是 branch world");
        }
        if (branch.worldName().equals(branch.mainWorld())) {
            throw new IllegalStateException("严禁通过 AI 修改 main world");
        }
        if (!branchManager.canModifyBranch(session.playerUuid(), branch)) {
            throw new IllegalStateException("你无权操作该分支");
        }
        if (!branchManager.isAiEditableBranch(branch)) {
            throw new IllegalStateException("该分支当前不是 AI 可编辑状态");
        }
        return branch;
    }

    private String normalizePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("Prompt 不能为空");
        }
        String normalized = prompt.trim();
        if (normalized.length() > pluginConfig.aiMaxPromptCharacters()) {
            throw new IllegalStateException("Prompt 超过长度限制");
        }
        return normalized;
    }

    private AiImageInput normalizeImage(AiImageInput imageInput) {
        if (imageInput == null || !imageInput.isPresent()) {
            return null;
        }
        String mediaType = imageInput.mimeType().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_IMAGE_TYPES.contains(mediaType)) {
            throw new IllegalStateException("暂不支持该图片类型: " + mediaType);
        }
        int byteLength;
        try {
            byteLength = imageInput.byteLength();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("图片 base64 数据无效", exception);
        }
        if (byteLength > pluginConfig.aiMaxImageBytes()) {
            throw new IllegalStateException("图片超过大小限制");
        }
        return new AiImageInput(imageInput.fileName(), mediaType, imageInput.base64Data());
    }

    private String buildSystemPrompt(Branch branch) {
        return """
                你是 WorldGit 的 AI 建造助手。
                你的唯一目标是在一个可编辑 branch world 内，通过工具调用完成建造或修改任务。

                【工作流硬性要求】
                - 第一轮的用户消息里会给你一份整盒 editable 区域的粗粒度地形快照（高度热力图 + 主要材质 + 顶面 Y 范围）。**先读这份快照**判断地面大致在哪里、哪里已有结构、哪里是空气，再决定动工位置。
                - 快照之后、真正改块之前，你必须至少调用一次 scan_box_summary 精查你打算动的局部区域；不要把方块堆在毫无观察依据的坐标上。
                - 目标如果是“在地面上放一个东西”：先用 scan_box_summary 确认该局部的顶面 Y（大多数情况该 Y+1 就是放置起点），避免把东西埋进土里或悬空在天上。
                - 禁止在未观察的情况下一次性填一个大长方形占满整个 editableBounds——这几乎总是错误答案，会被玩家 Drop 掉。

                【编辑区域硬性限制 — 违反即报错】
                - 可编辑区域是一个闭区间盒子 editableBounds：
                  minX=%d, minY=%d, minZ=%d, maxX=%d, maxY=%d, maxZ=%d
                - 你调用的所有坐标 (x, y, z) 必须同时满足：
                    minX <= x <= maxX，minY <= y <= maxY，minZ <= z <= maxZ
                - 所有工具参数都使用世界绝对坐标（与上面的 editableBounds 同一套坐标系），不是相对坐标。
                - 任何越界坐标都会被工具拒绝并抛错，请在规划阶段就把目标限制在此盒子内。
                - place_blocks_in_box / break_blocks_in_box / get_blocks_in_box / scan_box_summary 的 min/max 两个角都必须落在 editableBounds 内，且 min <= max。
                - apply_block_jsonl 的每一行都必须完全落在 editableBounds 内；越界行会整批失败。
                - 不要在边界之外“预留缓冲”或“做地面延伸”；请就地在盒子内完成。

                【世界与数据约束】
                1. 只能操作当前绑定的 branch world，绝不修改 main world，也绝不修改任何其他世界。
                2. 只能使用提供的工具读取和改动世界，不能臆测方块状态。
                3. 当前世界是 AI 预览分支，其内容是源分支 editableBounds 区域的精确拷贝；你的改动不会直接写回源分支，只有玩家选择 Keep 才会回写。
                4. 必须先观察再施工；大范围观察优先使用 scan_box_summary，只有在需要精查局部细节时才使用 get_blocks_in_box。
                5. 规则性大范围改动优先使用 place_blocks_in_box / break_blocks_in_box；只有不规则结构才优先考虑 apply_block_jsonl。
                6. 读取类盒工具仍然有单次体积限制（maxBoxBlocksPerReadTool）；如需观察大区域，请按 editableBounds 拆分。
                7. 如果工具返回错误，先分析原因；必要时缩小范围、重新观察，再继续；不要在相同参数上反复重试。
                8. 控制上下文体积：不要为了观察把大区域完整方块列表反复读出来。
                9. 如果最终无法完成，请在最终回复里明确说明失败原因和建议下一步。

                当前分支上下文：
                - branchId: %s
                - owner: %s (%s)
                - worldName: %s
                - mainWorld: %s
                - editableBounds: (%d, %d, %d) -> (%d, %d, %d)
                - editableSize: dx=%d, dy=%d, dz=%d
                - maxToolRounds: %d
                - maxBoxBlocksPerReadTool: %d
                - maxTotalBlockChangesForLegacyMutationTools: %d

                最终回复请简洁说明：
                - 已完成什么
                - 使用了哪些关键工具
                - 是否有剩余风险或未完成项
                """.formatted(
                branch.minX(),
                branch.minY(),
                branch.minZ(),
                branch.maxX(),
                branch.maxY(),
                branch.maxZ(),
                branch.id(),
                branch.ownerName(),
                branch.ownerUuid(),
                branch.worldName(),
                branch.mainWorld(),
                branch.minX(),
                branch.minY(),
                branch.minZ(),
                branch.maxX(),
                branch.maxY(),
                branch.maxZ(),
                branch.maxX() - branch.minX() + 1,
                branch.maxY() - branch.minY() + 1,
                branch.maxZ() - branch.minZ() + 1,
                pluginConfig.aiMaxToolRounds(),
                pluginConfig.aiMaxBoxBlocks(),
                pluginConfig.aiMaxTotalBlockChanges()
        );
    }

    private String buildUserPrompt(Branch branch, String prompt, AiImageInput imageInput, String terrainSnapshot) {
        StringBuilder builder = new StringBuilder();
        builder.append("目标 branchId: ").append(branch.id()).append('\n');
        builder.append("目标 worldName: ").append(branch.worldName()).append('\n');
        builder.append("编辑区域: (")
                .append(branch.minX()).append(", ")
                .append(branch.minY()).append(", ")
                .append(branch.minZ()).append(") -> (")
                .append(branch.maxX()).append(", ")
                .append(branch.maxY()).append(", ")
                .append(branch.maxZ()).append(")\n");
        if (terrainSnapshot != null && !terrainSnapshot.isBlank()) {
            builder.append("\n== 预览分支初始地形快照（来自预览分支，等于源分支当前状态）==\n");
            builder.append(terrainSnapshot);
            builder.append("\n== 快照结束 ==\n\n");
        }
        if (imageInput != null && imageInput.isPresent()) {
            builder.append("用户还上传了一张参考图片，请结合图片理解目标。\n");
        }
        builder.append("用户需求：\n").append(prompt);
        return builder.toString();
    }

    private String captureTerrainSnapshot(Branch branch) {
        try {
            return mainThreadBridge.call(() -> buildTerrainSnapshot(branch));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "生成地形快照失败: " + branch.id(), exception);
            return null;
        }
    }

    private String buildTerrainSnapshot(Branch branch) {
        org.bukkit.World world = Bukkit.getWorld(branch.worldName());
        if (world == null) {
            return null;
        }
        int minX = branch.minX();
        int minY = branch.minY();
        int minZ = branch.minZ();
        int maxX = branch.maxX();
        int maxY = branch.maxY();
        int maxZ = branch.maxZ();
        int dx = maxX - minX + 1;
        int dz = maxZ - minZ + 1;
        int step = Math.max(1, Math.min(dx, dz) / 16);

        java.util.LinkedHashMap<String, Integer> materialCounts = new java.util.LinkedHashMap<>();
        long totalSamples = 0;
        long nonAirSamples = 0;
        int minSurfaceY = Integer.MAX_VALUE;
        int maxSurfaceY = Integer.MIN_VALUE;
        long surfaceSum = 0;
        int surfaceCount = 0;

        StringBuilder heightmapRows = new StringBuilder();
        int rowsKept = 0;
        int maxRows = 16;
        int rowStep = Math.max(step, Math.max(1, dz / maxRows));
        int colStep = Math.max(step, Math.max(1, dx / maxRows));

        for (int z = minZ; z <= maxZ; z += rowStep) {
            StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x += colStep) {
                int surfaceY = Integer.MIN_VALUE;
                String surfaceMaterial = "AIR";
                for (int y = maxY; y >= minY; y--) {
                    totalSamples++;
                    var block = world.getBlockAt(x, y, z);
                    String name = block.getType().name();
                    materialCounts.merge(name, 1, Integer::sum);
                    if (block.getType() != org.bukkit.Material.AIR) {
                        nonAirSamples++;
                        if (surfaceY == Integer.MIN_VALUE) {
                            surfaceY = y;
                            surfaceMaterial = name;
                        }
                    }
                }
                if (surfaceY == Integer.MIN_VALUE) {
                    row.append(".. ");
                } else {
                    minSurfaceY = Math.min(minSurfaceY, surfaceY);
                    maxSurfaceY = Math.max(maxSurfaceY, surfaceY);
                    surfaceSum += surfaceY;
                    surfaceCount++;
                    row.append(String.format(Locale.ROOT, "%02d ", Math.floorMod(surfaceY, 100)));
                }
                if (totalSamples > 200_000L) {
                    break;
                }
            }
            if (rowsKept < maxRows) {
                heightmapRows.append(String.format(Locale.ROOT, "z=%d: %s%n", z, row.toString().trim()));
                rowsKept++;
            }
            if (totalSamples > 200_000L) {
                break;
            }
        }

        StringBuilder snapshot = new StringBuilder();
        snapshot.append(String.format(Locale.ROOT,
                "- 采样步长: xStep=%d zStep=%d（整个 editable 盒子的粗粒度扫描）%n",
                colStep, rowStep));
        snapshot.append(String.format(Locale.ROOT,
                "- 采样总方块: %d，其中非空气: %d (%.1f%%)%n",
                totalSamples, nonAirSamples,
                totalSamples == 0 ? 0.0 : nonAirSamples * 100.0 / totalSamples));
        if (surfaceCount > 0) {
            snapshot.append(String.format(Locale.ROOT,
                    "- 顶面 Y 范围: [%d, %d]，平均 ≈ %d%n",
                    minSurfaceY, maxSurfaceY, (int) (surfaceSum / surfaceCount)));
        } else {
            snapshot.append("- 整个采样区域未发现任何非空气方块（预览分支可能刚被初始化为空）\n");
        }
        snapshot.append("- 主要材质 (Top 8)：\n");
        materialCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .forEach(entry -> snapshot.append(String.format(Locale.ROOT,
                        "    %s × %d%n", entry.getKey(), entry.getValue())));
        snapshot.append("- 顶面高度热力图（每格为该 (x,z) 列的最高非空气方块 Y 的后两位，.. 表示空列）：\n");
        snapshot.append(heightmapRows);
        snapshot.append("提示：要精查某处细节请再调用 get_block / scan_box_summary；这里给的是粗略全局视图，便于你定位地面、判断已有结构位置。");
        return snapshot.toString();
    }

    private PreviewCreation createPreviewBranch(AiSession session, Branch sourceBranch) {
        return mainThreadBridge.call(() -> {
            Player player = Bukkit.getPlayer(session.playerUuid());
            if (player == null || !player.isOnline()) {
                throw new IllegalStateException("执行 AI 前玩家必须在线，便于进入预览分支确认结果");
            }
            LocationSnapshot sourceAnchor = captureSourceAnchor(player, sourceBranch);
            Branch previewBranch = branchManager.createAiPreviewBranch(
                    session.playerUuid(),
                    session.playerName(),
                    sourceBranch,
                    "AI预览 " + BranchDisplayUtil.shortId(sourceBranch.id())
            );
            return new PreviewCreation(previewBranch, sourceAnchor);
        });
    }

    private LocationSnapshot captureSourceAnchor(Player player, Branch sourceBranch) {
        if (player == null || !player.isOnline()) {
            return null;
        }
        Location location = player.getLocation();
        if (location.getWorld() == null || !sourceBranch.worldName().equals(location.getWorld().getName())) {
            return null;
        }
        return LocationSnapshot.from(location);
    }

    private void cleanupSupersededPreviewSessions(UUID playerUuid, String sourceBranchId) {
        List<AiPreviewSession> staleSessions = previewSessions.values().stream()
                .filter(session -> session.playerUuid().equals(playerUuid))
                .filter(session -> session.sourceBranchId().equals(sourceBranchId))
                .sorted(Comparator.comparing(AiPreviewSession::createdAt).reversed())
                .toList();
        for (AiPreviewSession staleSession : staleSessions) {
            try {
                mainThreadBridge.run(() -> discardPreviewSession(staleSession, "新的 AI 任务已开始，旧预览已丢弃"));
            } catch (RuntimeException exception) {
                unregisterPreviewSession(staleSession.previewBranchId());
                plugin.getLogger().log(Level.WARNING, "清理旧 AI 预览失败: " + staleSession.previewBranchId(), exception);
            }
        }
    }

    private void discardPreviewSession(AiPreviewSession previewSession, String note) {
        Branch previewBranch;
        try {
            previewBranch = branchManager.requireBranch(previewSession.previewBranchId());
        } catch (IllegalStateException exception) {
            unregisterPreviewSession(previewSession.previewBranchId());
            return;
        }

        Branch sourceBranch = null;
        try {
            sourceBranch = branchManager.requireBranch(previewSession.sourceBranchId());
        } catch (IllegalStateException ignored) {
            // 源分支缺失时退回主世界即可。
        }

        Player player = Bukkit.getPlayer(previewSession.playerUuid());
        if (player != null && player.isOnline()) {
            if (sourceBranch != null && sourceBranch.hasRegion() && worldManager.isBranchWorld(sourceBranch.worldName())) {
                branchManager.teleportToCorrespondingBranch(player, previewBranch, sourceBranch, player.getLocation());
            } else {
                player.teleport(worldManager.getMainWorld().getSpawnLocation());
            }
        }

        branchManager.forceDeleteBranch(previewBranch.id(), note);
        unregisterPreviewSession(previewSession.previewBranchId());
    }

    private void notifyPreviewReady(
            UUID playerUuid,
            Branch sourceBranch,
            Branch previewBranch,
            AiPreviewSession previewSession,
            boolean success,
            String failureMessage
    ) {
        try {
            mainThreadBridge.run(() -> {
                Player player = Bukkit.getPlayer(playerUuid);
                if (player == null || !player.isOnline()) {
                    return;
                }
                branchManager.teleportToCorrespondingBranch(
                        player,
                        sourceBranch,
                        previewBranch,
                        previewSession.sourceAnchor() == null ? null : previewSession.sourceAnchor().toLocation()
                );
                String shortPreviewId = BranchDisplayUtil.shortId(previewBranch.id());
                if (success) {
                    MessageUtil.sendSuccess(player, "AI 已完成预览并切换到预览分支 [" + shortPreviewId + "]。");
                } else {
                    MessageUtil.sendWarning(player, failureMessage + "，已切换到预览分支 [" + shortPreviewId + "]。");
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "通知 AI 预览结果失败: " + previewBranch.id(), exception);
        }
    }

    private Component previewDecisionMessage(Branch sourceBranch, Branch previewBranch) {
        Component keepButton = Component.text("[Keep]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/ai keep " + previewBranch.id()))
                .hoverEvent(HoverEvent.showText(Component.text("保留预览并回写源分支", NamedTextColor.GREEN)));
        Component dropButton = Component.text("[Drop]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/ai drop " + previewBranch.id()))
                .hoverEvent(HoverEvent.showText(Component.text("丢弃预览并删除该分支", NamedTextColor.RED)));
        String sourceText = sourceBranch == null
                ? "源分支已不可用"
                : "源分支 [" + BranchDisplayUtil.shortId(sourceBranch.id()) + "]";
        return MessageUtil.prefix()
                .append(Component.text(
                        "AI 预览 ["
                                + BranchDisplayUtil.shortId(previewBranch.id())
                                + "] 已就绪，" + sourceText + "：",
                        NamedTextColor.YELLOW
                ))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(keepButton)
                .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                .append(dropButton);
    }

    private Component previewEntryMessage(Branch sourceBranch, Branch previewBranch) {
        Component enterButton = Component.text("[进入 AI 分支]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/ai preview " + previewBranch.id()))
                .hoverEvent(HoverEvent.showText(Component.text("进入 AI 预览分支查看结果", NamedTextColor.AQUA)));
        String sourceText = sourceBranch == null
                ? "源分支已不可用"
                : "源分支 [" + BranchDisplayUtil.shortId(sourceBranch.id()) + "]";
        return MessageUtil.prefix()
                .append(Component.text(
                        "你有待处理的 AI 预览 ["
                                + BranchDisplayUtil.shortId(previewBranch.id())
                                + "]，"
                                + sourceText
                                + "。",
                        NamedTextColor.YELLOW
                ))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(enterButton);
    }

    private AiPreviewSession resolvePreviewSession(Player player, String previewBranchId) {
        List<AiPreviewSession> ownedSessions = previewSessions.values().stream()
                .filter(session -> session.playerUuid().equals(player.getUniqueId()))
                .sorted(Comparator.comparing(AiPreviewSession::createdAt).reversed())
                .toList();
        if (ownedSessions.isEmpty()) {
            throw new IllegalStateException("你当前没有待处理的 AI 预览");
        }
        if (previewBranchId == null || previewBranchId.isBlank()) {
            if (ownedSessions.size() == 1) {
                return ownedSessions.get(0);
            }
            String available = ownedSessions.stream()
                    .map(session -> BranchDisplayUtil.shortId(session.previewBranchId()))
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            throw new IllegalStateException("你有多个 AI 预览，请指定预览 ID。可选: " + available);
        }
        AiPreviewSession previewSession = previewSessions.get(previewBranchId);
        if (previewSession == null || !previewSession.playerUuid().equals(player.getUniqueId())) {
            throw new IllegalStateException("找不到该 AI 预览分支");
        }
        return previewSession;
    }

    private JsonObject previewJson(Branch sourceBranch, Branch previewBranch) {
        JsonObject payload = new JsonObject();
        payload.addProperty("sourceBranchId", sourceBranch.id());
        payload.addProperty("sourceWorldName", sourceBranch.worldName());
        payload.addProperty("previewBranchId", previewBranch.id());
        payload.addProperty("previewWorldName", previewBranch.worldName());
        return payload;
    }

    private AiPreviewSession sessionFromRecord(AiPreviewSessionRecord record) {
        return new AiPreviewSession(
                record.previewBranchId(),
                record.sourceBranchId(),
                record.playerUuid(),
                LocationSnapshot.fromRecord(record),
                record.createdAt()
        );
    }

    private AiPreviewSessionRecord recordFromSession(AiPreviewSession session) {
        LocationSnapshot anchor = session.sourceAnchor();
        return new AiPreviewSessionRecord(
                session.previewBranchId(),
                session.sourceBranchId(),
                session.playerUuid(),
                anchor == null ? null : anchor.worldName(),
                anchor == null ? null : anchor.x(),
                anchor == null ? null : anchor.y(),
                anchor == null ? null : anchor.z(),
                anchor == null ? null : anchor.yaw(),
                anchor == null ? null : anchor.pitch(),
                session.createdAt()
        );
    }

    private String generateSecret(UUID playerUuid) {
        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        return "wgai."
                + compactUuid(playerUuid)
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    }

    private UUID parsePlayerUuidFromSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Secret 不能为空");
        }
        String[] parts = secret.trim().split("\\.");
        if (parts.length != 3 || !"wgai".equals(parts[0])) {
            throw new IllegalStateException("Secret 格式无效");
        }
        return parseCompactUuid(parts[1]);
    }

    private boolean verifySecret(String secret, PlayerAiSecretRecord record) {
        byte[] salt = Base64.getDecoder().decode(record.secretSalt());
        String actualHash = hashSecret(secret, salt, record.hashIterations());
        return constantTimeEquals(actualHash, record.secretHash());
    }

    private String hashSecret(String secret, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    secret.toCharArray(),
                    salt,
                    iterations,
                    SECRET_HASH_BYTES * 8
            );
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] bytes = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("生成 Secret 哈希失败", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(leftBytes, rightBytes);
    }

    private String compactUuid(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private UUID parseCompactUuid(String compactUuid) {
        if (compactUuid == null || compactUuid.length() != 32) {
            throw new IllegalStateException("Secret 中的玩家标识无效");
        }
        String uuid = compactUuid.substring(0, 8)
                + "-"
                + compactUuid.substring(8, 12)
                + "-"
                + compactUuid.substring(12, 16)
                + "-"
                + compactUuid.substring(16, 20)
                + "-"
                + compactUuid.substring(20);
        return UUID.fromString(uuid);
    }

    private JsonObject errorPayload(Exception exception) {
        JsonObject payload = new JsonObject();
        payload.addProperty("message", exception.getMessage());
        return payload;
    }

    private record PreviewCreation(Branch previewBranch, LocationSnapshot sourceAnchor) {
    }

    private record AiPreviewSession(
            String previewBranchId,
            String sourceBranchId,
            UUID playerUuid,
            LocationSnapshot sourceAnchor,
            Instant createdAt
    ) {
    }

    private record KeepPreviewResult(Branch sourceBranch, Branch previewBranch, int changedBlocks) {
    }

    private record DropPreviewResult(Branch sourceBranch, Branch previewBranch) {
    }

    private record LocationSnapshot(String worldName, double x, double y, double z, float yaw, float pitch) {

        private static LocationSnapshot from(Location location) {
            if (location == null || location.getWorld() == null) {
                return null;
            }
            return new LocationSnapshot(
                    location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch()
            );
        }

        private static LocationSnapshot fromRecord(AiPreviewSessionRecord record) {
            if (record == null
                    || record.sourceWorldName() == null
                    || record.sourceX() == null
                    || record.sourceY() == null
                    || record.sourceZ() == null
                    || record.sourceYaw() == null
                    || record.sourcePitch() == null) {
                return null;
            }
            return new LocationSnapshot(
                    record.sourceWorldName(),
                    record.sourceX(),
                    record.sourceY(),
                    record.sourceZ(),
                    record.sourceYaw(),
                    record.sourcePitch()
            );
        }

        private Location toLocation() {
            if (worldName == null || worldName.isBlank()) {
                return null;
            }
            org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            return new Location(world, x, y, z, yaw, pitch);
        }
    }

    private final class AiThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "WorldGit-AI-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
