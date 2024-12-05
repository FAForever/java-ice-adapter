package com.faforever.iceadapter.ice;

import static com.faforever.iceadapter.debug.Debug.debug;
import static com.faforever.iceadapter.ice.IceState.*;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.CandidateUtil;
import com.faforever.iceadapter.util.LockUtil;
import com.faforever.iceadapter.util.TrayIcon;
import java.io.IOException;
import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

@Getter
@Slf4j
public class PeerIceModule {
    private static boolean ALLOW_HOST = true;
    private static boolean ALLOW_REFLEXIVE = true;
    private static boolean ALLOW_RELAY = true;

    public static void setForceRelay(boolean forceRelay) {
        if (forceRelay) {
            ALLOW_HOST = false;
            ALLOW_REFLEXIVE = false;
            ALLOW_RELAY = true;
        } else {
            ALLOW_HOST = true;
            ALLOW_REFLEXIVE = true;
            ALLOW_RELAY = true;
        }
    }

    private static final int MINIMUM_PORT =
            6112; // PORT (range +1000) to be used by ICE for communicating, each peer needs a seperate port
    private static final long FORCE_SRFLX_RELAY_INTERVAL =
            2 * 60 * 1000; // 2 mins, the interval in which multiple connects have to happen to force srflx/relay
    private static final int FORCE_SRFLX_COUNT = 1;
    private static final int FORCE_RELAY_COUNT = 2;

    private final Peer peer;

    private Agent agent;
    private IceMediaStream mediaStream;
    private Component component;

    private volatile IceState iceState = NEW;
    private volatile boolean connected = false;
    private volatile Thread listenerThread;

    private PeerTurnRefreshModule turnRefreshModule;

    // Checks the connection by sending echo requests and initiates a reconnect if needed
    private final PeerConnectivityCheckerModule connectivityChecker = new PeerConnectivityCheckerModule(this);

    // A list of the timestamps of initiated connectivity attempts, used to detect if relay/srflx should be forced
    private final List<Long> connectivityAttemptTimes = new ArrayList<>();
    // How often have we been waiting for a response to local candidates/offer
    private final AtomicInteger awaitingCandidatesEventId = new AtomicInteger(0);

    private final Lock lockInit = new ReentrantLock();
    private final Lock lockLostConnection = new ReentrantLock();
    private final Lock lockMessageReceived = new ReentrantLock();

    public PeerIceModule(Peer peer) {
        this.peer = peer;
    }

    /**
     * Updates the current iceState and informs the client via RPC
     * @param newState the new State
     */
    private void setState(IceState newState) {
        this.iceState = newState;
        RPCService.onIceConnectionStateChanged(IceAdapter.getId(), peer.getRemoteId(), iceState.getMessage());
        debug().peerStateChanged(this.peer);
    }

    /**
     * Will start the ICE Process
     */
    void initiateIce() {
        LockUtil.executeWithLock(lockInit, () -> {
            if (peer.isClosing()) {
                log.warn("{} Peer not connected anymore, aborting reinitiation of ICE", getLogPrefix());
                return;
            }

            if (iceState != NEW && iceState != DISCONNECTED) {
                log.warn(
                        "{} ICE already in progress, aborting re initiation. current state: {}",
                        getLogPrefix(),
                        iceState.getMessage());
                return;
            }

            setState(GATHERING);
            log.info("{} Initiating ICE for peer", getLogPrefix());

            createAgent();
            gatherCandidates();
        });
    }

    /**
     * Creates an agent and media stream for handling the ICE
     */
    private void createAgent() {
        if (agent != null) {
            agent.free();
        }

        agent = new Agent();
        agent.setControlling(peer.isLocalOffer());

        mediaStream = agent.createMediaStream("faData");
    }

