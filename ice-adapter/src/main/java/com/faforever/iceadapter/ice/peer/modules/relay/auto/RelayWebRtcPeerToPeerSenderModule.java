package com.faforever.iceadapter.ice.peer.modules.relay.auto;

import static com.faforever.iceadapter.ice.peer.modules.other.PeerConnectivityCheckerModule.COMMAND_ECHO;

import com.faforever.iceadapter.dto.FullRelayMessage;
import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.modules.webrtc.WebRtcPeerToPeerSenderModule;
import com.faforever.iceadapter.util.CollectionUtils;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RelayWebRtcPeerToPeerSenderModule extends WebRtcPeerToPeerSenderModule {

    public static final char COMMAND_AUTO_RELAY = 'R';

    public RelayWebRtcPeerToPeerSenderModule(Peer peer) {
        super(peer);
    }

    @Override
    public void onSendToPeer(Peer peer, byte[] data) {

        if (data[0] == COMMAND_AUTO_RELAY || data[0] == COMMAND_ECHO) {
            if (peer.isConnected()) {
                sendViaWebRtc(data);
            }
            return;
        }

        if (peer.isAdditionalPacketForwarding()) {
            if (peer.isConnected() && peer.existBestRelays()) {
                sendViaWebRtc(data);
                trySendRelay(data);
            } else if (peer.isConnected()) {
                sendViaWebRtc(data);
            } else if (peer.existBestRelays()) {
                trySendRelay(data);
            }
            return;
        }

        sendViaWebRtc(data);
    }

    @Override
    public void onSendCommand(Peer peer, CommandBase command, boolean force) {
        byte[] data = command.bytes();
        if (command.isOnlyDirect()) {
            sendViaWebRtc(data);
            return;
        }

        sendViaWebRtc(data);
    }

    @Override
    public void onHandleData(Peer peer, byte[] data) {
        if (data[0] != COMMAND_AUTO_RELAY) {
            return;
        }
        handleRelayMsg(data);
    }

    private void handleRelayMsg(byte[] data) {
        FullRelayMessage relayMessage = FullRelayMessage.fromBytes(data, 1, data.length - 1);
        if (relayMessage == null) {
            log.warn("AutoRelay: message is null from peer {}", peer.getPeerIdentifier());
            return;
        }

        IceGameSession gameSession = peer.getGameSession();
        if (gameSession == null) {
            return;
        }

        if (relayMessage.targetId() == peer.getFromId()) {
            Peer fromMsg = gameSession.getPeer(relayMessage.fromId()).orElse(null);
            if (fromMsg == null) {
                return;
            }

            fromMsg.setRelayLastPacketReceived(System.currentTimeMillis());
            fromMsg.handleData(relayMessage.data());
            return;
        }

        Peer target = gameSession.getPeer(relayMessage.targetId()).orElse(null);
        if (target == null) {
            return;
        }
        target.sendToPeer(data);
    }

    private void trySendRelay(byte[] data) {
        if (!isEnabled()) {
            return;
        }

        Peer relay = getPeerForRelay();

        if (relay == null) {
            return;
        }

        FullRelayMessage relayMessage = new FullRelayMessage(peer.getFromId(), peer.getRemoteId(), data);
        byte[] messageBytes = relayMessage.toBytes((byte) COMMAND_AUTO_RELAY);
        relay.sendToPeer(messageBytes);
    }

    private Peer getPeerForRelay() {
        IceGameSession gameSession = peer.getGameSession();
        if (gameSession == null) {
            return null;
        }

        List<Integer> ids = peer.getBestRelays();
        if (CollectionUtils.isEmpty(ids)) {
            return null;
        }

        for (Integer id : peer.getBestRelays()) {
            Peer relay = gameSession.getPeer(id).orElse(null);

            if (relay == null || !relay.isConnected() || !relay.isAllowRelay()) {
                continue;
            }
            return relay;
        }

        return null;
    }
}
