package com.worldgit.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainThreadBridge {

    private final JavaPlugin plugin;
    private final Duration timeout;

    public MainThreadBridge(JavaPlugin plugin, Duration timeout) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.timeout = Objects.requireNonNull(timeout, "超时时间不能为空");
    }

    public <T> T call(Callable<T> callable) {
        Objects.requireNonNull(callable, "主线程任务不能为空");
        try {
            if (Bukkit.isPrimaryThread()) {
                return callable.call();
            }
            Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, callable);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Bukkit 主线程任务时被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("执行 Bukkit 主线程任务失败", cause);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("等待 Bukkit 主线程任务超时", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("执行 Bukkit 主线程任务失败", exception);
        }
    }

    public void run(Runnable runnable) {
        call(() -> {
            runnable.run();
            return null;
        });
    }
}
