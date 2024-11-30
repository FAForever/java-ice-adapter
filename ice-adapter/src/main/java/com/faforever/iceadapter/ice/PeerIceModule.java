package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.AsyncService;
import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.rpc.RPCService;
import com.faforever.iceadapter.util.CandidateUtil;
import com.faforever.iceadapter.util.TrayIcon;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.faforever.iceadapter.debug.Debug.debug;
import static com.faforever.iceadapter.ice.IceState.*;

@Getter
@Slf4j
public class PeerIceModule {
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
    private volatile CompletableFuture<Void> listener;

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
        RPCService.onIceConnectionStateChanged(IceAdapter.id, peer.getRemoteId(), iceState.getMessage());
        debug().peerStateChanged(this.peer);
    }

    /**
     * Will start the ICE Process
     */
    void initiateIce() {
        AsyncService.executeWithLock(lockInit, () -> {
            if (peer.isClosing()) {
                log.warn("{} Peer not connected anymore, aborting reinitiation of ICE", getLogPrefix());
                return;
            }

            if (iceState != NEW && iceState != DISCONNECTED) {
                log.warn(
                        getLogPrefix() + "ICE already in progress, aborting re initiation. current state: {}",
                        iceState.getMessage());
                return;
            }

            setState(GATHERING);
            log.info(getLogPrefix() + "Initiating ICE for peer");

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
        log.info(getLogPrefix() + "Gathering ice candidates");

        List<IceServer> iceServers = getViableIceServers();

        iceServers.stream()
                .flatMap(s -> s.getStunAddresses().stream())
                .map(StunCandidateHarvester::new)
                .forEach(agent::addCandidateHarvester);
        iceServers.forEach(iceServer -> iceServer.getTurnAddresses().stream()
                .map(a -> new TurnCandidateHarvester(
                        a, new LongTermCredential(iceServer.getTurnUsername(), iceServer.getTurnCredential())))
                .forEach(agent::addCandidateHarvester));

        CompletableFuture<Void> gatheringFuture = AsyncService.runAsync(() -> {
            try {
                component = agent.createComponent(
                        mediaStream,
                        MINIMUM_PORT + (int) (ThreadLocalRandom.current().nextDouble() * 999.0),
                        MINIMUM_PORT,
                        MINIMUM_PORT + 1000);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        AsyncService.executeDelayed(5000, () -> {
            if (!gatheringFuture.isDone()) {
                gatheringFuture.cancel(true);
            }
        });

        try {
            gatheringFuture.join();
        } catch (CompletionException e) {
            // Completed exceptionally
            log.error(getLogPrefix() + "Error while creating stream component/gathering candidates", e);
            AsyncService.runAsync(this::onConnectionLost);
            return;
        } catch (CancellationException e) {
            // was cancelled due to timeout
            log.error(getLogPrefix() + "Gathering candidates timed out", e);
            AsyncService.runAsync(this::onConnectionLost);
            return;
        }

        int previousConnectivityAttempts = getConnectivityAttempsInThePast(FORCE_SRFLX_RELAY_INTERVAL);
        CandidatesMessage localCandidatesMessage = CandidateUtil.packCandidates(
                IceAdapter.id,
                peer.getRemoteId(),
                agent,
                component,
                previousConnectivityAttempts < FORCE_SRFLX_COUNT && IceAdapter.ALLOW_HOST,
                previousConnectivityAttempts < FORCE_RELAY_COUNT && IceAdapter.ALLOW_REFLEXIVE,
                IceAdapter.ALLOW_RELAY);
        log.debug(
                getLogPrefix() + "Sending own candidates to {}, offered candidates: {}",
                peer.getRemoteId(),
                localCandidatesMessage.candidates().stream()
                        .map(it -> it.type().toString() + "(" + it.protocol() + ")")
                        .collect(Collectors.joining(", ")));
        setState(AWAITING_CANDIDATES);
        RPCService.onIceMsg(localCandidatesMessage);

        // Make sure to abort the connection process and reinitiate when we haven't received an answer to our offer in 6
        // seconds, candidate packet was probably lost
        final int currentAwaitingCandidatesEventId = awaitingCandidatesEventId.incrementAndGet();
        AsyncService.executeDelayed(6000, () -> {
            if (peer.isClosing()) {
                log.warn(
                        getLogPrefix() + "Peer {} not connected anymore, aborting reinitiation of ICE",
                        peer.getRemoteId());
                return;
            }
            if (iceState == AWAITING_CANDIDATES
                    && currentAwaitingCandidatesEventId == awaitingCandidatesEventId.get()) {
                onConnectionLost();
            }
        });
    }

    private List<IceServer> getViableIceServers() {
        List<IceServer> allIceServers = GameSession.getIceServers();
        if (IceAdapter.PING_COUNT <= 0 || allIceServers.isEmpty()) {
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
        AsyncService.executeWithLock(lockMessageReceived,() -> {
            if (peer.isClosing()) {
                log.warn(getLogPrefix() + "Peer not connected anymore, discarding ice message");
                return;
            }

            // Start ICE async as it's blocking and this is the RPC thread
            AsyncService.runAsync(() -> {
                log.debug(
                        getLogPrefix() + "Got IceMsg for peer, offered candidates: {}",
                        remoteCandidatesMessage.candidates().stream()
                                .map(it -> it.type().toString() + "(" + it.protocol() + ")")
                                .collect(Collectors.joining(", ")));

                if (peer.isLocalOffer()) {
                    if (iceState != AWAITING_CANDIDATES) {
                        log.warn(
                                getLogPrefix() + "Received candidates unexpectedly, current state: {}",
                                iceState.getMessage());
                        return;
                    }

                } else {
                    // Check if we are already processing an ICE offer and if so stop it
                    if (iceState != NEW && iceState != DISCONNECTED) {
                        log.info(getLogPrefix() + "Received new candidates/offer, stopping...");
                        onConnectionLost();
                    }

                    // Answer mode, initialize agent and gather candidates
                    initiateIce();
                }

                setState(CHECKING);

                int previousConnectivityAttempts = getConnectivityAttempsInThePast(FORCE_SRFLX_RELAY_INTERVAL);
                CandidateUtil.unpackCandidates(
                        remoteCandidatesMessage,
                        agent,
                        component,
                        mediaStream,
                        previousConnectivityAttempts < FORCE_SRFLX_COUNT && IceAdapter.ALLOW_HOST,
                        previousConnectivityAttempts < FORCE_RELAY_COUNT && IceAdapter.ALLOW_REFLEXIVE,
                        IceAdapter.ALLOW_RELAY);

                startIce();
            });
        });
    }

    /**
     * Runs the actual connectivity establishment, candidates have been exchanged and need to be checked
     */
    private void startIce() {
        connectivityAttemptTimes.add(0, System.currentTimeMillis());

        log.debug(getLogPrefix() + "Starting ICE for peer {}", peer.getRemoteId());
        agent.startConnectivityEstablishment();

        // Wait for termination/completion of the agent
        long iceStartTime = System.currentTimeMillis();
        while (!Thread.currentThread().isInterrupted()
                && agent.getState() != IceProcessingState.COMPLETED) { // TODO include more?, maybe stop on COMPLETED, is that to early?
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                log.error(getLogPrefix() + "Interrupted while waiting for ICE", e);
                onConnectionLost();
                return;
            }

            if (agent.getState() == IceProcessingState.FAILED) { // TODO null pointer due to no agent?
                onConnectionLost();
                return;
            }

            if (System.currentTimeMillis() - iceStartTime > 15_000) {
                log.error(getLogPrefix() + "ABORTING ICE DUE TO TIMEOUT");
                onConnectionLost();
                return;
            }
        }

        log.debug(getLogPrefix() + "ICE terminated, connected, selected candidate pair: "
                + component.getSelectedPair().getLocalCandidate().getType().toString() + " <-> "
                + component.getSelectedPair().getRemoteCandidate().getType().toString());

        // We are connected
        connected = true;
        RPCService.onConnected(IceAdapter.id, peer.getRemoteId(), true);
        setState(CONNECTED);

        if (component.getSelectedPair().getLocalCandidate().getType() == CandidateType.RELAYED_CANDIDATE) {
            turnRefreshModule = new PeerTurnRefreshModule(
                    this, (RelayedCandidate) component.getSelectedPair().getLocalCandidate());
        }

        if (peer.isLocalOffer()) {
            connectivityChecker.start();
        }

        listener = AsyncService.runAsync(this::listener);
    }

    /**
     * Connection has been lost, ice failed or we received a new offer
     * Will close agent, stop listener and connectivity checker thread and change state to disconnected
     * Will then reinitiate ICE
     */
    public void onConnectionLost() {
        AsyncService.executeWithLock(lockLostConnection, () ->{
            if (iceState == DISCONNECTED) {
                log.warn(getLogPrefix() + "Lost connection, albeit already in ice state disconnected");
                return; // TODO: will this kill the life cycle?
            }

            IceState previousState = getIceState();

            if (listener != null) {
                listener.cancel(true);
                listener = null;
            }

            if (turnRefreshModule != null) {
                turnRefreshModule.close();
                turnRefreshModule = null;
            }

            connectivityChecker.stop();

            if (connected) {
                connected = false;
                log.warn(getLogPrefix() + "ICE connection has been lost for peer");
                RPCService.onConnected(IceAdapter.id, peer.getRemoteId(), false);
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
                log.warn(getLogPrefix() + "Peer not connected anymore, aborting onConnectionLost of ICE");
                return;
            }

            if (peer.getGameSession().isGameEnded()) {
                log.warn(getLogPrefix() + "GAME ENDED, ABORTING onConnectionLost of ICE for peer ");
                return;
            }

            if (previousState == CONNECTED) {
                TrayIcon.showMessage("Reconnecting to " + this.peer.getRemoteLogin() + " (connection lost)");
            }

            if (previousState == CONNECTED && peer.isLocalOffer()) {
                // We were connected before, retry immediately
                AsyncService.executeDelayed(0, this::initiateIce);
            } else if (peer.isLocalOffer()) {
                // Last ice attempt didn't succeed, so wait a bit
                AsyncService.executeDelayed(5000, this::initiateIce);
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
                log.warn(getLogPrefix() + "Failed to send data via ICE", e);
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
        log.debug(getLogPrefix() + "Now forwarding data from ICE to FA for peer");
        Component localComponent = component;

        byte[] data = new byte[65536]; // 64KiB = UDP MTU, in practice due to ethernet frames being <= 1500 B, this is often not used
        while (!Thread.currentThread().isInterrupted()
                && IceAdapter.running
                && IceAdapter.gameSession == peer.getGameSession()) {
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
                            getLogPrefix() + "Received invalid packet, first byte: 0x{}, length: {}",
                            data[0],
                            packet.getLength());
                }

            } catch (IOException e) { // TODO: nullpointer from localComponent.xxxx????
                log.warn(getLogPrefix() + "Error while reading from ICE adapter", e);
                if (component == localComponent) {
                    onConnectionLost();
                }
                return;
            }
        }

        log.debug(getLogPrefix() + "No longer listening for messages from ICE");
    }

    void close() {
        if (turnRefreshModule != null) {
            turnRefreshModule.close();
        }
        if (agent != null) {
            agent.free();
        }
        connectivityChecker.stop();
    }

    public int getConnectivityAttempsInThePast(final long millis) {
        // copy list to avoid concurrency issues
        return (int) new ArrayList<Long>(connectivityAttemptTimes)
                .stream()
                        .filter(time -> time > (System.currentTimeMillis() - millis))
                        .count();
    }

    public String getLogPrefix() {
        return "ICE %s: ".formatted(peer.getPeerIdentifier());
    }
}
