package com.faforever.iceadapter;

import com.faforever.iceadapter.exception.AsyncException;
import lombok.experimental.UtilityClass;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.Supplier;

@UtilityClass
public class AsyncService {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CompletableFuture<Void> runAsync(Runnable task) {
        return callAsync(() -> {
            task.run();
            return null;
        });
    }

    public CompletableFuture<Void> runAsync(Runnable task, String nameThread) {
        return callAsync(() -> {
            Thread.currentThread().setName(nameThread);
            task.run();
            return null;
        });
    }

    public <T> CompletableFuture<T> callAsync(Callable<T> task) {
        return supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new AsyncException(e);
            }
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
        runAsync(() -> {
            try {
                if (timeMs > 0) {
                    Thread.sleep(timeMs);
                }
                runnable.run();
            } catch (InterruptedException e) {
                throw new AsyncException(e);
            }
        });
    }

    public void executeWithLock(Lock lock, Runnable task) {
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        executor.shutdown();
    }
}
