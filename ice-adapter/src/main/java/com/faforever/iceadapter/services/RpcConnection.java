package com.faforever.iceadapter.services;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.peer.Peer;

public interface RpcConnection {
    void sendToRpc(CandidatesMessage message);

    void onConnected(Peer peer, boolean connected);
}
