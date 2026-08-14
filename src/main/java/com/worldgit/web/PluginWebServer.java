package com.worldgit.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.worldgit.WorldGitPlugin;
import com.worldgit.ai.AiImageInput;
import com.worldgit.ai.AiSession;
import com.worldgit.ai.WorldGitAiService;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.BranchRepository;
import com.worldgit.database.RealshotRepository;
import com.worldgit.manager.BranchManager;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchStatus;
import com.worldgit.model.PlayerMergeLeaderboardEntry;
import com.worldgit.model.RealshotMedia;
import com.worldgit.model.RealshotRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * 插件内置 Web 服务，负责提供静态页面和最近活动 API。
 */
public final class PluginWebServer {

    private static final String DEFAULT_INDEX_RESOURCE = "web/index.html";
    private static final String DEFAULT_STATIC_DIRECTORY = "web";
    private static final int MAX_LIMIT = 100;
    private static final int MAX_REALSHOT_QUESTION_LENGTH = 1_000;
    private static final int MAX_REALSHOT_FILES_PER_UPLOAD = 8;
    private static final int MAX_REALSHOT_FILE_BYTES = 50 * 1024 * 1024;

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final BranchRepository branchRepository;
    private final BranchManager branchManager;
    private final RealshotRepository realshotRepository;
    private final WorldGitAiService aiService;
    private final WebLoginRequestService webLoginRequestService;
    private final Gson gson;

    private HttpServer httpServer;
    private ExecutorService executor;

    public PluginWebServer(
            WorldGitPlugin plugin,
            PluginConfig pluginConfig,
            BranchRepository branchRepository,
            BranchManager branchManager,
            RealshotRepository realshotRepository,
            WorldGitAiService aiService,
            WebLoginRequestService webLoginRequestService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.pluginConfig = Objects.requireNonNull(pluginConfig, "插件配置不能为空");
        this.branchRepository = Objects.requireNonNull(branchRepository, "分支仓储不能为空");
        this.branchManager = Objects.requireNonNull(branchManager, "分支管理器不能为空");
        this.realshotRepository = Objects.requireNonNull(realshotRepository, "实景图片仓储不能为空");
        this.aiService = Objects.requireNonNull(aiService, "AI 服务不能为空");
        this.webLoginRequestService = Objects.requireNonNull(webLoginRequestService, "Web 登录请求服务不能为空");
        this.gson = aiService.gson();
    }

