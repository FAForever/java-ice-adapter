package com.faforever.iceadapter.ice.peer;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.dto.command.CommandBase;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.IceGameSession;
import com.faforever.iceadapter.ice.IceState;
import com.faforever.iceadapter.ice.ModuleBase;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.ice.peer.modules.EventBusModule;
import com.faforever.iceadapter.ice.peer.modules.other.AutoSettingAllowCandidates;
import com.faforever.iceadapter.util.CollectionUtils;
import com.faforever.iceadapter.util.Pair;
import com.faforever.iceadapter.webrtc.WebRtcSession;
import com.faforever.iceadapter.webrtc.WebRtcSignalingService;
import java.net.DatagramSocket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Represents a peer in the current game session which we are connected to
 */
@Data
@Slf4j
public class Peer {
    private final int fromId;
    private final int remoteId;
    private final String remoteLogin;
    private final boolean localOffer; // Do we offer or are we waiting for a remote offer
    private final int preferredPort;
    private final int lobbyPort;
    private final Set<PeerModule> disabledModules;
    private IceGameSession gameSession;

    private String peerIdentifier;

    private volatile long lastLostConnect = 0;

    private List<Integer> bestRelays = List.of();

    private final Map<Integer, RelayPing> rtts = new ConcurrentHashMap<>();
    private volatile boolean additionalPacketForwarding;

    private volatile float rtt = 0.0f;
    private volatile Long lastEcho;
    private volatile Long lastPacketReceived;
    private volatile Long relayLastPacketReceived;
    private AtomicInteger echosReceived = new AtomicInteger(0);
    private AtomicInteger invalidPacket = new AtomicInteger(0);

    private volatile boolean closing = false;

    private volatile DatagramSocket faSocket;

    private volatile boolean allowPeerRelay;
    private volatile boolean allowRelay = false;
    private volatile boolean connected = false;

    // WebRTC fields
    private volatile WebRtcSession webRtcSession;
    private volatile WebRtcSignalingService webRtcSignalingService;

    private AllowCombination combination = AllowCombination.ALL;
    private boolean disableConnectService = false;

    private final AtomicInteger awaitingCandidatesEventId = new AtomicInteger(0);
    private final AtomicInteger reInitEventId = new AtomicInteger(0);
    private volatile IceState iceState = null;

    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    private final Map<PeerModule, ModuleBase> modules = new ConcurrentHashMap<>();

    public Peer(
            int fromId,
            int remoteId,
            String remoteLogin,
            boolean localOffer,
            int preferredPort,
            int lobbyPort,
            boolean allowPeerRelay,
            boolean additionalPacketForwarding,
            Set<PeerModule> disabledModules) {
        this.fromId = fromId;
        this.remoteId = remoteId;
        this.remoteLogin = remoteLogin;
        this.localOffer = localOffer;
        this.preferredPort = preferredPort;
        this.lobbyPort = lobbyPort;
        this.allowPeerRelay = allowPeerRelay;
        this.additionalPacketForwarding = additionalPacketForwarding;
        this.disabledModules = disabledModules;
    }

    public Peer(
            int fromId,
            int remoteId,
            String remoteLogin,
            boolean localOffer,
            int preferredPort,
            int lobbyPort,
            boolean allowPeerRelay,
            Set<PeerModule> disabledModules) {
        this(
                fromId,
                remoteId,
                remoteLogin,
                localOffer,
                preferredPort,
                lobbyPort,
                allowPeerRelay,
                false,
                disabledModules);
    }

    public Peer(
            int fromId,
            int remoteId,
            String remoteLogin,
            boolean localOffer,
            int preferredPort,
            int lobbyPort,
            Set<PeerModule> disabledModules) {
        this(fromId, remoteId, remoteLogin, localOffer, preferredPort, lobbyPort, false, disabledModules);
    }