    /**
     * Gathers all local candidates, packs them into a message and sends them to the other peer via RPC
     */
    private void gatherCandidates() {
        log.info("{} Gathering ice candidates", getLogPrefix());

        List<IceServer> iceServers = getViableIceServers();

        iceServers.stream()
                .flatMap(s -> s.getStunAddresses().stream())
                .map(StunCandidateHarvester::new)
                .forEach(agent::addCandidateHarvester);
        iceServers.forEach(iceServer -> iceServer.getTurnAddresses().stream()
                .map(a -> new TurnCandidateHarvester(
                        a, new LongTermCredential(iceServer.getTurnUsername(), iceServer.getTurnCredential())))
                .forEach(agent::addCandidateHarvester));

        CompletableFuture<Void> gatheringFuture = CompletableFuture.runAsync(
                () -> {
                    try {
                        component = agent.createComponent(
                                mediaStream,
                                MINIMUM_PORT
                                        + (int) (ThreadLocalRandom.current().nextDouble() * 999.0),
                                MINIMUM_PORT,
                                MINIMUM_PORT + 1000);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                IceAdapter.getExecutor());

        CompletableFuture.runAsync(
                () -> {
                    if (!gatheringFuture.isDone()) {
                        gatheringFuture.cancel(true);
                    }
                },
                CompletableFuture.delayedExecutor(5000, TimeUnit.MILLISECONDS, IceAdapter.getExecutor()));

        try {
            gatheringFuture.join();
        } catch (CompletionException e) {
            // Completed exceptionally
            log.error("{} Error while creating stream component/gathering candidates", getLogPrefix(), e);
            CompletableFuture.runAsync(this::onConnectionLost, IceAdapter.getExecutor());
            return;
        } catch (CancellationException e) {
            // was cancelled due to timeout
            log.error("{} Gathering candidates timed out", getLogPrefix(), e);
            CompletableFuture.runAsync(this::onConnectionLost, IceAdapter.getExecutor());
            return;
        }

        long previousConnectivityAttempts = getConnectivityAttempsInThePast(FORCE_SRFLX_RELAY_INTERVAL);
        CandidatesMessage localCandidatesMessage = CandidateUtil.packCandidates(
                IceAdapter.getId(),
                peer.getRemoteId(),
                agent,
                component,
                previousConnectivityAttempts < FORCE_SRFLX_COUNT && ALLOW_HOST,
                previousConnectivityAttempts < FORCE_RELAY_COUNT && ALLOW_REFLEXIVE,
                ALLOW_RELAY);
        log.debug(
                "{} Sending own candidates to {}, offered candidates: {}",
                getLogPrefix(),
                peer.getRemoteId(),
                localCandidatesMessage.candidates().stream()
                        .map(it -> it.type().toString() + "(" + it.protocol() + ")")
                        .collect(Collectors.joining(", ")));
        setState(AWAITING_CANDIDATES);
        RPCService.onIceMsg(localCandidatesMessage);

        // Make sure to abort the connection process and reinitiate when we haven't received an answer to our offer in 6
        // seconds, candidate packet was probably lost
        final int currentAwaitingCandidatesEventId = awaitingCandidatesEventId.incrementAndGet();
        CompletableFuture.runAsync(
                () -> {
                    if (peer.isClosing()) {
                        log.warn(
                                "{} Peer {} not connected anymore, aborting reinitiation of ICE",
                                getLogPrefix(),
                                peer.getRemoteId());
                        return;
                    }
                    if (iceState == AWAITING_CANDIDATES
                            && currentAwaitingCandidatesEventId == awaitingCandidatesEventId.get()) {
                        onConnectionLost();
                    }
                },
                CompletableFuture.delayedExecutor(6000, TimeUnit.MILLISECONDS, IceAdapter.getExecutor()));
    }

    private List<IceServer> getViableIceServers() {
        List<IceServer> allIceServers = GameSession.getIceServers();
        if (IceAdapter.getPingCount() <= 0 || allIceServers.isEmpty()) {
            return allIceServers;
        }

        // Try servers with acceptable latency
        List<IceServer> viableIceServers =
                allIceServers.stream().filter(IceServer::hasAcceptableLatency).collect(Collectors.toList());
        if (!viableIceServers.isEmpty()) {
            log.info(
                    "Using all viable ice servers: {}",
                    viableIceServers.stream()
                            .map(it -> "["
                                    + it.getTurnAddresses().stream()
                                            .map(TransportAddress::toString)
                                            .collect(Collectors.joining(", "))
                                    + "]")
                            .collect(Collectors.joining(", ")));
            return viableIceServers;
        }

        log.info(
                "Using all ice servers: {}",
                allIceServers.stream()
                        .map(it -> "["
                                + it.getTurnAddresses().stream()
                                        .map(TransportAddress::toString)
                                        .collect(Collectors.joining(", "))
                                + "]")
                        .collect(Collectors.joining(", ")));
        return allIceServers;
    }

    /**
     * Starts harvesting local candidates if in answer mode, then initiates the actual ICE process
     * @param remoteCandidatesMessage
     */
    public void onIceMessageReceived(CandidatesMessage remoteCandidatesMessage) {
        LockUtil.executeWithLock(lockMessageReceived, () -> {
            if (peer.isClosing()) {
                log.warn("{} Peer not connected anymore, discarding ice message", getLogPrefix());
                return;
            }

            // Start ICE async as it's blocking and this is the RPC thread
            CompletableFuture.runAsync(
                    () -> {
                        log.debug(
                                "{} Got IceMsg for peer, offered candidates: {}",
                                getLogPrefix(),
                                remoteCandidatesMessage.candidates().stream()
                                        .map(it -> it.type().toString() + "(" + it.protocol() + ")")
                                        .collect(Collectors.joining(", ")));

                        if (peer.isLocalOffer()) {
                            if (iceState != AWAITING_CANDIDATES) {
                                log.warn(
                                        "{} Received candidates unexpectedly, current state: {}",
                                        getLogPrefix(),
                                        iceState.getMessage());
                                return;
                            }

                        } else {
                            // Check if we are already processing an ICE offer and if so stop it
                            if (iceState != NEW && iceState != DISCONNECTED) {
                                log.info("{} Received new candidates/offer, stopping...", getLogPrefix());
                                onConnectionLost();
                            }

                            // Answer mode, initialize agent and gather candidates
                            initiateIce();
                        }

                        setState(CHECKING);

                        long previousConnectivityAttempts = getConnectivityAttempsInThePast(FORCE_SRFLX_RELAY_INTERVAL);
                        CandidateUtil.unpackCandidates(
                                remoteCandidatesMessage,
                                agent,
                                component,
                                mediaStream,
                                previousConnectivityAttempts < FORCE_SRFLX_COUNT && ALLOW_HOST,
                                previousConnectivityAttempts < FORCE_RELAY_COUNT && ALLOW_REFLEXIVE,
                                ALLOW_RELAY);

                        startIce();
                    },
                    IceAdapter.getExecutor());
        });
    }

    /**
     * Runs the actual connectivity establishment, candidates have been exchanged and need to be checked
     */
    private void startIce() {
        connectivityAttemptTimes.add(0, System.currentTimeMillis());

        log.debug("{} Starting ICE for peer {}", getLogPrefix(), peer.getRemoteId());
        agent.startConnectivityEstablishment();

        // Wait for termination/completion of the agent
        long iceStartTime = System.currentTimeMillis();
        while (!Thread.currentThread().isInterrupted()
                && agent.getState()
                        != IceProcessingState
                                .COMPLETED) { // TODO include more?, maybe stop on COMPLETED, is that to early?
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                log.error("{} Interrupted while waiting for ICE", getLogPrefix(), e);
                onConnectionLost();
                return;
            }

            if (agent.getState() == IceProcessingState.FAILED) { // TODO null pointer due to no agent?
                onConnectionLost();
                return;
            }

            if (System.currentTimeMillis() - iceStartTime > 15_000) {
                log.error("{} ABORTING ICE DUE TO TIMEOUT", getLogPrefix());
                onConnectionLost();
                return;
            }
        }

        log.debug(
                "{} ICE terminated, connected, selected candidate pair: {} <-> {}",
                getLogPrefix(),
                component.getSelectedPair().getLocalCandidate().getType().toString(),
                component.getSelectedPair().getRemoteCandidate().getType().toString());

        // We are connected
        connected = true;
        RPCService.onConnected(IceAdapter.getId(), peer.getRemoteId(), true);
        setState(CONNECTED);

        if (component.getSelectedPair().getLocalCandidate().getType() == CandidateType.RELAYED_CANDIDATE) {
            turnRefreshModule = new PeerTurnRefreshModule(
                    this, (RelayedCandidate) component.getSelectedPair().getLocalCandidate());
        }

        if (peer.isLocalOffer()) {
            connectivityChecker.start();
        }

        listenerThread = Thread.startVirtualThread(this::listener);
    }

    /**
     * Connection has been lost, ice failed or we received a new offer
     * Will close agent, stop listener and connectivity checker thread and change state to disconnected
     * Will then reinitiate ICE
     */
    public void onConnectionLost() {
        LockUtil.executeWithLock(lockLostConnection, () -> {
            if (iceState == DISCONNECTED) {
                log.warn("{} Lost connection, albeit already in ice state disconnected", getLogPrefix());
                return; // TODO: will this kill the life cycle?
            }

            IceState previousState = getIceState();

            if (listenerThread != null) {
                listenerThread.interrupt();
                listenerThread = null;
            }

            if (turnRefreshModule != null) {
                turnRefreshModule.close();
                turnRefreshModule = null;
            }

            connectivityChecker.stop();

            if (connected) {
                connected = false;
                log.warn("{} ICE connection has been lost for peer", getLogPrefix());
                RPCService.onConnected(IceAdapter.getId(), peer.getRemoteId(), false);
            }

            setState(DISCONNECTED);

            if (agent != null) {
                agent.free();
                agent = null;
                mediaStream = null;
                component = null;
            }

            debug().peerStateChanged(this.peer);

            if (peer.isClosing()) {
                log.warn("{} Peer not connected anymore, aborting onConnectionLost of ICE", getLogPrefix());
                return;
            }

            if (peer.getGameSession().isGameEnded()) {
                log.warn("{} GAME ENDED, ABORTING onConnectionLost of ICE for peer ", getLogPrefix());
                return;
            }

            if (previousState == CONNECTED) {
                TrayIcon.showMessage("Reconnecting to %s (connection lost)".formatted(this.peer.getRemoteLogin()));
            }

            if (previousState == CONNECTED && peer.isLocalOffer()) {
                // We were connected before, retry immediately
                CompletableFuture.runAsync(
                        this::initiateIce,
                        CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS, IceAdapter.getExecutor()));
            } else if (peer.isLocalOffer()) {
                // Last ice attempt didn't succeed, so wait a bit
                CompletableFuture.runAsync(
                        this::initiateIce,
                        CompletableFuture.delayedExecutor(5000, TimeUnit.MILLISECONDS, IceAdapter.getExecutor()));
            }
        });
    }

