# Websocket Protocol

The websocket protocol covers different use case:

1. It's a control plane for the FAF client (replacement for prior RPC connections).
2. It's an event stream for the web UI, notifying about the current state (replacement for prior JavaFX debug window).
3. A telemetry event stream for future debugging. (Opposed to 1 & 2 this requires the ICE adapter to act as a websocket client! Nevertheless it should use the same protocol.)

## Versioning

Nobody is perfect, so it is to assume that there will be changes in the protocol version sooner or later. The web ui and a potential telemetry server will be centrally deployed and as such must be compatible to multiple ice adapter versions. Therefore the protocol must be versioned. Web UI and telemetry server need to support multiple ice adapter protocols at once. 