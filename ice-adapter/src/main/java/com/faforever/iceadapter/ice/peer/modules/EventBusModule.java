package com.faforever.iceadapter.ice.peer.modules;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class EventBusModule implements ModuleBase, PeerEventListener {
    private final CopyOnWriteArrayList<PeerEventListener> listeners = new CopyOnWriteArrayList<>();

    private final Peer peer;

    public void register(PeerEventListener listener) {
        listeners.add(listener);
        log.debug("EventListener registered: {}", listener.getClass().getSimpleName());
    }

    public void unregister(PeerEventListener listener) {
        listeners.remove(listener);
        log.debug("EventListener unregistered: {}", listener.getClass().getSimpleName());
    }

    public void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
        log.info("Peer {} change iceState {} -> {}", peer.getPeerIdentifier(), oldState, newState);
        listeners.forEach(
                l -> callMethod(peer, "onIceStateChange", () -> l.onIceStateChange(peer, oldState, newState)));
    }

    @Override
    public void onConnectingChange(Peer peer, boolean connecting) {
        log.trace("Peer {} change connecting -> {}", peer.getPeerIdentifier(), connecting);
        listeners.forEach(l -> callMethod(peer, "onConnectingChange", () -> l.onConnectingChange(peer, connecting)));
    }

    @Override
    public void onCombinationChange(Peer peer, AllowCombination combination) {
        log.info("Peer {} change combination -> {}", peer.getPeerIdentifier(), combination);
        listeners.forEach(l -> callMethod(peer, "onCombinationChange", () -> l.onCombinationChange(peer, combination)));
    }

    @Override
    public void onIceMessageFromRPC(Peer peer, CandidatesMessage message) {
        log.info("Peer {} on ice message from rpc {}", peer.getPeerIdentifier(), message);
        listeners.forEach(l -> callMethod(peer, "onIceMessageFromRPC", () -> l.onIceMessageFromRPC(peer, message)));
    }

    @Override
    public void onSendToRpc(Peer peer, CandidatesMessage message) {
        log.trace("Peer {} on send to rpc {}", peer.getPeerIdentifier(), message);
        listeners.forEach(l -> callMethod(peer, "onSendToRpc", () -> l.onSendToRpc(peer, message)));
    }

    @Override
    public void onHandleData(Peer peer, byte[] data) {
        log.trace("Peer {} handle data. data.length={}", peer.getPeerIdentifier(), data.length);
        listeners.forEach(l -> callMethod(peer, "onHandleData", () -> l.onHandleData(peer, data)));
    }

    @Override
    public void onHandleCommand(Peer peer, CommandBase command) {
        log.trace("Peer {} onHandleCommand {}", peer.getPeerIdentifier(), command);
        listeners.forEach(l -> callMethod(peer, "onHandleCommand", () -> l.onHandleCommand(peer, command)));
    }

    @Override
    public void onChangeEcho(Peer peer, Long lastEcho, long echo) {
        listeners.forEach(l -> callMethod(peer, "onChangeEcho", () -> l.onChangeEcho(peer, lastEcho, echo)));
    }

    public void onLastPacketReceived(Peer peer, Long lastTimestamp, Long timestamp) {
        log.trace("Peer {} change lastPacketReceived {} -> {}", peer.getPeerIdentifier(), lastTimestamp, timestamp);
        listeners.forEach(l ->
                callMethod(peer, "onLastPacketReceived", () -> l.onLastPacketReceived(peer, lastTimestamp, timestamp)));
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data) {
        log.trace("Peer {} onSendToPeer data with length {}", peer.getPeerIdentifier(), data.length);
        listeners.forEach(l -> callMethod(peer, "onSendToPeer", () -> l.onSendToPeer(peer, data)));
    }

    @Override
    public void onSendCommand(Peer peer, CommandBase command, boolean force) {
        log.info("Peer {} onSendCommand {} {}", peer.getPeerIdentifier(), command, force);
        listeners.forEach(l -> callMethod(peer, "onSendCommand", () -> l.onSendCommand(peer, command, force)));
    }

    @Override
    public void onConnectionLost(Peer peer, boolean clearIceState) {
        log.info("Peer onConnectionLost: {} {}", peer.getPeerIdentifier(), clearIceState);
        listeners.forEach(l -> callMethod(peer, "onConnectionLost", () -> l.onConnectionLost(peer, clearIceState)));
    }

    @Override
    public void onClose(Peer peer, boolean hasClosed) {
        log.info("Peer closed: {}", peer.getPeerIdentifier());
        listeners.forEach(l -> callMethod(peer, "onClose", () -> l.onClose(peer, hasClosed)));
    }

    private void callMethod(Peer peer, String methodName, Runnable call) {
        String name = Thread.currentThread().getName();
        try {
            Thread.currentThread().setName("%s|%s".formatted(methodName, peer.getPeerIdentifier()));
            call.run();
        } finally {
            Thread.currentThread().setName(name);
        }
    }

    @Override
    public void stop() {
        if (!peer.isClosing()) {
            return;
        }

        listeners.clear();
    }
}
