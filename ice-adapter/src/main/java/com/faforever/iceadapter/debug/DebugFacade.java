package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.telemetry.CoturnServer;
import com.nbarraille.jjsonrpc.JJsonPeer;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

public class DebugFacade implements Debugger {
    private final List<Debugger> debuggers = new CopyOnWriteArrayList<>();

    public void add(Debugger debugger) {
        debuggers.add(debugger);
    }

    public void remove(Debugger debugger) {
        debuggers.remove(debugger);
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

    @Override
    public void updateCoturnList(Collection<CoturnServer> servers) {
        debuggers.forEach(d -> d.updateCoturnList(servers));
    }
}
