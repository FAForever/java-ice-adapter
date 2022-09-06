package com.faforever.iceadapter.rpc;

import com.faforever.iceadapter.FafRpcCallbacks;
import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.IceStatus;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.LobbyInitMode;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.GameSession;
import com.faforever.iceadapter.ice.Peer;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles calls from JsonRPC (the client)
 */
@Slf4j
@RequiredArgsConstructor
public class RPCHandler {

    private final Gson gson = new Gson();
    private final int rpcPort;
    private final FafRpcCallbacks callbacks;
    private final GPGNetServer gpgNetServer;

    public void hostGame(String mapName) {
        callbacks.onHostGame(mapName);
    }

    public void joinGame(String remotePlayerLogin, long remotePlayerId) {
        callbacks.onJoinGame(remotePlayerLogin, (int) remotePlayerId);
    }

    public void connectToPeer(String remotePlayerLogin, long remotePlayerId, boolean offer) {
        callbacks.onConnectToPeer(remotePlayerLogin, (int) remotePlayerId, offer);
    }

    public void disconnectFromPeer(long remotePlayerId) {
        callbacks.onDisconnectFromPeer((int) remotePlayerId);
    }

    public void setLobbyInitMode(String lobbyInitMode) {
        gpgNetServer.setLobbyInitMode(LobbyInitMode.getByName(lobbyInitMode));
        log.debug("LobbyInitMode set to {}", lobbyInitMode);
    }

    public void iceMsg(long remotePlayerId, Object msg) {
        boolean err = true;

        GameSession gameSession = IceAdapter.gameSession;
        if (gameSession != null) {//This is highly unlikely, game session got created if JoinGame/HostGame came first
            Peer peer = gameSession.getPeers().get((int) remotePlayerId);
            if (peer != null) {//This is highly unlikely, peer is present if connectToPeer was called first
                peer.getIce().onIceMessageReceived(gson.fromJson((String) msg, CandidatesMessage.class));
                err = false;
            }
        }

        if (err) {
            log.error("ICE MESSAGE IGNORED for id: {}", remotePlayerId);
        }

		log.info("IceMsg received {}", msg);
    }

    public void sendToGpgNet(String header, Object... args) {
        callbacks.sendToGpgNet(header, args);
    }

    public void setIceServers(List<Map<String, Object>> iceServers) {
        GameSession.setIceServers(iceServers);
    }

    //TODO: this method is temporary and needs to be improved
    public String status() {
        IceStatus.IceGPGNetState gpgpnet = new IceStatus.IceGPGNetState(gpgNetServer.getGpgnetPort(), gpgNetServer.isConnected(), gpgNetServer.getGameStateString(), "-");

        List<IceStatus.IceRelay> relays = new ArrayList<>();
        GameSession gameSession = IceAdapter.gameSession;
        if (gameSession != null) {
            synchronized (gameSession.getPeers()) {
                gameSession.getPeers().values().stream()
                        .map(peer -> {
                            IceStatus.IceRelay.IceRelayICEState iceRelayICEState = new IceStatus.IceRelay.IceRelayICEState(
                                    peer.isLocalOffer(),
                                    peer.getIce().getIceState().getMessage(),
                                    "",
                                    "",
                                    peer.getIce().isConnected(),
                                    Optional.ofNullable(peer.getIce().getComponent()).map(Component::getSelectedPair).map(CandidatePair::getLocalCandidate).map(Candidate::getHostAddress).map(TransportAddress::toString).orElse(""),
                                    Optional.ofNullable(peer.getIce().getComponent()).map(Component::getSelectedPair).map(CandidatePair::getRemoteCandidate).map(Candidate::getHostAddress).map(TransportAddress::toString).orElse(""),
                                    Optional.ofNullable(peer.getIce().getComponent()).map(Component::getSelectedPair).map(CandidatePair::getLocalCandidate).map(Candidate::getType).map(CandidateType::toString).orElse(""),
                                    Optional.ofNullable(peer.getIce().getComponent()).map(Component::getSelectedPair).map(CandidatePair::getRemoteCandidate).map(Candidate::getType).map(CandidateType::toString).orElse(""),
                                    -1.0
                            );

                            return new IceStatus.IceRelay(peer.getRemoteId(), peer.getRemoteLogin(), peer.getFaSocket().getLocalPort(), iceRelayICEState);
                        })
                        .forEach(relays::add);
            }
        }

        IceStatus status = new IceStatus(
                IceAdapter.VERSION,
                GameSession.getIceServers().stream().mapToInt(s -> s.getTurnAddresses().size() + s.getStunAddresses().size()).sum(),
                gpgNetServer.getLobbyPort(),
                gpgNetServer.getLobbyInitMode().getName(),
                new IceStatus.IceOptions(IceAdapter.getId(), IceAdapter.getLogin(), rpcPort, gpgNetServer.getGpgnetPort()),
                gpgpnet,
                relays.toArray(new IceStatus.IceRelay[relays.size()])
        );

        return gson.toJson(status);
    }

    public void quit() {
        log.warn("Close requested, stopping...");
        callbacks.close();
    }

}
