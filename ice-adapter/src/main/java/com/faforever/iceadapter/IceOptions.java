package com.faforever.iceadapter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import picocli.CommandLine.Option;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IceOptions {
    @Option(names = "--id", required = true, description = "set the ID of the local player")
    private int id;

    @Option(names = "--game-id", required = true, description = "set the ID of the game")
    private int gameId;

    @Option(names = "--login", required = true, description = "set the login of the local player e.g. \"Rhiza\"")
    private String login;

    @Option(names = "--rpc-port", defaultValue = "7236", description = "set the port of internal JSON-RPC server")
    private int rpcPort;

    @Option(names = "--gpgnet-port", defaultValue = "0", description = "set the port of internal GPGNet server")
    private int gpgnetPort;

    @Option(
            names = "--lobby-port",
            defaultValue = "0",
            description = "set the port the game lobby should use for incoming UDP packets from the PeerRelay")
    private int lobbyPort;

    @Option(names = "--force-relay", description = "force the usage of relay candidates only")
    private boolean forceRelay;

    @Option(
            names = "--min-port",
            defaultValue = "0",
            description = "set minimum port for WebRTC ICE candidates (0 = automatic)")
    private int minPort;

    @Option(
            names = "--max-port",
            defaultValue = "0",
            description = "set maximum port for WebRTC ICE candidates (0 = automatic)")
    private int maxPort;

    @Option(names = "--debug-window", description = "activate the debug window")
    private boolean debugWindow;

    @Option(names = "--info-window", description = "activate the info window")
    private boolean infoWindow;

    @Option(
            names = "--delay-ui",
            defaultValue = "0",
            description = "delays the launch of the info and debug window (in ms)")
    private int delayUi;

    @Option(
            names = "--ping-count",
            defaultValue = "1",
            description = "number of times to ping each turn server to determine latency")
    private int pingCount;

    @Option(
            names = "--acceptable-latency",
            defaultValue = "250.0",
            description = "number of times to ping each turn server to determine latency")
    private double acceptableLatency;

    @Option(
            names = "--telemetry-server",
            defaultValue = "wss://ice-telemetry.faforever.com",
            description = "Telemetry server to connect to")
    private String telemetryServer;

    @Option(
            names = "--manual-strategy-connection",
            defaultValue = "true",
            description = "Manually editing the connection strategy in the UI")
    private boolean manualStrategyConnection;

    @Option(
            names = {"--show-ip-addresses", "--show-peer-ips", "--show-ip"},
            defaultValue = "false",
            description = "Show IP addresses in the UI")
    private boolean showIpAddresses;

    @Option(
            names = "--allow-peer-relay",
            defaultValue = "false",
            description =
                    "Allows this peer to act as a relay host, forwarding packets for other peers when their direct connection is unstable. Increases bandwidth usage (may affect mobile or metered connections)")
    private boolean allowPeerRelay;

    @Option(
            names = {"--show-allow-combination", "--allow-combination"},
            defaultValue = "true",
            fallbackValue = "true",
            description = "Show AllowCombination selection in the UI")
    private boolean showAllowCombination;

    @Option(
            names = {"--additional-packet-forwarding", "--additional-forwarding"},
            defaultValue = "false",
            fallbackValue = "false",
            description = "Enable additional packet forwarding via relay by default for peers")
    private boolean additionalPacketForwarding = false;

    public boolean isAllowCombination() {
        return showAllowCombination;
    }

    public IceOptions(
            int id,
            int gameId,
            String login,
            int rpcPort,
            int gpgnetPort,
            int lobbyPort,
            boolean forceRelay,
            boolean debugWindow,
            boolean infoWindow,
            int delayUi,
            int pingCount,
            double acceptableLatency,
            String telemetryServer,
            boolean manualStrategyConnection,
            boolean showIpAddresses,
            boolean allowPeerRelay) {
        this(
                id,
                gameId,
                login,
                rpcPort,
                gpgnetPort,
                lobbyPort,
                forceRelay,
                debugWindow,
                infoWindow,
                delayUi,
                pingCount,
                acceptableLatency,
                telemetryServer,
                manualStrategyConnection,
                showIpAddresses,
                allowPeerRelay,
                false,
                true);
    }

    public IceOptions(
            int id,
            int gameId,
            String login,
            int rpcPort,
            int gpgnetPort,
            int lobbyPort,
            boolean forceRelay,
            boolean debugWindow,
            boolean infoWindow,
            int delayUi,
            int pingCount,
            double acceptableLatency,
            String telemetryServer,
            boolean manualStrategyConnection,
            boolean showIpAddresses,
            boolean allowPeerRelay,
            boolean showAllowCombination) {
        this(
                id,
                gameId,
                login,
                rpcPort,
                gpgnetPort,
                lobbyPort,
                forceRelay,
                debugWindow,
                infoWindow,
                delayUi,
                pingCount,
                acceptableLatency,
                telemetryServer,
                manualStrategyConnection,
                showIpAddresses,
                allowPeerRelay,
                showAllowCombination,
                true);
    }

    public IceOptions(
            int id,
            int gameId,
            String login,
            int rpcPort,
            int gpgnetPort,
            int lobbyPort,
            boolean forceRelay,
            boolean debugWindow,
            boolean infoWindow,
            int delayUi,
            int pingCount,
            double acceptableLatency,
            String telemetryServer,
            boolean manualStrategyConnection,
            boolean showIpAddresses,
            boolean allowPeerRelay,
            boolean showAllowCombination,
            boolean additionalPacketForwarding) {
        this(
                id,
                gameId,
                login,
                rpcPort,
                gpgnetPort,
                lobbyPort,
                forceRelay,
                0,
                0,
                debugWindow,
                infoWindow,
                delayUi,
                pingCount,
                acceptableLatency,
                telemetryServer,
                manualStrategyConnection,
                showIpAddresses,
                allowPeerRelay,
                showAllowCombination,
                additionalPacketForwarding);
    }
}