    public Peer(
            int remoteId,
            String remoteLogin,
            boolean localOffer,
            int preferredPort,
            int lobbyPort,
            Set<PeerModule> disabledModules) {
        this(0, remoteId, remoteLogin, localOffer, preferredPort, lobbyPort, false, disabledModules);
    }

    public Integer getLocalPort() {
        return faSocket != null ? faSocket.getLocalPort() : 0;
    }

    public void init() {
        peerIdentifier = "%s(%d)".formatted(remoteLogin, remoteId);
    }

    public void initModules() {
        PeerModule.getSortedModules().stream()
                .filter(Predicate.not(disabledModules::contains))
                .forEach((module) -> {
                    modules.putIfAbsent(module, module.createModule(this));
                });
    }

    public void startModules() {
        for (ModuleBase module : modules.values()) {
            module.start();
        }
    }

    public void stopModules() {
        for (ModuleBase module : modules.values()) {
            module.stop();
        }
    }

    public boolean isConnected() {
        return (iceState == IceState.CONNECTED && webRtcSession != null && webRtcSession.isConnected()) || connected;
    }

    public void startInitPeer() {
        log.debug(
                "Peer created: {}, localOffer: {}, preferredPort: {}", getPeerIdentifier(), localOffer, preferredPort);

        setIceState(IceState.NEW);
    }

    public void setIceState(IceState iceState) {
        IceState old = this.iceState;
        if (Objects.equals(old, iceState)) {
            return;
        }
        this.iceState = iceState;
        event(bus -> bus.onIceStateChange(this, old, iceState));
        debug().peerStateChanged(this);
    }

    public void setLastPacketReceived(Long lastPacketReceived) {
        Long old = this.lastPacketReceived;
        this.lastPacketReceived = lastPacketReceived;
        event(bus -> bus.onLastPacketReceived(this, old, lastPacketReceived));
    }

    public void setIceStateWithoutTrigger(IceState iceState) {
        this.iceState = iceState;
        debug().peerStateChanged(this);
    }

    public Lock getLock(String lockName) {
        return locks.computeIfAbsent(lockName, k -> new ReentrantLock());
    }

    public <T extends ModuleBase> Optional<T> getModule(PeerModule module, Class<T> type) {
        ModuleBase foundModule = modules.get(module);
        if (type != null && type.isInstance(foundModule)) {
            return Optional.of(type.cast(foundModule));
        } else {
            log.warn("Could not find module {} - {}", module, type);
            return Optional.empty();
        }
    }

    private void event(Consumer<EventBusModule> consumer) {
        getEventBus().ifPresent(consumer);
    }

    private Optional<EventBusModule> getEventBus() {
        return getModule(PeerModule.EVENT_BUS, EventBusModule.class);
    }

    public void addEventListener(PeerEventListener listener) {
        getEventBus().ifPresent(bus -> bus.register(listener));
    }

    public void removeEventListener(PeerEventListener listener) {
        getEventBus().ifPresent(bus -> bus.unregister(listener));
    }

    public void setCombination(AllowCombination combination) {
        setCombination(combination, false);
    }

    public void setCombination(AllowCombination combination, boolean disableAutomatic) {
        setCombination(combination, disableAutomatic, disableAutomatic);
    }

    public void setCombination(AllowCombination combination, boolean disableAutomatic, boolean triggerReconnect) {
        AllowCombination oldCombination = this.combination;
        this.combination = combination;
        event(bus -> bus.onCombinationChange(this, combination));

        if (disableAutomatic) {
            getModule(PeerModule.AUTO_SETTING_ALLOW_CANDIDATE, AutoSettingAllowCandidates.class)
                    .ifPresent(ModuleBase::disable);
        }

        if (triggerReconnect && webRtcSession != null && oldCombination != combination) {
            log.info(
                    "AllowCombination changed for WebRTC peer {} from {} to {}, reconnecting",
                    getPeerIdentifier(),
                    oldCombination,
                    combination);
            reconnect();
        }
    }

