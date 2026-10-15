package com.faforever.iceadapter.ice.peer;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;

public interface PeerEventListener {
    default void onIceStateChange(Peer peer, IceState oldState, IceState newState) {}

    default void onConnectingChange(Peer peer, boolean connecting) {}

    default void onCombinationChange(Peer peer, AllowCombination combination) {}

    default void onLastPacketReceived(Peer peer, Long lastTimestamp, Long timestamp) {}

    default void onChangeEcho(Peer peer, Long lastEcho, long echo) {}

    default void onHandleData(Peer peer, byte[] data) {}

    default void onHandleCommand(Peer peer, CommandBase command) {}

    default void onSendToPeer(Peer peer, byte[] data) {}

    default void onSendCommand(Peer peer, CommandBase command, boolean force) {}

    default void onConnectionLost(Peer peer, boolean clearIceState) {}

    default void onClose(Peer peer, boolean hasClosed) {}

    default void onSendToRpc(Peer peer, CandidatesMessage message) {}

    default void onIceMessageFromRPC(Peer peer, CandidatesMessage message) {}
}
