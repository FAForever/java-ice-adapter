package com.faforever.iceadapter.webrtc;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;

/**
 * WebRTC-based connect service interface.
 * Replaces ConnectService when --transport=webrtc is used.
 */
public interface WebRtcConnectService {
    void onChangeIceState(Peer peer, IceState oldState, IceState iceState);

    void onConnectionLost(Peer peer, boolean clearIceState);

    void onMessageFromRPC(Peer peer, Object message);
}
