package com.faforever.iceadapter.services.impl.rpc;

import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.services.RpcConnection;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class RpcConnectionImpl implements RpcConnection {

    private final RPCService rpcService;

    @Override
    public void sendToRpc(CandidatesMessage message) {
        rpcService.onIceMsg(message);
    }

    @Override
    public void onConnected(Peer peer, boolean connected) {
        rpcService.onConnected(peer.getFromId(), peer.getRemoteId(), connected);
    }
}
