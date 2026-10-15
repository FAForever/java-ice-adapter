package com.faforever.iceadapter.webrtc;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handler that routes WebRTC connect service calls to the appropriate implementation.
 * Replaces ConnectServiceHandler when --transport=webrtc is used.
 */
@Slf4j
@RequiredArgsConstructor
public class WebRtcConnectServiceHandler implements WebRtcConnectService {
    private final WebRtcConnectService controlledConnectService;
    private final WebRtcConnectService notControlledConnectService;

    @Override
    public void onChangeIceState(Peer peer, IceState oldState, IceState iceState) {
        if (peer == null) {
            return;
        }

        if (peer.isLocalOffer()) {
            controlledConnectService.onChangeIceState(peer, oldState, iceState);
        } else {
            notControlledConnectService.onChangeIceState(peer, oldState, iceState);
        }
    }

    @Override
    public void onConnectionLost(Peer peer, boolean clearIceState) {
        if (peer == null) {
            return;
        }

        if (peer.isLocalOffer()) {
            controlledConnectService.onConnectionLost(peer, clearIceState);
        } else {
            notControlledConnectService.onConnectionLost(peer, clearIceState);
        }
    }

    @Override
    public void onMessageFromRPC(Peer peer, Object message) {
        if (message == null || peer == null) {
            return;
        }

        if (peer.isLocalOffer()) {
            controlledConnectService.onMessageFromRPC(peer, message);
        } else {
            notControlledConnectService.onMessageFromRPC(peer, message);
        }
    }
}
