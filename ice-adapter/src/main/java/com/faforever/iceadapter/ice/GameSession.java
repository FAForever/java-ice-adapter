package com.faforever.iceadapter.ice;

import static com.faforever.iceadapter.debug.Debug.debug;

import com.faforever.iceadapter.IceOptions;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.ice.peer.Peer;
import com.faforever.iceadapter.ice.peer.PeerModule;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.services.IceAsync;
import com.faforever.iceadapter.services.IceTrigger;
import com.faforever.iceadapter.services.MessageService;
import com.faforever.iceadapter.services.RpcConnection;
import com.faforever.iceadapter.services.impl.IceAsyncImpl;
import com.faforever.iceadapter.services.impl.MessageServiceImpl;
import com.faforever.iceadapter.telemetry.CoturnServer;
import com.faforever.iceadapter.util.ExecutorHolder;
import com.faforever.iceadapter.util.Pair;
import com.faforever.iceadapter.webrtc.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Represents a game session and the current ICE status/communication with all peers
 * Is created by a JoinGame or HostGame event (via RPC), is destroyed by a gpgnet connection breakdown
 */
@Slf4j
@RequiredArgsConstructor
public class GameSession implements IceGameSession {

    private static final List<IceServer> iceServers = createIceServers();

    public static List<IceServer> getAllServers() {
        return iceServers;
    }

    @Getter
    private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();

    @Getter
    private IceOptions options;

    private final MessageService messageService = new MessageServiceImpl();
    private final IceAsync iceAsync =
            new IceAsyncImpl(ExecutorHolder.getExecutor(), ExecutorHolder.getScheduledExecutor());
    private IceTrigger iceTrigger;
    private IceServerChecker iceServerChecker;
    private WebRtcConnectService webRtcConnectService;

    @Getter
    @Setter
    private volatile boolean gameEnded = false;

    // WebRTC fields
    @Getter
    private WebRtcConnectionFactory webRtcConnectionFactory;

    /**
     * Primary constructor for WebRTC mode (with TCP RPC).
     */
    public GameSession(RpcConnection rpcConnection, IceOptions options) {
        this(options, WebRtcConnectionFactory.getInstance(), rpcConnection);
    }

    /**
     * Constructor for WebRTC mode with custom factory.
     */
    public GameSession(
            IceOptions options, WebRtcConnectionFactory webRtcConnectionFactory, RpcConnection rpcConnection) {
        init(options);
        this.webRtcConnectionFactory = webRtcConnectionFactory;

        WebRtcConnectService controlledService = new WebRtcConnectServiceControlledImpl(messageService, this, iceAsync);
        WebRtcConnectService notControlledService =
                new WebRtcConnectServiceNotControlledImpl(messageService, this, iceAsync);
        this.webRtcConnectService = new WebRtcConnectServiceHandler(controlledService, notControlledService);

        this.iceTrigger = new IceTrigger(iceAsync, webRtcConnectService, rpcConnection);
    }

    private void init(IceOptions options) {
        this.options = options;
        iceServerChecker = new IceServerChecker(options, this);
        iceServerChecker.start();
    }

    /**
     * Initiates a connection to a peer (WebRTC)
     *
     * @return the port the ice adapter will be listening/sending for FA
     */
    public int connectToPeer(
            String remotePlayerLogin,
            int remotePlayerId,
            boolean offer,
            int preferredPort,
            AllowCombination combination) {

        if (peers.containsKey(remotePlayerId)) {
            reCreatePeer(remotePlayerId);
            debug().connectToPeer(remotePlayerId, remotePlayerLogin, offer);
            return peers.get(remotePlayerId).getLocalPort();
        }
        Set<PeerModule> allDisabled = new HashSet<>(getDisabledModules());
        allDisabled.addAll(getAdditionalDisabledModules());
        Peer peer = new Peer(
                options.getId(),
                remotePlayerId,
                remotePlayerLogin,
                offer,
                preferredPort,
                getLobbyPort(),
                options.isAllowPeerRelay(),
                options.isAdditionalPacketForwarding(),
                allDisabled);
        peer.init();
        peer.setCombination(combination);
        peer.setGameSession(this);
        peer.initModules();
        peer.addEventListener(iceTrigger);
        peer.startInitPeer();
        peers.put(remotePlayerId, peer);
        debug().connectToPeer(remotePlayerId, remotePlayerLogin, offer);
        return peer.getLocalPort();
    }

    /**
     * Disconnects from a peer
     */
    public void disconnectFromPeer(int remotePlayerId) {
        Peer removedPeer = peers.remove(remotePlayerId);
        if (removedPeer != null) {
            removedPeer.close();
            debug().disconnectFromPeer(remotePlayerId);
        }
    }

    /**
     * Stops the connection to all peers
     */
    public void close() {
        log.info("Closing gameSession");
        peers.values().forEach(Peer::close);
        peers.clear();
        iceServerChecker.stop();
    }

    public List<IceServer> getIceServers() {
        return iceServers;
    }

    @Override
    public Optional<Peer> getPeer(int peerId) {
        return Optional.ofNullable(peers.get(peerId));
    }

    private Set<PeerModule> getDisabledModules() {
        Set<PeerModule> disabledModules = new HashSet<>();
        if (options.isForceRelay()) {
            disabledModules.add(PeerModule.AUTO_SETTING_ALLOW_CANDIDATE);
        }
        //        disabledModules.add(PeerModule.AUTO_RELAY_CALCULATE_RTT);

        return disabledModules;
    }

    /**
     * Returns additional disabled modules for test subclasses.
     */
    protected Set<PeerModule> getAdditionalDisabledModules() {
        return Set.of();
    }

    public static List<IceServer> createIceServers() {
        List<IceServer> iceServers = new ArrayList<>();
        addDefaultIceServers(iceServers);
        return iceServers;
    }

    public static void addDefaultIceServers(List<IceServer> iceServers) {
        iceServers.addAll(IceServer.createPublicServers());
    }

    /**
     * Set ice servers (to be used for harvesting candidates)
     * Called by the client via jsonRPC
     */
    public static void setIceServers(List<Map<String, Object>> iceServersData) {
        iceServers.clear();
        addDefaultIceServers(iceServers);

        if (iceServersData.isEmpty()) {
            return;
        }

        Pair<List<IceServer>, Set<CoturnServer>> pair = IceServer.mapperFromMap(iceServersData);

        iceServers.addAll(pair.first());
        debug().updateCoturnList(pair.second());

        log.info("Ice Servers set, total addresses: {}", iceServers.size());
    }

    @Override
    public int getLobbyPort() {
        return GPGNetServer.getStaticLobbyPort();
    }

    private void reCreatePeer(Integer remotePlayerId) {
        Peer reconnectPeer = peers.get(remotePlayerId);
        if (Objects.nonNull(reconnectPeer)) {
            String remotePlayerLogin = reconnectPeer.getRemoteLogin();
            boolean offer = reconnectPeer.isLocalOffer();
            int port = reconnectPeer.getLocalPort();
            AllowCombination combination = reconnectPeer.getCombination();
            boolean additionalPacketForwarding = reconnectPeer.isAdditionalPacketForwarding();

            disconnectFromPeer(remotePlayerId);
            connectToPeer(remotePlayerLogin, remotePlayerId, offer, port, combination);
            getPeer(remotePlayerId).ifPresent(p -> p.setAdditionalPacketForwarding(additionalPacketForwarding));
        }
    }
}