    public void start() {
        if (!pluginConfig.webEnabled()) {
            plugin.getLogger().info("Web 服务已在配置中禁用");
            return;
        }

        try {
            ensureStaticAssets();

            httpServer = HttpServer.create(
                    new InetSocketAddress(pluginConfig.webHost(), pluginConfig.webPort()),
                    0
            );
            httpServer.createContext("/api/health", this::handleHealth);
            httpServer.createContext("/api/auth/login-requests", this::handleLoginRequests);
            httpServer.createContext("/api/auth/login-requests/", this::handleLoginRequestStatus);
            httpServer.createContext("/api/activity/recent", this::handleRecentActivity);
            httpServer.createContext("/api/stats/merge-leaderboard", this::handleMergeLeaderboard);
            httpServer.createContext("/api/ai/status", this::handleAiStatus);
            httpServer.createContext("/api/ai/login", this::handleAiLogin);
            httpServer.createContext("/api/ai/session", this::handleAiSession);
            httpServer.createContext("/api/ai/run", this::handleAiRun);
            httpServer.createContext("/api/ai/jobs/", this::handleAiJob);
            httpServer.createContext("/api/ai/tools", this::handleAiTools);
            httpServer.createContext("/api/ai/tools/call", this::handleAiToolCall);
            httpServer.createContext("/api/realshots/requests", this::handleRealshotRequests);
            httpServer.createContext("/api/realshots/requests/", this::handleRealshotRequestItem);
            httpServer.createContext("/api/realshots/media/", this::handleRealshotMedia);
            httpServer.createContext("/", this::handleStatic);

            executor = Executors.newFixedThreadPool(4, new WebThreadFactory());
            httpServer.setExecutor(executor);
            httpServer.start();

            plugin.getLogger().info(
                    "Web 服务已启动: http://" + pluginConfig.webHost() + ":" + pluginConfig.webPort()
            );
        } catch (Exception exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "启动 Web 服务失败，插件其余功能继续运行。请检查 web.port / web.host 配置: "
                            + exception.getMessage(),
                    exception
            );
            stop();
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void ensureStaticAssets() throws IOException {
        Path staticDirectory = pluginConfig.webStaticDirectoryPath(plugin);
        Files.createDirectories(staticDirectory);

        Path indexFile = staticDirectory.resolve("index.html");
        if (!DEFAULT_STATIC_DIRECTORY.equals(pluginConfig.webStaticDirectory()) && Files.exists(indexFile)) {
            return;
        }

        byte[] bundledBytes;
        try (InputStream inputStream = plugin.getResource(DEFAULT_INDEX_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("缺少默认前端资源: " + DEFAULT_INDEX_RESOURCE);
            }
            bundledBytes = inputStream.readAllBytes();
        }

        if (Files.exists(indexFile)) {
            byte[] existingBytes = Files.readAllBytes(indexFile);
            if (Arrays.equals(existingBytes, bundledBytes)) {
                return;
            }
        }

        Files.write(indexFile, bundledBytes);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange)) {
                return;
            }
            if (!isGet(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }
            String body = "{"
                    + "\"status\":\"ok\","
                    + "\"plugin\":\"WorldGit\","
                    + "\"version\":" + jsonString(plugin.getDescription().getVersion()) + ","
                    + "\"prefix\":" + jsonString(pluginConfig.displayPrefix()) + ","
                    + "\"blueMapUrl\":" + jsonString(pluginConfig.webBlueMapUrl()) + ","
                    + "\"pointCloudUrl\":" + jsonString(pluginConfig.webPointCloudUrl()) + ","
                    + "\"generatedAt\":" + jsonString(Instant.now().toString())
                    + "}";
            sendJson(exchange, 200, body);
        } catch (Exception exception) {
            sendServerError(exchange, "health_check_failed", exception);
        }
    }

    private void handleRecentActivity(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange)) {
                return;
            }
            if (!isGet(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }

            int limit = resolveLimit(exchange);
            List<Branch> created = branchRepository.listRecentCreatedUnchecked(limit);
            List<Branch> submitted = branchRepository.listRecentSubmittedUnchecked(limit);
            List<Branch> merged = branchRepository.listRecentMergedUnchecked(limit);

            String body = "{"
                    + "\"generatedAt\":" + jsonString(Instant.now().toString()) + ","
                    + "\"limit\":" + limit + ","
                    + "\"created\":" + serializeBranchList("CREATED", created) + ","
                    + "\"submitted\":" + serializeBranchList("SUBMITTED", submitted) + ","
                    + "\"merged\":" + serializeBranchList("MERGED", merged)
                    + "}";
            sendJson(exchange, 200, body);
        } catch (Exception exception) {
            sendServerError(exchange, "recent_activity_failed", exception);
        }
    }

    private void handleLoginRequests(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange)) {
                return;
            }
            if (!isPost(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }
            JsonObject body = readJsonBody(exchange);
            sendJson(exchange, 200, webLoginRequestService.createRequest(getString(body, "playerId")));
        } catch (IllegalStateException exception) {
            sendClientError(exchange, "bad_request", exception.getMessage());
        } catch (Exception exception) {
            sendServerError(exchange, "login_request_failed", exception);
        }
    }

    private void handleLoginRequestStatus(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange)) {
                return;
            }
            if (!isGet(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/auth/login-requests/";
            if (path == null || !path.startsWith(prefix) || path.length() <= prefix.length()) {
                sendNotFound(exchange);
                return;
            }
            String requestId = path.substring(prefix.length());
            int slashIndex = requestId.indexOf('/');
            if (slashIndex >= 0) {
                requestId = requestId.substring(0, slashIndex);
            }
            sendJson(exchange, 200, webLoginRequestService.status(requestId));
        } catch (IllegalStateException exception) {
            sendClientError(exchange, "bad_request", exception.getMessage());
        } catch (Exception exception) {
            sendServerError(exchange, "login_request_status_failed", exception);
        }
    }

    private void handleMergeLeaderboard(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange)) {
                return;
            }
            if (!isGet(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }

            int limit = resolveLimit(exchange);
            List<PlayerMergeLeaderboardEntry> leaderboard = branchRepository.listMergeLeaderboardUnchecked(limit);

            String body = "{"
                    + "\"generatedAt\":" + jsonString(Instant.now().toString()) + ","
                    + "\"limit\":" + limit + ","
                    + "\"leaderboard\":" + serializeLeaderboard(leaderboard)
                    + "}";
            sendJson(exchange, 200, body);
        } catch (Exception exception) {
            sendServerError(exchange, "merge_leaderboard_failed", exception);
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange)) {
                return;
            }
            if (!isGet(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }

            String requestPath = exchange.getRequestURI().getPath();
            if (requestPath != null && requestPath.startsWith("/api/")) {
                sendNotFound(exchange);
                return;
            }

            Path staticFile = resolveStaticFile(requestPath);
            if (staticFile == null || !Files.exists(staticFile) || !Files.isRegularFile(staticFile)) {
                sendNotFound(exchange);
                return;
            }

            byte[] body = Files.readAllBytes(staticFile);
            exchange.getResponseHeaders().set("Content-Type", detectContentType(staticFile));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        } catch (Exception exception) {
            sendServerError(exchange, "static_file_failed", exception);
        }
    }

    private void handleAiStatus(HttpExchange exchange) throws IOException {
        handleAiRequest(exchange, "GET", () -> aiService.publicStatus());
    }

    private void handleAiLogin(HttpExchange exchange) throws IOException {
        handleAiRequest(exchange, "POST", () -> {
            JsonObject body = readJsonBody(exchange);
            return aiService.login(getString(body, "secret"));
        });
    }

    private void handleAiSession(HttpExchange exchange) throws IOException {
        handleAiRequest(exchange, "GET", () -> aiService.sessionState(requireSessionToken(exchange)));
    }

    private void handleAiRun(HttpExchange exchange) throws IOException {
        handleAiRequest(exchange, "POST", () -> {
            JsonObject body = readJsonBody(exchange);
            return aiService.startConversation(
                    requireSessionToken(exchange),
                    getString(body, "branchId"),
                    getString(body, "prompt"),
                    parseImage(body)
            );
        });
    }

    private void handleAiJob(HttpExchange exchange) throws IOException {
        handleAiRequest(exchange, "GET", () -> {
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/ai/jobs/";
            if (path == null || !path.startsWith(prefix) || path.length() <= prefix.length()) {
                throw new IllegalStateException("缺少 jobId");
            }
            String jobId = path.substring(prefix.length());
            int slashIndex = jobId.indexOf('/');
            if (slashIndex >= 0) {
                jobId = jobId.substring(0, slashIndex);
            }
            int sinceLog = parseIntQueryParam(exchange, "sinceLog", 0);
            return aiService.jobStatus(requireSessionToken(exchange), jobId, sinceLog);
        });
    }

    private int parseIntQueryParam(HttpExchange exchange, String name, int defaultValue) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return defaultValue;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (!name.equals(key)) {
                continue;
            }
            try {
                return Integer.parseInt(URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private void handleAiTools(HttpExchange exchange) throws IOException {
        handleAiRequest(exchange, "GET", () -> aiService.listTools(requireSessionToken(exchange)));
    }

    private void handleAiToolCall(HttpExchange exchange) throws IOException {
        handleAiRequest(exchange, "POST", () -> {
            JsonObject body = readJsonBody(exchange);
            JsonObject arguments = body.has("arguments") && body.get("arguments").isJsonObject()
                    ? body.getAsJsonObject("arguments")
                    : new JsonObject();
            return aiService.callTool(
                    requireSessionToken(exchange),
                    getString(body, "branchId"),
                    getString(body, "toolName"),
                    arguments
            );
        });
    }

    private void handleRealshotRequests(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange)) {
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!"/api/realshots/requests".equals(path)) {
                sendNotFound(exchange);
                return;
            }
            if (isGet(exchange)) {
                AiSession session = aiService.requireSession(requireSessionToken(exchange));
                sendJson(exchange, 200, realshotListPayload(session));
                return;
            }
            if (isPost(exchange)) {
                AiSession session = aiService.requireSession(requireSessionToken(exchange));
                JsonObject body = readJsonBody(exchange);
                Branch branch = requireRealshotBranch(session, getString(body, "branchId"));
                String question = normalizeRealshotQuestion(getString(body, "question"));
                RealshotRequest request = new RealshotRequest(
                        UUID.randomUUID().toString().replace("-", ""),
                        branch.id(),
                        session.playerUuid(),
                        session.playerName(),
                        question,
                        Instant.now()
                );
                realshotRepository.saveRequest(request);
                JsonObject payload = new JsonObject();
                payload.addProperty("ok", true);
                payload.add("request", realshotRequestJson(request, branch, List.of(), session.token()));
                sendJson(exchange, 200, payload);
                return;
            }
            sendMethodNotAllowed(exchange);
        } catch (IllegalStateException exception) {
            sendClientError(exchange, "bad_request", exception.getMessage());
        } catch (Exception exception) {
            sendServerError(exchange, "realshot_request_failed", exception);
        }
    }

    private void handleRealshotRequestItem(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange)) {
                return;
            }
            if (!isPost(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/realshots/requests/";
            if (path == null || !path.startsWith(prefix) || !path.endsWith("/media")) {
                sendNotFound(exchange);
                return;
            }
            String requestId = path.substring(prefix.length(), path.length() - "/media".length());
            if (requestId.isBlank() || requestId.contains("/")) {
                throw new IllegalStateException("实景图片请求ID不合法");
            }

            AiSession session = aiService.requireSession(requireSessionToken(exchange));
            RealshotRequest request = realshotRepository.findRequest(requestId)
                    .orElseThrow(() -> new IllegalStateException("实景图片请求不存在"));
            Branch branch = requireRealshotBranch(session, request.branchId());
            List<RealshotMedia> savedMedia = saveRealshotMedia(session, request, readJsonBody(exchange));

            JsonObject payload = new JsonObject();
            payload.addProperty("ok", true);
            payload.add("request", realshotRequestJson(request, branch, savedMedia, session.token()));
            sendJson(exchange, 200, payload);
        } catch (IllegalStateException exception) {
            sendClientError(exchange, "bad_request", exception.getMessage());
        } catch (Exception exception) {
            sendServerError(exchange, "realshot_media_upload_failed", exception);
        }
    }

    private void handleRealshotMedia(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange)) {
                return;
            }
            if (!isGet(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/realshots/media/";
            if (path == null || !path.startsWith(prefix) || path.length() <= prefix.length()) {
                sendNotFound(exchange);
                return;
            }
            String mediaId = path.substring(prefix.length());
            int slashIndex = mediaId.indexOf('/');
            if (slashIndex >= 0) {
                mediaId = mediaId.substring(0, slashIndex);
            }

            AiSession session = aiService.requireSession(requireSessionTokenOrQuery(exchange));
            RealshotMedia media = realshotRepository.findMedia(mediaId)
                    .orElseThrow(() -> new IllegalStateException("实景图片素材不存在"));
            requireRealshotBranch(session, media.branchId());
            Path file = plugin.getDataFolder().toPath().toAbsolutePath().normalize().resolve(media.filePath()).normalize();
            Path base = realshotStorageRoot().normalize();
            if (!file.startsWith(base) || !Files.exists(file) || !Files.isRegularFile(file)) {
                sendNotFound(exchange);
                return;
            }

            byte[] body = Files.readAllBytes(file);
            applyCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
            exchange.getResponseHeaders().set("Content-Type", media.mimeType());
            exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + sanitizeHeaderValue(media.fileName()) + "\"");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        } catch (IllegalStateException exception) {
            sendClientError(exchange, "bad_request", exception.getMessage());
        } catch (Exception exception) {
            sendServerError(exchange, "realshot_media_failed", exception);
        }
    }

    private Path resolveStaticFile(String requestPath) {
        String pathValue = requestPath == null || requestPath.isBlank() || "/".equals(requestPath)
                ? "index.html"
                : requestPath.substring(1);
        pathValue = URLDecoder.decode(pathValue, StandardCharsets.UTF_8);

        Path baseDirectory = pluginConfig.webStaticDirectoryPath(plugin).toAbsolutePath().normalize();
        Path candidate = baseDirectory.resolve(pathValue).normalize();
        if (!candidate.startsWith(baseDirectory)) {
            return null;
        }
        if (Files.isDirectory(candidate)) {
            candidate = candidate.resolve("index.html").normalize();
        }
        return candidate.startsWith(baseDirectory) ? candidate : null;
    }

    private JsonObject realshotListPayload(AiSession session) {
        List<Branch> branches = branchManager.listEditableBranches(session.playerUuid()).stream()
                .filter(Branch::hasRegion)
                .filter(branch -> branch.status() == BranchStatus.ACTIVE)
                .toList();
        List<String> branchIds = branches.stream().map(Branch::id).toList();
        Map<String, Branch> branchesById = new HashMap<>();
        for (Branch branch : branches) {
            branchesById.put(branch.id(), branch);
        }
        List<RealshotRequest> requests = realshotRepository.listRequestsByBranchIds(branchIds);
        List<RealshotMedia> media = realshotRepository.listMediaByRequestIds(requests.stream()
                .map(RealshotRequest::id)
                .toList());
        Map<String, List<RealshotMedia>> mediaByRequest = new HashMap<>();
        for (RealshotMedia item : media) {
            mediaByRequest.computeIfAbsent(item.requestId(), ignored -> new ArrayList<>()).add(item);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("generatedAt", Instant.now().toString());
        var requestArray = new com.google.gson.JsonArray();
        for (RealshotRequest request : requests) {
            Branch branch = branchesById.get(request.branchId());
            if (branch == null) {
                continue;
            }
            requestArray.add(realshotRequestJson(
                    request,
                    branch,
                    mediaByRequest.getOrDefault(request.id(), List.of()),
                    session.token()
            ));
        }
        payload.add("requests", requestArray);
        return payload;
    }

    private Branch requireRealshotBranch(AiSession session, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            throw new IllegalStateException("必须绑定一个分支后才能提交实景图片需求");
        }
        Branch branch = branchManager.requireBranch(branchId);
        if (!branch.hasRegion()) {
            throw new IllegalStateException("该分支没有有效编辑区域");
        }
        if (branch.status() != BranchStatus.ACTIVE) {
            throw new IllegalStateException("实景图片只能绑定 ACTIVE 分支");
        }
        if (!branchManager.canModifyBranch(session.playerUuid(), branch)) {
            throw new IllegalStateException("你不是该分支参与者，无权访问相关实景图片");
        }
        return branch;
    }

    private String normalizeRealshotQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalStateException("请填写需要的实景图片说明");
        }
        String normalized = question.trim();
        if (normalized.length() > MAX_REALSHOT_QUESTION_LENGTH) {
            throw new IllegalStateException("实景图片说明超过长度限制");
        }
        return normalized;
    }

    private List<RealshotMedia> saveRealshotMedia(AiSession session, RealshotRequest request, JsonObject body) throws IOException {
        if (!body.has("files") || !body.get("files").isJsonArray()) {
            throw new IllegalStateException("files 字段必须是文件数组");
        }
        var files = body.getAsJsonArray("files");
        if (files.isEmpty()) {
            throw new IllegalStateException("请至少上传 1 个图片或视频");
        }
        if (files.size() > MAX_REALSHOT_FILES_PER_UPLOAD) {
            throw new IllegalStateException("一次最多上传 " + MAX_REALSHOT_FILES_PER_UPLOAD + " 个文件");
        }

        Path requestDirectory = realshotStorageRoot().resolve(request.id()).normalize();
        Files.createDirectories(requestDirectory);
        List<RealshotMedia> saved = new ArrayList<>();
        for (JsonElement element : files) {
            if (!element.isJsonObject()) {
                throw new IllegalStateException("文件条目必须是对象");
            }
            JsonObject fileObject = element.getAsJsonObject();
            String fileName = normalizeFileName(getString(fileObject, "fileName"));
            String mimeType = normalizeRealshotMimeType(getString(fileObject, "mimeType"));
            String base64Data = getString(fileObject, "base64Data");
            if (base64Data == null || base64Data.isBlank()) {
                throw new IllegalStateException("文件内容不能为空");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(base64Data);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("文件不是合法 Base64", exception);
            }
            if (bytes.length <= 0 || bytes.length > MAX_REALSHOT_FILE_BYTES) {
                throw new IllegalStateException("单个文件大小必须在 1B 到 50MB 之间");
            }

            String mediaId = UUID.randomUUID().toString().replace("-", "");
            String extension = extensionFor(fileName, mimeType);
            String storedName = mediaId + extension;
            Path file = requestDirectory.resolve(storedName).normalize();
            if (!file.startsWith(realshotStorageRoot().normalize())) {
                throw new IllegalStateException("文件路径不合法");
            }
            Files.write(file, bytes);
            RealshotMedia media = new RealshotMedia(
                    mediaId,
                    request.id(),
                    request.branchId(),
                    session.playerUuid(),
                    session.playerName(),
                    fileName,
                    mimeType,
                    plugin.getDataFolder().toPath().toAbsolutePath().normalize().relativize(file).toString(),
                    bytes.length,
                    Instant.now()
            );
            realshotRepository.saveMedia(media);
            saved.add(media);
        }
        return saved;
    }

    private Path realshotStorageRoot() throws IOException {
        Path root = plugin.getDataFolder().toPath().resolve("realshots").toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root;
    }

    private JsonObject realshotRequestJson(
            RealshotRequest request,
            Branch branch,
            List<RealshotMedia> media,
            String sessionToken
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", request.id());
        payload.addProperty("branchId", request.branchId());
        payload.addProperty("branchLabel", branch.label() == null || branch.label().isBlank() ? branch.id() : branch.label());
        payload.addProperty("branchWorldName", branch.worldName());
        payload.addProperty("requesterUuid", request.requesterUuid().toString());
        payload.addProperty("requesterName", request.requesterName());
        payload.addProperty("question", request.question());
        payload.addProperty("createdAt", request.createdAt().toString());
        var mediaArray = new com.google.gson.JsonArray();
        for (RealshotMedia item : media) {
            mediaArray.add(realshotMediaJson(item, sessionToken));
        }
        payload.add("media", mediaArray);
        return payload;
    }

    private JsonObject realshotMediaJson(RealshotMedia media, String sessionToken) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", media.id());
        payload.addProperty("requestId", media.requestId());
        payload.addProperty("branchId", media.branchId());
        payload.addProperty("uploaderUuid", media.uploaderUuid().toString());
        payload.addProperty("uploaderName", media.uploaderName());
        payload.addProperty("fileName", media.fileName());
        payload.addProperty("mimeType", media.mimeType());
        payload.addProperty("fileSize", media.fileSize());
        payload.addProperty("createdAt", media.createdAt().toString());
        payload.addProperty("url", "/api/realshots/media/" + media.id() + "?token=" + urlEncode(sessionToken));
        payload.addProperty("kind", media.mimeType().startsWith("video/") ? "video" : "image");
        return payload;
    }

    private String normalizeFileName(String fileName) {
        String normalized = fileName == null || fileName.isBlank() ? "upload" : fileName.trim();
        normalized = normalized.replace('\\', '_').replace('/', '_');
        if (normalized.length() > 120) {
            normalized = normalized.substring(0, 120);
        }
        return normalized;
    }

    private String normalizeRealshotMimeType(String mimeType) {
        String normalized = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("image/") && !normalized.startsWith("video/")) {
            throw new IllegalStateException("仅支持图片或视频文件");
        }
        return normalized;
    }

    private String extensionFor(String fileName, String mimeType) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < fileName.length() - 1 && fileName.length() - dotIndex <= 8) {
            return fileName.substring(dotIndex).replaceAll("[^A-Za-z0-9.]", "").toLowerCase(Locale.ROOT);
        }
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            default -> "";
        };
    }

    private String requireSessionTokenOrQuery(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null && !authorization.isBlank()) {
            return requireSessionToken(exchange);
        }
        String token = queryParam(exchange, "token");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("缺少 Authorization 头或 token 参数");
        }
        return token;
    }

    private String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (!name.equals(key)) {
                continue;
            }
            return parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
        }
        return null;
    }

    private String sanitizeHeaderValue(String value) {
        if (value == null || value.isBlank()) {
            return "download";
        }
        return value.replace("\r", "").replace("\n", "").replace("\"", "'");
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean handleOptions(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }
        applyCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private boolean isGet(HttpExchange exchange) {
        return "GET".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private boolean isPost(HttpExchange exchange) {
        return "POST".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
    }

    private void sendNotFound(HttpExchange exchange) throws IOException {
        sendJson(exchange, 404, "{\"error\":\"not_found\"}");
    }

    private void sendServerError(HttpExchange exchange, String code, Exception exception) throws IOException {
        plugin.getLogger().log(Level.WARNING, "Web 请求处理失败: " + code, exception);
        sendJson(exchange, 500, "{\"error\":" + jsonString(code) + "}");
    }

    private void sendClientError(HttpExchange exchange, String code, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", code);
        body.addProperty("message", message);
        sendJson(exchange, 400, body);
    }

    private void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        applyCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int statusCode, JsonElement body) throws IOException {
        sendJson(exchange, statusCode, gson.toJson(body));
    }

    private void applyCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private void handleAiRequest(HttpExchange exchange, String expectedMethod, AiHandler handler) throws IOException {
        try {
            if (handleOptions(exchange)) {
                return;
            }
            if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange);
                return;
            }
            sendJson(exchange, 200, handler.handle());
        } catch (IllegalStateException exception) {
            sendClientError(exchange, "bad_request", exception.getMessage());
        } catch (Exception exception) {
            sendServerError(exchange, "ai_request_failed", exception);
        }
    }

    private JsonObject readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            String rawBody = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            if (rawBody.isBlank()) {
                return new JsonObject();
            }
            JsonElement parsed = JsonParser.parseString(rawBody);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("请求体必须是 JSON 对象");
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException exception) {
            throw new IllegalStateException("请求体不是合法 JSON", exception);
        }
    }

    private String requireSessionToken(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || authorization.isBlank()) {
            throw new IllegalStateException("缺少 Authorization 头");
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new IllegalStateException("Authorization 头格式错误");
        }
        return authorization.substring(prefix.length()).trim();
    }

    private String getString(JsonObject object, String fieldName) {
        if (!object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            return null;
        }
        return object.get(fieldName).getAsString();
    }

    private AiImageInput parseImage(JsonObject body) {
        if (!body.has("image") || body.get("image").isJsonNull()) {
            return null;
        }
        if (!body.get("image").isJsonObject()) {
            throw new IllegalStateException("image 字段必须是对象");
        }
        JsonObject image = body.getAsJsonObject("image");
        return new AiImageInput(
                getString(image, "fileName"),
                getString(image, "mimeType"),
                getString(image, "base64Data")
        );
    }

    private int resolveLimit(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return pluginConfig.webRecentLimit();
        }

        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (!"limit".equals(key)) {
                continue;
            }
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            try {
                int parsed = Integer.parseInt(value);
                return Math.max(1, Math.min(MAX_LIMIT, parsed));
            } catch (NumberFormatException ignored) {
                return pluginConfig.webRecentLimit();
            }
        }

        return pluginConfig.webRecentLimit();
    }

    private String serializeBranchList(String eventType, List<Branch> branches) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < branches.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(serializeBranch(eventType, branches.get(index)));
        }
        return builder.append(']').toString();
    }

    private String serializeBranch(String eventType, Branch branch) {
        Instant eventAt = switch (eventType) {
            case "CREATED" -> branch.createdAt();
            case "SUBMITTED" -> branch.submittedAt();
            case "MERGED" -> branch.mergedAt();
            default -> branch.createdAt();
        };

        return "{"
                + "\"branchId\":" + jsonString(branch.id()) + ","
                + "\"eventType\":" + jsonString(eventType) + ","
                + "\"eventAt\":" + jsonString(eventAt == null ? null : eventAt.toString()) + ","
                + "\"ownerName\":" + jsonString(branch.ownerName()) + ","
                + "\"ownerUuid\":" + jsonString(branch.ownerUuid().toString()) + ","
                + "\"branchLabel\":" + jsonString(branch.label()) + ","
                + "\"worldName\":" + jsonString(branch.worldName()) + ","
                + "\"mainWorld\":" + jsonString(branch.mainWorld()) + ","
                + "\"status\":" + jsonString(branch.status().dbValue()) + ","
                + "\"createdAt\":" + jsonString(branch.createdAt().toString()) + ","
                + "\"submittedAt\":" + jsonString(branch.submittedAt() == null ? null : branch.submittedAt().toString()) + ","
                + "\"mergedAt\":" + jsonString(branch.mergedAt() == null ? null : branch.mergedAt().toString()) + ","
                + "\"mergedByUuid\":" + jsonString(branch.mergedBy() == null ? null : branch.mergedBy().toString()) + ","
                + "\"mergeMessage\":" + jsonString(branch.mergeMessage())
                + "}";
    }

    private String serializeLeaderboard(List<PlayerMergeLeaderboardEntry> entries) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            PlayerMergeLeaderboardEntry entry = entries.get(index);
            builder.append("{")
                    .append("\"playerUuid\":").append(jsonString(entry.playerUuid().toString())).append(',')
                    .append("\"playerName\":").append(jsonString(entry.playerName())).append(',')
                    .append("\"totalChangedBlocks\":").append(entry.totalChangedBlocks()).append(',')
                    .append("\"mergedBranchCount\":").append(entry.mergedBranchCount()).append(',')
                    .append("\"lastMergedAt\":").append(jsonString(entry.lastMergedAt() == null ? null : entry.lastMergedAt().toString()))
                    .append('}');
        }
        return builder.append(']').toString();
    }

    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }

        StringBuilder builder = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    private String detectContentType(Path file) throws IOException {
        String probed = Files.probeContentType(file);
        if (probed != null && !probed.isBlank()) {
            if (probed.startsWith("text/")) {
                return probed + "; charset=UTF-8";
            }
            return probed;
        }

        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (fileName.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (fileName.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        if (fileName.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        if (fileName.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    private final class WebThreadFactory implements ThreadFactory {

        private final AtomicInteger threadCounter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "WorldGit-Web-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    @FunctionalInterface
    private interface AiHandler {
        JsonObject handle() throws Exception;
    }
}
