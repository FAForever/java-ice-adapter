package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.webrtc.WebRtcConnectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class IceTrigger implements PeerEventListener {
    private final IceAsync iceAsync;
    private final WebRtcConnectService webRtcConnectService;
    private final RpcConnection rpcConnection;

    @Override
    public void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
        if (peer.isDisableConnectService()) {
            return;
        }

        iceAsync.runAsync(false, "onIceStateChange", peer, () -> {
            if (webRtcConnectService != null) {
                webRtcConnectService.onChangeIceState(peer, oldState, newState);
            }
        });
    }

    @Override
    public void onConnectionLost(Peer peer, boolean clearIceState) {
        iceAsync.runAsync(true, "onConnectionLost", peer, () -> {
            if (webRtcConnectService != null) {
                webRtcConnectService.onConnectionLost(peer, clearIceState);
            }
        });
    }

    @Override
    public void onIceMessageFromRPC(Peer peer, CandidatesMessage message) {
        if (peer.isDisableConnectService()) {
            return;
        }

        iceAsync.runAsync(true, "onIceMessageFromRPC", peer, () -> {
            if (webRtcConnectService != null) {
                webRtcConnectService.onMessageFromRPC(peer, message);
            }
        });
    }

    @Override
    public void onConnectingChange(Peer peer, boolean connecting) {
        rpcConnection.onConnected(peer, connecting);
    }

    @Override
    public void onSendToRpc(Peer peer, CandidatesMessage message) {
        rpcConnection.sendToRpc(message);
    }
}
