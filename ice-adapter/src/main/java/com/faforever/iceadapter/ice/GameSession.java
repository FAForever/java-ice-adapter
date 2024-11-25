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

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.faforever.iceadapter.debug.Debug.debug;

/**
 * Represents a game session and the current ICE status/communication with all peers
 * Is created by a JoinGame or HostGame event (via RPC), is destroyed by a gpgnet connection breakdown
 */
@Slf4j
public class GameSession {

    private static final String STUN = "stun";
    private static final String TURN = "turn";
    @Getter
    private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();
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
    public int connectToPeer(String remotePlayerLogin, int remotePlayerId, boolean offer, int preferredPort) {
        Peer peer = new Peer(this, remotePlayerId, remotePlayerLogin, offer, preferredPort);
        peers.put(remotePlayerId, peer);
        debug().connectToPeer(remotePlayerId, remotePlayerLogin, offer);
        return peer.getFaSocket().getLocalPort();
    }

    /**
     * Disconnects from a peer (ICE)
     */
    public void disconnectFromPeer(int remotePlayerId) {
        Peer removedPeer = peers.remove(remotePlayerId);
        if (removedPeer != null) {
            removedPeer.close();
            debug().disconnectFromPeer(remotePlayerId);
        }
        //TODO: still testing connectivity and reporting disconnect via rpc, why???
        //TODO: still attempting to ICE
    }

    /**
     * Does a manual {@link #disconnectFromPeer} and {@link #connectToPeer}.
     * Uses the same port that was on the previous connection.
     */
    public void reconnectToPeer(Integer remotePlayerId) {
        Peer reconnectPeer = peers.get(remotePlayerId);
        if (Objects.nonNull(reconnectPeer)) {
            String remotePlayerLogin = reconnectPeer.getRemoteLogin();
            boolean offer = reconnectPeer.isLocalOffer();
            int port = reconnectPeer.getFaSocket()
                    .getLocalPort();

            disconnectFromPeer(remotePlayerId);
            connectToPeer(remotePlayerLogin, remotePlayerId, offer, port);
        }
    }

    /**
     * Stops the connection to all peers and all ice agents
     */
    public void close() {
        log.info("Closing gameSession");
        peers.values().forEach(Peer::close);
        peers.clear();
    }

    @Getter
    private static final List<IceServer> iceServers = new ArrayList<>();

    /**
     * Set ice servers (to be used for harvesting candidates)
     * Called by the client via jsonRPC
     */
    public static void setIceServers(List<Map<String, Object>> iceServersData) {
        GameSession.iceServers.clear();

        if (iceServersData.isEmpty()) {
            return;
        }

        // For caching RTT to a given host (the same host can appear in multiple urls)
        LoadingCache<String, CompletableFuture<OptionalDouble>> hostRTTCache = CacheBuilder.newBuilder()
                                                                                           .build(new CacheLoader<>() {
                                                                                               @Override
                                                                                               public CompletableFuture<OptionalDouble> load(String host) {
                                                                                                   return PingWrapper.getLatency(host, IceAdapter.PING_COUNT)
                                                                                                                     .thenApply(OptionalDouble::of)
                                                                                                                     .exceptionally(ex -> OptionalDouble.empty());
                                                                                               }
                                                                                           });

        Set<CoturnServer> coturnServers = new HashSet<>();

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

                urls.stream().map(stringUrl -> {
                    try {
                        return new URI(stringUrl);
                    } catch (Exception e) {
                        log.warn("Invalid ICE server URI: {}", stringUrl);
                        return null;
                    }
                }).filter(Objects::nonNull).forEach(uri -> {
                    String host = uri.getHost();
                    int port = uri.getPort() == -1 ? 3478 : uri.getPort();
                    Transport transport = Optional.ofNullable(uri.getQuery())
                                                  .stream()
                                                  .flatMap(query -> Arrays.stream(query.split("&")))
                                                  .map(param -> param.split("="))
                                                  .filter(param -> param.length == 2)
                                                  .filter(param -> param[0].equals("transport"))
                                                  .map(param -> param[1])
                                                  .map(Transport::parse)
                                                  .findFirst()
                                                  .orElse(Transport.UDP);

                    TransportAddress address = new TransportAddress(host, port, transport);
                    switch (uri.getScheme()) {
                        case STUN -> iceServer.getStunAddresses().add(address);
                        case TURN -> iceServer.getTurnAddresses().add(address);
                        default -> log.warn("Invalid ICE server protocol: {}", uri);
                    }

                    if (IceAdapter.PING_COUNT > 0) {
                        iceServer.setRoundTripTime(hostRTTCache.getUnchecked(host));
                    }

                    coturnServers.add(new CoturnServer("n/a", host, port, null));
                });
            }

            iceServers.add(iceServer);
        }

        debug().updateCoturnList(coturnServers);

        log.info("Ice Servers set, total addresses: {}",
                 iceServers.stream()
                           .mapToInt(iceServer -> iceServer.getStunAddresses().size() +
                                                  iceServer.getTurnAddresses().size())
                           .sum());
    }
}
