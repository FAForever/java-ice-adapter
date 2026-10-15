package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.telemetry.CoturnServer;
import com.faforever.iceadapter.util.Pair;
import com.faforever.iceadapter.util.PingUtil;
import dev.onvoid.webrtc.RTCIceServer;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
@RequiredArgsConstructor
public class IceServer {

    public enum TransportProtocol {
        UDP,
        TCP;

        public static TransportProtocol parse(String str) {
            if (str != null && str.equalsIgnoreCase("tcp")) {
                return TCP;
            }
            return UDP;
        }
    }

    public record ServerAddress(String host, int port, TransportProtocol transport) {
        public String getHostName() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public TransportProtocol getTransport() {
            return transport;
        }

        @Override
        public String toString() {
            return host + ":" + port + (transport == TransportProtocol.TCP ? "?transport=tcp" : "");
        }
    }

    private static final List<ServerAddress> PUBLIC_STUN_SERVERS = List.of(
            new ServerAddress("stun.cloudflare.com", 3478, TransportProtocol.UDP),
            new ServerAddress("stun.l.google.com", 19302, TransportProtocol.UDP),
            new ServerAddress("stun.sipgate.net", 3478, TransportProtocol.UDP));

    private static final String STUN = "stun";
    private static final String TURN = "turn";
    private static final String TURNS = "turns";

    private final TypeServer type;
    private final ServerAddress address;
    private String turnUsername = "";
    private String turnCredential = "";
    private boolean enabled = true;
    private boolean auto = true;
    private CompletableFuture<OptionalDouble> roundTripTime = CompletableFuture.completedFuture(OptionalDouble.empty());

    public boolean hasAcceptableLatency(double latency) {
        OptionalDouble rtt = roundTripTime.join();
        return rtt.isEmpty() || rtt.getAsDouble() < latency;
    }

    public String strTripTime() {
        try {
            OptionalDouble rtt = roundTripTime.join();
            if (rtt.isPresent()) {
                return "%dms".formatted(Math.round(rtt.getAsDouble()));
            }

            return "-";
        } catch (Exception e) {
            return "Error";
        }
    }

    public boolean isTurn() {
        return type == TypeServer.TURN;
    }

    public RTCIceServer toWebRtcServer() {
        RTCIceServer webrtcServer = new RTCIceServer();
        String protocol = type == TypeServer.STUN ? "stun" : "turn";
        String host = address.host();
        int port = address.port();
        String transport = address.transport() == TransportProtocol.TCP ? "?transport=tcp" : "";
        webrtcServer.urls.add(protocol + ":" + host + ":" + port + transport);
        if (turnUsername != null && !turnUsername.isEmpty()) {
            webrtcServer.username = turnUsername;
        }
        if (turnCredential != null && !turnCredential.isEmpty()) {
            webrtcServer.password = turnCredential;
        }
        return webrtcServer;
    }

    public static List<IceServer> createPublicServers() {
        return PUBLIC_STUN_SERVERS.stream()
                .map(stunServer -> new IceServer(TypeServer.STUN, stunServer))
                .toList();
    }

    public static Pair<List<IceServer>, Set<CoturnServer>> mapperFromMap(List<Map<String, Object>> iceServersData) {
        List<IceServer> iceServers = new ArrayList<>();
        Set<CoturnServer> coturnServers = new HashSet<>();

        for (Map<String, Object> iceServerData : iceServersData) {

            if (iceServerData.containsKey("urls")) {
                List<String> urls;
                Object urlsData = iceServerData.get("urls");
                if (urlsData instanceof List) {
                    urls = (List<String>) urlsData;
                } else {
                    urls = Collections.singletonList((String) iceServerData.get("url"));
                }

                urls.stream()
                        .map(stringUrl -> {
                            try {
                                return new URI(stringUrl);
                            } catch (Exception e) {
                                log.warn("Invalid ICE server URI: {}", stringUrl);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .forEach(uri -> {
                            String host = uri.getHost();
                            int port = uri.getPort() == -1 ? 3478 : uri.getPort();
                            TransportProtocol transport = Optional.ofNullable(uri.getQuery()).stream()
                                    .flatMap(query -> Arrays.stream(query.split("&")))
                                    .map(param -> param.split("="))
                                    .filter(param -> param.length == 2)
                                    .filter(param -> param[0].equals("transport"))
                                    .map(param -> param[1])
                                    .map(TransportProtocol::parse)
                                    .findFirst()
                                    .orElse(TransportProtocol.UDP);

                            ServerAddress address = new ServerAddress(host, port, transport);
                            TypeServer type = TypeServer.TURN;
                            switch (uri.getScheme()) {
                                case STUN -> type = TypeServer.STUN;
                                case TURNS, TURN -> type = TypeServer.TURN;
                                default -> log.warn("Invalid ICE server protocol: {}", uri);
                            }
                            IceServer iceServer = new IceServer(type, address);
                            if (iceServerData.containsKey("username")) {
                                iceServer.setTurnUsername((String) iceServerData.get("username"));
                            }
                            if (iceServerData.containsKey("credential")) {
                                iceServer.setTurnCredential((String) iceServerData.get("credential"));
                            }
                            if (IceAdapter.getPingCount() > 0) {
                                iceServer.setRoundTripTime(PingUtil.getLatency(host));
                            }
                            iceServers.add(iceServer);

                            coturnServers.add(new CoturnServer("n/a", host, port, null));
                        });
            }
        }

        return new Pair<>(iceServers, coturnServers);
    }

    public enum TypeServer {
        STUN,
        TURN;
    }
}
