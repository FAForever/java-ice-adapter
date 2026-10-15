package com.faforever.iceadapter;

public class UiStarter {

    public static void main(String[] args) {
        String[] stubArgs = {
            "--id=12345",
            "--game-id=67890",
            "--login=testUser",
            "--gpgnet-port=5000",
            "--rpc-port=5001",
            "--lobby-port=5002",
            "--show-allow-combination=false",
            "--manual-strategy-connection=false",
            "--debug-window=true",
            "--info-window=true"
        };

        IceAdapter.main(stubArgs);

        IceAdapter adapter = IceAdapter.INSTANCE;
        adapter.onHostGame("test");

        adapter.onConnectToPeer("Player3", 124, true);
        adapter.onConnectToPeer("Player4", 125, false);
        adapter.getGameSession().getPeers().forEach((integer, peer) -> {
            peer.setAllowRelay(true);
        });
    }
}
