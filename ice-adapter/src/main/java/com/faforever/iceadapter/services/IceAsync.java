package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.peer.Peer;
import java.util.concurrent.CompletableFuture;

public interface IceAsync {

    CompletableFuture<Void> runAsync(boolean maxPriority, String methodName, Peer peer, Runnable runnable);

    CompletableFuture<Void> runAsync(Peer peer, Runnable runnable);

    void runAsyncDelay(Peer peer, Runnable runnable, int delayMs);
}
