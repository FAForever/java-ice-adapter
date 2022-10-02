package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.ice.Peer;
import com.nbarraille.jjsonrpc.JJsonPeer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DebugFacade implements Debugger {
    private final List<Debugger> debuggers = new ArrayList<>();

    public void add(Debugger debugger) {
        debuggers.add(debugger);
    }

    @Override
    public void startupComplete() {
        debuggers.forEach(Debugger::startupComplete);
    }

    @Override
    public void rpcStarted(CompletableFuture<JJsonPeer> peerFuture) {
        debuggers.forEach(d -> d.rpcStarted(peerFuture));
    }

    @Override
    public void gpgnetStarted() {
        debuggers.forEach(Debugger::gpgnetStarted);
    }

    @Override
    public void gpgnetConnectedDisconnected() {
        debuggers.forEach(Debugger::gpgnetConnectedDisconnected);
    }

    @Override
    public void gameStateChanged() {
        debuggers.forEach(Debugger::gameStateChanged);
    }

    @Override
    public void connectToPeer(int id, String login, boolean localOffer) {
        debuggers.forEach(d -> d.connectToPeer(id, login, localOffer));
    }

    @Override
    public void disconnectFromPeer(int id) {
        debuggers.forEach(d -> d.disconnectFromPeer(id));
    }

    @Override
    public void peerStateChanged(Peer peer) {
        debuggers.forEach(d -> d.peerStateChanged(peer));
    }

    @Override
    public void peerConnectivityUpdate(Peer peer) {
        debuggers.forEach(d -> d.peerConnectivityUpdate(peer));
    }
}
