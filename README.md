# ❆ faf-ice-adapter ❆

A modern, high-performance Peer-to-Peer (P2P) connection proxy for *Supreme Commander: Forged Alliance* using [ICE (Interactive Connectivity Establishment)](https://en.wikipedia.org/wiki/Interactive_Connectivity_Establishment) and native [WebRTC DataChannels](https://webrtc.org/).

`faf-ice-adapter` acts as a network bridge between the Forged Alliance Forever (FAF) client (e.g., Downlord's FAF Client) and the game executable (`ForgedAlliance.exe`). It manages NAT traversal, STUN/TURN candidate gathering, WebRTC peer sessions, UDP packet relaying, and fallback routing to provide robust, low-latency multiplayer connectivity.

---

## Key Features

* **Native WebRTC DataChannels:** Full migration to native WebRTC DataChannels via [`webrtc-java`](https://github.com/devonvoid/webrtc-java) for ultra-low latency, reliable/unreliable SCTP-over-DTLS transport (replacing legacy Ice4j implementations).
* **Peer-to-Peer Auto-Relay:** Peers in the same game session can act as intermediary relay hosts (`--allow-peer-relay`) to forward packets for other peers who cannot establish direct connectivity due to strict/symmetric NATs.
* **Multi-Path Packet Forwarding (Dual-Path):** Optional redundant packet forwarding (`--additional-packet-forwarding`, `--allow-combination`) sends packets simultaneously across both the direct WebRTC connection and the best available peer relay path. Redundant packets are deduplicated, drastically mitigating packet loss in poor network conditions.
* **Live Telemetry:** Built-in telemetry reporting (`--telemetry-server`) streaming connection statistics and candidate pairs to the FAF telemetry service for real-time monitoring and post-game diagnostics.
* **Modern JavaFX UI:** Built-in JavaFX 23 monitoring dashboard with a modern dark theme:
  * **Info Window (`--info-window`):** Compact floating status bar with quick actions (kill adapter, open telemetry web UI, minimize to system tray).
  * **Debug Window (`--debug-window`):** Comprehensive real-time metrics table tracking RTT, ICE states, candidate types (Host, Srflx, Relay), bytes/packets sent and received, peer search/filtering, and manual connection strategy controls.
  * **TURN Server Inspector:** Detailed latency measurements and reachability status for all configured ICE servers.
* **Robust GPGNet & JSON-RPC Bridge:** Fast, thread-safe bidirectional JSON-RPC server communicating with the FAF client and GPGNet socket server communicating with `ForgedAlliance.exe`.

---

## Architecture Overview

```
 ┌──────────────────────┐              JSON-RPC (TCP :7236)              ┌──────────────────────┐
 │      FAF Client      │ ◄────────────────────────────────────────────► │   faf-ice-adapter    │
 └──────────────────────┘                                                └──────────┬───────────┘
                                                                                    │
                                                                   GPGNet (TCP)     │  UDP Game Loop
                                                                   127.0.0.1:port   │  127.0.0.1:port
                                                                                    ▼
                                                                         ┌──────────────────────┐
                                                                         │  ForgedAlliance.exe  │
                                                                         └──────────────────────┘
                                                                                    ▲
                                                                                    │ WebRTC DataChannel
                                                                                    │ (UDP / DTLS / SCTP)
                                                                                    ▼
                                                                         ┌──────────────────────┐
                                                                         │     Remote Peer      │
                                                                         │  (faf-ice-adapter)   │
                                                                         └──────────────────────┘
```

### Module Structure

The project is structured as a multi-module Gradle project (`rootProject.name = 'java'`):

| Module | Role | Main Class | Description |
| :--- | :--- | :--- | :--- |
| `ice-adapter` | Production Binary | `com.faforever.iceadapter.IceAdapter` | The core adapter executable containing the JSON-RPC server, GPGNet server, WebRTC connection manager, Peer Relay modules, and JavaFX UI. |
| `client` | Test GUI Client | `client.TestClient` | Standalone JavaFX test client simulating FAF client behavior and signaling coordination. |
| `server` | Test Coordinator | `server.TestServer` | Mock signaling server for local multi-instance testing and integration tests. |
| `shared` | Common Library | — | Shared DTOs, network message definitions, utilities, and testing harnesses. |

---

## Requirements & Building

### Prerequisites
* **Java Development Kit (JDK):** Version 21 or newer.
* **Build Tool:** Gradle (wrapper included: `./gradlew` on Linux/macOS, `.\gradlew.bat` on Windows).
* **Supported Platforms:** Windows (`x86_64`, `aarch64`), Linux (`x86_64`, `aarch64`).

### Build Tasks

Execute Gradle commands from the project root:

| Command | Output Artifact | Description |
| :--- | :--- | :--- |
| `.\gradlew build` | — | Compiles all modules and runs the JUnit 5 test suite |
| `.\gradlew :ice-adapter:shadowJar` | `ice-adapter/build/libs/faf-ice-adapter-snapshot-<platform>.jar` | Builds the self-contained production shadow JAR |
| `.\gradlew :client:shadowJar` | `client/build/libs/client-snapshot-<platform>.jar` | Builds the standalone test client JAR |
| `.\gradlew :server:shadowJar` | `server/build/libs/server-snapshot-<platform>.jar` | Builds the standalone mock test server JAR |
| `.\gradlew createAllJars` | — | Compiles and packages shadow JARs for all three executables |
| `.\gradlew spotlessApply` | — | Automatically formats source code using Palantir Java Format & Cleanthat |

---

## Command-Line Arguments

The adapter uses [Picocli](https://picocli.info/) to parse CLI arguments.

### Launching the Adapter

```powershell
java -jar ice-adapter/build/libs/faf-ice-adapter-snapshot-win.jar --id 1 --game-id 12345 --login "Rhiza" [options]
```

### Argument Reference

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| **Mandatory Arguments** | | | |
| `--id` | `int` | *None* | **Required.** Numeric user ID of the local player. |
| `--game-id` | `int` | *None* | **Required.** Numeric session ID of the game. |
| `--login` | `String` | *None* | **Required.** Username/login of the local player (e.g. `"Rhiza"`). |
| **Networking & Ports** | | | |
| `--rpc-port` | `int` | `7236` | Port for the internal JSON-RPC TCP server. |
| `--gpgnet-port` | `int` | `0` | Port for the internal GPGNet TCP server (`0` allocates an ephemeral port). |
| `--lobby-port` | `int` | `0` | UDP port used by the game lobby to receive packets from the adapter (`0` allocates an ephemeral port). |
| `--min-port` | `int` | `0` | Minimum UDP port for WebRTC ICE candidates (`0` = automatic/unconstrained). |
| `--max-port` | `int` | `0` | Maximum UDP port for WebRTC ICE candidates (`0` = automatic/unconstrained). |
| **ICE & Relay Strategy** | | | |
| `--force-relay` | `boolean` | `false` | Force using TURN relay candidates only (disables direct host/reflexive candidates). |
| `--allow-peer-relay` | `boolean` | `true` | Allow this peer to act as an intermediary relay for other peers with poor direct connectivity. |
| `--additional-packet-forwarding`<br>`--additional-forwarding` | `boolean` | `true` | Enables duplicate packet forwarding via peer relay simultaneously with direct WebRTC delivery. |
| `--show-allow-combination`<br>`--allow-combination` | `boolean` | `true` | Expose the candidate combination selector (`ALL`, `REFLEXIVE_RELAY`, `HOST_RELAY`, `RELAY`) in the UI. |
| `--manual-strategy-connection` | `boolean` | `true` | Enable manual override of connection strategies in the UI. |
| **TURN Health Checking** | | | |
| `--ping-count` | `int` | `1` | Number of echo probes sent to each TURN server to determine round-trip latency. |
| `--acceptable-latency` | `double` | `250.0` | Maximum acceptable latency threshold (in ms) for selecting TURN servers. |
| **Telemetry & UI** | | | |
| `--telemetry-server` | `String` | `wss://ice-telemetry.faforever.com` | WebSocket endpoint for streaming live telemetry diagnostics. |
| `--info-window` | `boolean` | `false` | Display the compact JavaFX floating status bar. |
| `--debug-window` | `boolean` | `false` | Display the comprehensive JavaFX real-time peer monitoring dashboard. |
| `--delay-ui` | `int` | `0` | Delay launching UI windows (in milliseconds). |
| `--show-ip-addresses`<br>`--show-peer-ips`<br>`--show-ip` | `boolean` | `false` | Display peer IP addresses in the UI tables (disabled by default for privacy/streaming). |

---

## JSON-RPC Control Protocol

The adapter is controlled by the outer client via bidirectional [JSON-RPC 2.0](https://www.jsonrpc.org/specification) over TCP (default port `7236`).

### Methods (Client ➠ Adapter)

| Method | Parameters | Return | Description |
| :--- | :--- | :--- | :--- |
| `hostGame` | `mapName` (*string*) | *void* | Signals the adapter that the local player is hosting the game on the specified map. Initiates GPGNet `HostGame`. |
| `joinGame` | `remotePlayerLogin` (*string*), `remotePlayerId` (*int*) | *void* | Signals the adapter that the local player is joining an existing game. Initializes peer session in answer mode and sends GPGNet `JoinGame`. |
| `connectToPeer` | `remotePlayerLogin` (*string*), `remotePlayerId` (*int*), `offer` (*boolean*) | *void* | Connects to a remote peer. If `offer` is `true`, this adapter initiates the WebRTC offer; otherwise, it waits for an answer. |
| `disconnectFromPeer` | `remotePlayerId` (*int*) | *void* | Closes the WebRTC session and peer relay for the specified remote player. |
| `setLobbyInitMode` | `lobbyInitMode` (*string*) | *void* | Sets the game lobby initialization mode: `"normal"` (standard custom game lobby) or `"auto"` (automatch / ladder). |
| `iceMsg` | `remotePlayerId` (*int*), `msg` (*object* &#124; *string*) | *void* | Delivers a remote signaling message (SDP offer/answer or ICE candidate) received via the FAF server to the appropriate peer session. |
| `setIceServers` | `iceServers` (*array of objects*) | *void* | Configures STUN/TURN servers conforming to WebRTC `RTCIceServer` format. Must be called prior to `joinGame` or `connectToPeer`. |
| `sendToGpgNet` | `header` (*string*), `chunks` (*array*) | *void* | Sends a raw message directly to the game via the GPGNet protocol. |
| `status` | *none* | *string (JSON)* | *(Deprecated)* Returns a snapshot of the current adapter status, active relays, and connection states. |
| `quit` | *none* | *void* | Gracefully shuts down the adapter, terminates active sessions, and exits. |

### Notifications (Adapter ➠ Client)

| Notification | Parameters | Description |
| :--- | :--- | :--- |
| `onConnectionStateChanged` | `newState` (*string*) | Fired when `ForgedAlliance.exe` connects to or disconnects from the internal GPGNet server (`"Connected"` or `"Disconnected"`). |
| `onGpgNetMessageReceived` | `header` (*string*), `chunks` (*array*) | Forwarded when the game sends a message via GPGNet to the adapter. |
| `onIceMsg` | `localPlayerId` (*int*), `remotePlayerId` (*int*), `msg` (*string*) | Fired when the local adapter generates an SDP offer/answer or ICE candidate that must be relayed to the remote peer via the FAF server. |
| `onIceConnectionStateChanged` | `localPlayerId` (*int*), `remotePlayerId` (*int*), `state` (*string*) | Fired when the WebRTC ICE connection state transitions (`"NEW"`, `"CHECKING"`, `"CONNECTED"`, `"COMPLETED"`, `"FAILED"`, `"DISCONNECTED"`, `"CLOSED"`). |
| `onConnected` | `localPlayerId` (*int*), `remotePlayerId` (*int*), `connected` (*boolean*) | Fired when the P2P connection to the remote peer is established (`true`) or lost (`false`). |

---

## Data Structures

### 1. WebRTC ICE Message (`CandidatesMessage`)

Signaling payloads passed via `iceMsg` and `onIceMsg` are JSON representations of `CandidatesMessage`:

```json
{
  "srcId": 1,
  "destId": 2,
  "password": "v=0\r\no=- 12345 2 IN IP4 127.0.0.1...",
  "ufrag": "offer_ufrag_string",
  "candidates": [
    {
      "foundation": "1",
      "protocol": "udp",
      "priority": 2122260223,
      "ip": "192.168.1.100",
      "port": 54321,
      "type": "host",
      "generation": 0,
      "id": "audio",
      "relAddr": "",
      "relPort": 0
    }
  ]
}
```

* For SDP exchange, the `password` field carries the SDP string, while `ufrag` indicates `"offer"` or `"answer"`.
* For trickle ICE candidates, individual candidates are transmitted in the `candidates` array with their candidate `type` (`"host"`, `"srflx"`, `"prflx"`, or `"relay"`).

### 2. ICE Server Configuration (`setIceServers`)

Conforms to standard WebRTC `RTCIceServer` structures:

```json
[
  {
    "urls": [
      "stun:stun.faforever.com:3478"
    ]
  },
  {
    "urls": [
      "turn:turn.faforever.com:3478?transport=udp"
    ],
    "username": "sampleUser",
    "credential": "samplePassword"
  }
]
```

### 3. Status Structure (`status` method)

```json
{
  "version": "snapshot",
  "ice_servers_size": 2,
  "lobby_port": 50000,
  "init_mode": "normal",
  "options": {
    "player_id": 1,
    "player_login": "Rhiza",
    "rpc_port": 7236,
    "gpgnet_port": 55123
  },
  "gpgpnet": {
    "local_port": 55123,
    "connected": true,
    "game_state": "Lobby",
    "task_string": "-"
  },
  "relays": [
    {
      "remote_player_id": 2,
      "remote_player_login": "Geoff",
      "local_game_udp_port": 51000,
      "ice": {
        "offerer": true,
        "state": "Connected",
        "gathering_state": "Complete",
        "datachannel_state": "Open",
        "connected": true,
        "loc_cand_addr": "192.168.1.100:54321",
        "rem_cand_addr": "203.0.113.50:61234",
        "loc_cand_type": "host",
        "rem_cand_type": "srflx",
        "time_to_connected": 0.42
      }
    }
  ]
}
```

---

## Connection Sequence Example

```mermaid
sequenceDiagram
    autonumber
    participant FA as ForgedAlliance.exe
    participant ICE as faf-ice-adapter
    participant Client as FAF Client
    participant Server as FAF Server / Remote Peer

    Client->>ICE: Launch process (--id 1, --game-id 100, --login "Alice", --rpc-port 7236)
    Client->>ICE: setIceServers([...])
    Client->>FA: Launch with /gpgnet 127.0.0.1:GPGNET_PORT
    FA->>ICE: TCP Connect to GPGNet server
    ICE-->>Client: onConnectionStateChanged("Connected")
    FA->>ICE: GameState "Idle"
    ICE-->>Client: onGpgNetMessageReceived("GameState", ["Idle"])

    alt Host Game
        Client->>ICE: hostGame("scmp_001")
        ICE->>FA: HostGame "scmp_001"
    else Join Game
        Client->>ICE: joinGame("Bob", 2)
        ICE->>FA: JoinGame "127.0.0.1:PORT" "Bob" 2
    end

    Client->>ICE: connectToPeer("Bob", 2, true)
    ICE-->>Client: onIceMsg(1, 2, candidatesMessage)
    Client->>Server: Forward ICE Message to Bob
    Server->>Client: Forward Bob's ICE Message
    Client->>ICE: iceMsg(2, bobCandidatesMessage)

    Note over ICE: WebRTC Handshake & DTLS/SCTP Setup
    ICE-->>Client: onIceConnectionStateChanged(1, 2, "CONNECTED")
    ICE-->>Client: onConnected(1, 2, true)
    FA<-->>ICE: Local UDP Game Packets
    ICE<-->>Server: Encrypted WebRTC DataChannel Packets
```

---

## Telemetry Web UI

When running with telemetry enabled, live connection stats and topological routing graphs can be viewed directly in your web browser:

```
https://ice-telemetry.faforever.com/app.html?gameId=<GAME_ID>&playerId=<PLAYER_ID>
```

*(You can also click the **"Show Telemetry Web UI"** button inside the JavaFX Info Window).*

---

## Development & Code Style

* **Code Formatting:** The codebase enforces strict formatting with [Spotless](https://github.com/diffplug/spotless) using **Palantir Java Format** and **Cleanthat**:
  ```powershell
  .\gradlew spotlessApply
  ```
* **Lombok:** Extensively used for getters, setters, and logging (`@Slf4j`). Make sure annotation processing is enabled in your IDE.
* **Imports:** Do not use fully qualified class names (FQCN) in code unless required to resolve name conflicts.

---

## License

This project is licensed under the MIT License - see the `LICENSE` file for details.
