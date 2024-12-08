package com.faforever.iceadapter;

public interface FafRpcCallbacks {
    void onHostGame(String mapName);

    void onJoinGame(String remotePlayerLogin, int remotePlayerId);

    void onConnectToPeer(String remotePlayerLogin, int remotePlayerId, boolean offer);

    void onDisconnectFromPeer(int remotePlayerId);

    void close();

    void sendToGpgNet(String header, Object... args);
}
