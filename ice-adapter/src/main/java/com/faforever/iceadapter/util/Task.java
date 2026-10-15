package com.faforever.iceadapter.util;

import com.faforever.iceadapter.ice.peer.Peer;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Data;

@Data
public class Task implements Runnable {
    public static final AtomicLong SEQUENCE = new AtomicLong();
    private final int priority;
    private final Runnable task;
    private long seq;

    public Task(boolean maxPriority, Peer peer, Runnable runnable) {
        this.priority = maxPriority || !peer.isLocalOffer() ? Integer.MAX_VALUE : peer.getRemoteId();
        this.task = runnable;
        this.seq = SEQUENCE.incrementAndGet();
    }

    public static Comparator<Runnable> createComparator() {
        return Comparator.comparingInt((Runnable r) -> {
                    if (r instanceof Task t) {
                        return t.getPriority();
                    }

                    return Integer.MAX_VALUE;
                })
                .reversed()
                .thenComparingLong(r -> {
                    if (r instanceof Task t) {
                        return t.getSeq();
                    }
                    return Task.SEQUENCE.incrementAndGet();
                });
    }

    @Override
    public void run() {
        task.run();
    }
}
