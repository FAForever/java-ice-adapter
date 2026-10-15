package com.faforever.iceadapter.dto.command.relay.auto.info;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.RelayPing;
import java.util.Map;
import java.util.Objects;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false)
public class RelayPingCommand extends CommandBase {
    private int fromId;
    private int targetId;

    private long echo;
    private boolean toTarget;

    @Override
    public boolean isOnlyDirect() {
        return true;
    }

    @Override
    public void execute(Peer peer) {
        IceGameSession gameSession = peer.getGameSession();
        if (gameSession == null || !peer.isAllowPeerRelay()) {
            return;
        }

        long myId = peer.getFromId();

        if (fromId == myId && !toTarget) {
            gameSession.getPeer(targetId).ifPresent(p -> commandOnFrom(peer, p));
            return;
        } else if (targetId == myId && toTarget) {
            commandOnTarget(peer);
            return;
        }

        gameSession.getPeer(toTarget ? targetId : fromId).ifPresent(target -> commandOnRelayPeer(peer, target));
    }

    private void commandOnFrom(Peer remotePeer, Peer peer) {
        Map<Integer, RelayPing> rtts = peer.getRtts();
        RelayPing relayPing =
                rtts.computeIfAbsent(remotePeer.getRemoteId(), k -> new RelayPing(remotePeer.getRemoteLogin()));
        float oldRtt = relayPing.getRtt();
        long rttMs = System.currentTimeMillis() - echo;
        relayPing.updateRtt(calculateRtt(oldRtt, rttMs));
    }

    private void commandOnTarget(Peer peer) {
        setToTarget(false);
        peer.sendCommand(this);
    }

    private void commandOnRelayPeer(Peer fromPeer, Peer toPeer) {
        if (Objects.equals(fromPeer, toPeer)) {
            return;
        }

        toPeer.sendCommand(this);
    }

    private float calculateRtt(float oldRtt, long rtt) {
        return oldRtt == 0 ? rtt : oldRtt * 0.8f + (float) rtt * 0.2f;
    }
}