    public void sendToRpc(CandidatesMessage message) {
        event(bus -> bus.onSendToRpc(this, message));
    }

    public void iceMessageFromRPC(CandidatesMessage message) {
        event(bus -> bus.onIceMessageFromRPC(this, message));
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        event(bus -> bus.onConnectingChange(this, connected));
    }

    public float getRtt() {
        if (webRtcSession != null) {
            return webRtcSession.getStats().getRttMs();
        }
        return rtt;
    }

    public float getEchoRtt() {
        return rtt;
    }

    public List<Pair<String, String>> getCandidateTypes() {
        if (webRtcSession == null) {
            return List.of();
        }

        List<Pair<String, String>> pairs = new ArrayList<>();
        WebRtcSession.SessionStats s = webRtcSession.getStats();
        boolean hasLocal =
                s.getLocalCandidateType() != null && !s.getLocalCandidateType().isEmpty();
        boolean hasRemote = s.getRemoteCandidateType() != null
                && !s.getRemoteCandidateType().isEmpty();
        if (hasLocal || hasRemote) {
            pairs.add(new Pair<>(s.getLocalCandidateType(), s.getRemoteCandidateType()));
        }
        return pairs;
    }

    public String getStrCandidateTypes(String delimiter) {
        StringJoiner pairCandidates = new StringJoiner(delimiter);
        for (Pair<String, String> pair : getCandidateTypes()) {
            pairCandidates.add("%s<->%s".formatted(pair.first(), pair.second()));
        }
        return pairCandidates.toString();
    }

    public IceState getState() {
        return iceState;
    }

    public Optional<Float> getAverageRtt() {
        return Optional.of(getRtt());
    }

    public Optional<Long> getLastReceived() {
        return Optional.ofNullable(getLastPacketReceived());
    }

    public Optional<Long> getRelayLastReceived() {
        return Optional.ofNullable(getRelayLastPacketReceived());
    }

    public Integer countEchosReceived() {
        return echosReceived.get();
    }

    public Integer countInvalidEchosReceived() {
        return invalidPacket.get();
    }

    public void handleData(byte[] data) {
        event(bus -> bus.onHandleData(this, data));
    }

    public void handleCommand(CommandBase command) {
        event(bus -> bus.onHandleCommand(this, command));
    }

    public void sendToPeer(byte[] data) {
        event(bus -> bus.onSendToPeer(this, data));
    }

    public void sendCommand(CommandBase command) {
        sendCommand(command, false);
    }

    public void sendCommand(CommandBase command, boolean force) {
        event(bus -> bus.onSendCommand(this, command, force));
    }

    public void lostConnect(boolean clearIceState) {
        event(bus -> bus.onConnectionLost(this, clearIceState));
    }

    public void lostConnect() {
        lostConnect(false);
    }

    public void reconnect() {
        lostConnect();
    }

    public void setLastEcho(long echo) {
        Long lastEcho = this.lastEcho;
        this.lastEcho = echo;
        event(bus -> bus.onChangeEcho(this, lastEcho, echo));
    }

    public boolean existBestRelays() {
        return !CollectionUtils.isEmpty(getBestRelays());
    }

    public void close() {
        if (closing) {
            return;
        }

        log.info("Closing peer for player {}", getPeerIdentifier());

        closing = true;
        event(bus -> bus.onClose(this, closing));
        for (ModuleBase module : modules.values()) {
            module.stop();
        }

        if (webRtcSession != null) {
            try {
                webRtcSession.close();
            } catch (Exception e) {
                log.warn("Error closing webRtcSession for peer {}", getPeerIdentifier(), e);
            }
            webRtcSession = null;
        }

        log.info("Peer closed: {}", getPeerIdentifier());
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Peer peer)) return false;
        return getRemoteId() == peer.getRemoteId() && getFromId() == peer.getFromId();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getRemoteId(), getFromId());
    }
}
