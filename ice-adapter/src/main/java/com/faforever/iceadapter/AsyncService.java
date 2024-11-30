package com.faforever.iceadapter;

import lombok.experimental.UtilityClass;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

@UtilityClass
public class AsyncService {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task, executor);
    }

    public CompletableFuture<Void> runAsync(Runnable task, String nameThread) {
        return runAsync(() -> {
            Thread.currentThread().setName(nameThread);
            task.run();
        });
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    public <T> CompletableFuture<Void> thenAcceptAsync(CompletableFuture<T> future, Consumer<? super T> consumer) {
        return future.thenAcceptAsync(consumer, executor);
    }

    public <T> CompletableFuture<Void> thenAccept(CompletableFuture<T> future, Consumer<? super T> consumer) {
        return future.thenAccept(consumer);
    }

    public <T> CompletableFuture<Void> thenRun(CompletableFuture<T> future, Runnable action) {
        return future.thenRun(action);
    }

    public void executeDelayed(int timeMs, Runnable runnable) {
        CompletableFuture.runAsync(runnable, CompletableFuture.delayedExecutor(timeMs, TimeUnit.MILLISECONDS, executor));
    }

    public void close() {
        executor.shutdown();
        CompletableFuture.runAsync(executor::shutdownNow, CompletableFuture.delayedExecutor(1000, TimeUnit.MILLISECONDS));
    }
}
