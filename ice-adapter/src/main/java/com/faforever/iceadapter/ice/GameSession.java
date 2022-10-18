package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.telemetry.CoturnServer;
import com.faforever.iceadapter.util.PingWrapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Represents a game session and the current ICE status/communication with all peers
 * Is created by a JoinGame or HostGame event (via RPC), is destroyed by a gpgnet connection breakdown
 */
@Slf4j
public class GameSession {

    @Getter
    private Map<Integer, Peer> peers = new HashMap<>();
    @Getter
    @Setter
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
     *
     * @param iceServersData
     */
    public static void setIceServers(List<Map<String, Object>> iceServersData) {
        GameSession.iceServers.clear();

        if (iceServersData.isEmpty()) {
            return;
        }

        // For caching RTT to a given host (the same host can appear in multiple urls)
        LoadingCache<String, CompletableFuture<OptionalDouble>> hostRTTCache = CacheBuilder.newBuilder().build(
                new CacheLoader<String, CompletableFuture<OptionalDouble>>() {
                    @Override
                    public CompletableFuture<OptionalDouble> load(String host) throws Exception {
                        return PingWrapper.getLatency(host, IceAdapter.PING_COUNT)
                                .thenApply(OptionalDouble::of)
                                .exceptionally(ex -> OptionalDouble.empty());
                    }
                }
        );

        List<CoturnServer> coturnServers = new ArrayList<>();

        for (Map<String, Object> iceServerData : iceServersData) {
            IceServer iceServer = new IceServer();

            if (iceServerData.containsKey("username")) {
                iceServer.setTurnUsername((String) iceServerData.get("username"));
            }
            if (iceServerData.containsKey("credential")) {
                iceServer.setTurnCredential((String) iceServerData.get("credential"));
            }

            if (iceServerData.containsKey("urls")) {
                List<String> urls;
                Object urlsData = iceServerData.get("urls");
                if (urlsData instanceof List) {
                    urls = (List<String>) urlsData;
                } else {
                    urls = Collections.singletonList((String) iceServerData.get("url"));
                }

                urls.stream()
                        .map(IceServer.urlPattern::matcher)
                        .filter(Matcher::matches)
                        .forEach(matcher -> {
                            String host = matcher.group("host");
                            int port = Optional.ofNullable(matcher.group("port")).map(Integer::parseInt).orElse(3478);
                            Transport transport = Optional.ofNullable(matcher.group("transport")).map(Transport::parse).orElse(Transport.UDP);

                            TransportAddress address = new TransportAddress(host, port, transport);
                            (matcher.group("protocol").equals("stun") ? iceServer.getStunAddresses() : iceServer.getTurnAddresses()).add(address);

                            if (IceAdapter.PING_COUNT > 0) {
                                iceServer.setRoundTripTime(hostRTTCache.getUnchecked(host));
                            }
                        });
            }
            iceServers.add(iceServer);
        }

        log.info("Ice Servers set, total addresses: {}",
                iceServers.stream().mapToInt(s -> s.getStunAddresses().size() + s.getTurnAddresses().size()).sum());
    }
}
