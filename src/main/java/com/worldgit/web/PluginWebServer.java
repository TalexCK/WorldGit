package com.worldgit.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.BranchRepository;
import com.worldgit.model.Branch;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final BranchRepository branchRepository;

    private HttpServer httpServer;
    private ExecutorService executor;

    public PluginWebServer(
            WorldGitPlugin plugin,
            PluginConfig pluginConfig,
            BranchRepository branchRepository
    ) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.pluginConfig = Objects.requireNonNull(pluginConfig, "插件配置不能为空");
        this.branchRepository = Objects.requireNonNull(branchRepository, "分支仓储不能为空");
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
            httpServer.createContext("/api/activity/recent", this::handleRecentActivity);
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

    private void applyCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
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
}
