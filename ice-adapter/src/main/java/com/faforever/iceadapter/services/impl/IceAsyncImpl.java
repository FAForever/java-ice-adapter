package com.faforever.iceadapter.services.impl;

import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.util.Task;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class IceAsyncImpl implements IceAsync {
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;

    @Override
    public CompletableFuture<Void> runAsync(boolean maxPriority, String methodName, Peer peer, Runnable runnable) {
        return CompletableFuture.runAsync(doBeforeRun(maxPriority, methodName, peer, runnable), executorService);
    }

    @Override
    public CompletableFuture<Void> runAsync(Peer peer, Runnable runnable) {
        return CompletableFuture.runAsync(doBeforeRun(false, peer, runnable), executorService);
    }

    @Override
    public void runAsyncDelay(Peer peer, Runnable runnable, int delayMs) {
        scheduledExecutorService.schedule(initBeforeRun(null, peer, runnable), delayMs, TimeUnit.MILLISECONDS);
    }

    private Runnable initBeforeRun(String methodName, Peer peer, Runnable runnable) {
        return () -> {
            String name = Thread.currentThread().getName();
            try {
                Thread.currentThread().setName(createNameForThread(methodName, getPeerName(peer)));
                runnable.run();
            } catch (Throwable t) {
                log.error("Unhandled exception in async task '{}' for peer {}:", methodName, getPeerName(peer), t);
                throw t;
            } finally {
                Thread.currentThread().setName(name);
            }
        };
    }

    private Task doBeforeRun(boolean maxPriority, String methodName, Peer peer, Runnable runnable) {
        return new Task(maxPriority, peer, initBeforeRun(methodName, peer, runnable));
    }

    private Task doBeforeRun(boolean maxPriority, Peer peer, Runnable runnable) {
        return new Task(maxPriority, peer, initBeforeRun(null, peer, runnable));
    }

    private String createNameForThread(Object... args) {
        StringJoiner joiner = new StringJoiner("-");
        for (Object arg : args) {
            if (arg != null) {
                joiner.add(String.valueOf(arg));
            }
        }
        return joiner.toString();
    }

    private String getPeerName(Peer peer) {
        return Optional.ofNullable(peer).map(Peer::getPeerIdentifier).orElse(null);
    }
}
