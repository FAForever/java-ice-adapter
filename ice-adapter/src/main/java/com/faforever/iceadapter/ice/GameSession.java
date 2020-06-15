package com.faforever.iceadapter.ice;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Represents a game session and the current ICE status/communication with all peers
 * Is created by a JoinGame or HostGame event (via RPC), is destroyed by a gpgnet connection breakdown
 */
@Slf4j
public class GameSession {

    @Getter
    private Map<Integer, Peer> peers = new HashMap<>();
    @Getter @Setter
    private volatile boolean gameEnded = false;

    public GameSession() {

    }

    /**
     * Initiates a connection to a peer (ICE)
     *
     * @return the port the ice adapter will be listening/sending for FA
     */
    public int connectToPeer(String remotePlayerLogin, int remotePlayerId, boolean offer) {
        synchronized (peers) {
            Peer peer = new Peer(this, remotePlayerId, remotePlayerLogin, offer);
            peers.put(remotePlayerId, peer);
            debug().connectToPeer(remotePlayerId, remotePlayerLogin, offer);
            return peer.getFaSocket().getLocalPort();
        }
    }

    /**
     * Disconnects from a peer (ICE)
     */
    public void disconnectFromPeer(int remotePlayerId) {
        synchronized (peers) {
            if (peers.containsKey(remotePlayerId)) {
                peers.get(remotePlayerId).close();
                peers.remove(remotePlayerId);
                debug().disconnectFromPeer(remotePlayerId);
            }
        }
        //TODO: still testing connectivity and reporting disconnect via rpc, why???
        //TODO: still attempting to ICE
    }

    /**
     * Stops the connection to all peers and all ice agents
     */
    public void close() {
        synchronized (peers) {
            peers.values().forEach(Peer::close);
        }
    }


    @Getter
    private static final List<IceServer> iceServers = new ArrayList();

    /**
     * Set ice servers (to be used for harvesting candidates)
     * Called by the client via jsonRPC
     * @param iceServersData
     */
    public static void setIceServers(List<Map<String, Object>> iceServersData) {
        GameSession.iceServers.clear();

        if (iceServersData.isEmpty()) {
            return;
        }

        for(Map<String, Object> iceServerData : iceServersData) {
            iceServers.add(IceServer.fromData(iceServerData));
        }

        log.info("Ice Servers set, total addresses: {}",
                iceServers.stream().mapToInt(s -> s.getStunAddresses().size() + s.getTurnAddresses().size()).sum());
    }
}
