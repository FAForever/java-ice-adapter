package com.faforever.iceadapter.ice.base;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.services.RpcConnection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of {@link RpcConnection} that delivers {@link CandidatesMessage}
 * directly to target peers via {@link Peer#iceMessageFromRPC}.
 *
 * <p>Peers are registered through {@link #registerPeer(int, Peer)} which stores them
 * in an internal map keyed by {@code peerId}. Messages are routed using destination IDs.
 * Messages arriving before registration are buffered and delivered upon registration.
 */
public class InMemoryRpcBus implements RpcConnection {

    private final Map<Integer, Peer> peersById = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> peersConnected = new ConcurrentHashMap<>();
    private final Map<Integer, List<CandidatesMessage>> pendingCandidates = new ConcurrentHashMap<>();
    private volatile boolean running;

    public InMemoryRpcBus() {
        running = true;
    }

    /**
     * Register a peer with this bus. The peer will receive messages
     * addressed to its {@code peerId}, including any buffered messages.
     */
    public synchronized void registerPeer(int peerId, Peer peer) {
        peersById.put(peerId, peer);
        List<CandidatesMessage> candidatesMessages = pendingCandidates.remove(peerId);
        if (candidatesMessages != null) {
            for (CandidatesMessage msg : candidatesMessages) {
                peer.iceMessageFromRPC(msg);
            }
        }
    }

    /**
     * Unregister a peer.
     */
    public synchronized void unregisterPeer(int peerId) {
        peersById.remove(peerId);
        pendingCandidates.remove(peerId);
    }

    /**
     * Start the bus. For InMemoryRpcBus this is a no-op as delivery is synchronous,
     * but kept for API compatibility with planned async variants.
     */
    public void start() {
        // Synchronous delivery — no thread needed
    }

    /**
     * Stop the bus.
     */
    public synchronized void stop() {
        running = false;
        peersById.clear();
        pendingCandidates.clear();
    }

    @Override
    public synchronized void sendToRpc(CandidatesMessage message) {
        if (!running) {
            return;
        }
        Peer target = peersById.get(message.destId());
        if (target != null) {
            target.iceMessageFromRPC(message);
        } else {
            pendingCandidates
                    .computeIfAbsent(message.destId(), k -> new CopyOnWriteArrayList<>())
                    .add(message);
        }
    }

    @Override
    public void onConnected(Peer peer, boolean connected) {
        peersConnected.put(peer.getFromId(), connected);
    }
}