    /**
     * Data received from FA, prepends prefix and sends it via ICE to the other peer
     * @param faData
     * @param length
     */
    void onFaDataReceived(byte[] faData, int length) {
        byte[] data = new byte[length + 1];
        data[0] = 'd';
        System.arraycopy(faData, 0, data, 1, length);
        sendViaIce(data, 0, data.length);
    }

    /**
     * Send date via ice to the other peer
     * @param data
     * @param offset
     * @param length
     */
    void sendViaIce(byte[] data, int offset, int length) {
        if (connected && component != null) {
            try {
                component
                        .getSelectedPair()
                        .getIceSocketWrapper()
                        .send(new DatagramPacket(
                                data,
                                offset,
                                length,
                                component
                                        .getSelectedPair()
                                        .getRemoteCandidate()
                                        .getTransportAddress()
                                        .getAddress(),
                                component
                                        .getSelectedPair()
                                        .getRemoteCandidate()
                                        .getTransportAddress()
                                        .getPort()));
            } catch (IOException e) {
                log.warn("{} Failed to send data via ICE", getLogPrefix(), e);
                onConnectionLost();
            } catch (NullPointerException e) {
                log.error("Component is null", e);
            }
        }
    }

    /**
     * Listens for data incoming via ice socket
     */
    public void listener() {
        log.debug("{} Now forwarding data from ICE to FA for peer", getLogPrefix());
        Component localComponent = component;

        byte[] data = new byte
                [65536]; // 64KiB = UDP MTU, in practice due to ethernet frames being <= 1500 B, this is often not used
        while (!Thread.currentThread().isInterrupted()
                && IceAdapter.isRunning()
                && IceAdapter.getGameSession() == peer.getGameSession()) {
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length);
                localComponent
                        .getSelectedPair()
                        .getIceSocketWrapper()
                        .getUDPSocket()
                        .receive(packet);

                if (packet.getLength() == 0) {
                    continue;
                }

                if (data[0] == 'd') {
                    // Received data
                    peer.onIceDataReceived(data, 1, packet.getLength() - 1);
                } else if (data[0] == 'e') {
                    // Received echo req/res
                    if (peer.isLocalOffer()) {
                        connectivityChecker.echoReceived(data, 0, packet.getLength());
                    } else {
                        sendViaIce(data, 0, packet.getLength()); // Turn around, send echo back
                    }
                } else {
                    log.warn(
                            "{} Received invalid packet, first byte: 0x{}, length: {}",
                            getLogPrefix(),
                            data[0],
                            packet.getLength());
                }

            } catch (IOException e) { // TODO: nullpointer from localComponent.xxxx????
                log.warn("{} Error while reading from ICE adapter", getLogPrefix(), e);
                if (component == localComponent) {
                    onConnectionLost();
                }
                return;
            }
        }

        log.debug("{} No longer listening for messages from ICE", getLogPrefix());
    }

    void close() {
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
        if (turnRefreshModule != null) {
            turnRefreshModule.close();
        }
        if (agent != null) {
            agent.free();
        }
        connectivityChecker.stop();
    }

    public long getConnectivityAttempsInThePast(final long millis) {
        // copy list to avoid concurrency issues
        return new ArrayList<>(connectivityAttemptTimes)
                .stream()
                        .filter(time -> time > (System.currentTimeMillis() - millis))
                        .count();
    }

    public String getLogPrefix() {
        return "ICE %s:".formatted(peer.getPeerIdentifier());
    }
}
