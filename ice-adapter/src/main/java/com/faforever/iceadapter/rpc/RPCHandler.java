package com.faforever.iceadapter.rpc;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.IceStatus;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.LobbyInitMode;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.Peer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;

/**
 * Handles calls from JsonRPC (the client)
 */
@Slf4j
@RequiredArgsConstructor
public class RPCHandler {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final int rpcPort;

    public void hostGame(String mapName) {
        IceAdapter.onHostGame(mapName);
    }

    public void joinGame(String remotePlayerLogin, long remotePlayerId) {
        IceAdapter.onJoinGame(remotePlayerLogin, (int) remotePlayerId);
    }

    public void connectToPeer(String remotePlayerLogin, long remotePlayerId, boolean offer) {
        IceAdapter.onConnectToPeer(remotePlayerLogin, (int) remotePlayerId, offer);
    }

    public void disconnectFromPeer(long remotePlayerId) {
        IceAdapter.onDisconnectFromPeer((int) remotePlayerId);
    }

    public void setLobbyInitMode(String lobbyInitMode) {
        GPGNetServer.lobbyInitMode = LobbyInitMode.getByName(lobbyInitMode);
        log.debug("LobbyInitMode set to {}", lobbyInitMode);
    }

    public void iceMsg(long remotePlayerId, Object msg) {
        boolean err = true;

        GameSession gameSession = IceAdapter.getGameSession();
        if (gameSession != null) { // This is highly unlikely, game session got created if JoinGame/HostGame came first
            Peer peer = gameSession.getPeers().get((int) remotePlayerId);
            if (peer != null) { // This is highly unlikely, peer is present if connectToPeer was called first
                try {
                    peer.getIce().onIceMessageReceived(objectMapper.readValue((String) msg, CandidatesMessage.class));
                    err = false;
                } catch (IOException e) {
                    log.error("Failed to parse iceMsg {}", msg, e);
                    return;
                }
            }
        }

        if (err) {
            log.error("ICE MESSAGE IGNORED for id: {}", remotePlayerId);
        }

        log.info("IceMsg received {}", msg);
    }

    public void sendToGpgNet(String header, String... chunks) {
        GPGNetServer.clientFuture.thenAccept(gpgNetClient -> {
            gpgNetClient.getLobbyFuture().thenRun(() -> {
                gpgNetClient.sendGpgnetMessage(header, chunks);
            });
        });
    }

    public void setIceServers(List<Map<String, Object>> iceServers) {
        GameSession.setIceServers(iceServers);
    }

    @Deprecated(forRemoval = true)
    @SneakyThrows
    public String status() {
        IceStatus.IceGPGNetState gpgpnet = new IceStatus.IceGPGNetState(
                GPGNetServer.getGpgnetPort(), GPGNetServer.isConnected(), GPGNetServer.getGameStateString(), "-");

        List<IceStatus.IceRelay> relays = new ArrayList<>();
        GameSession gameSession = IceAdapter.getGameSession();
        if (gameSession != null) {
            synchronized (gameSession.getPeers()) {
                gameSession.getPeers().values().stream()
                        .map(peer -> {
                            IceStatus.IceRelay.IceRelayICEState iceRelayICEState =
                                    new IceStatus.IceRelay.IceRelayICEState(
                                            peer.isLocalOffer(),
                                            peer.getIce().getIceState().getMessage(),
                                            "",
                                            "",
                                            peer.getIce().isConnected(),
                                            Optional.ofNullable(peer.getIce().getComponent())
                                                    .map(Component::getSelectedPair)
                                                    .map(CandidatePair::getLocalCandidate)
                                                    .map(Candidate::getHostAddress)
                                                    .map(TransportAddress::toString)
                                                    .orElse(""),
                                            Optional.ofNullable(peer.getIce().getComponent())
                                                    .map(Component::getSelectedPair)
                                                    .map(CandidatePair::getRemoteCandidate)
                                                    .map(Candidate::getHostAddress)
                                                    .map(TransportAddress::toString)
                                                    .orElse(""),
                                            Optional.ofNullable(peer.getIce().getComponent())
                                                    .map(Component::getSelectedPair)
                                                    .map(CandidatePair::getLocalCandidate)
                                                    .map(Candidate::getType)
                                                    .map(CandidateType::toString)
                                                    .orElse(""),
                                            Optional.ofNullable(peer.getIce().getComponent())
                                                    .map(Component::getSelectedPair)
                                                    .map(CandidatePair::getRemoteCandidate)
                                                    .map(Candidate::getType)
                                                    .map(CandidateType::toString)
                                                    .orElse(""),
                                            -1.0);

                            return new IceStatus.IceRelay(
                                    peer.getRemoteId(),
                                    peer.getRemoteLogin(),
                                    peer.getFaSocket().getLocalPort(),
                                    iceRelayICEState);
                        })
                        .forEach(relays::add);
            }
        }

        IceStatus status = new IceStatus(
                IceAdapter.getVersion(),
                GameSession.getIceServers().stream()
                        .mapToInt(s -> s.getTurnAddresses().size()
                                + s.getStunAddresses().size())
                        .sum(),
                GPGNetServer.getLobbyPort(),
                GPGNetServer.lobbyInitMode.getName(),
                new IceStatus.IceOptions(
                        IceAdapter.getId(), IceAdapter.getLogin(), rpcPort, GPGNetServer.getGpgnetPort()),
                gpgpnet,
                relays.toArray(new IceStatus.IceRelay[relays.size()]));

        return objectMapper.writeValueAsString(status);
    }

    public void quit() {
        log.warn("Close requested, stopping...");
        IceAdapter.close();
    }
}
