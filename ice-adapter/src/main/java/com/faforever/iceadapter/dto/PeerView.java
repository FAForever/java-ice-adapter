package com.faforever.iceadapter.dto;

import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerEventListener;
import com.faforever.iceadapter.ice.peer.RelayPing;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.util.CollectionUtils;
import com.faforever.iceadapter.util.Pair;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.beans.property.*;
import lombok.Data;

@Data
public class PeerView implements PeerEventListener {
    private final IntegerProperty id = new SimpleIntegerProperty();
    private final StringProperty login = new SimpleStringProperty();
    private final BooleanProperty connected = new SimpleBooleanProperty();
    private final StringProperty localCand = new SimpleStringProperty();
    private final StringProperty remoteCand = new SimpleStringProperty();
    private final StringProperty pairConnection = new SimpleStringProperty();
    private final StringProperty state = new SimpleStringProperty();
    private final StringProperty offer = new SimpleStringProperty();
    private final StringProperty directRtt = new SimpleStringProperty();
    private final StringProperty relayRtt = new SimpleStringProperty();
    private final StringProperty relayLogin = new SimpleStringProperty();
    private final StringProperty lastRecv = new SimpleStringProperty();
    private final StringProperty lastRelayRecv = new SimpleStringProperty();
    private final StringProperty echosReceived = new SimpleStringProperty();
    private final BooleanProperty peerRelaySupport = new SimpleBooleanProperty();
    private final AdditionalInfo additionalInfo = new AdditionalInfo();

    @Data
    public static class AdditionalInfo {
        private final BooleanProperty allowHost = new SimpleBooleanProperty();
        private final BooleanProperty allowReflexive = new SimpleBooleanProperty();
        private final BooleanProperty allowRelay = new SimpleBooleanProperty();
        private AllowCombination combination = AllowCombination.ALL;
        private final BooleanProperty sendDirectAndRelay = new SimpleBooleanProperty(true);
    }

    public PeerView(int id, String login) {
        this.id.set(id);
        this.login.set(login);
    }

    @Override
    public void onIceStateChange(Peer peer, IceState oldState, IceState newState) {
        update(peer);
    }

    @Override
    public void onConnectingChange(Peer peer, boolean connecting) {
        update(peer);
    }

    @Override
    public void onLastPacketReceived(Peer peer, Long lastTimestamp, Long timestamp) {
        update(peer);
    }

    @Override
    public void onCombinationChange(Peer peer, AllowCombination combination) {
        update(peer);
    }

    public void update(Peer peer) {
        getConnected().set(peer.isConnected());

        getPairConnection().set(peer.getStrCandidateTypes("\n"));

        List<Pair<String, String>> pairs = peer.getCandidateTypes();
        if (pairs != null && !pairs.isEmpty()) {
            Pair<String, String> firstPair = pairs.get(0);
            getLocalCand().set(firstPair.first() != null && !firstPair.first().isEmpty() ? firstPair.first() : "-");
            getRemoteCand()
                    .set(firstPair.second() != null && !firstPair.second().isEmpty() ? firstPair.second() : "-");
        } else {
            getLocalCand().set("-");
            getRemoteCand().set("-");
        }

        getState().set(String.valueOf(peer.getState()));

        getOffer().set(String.valueOf(peer.isLocalOffer()));

        String direct = peer.getAverageRtt()
                .filter(r -> r >= 0)
                .map(Math::round)
                .map(String::valueOf)
                .orElse("–");
        getDirectRtt().set(direct);

        Map<Integer, RelayPing> rtts = peer.getRtts();
        List<Integer> ids = peer.getBestRelays().stream().limit(1).toList();
        String relay = "–";
        String bestRelayLogin = "";
        if (!CollectionUtils.isEmpty(ids)) {
            for (Integer idPeer : ids) {
                RelayPing ping = rtts.get(idPeer);
                if (ping != null && ping.isActual()) {
                    relay = String.valueOf(Math.round(ping.getRtt()));
                    bestRelayLogin = ping.getRemoteLogin();
                    break;
                }
            }
        }
        getRelayRtt().set(relay);
        getRelayLogin().set(bestRelayLogin);
        getLastRecv()
                .set(peer.getLastReceived().map(PeerView::formatElapsedTime).orElse("never"));
        getLastRelayRecv()
                .set(peer.getRelayLastReceived()
                        .map(ts -> {
                            long elapsed = (System.currentTimeMillis() - ts) / 1000;
                            return elapsed <= 30 ? formatElapsedTime(ts) : "";
                        })
                        .orElse(""));
        getEchosReceived()
                .set("%s/%s"
                        .formatted(
                                String.valueOf(peer.countEchosReceived()),
                                String.valueOf(peer.countInvalidEchosReceived())));
        getPeerRelaySupport().set(peer.isAllowRelay());

        AllowCombination combination = peer.getCombination();
        getAdditionalInfo().getAllowHost().set(combination.isAllowHost());
        getAdditionalInfo().getAllowReflexive().set(combination.isAllowReflexive());
        getAdditionalInfo().getAllowRelay().set(combination.isAllowRelay());
        getAdditionalInfo().setCombination(combination);

        getAdditionalInfo().getSendDirectAndRelay().set(peer.isAdditionalPacketForwarding());
    }

    public static String formatElapsedTime(long timestamp) {
        long elapsedMs = System.currentTimeMillis() - timestamp;
        if (elapsedMs < 1000) {
            return "< 1s ago";
        }
        long elapsedSec = elapsedMs / 1000;
        if (elapsedSec < 60) {
            return elapsedSec + "s ago";
        }
        long elapsedMin = elapsedSec / 60;
        if (elapsedMin < 60) {
            return elapsedMin + "m ago";
        }
        long elapsedHours = elapsedMin / 60;
        return elapsedHours + "h ago";
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        PeerView peerView = (PeerView) object;
        return Objects.equals(id.get(), peerView.id.get());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id.get());
    }
}
