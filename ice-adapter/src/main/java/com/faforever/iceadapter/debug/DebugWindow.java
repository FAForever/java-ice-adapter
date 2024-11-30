package com.faforever.iceadapter.debug;

import com.faforever.iceadapter.AsyncService;
import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.gpgnet.GPGNetServer;
import com.faforever.iceadapter.gpgnet.GameState;
import com.faforever.iceadapter.ice.Peer;
import com.faforever.iceadapter.ice.PeerConnectivityCheckerModule;
import com.nbarraille.jjsonrpc.JJsonPeer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class DebugWindow extends Application implements Debugger {
    public static CompletableFuture<DebugWindow> INSTANCE = new CompletableFuture<>();

    private Parent root;
    private Scene scene;
    private DebugWindowController controller;
    private Stage stage;

    private static final int WIDTH = 1200;
    private static final int HEIGHT = 700;

    private final ObservableList<DebugPeer> peers = FXCollections.observableArrayList();

    @Override
    public void start(Stage stage) {
        INSTANCE = CompletableFuture.completedFuture(this);
        Debug.register(this);

        this.stage = stage;
        stage.getIcons().add(new Image("https://faforever.com/images/faf-logo.png"));

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/debugWindow.fxml"));
            root = loader.load();

            controller = loader.getController();
            controller.peerTable.setItems(peers);

        } catch (IOException e) {
            log.error("Could not load debugger window fxml", e);
        }

        setUserAgentStylesheet(STYLESHEET_MODENA);

        scene = new Scene(root, WIDTH, HEIGHT);

        stage.setScene(scene);
        stage.setTitle("FAF ICE adapter - Debugger - Build: %s".formatted(IceAdapter.getVersion()));

        if (Debug.ENABLE_DEBUG_WINDOW) {
            AsyncService.executeDelayed(Debug.DELAY_UI_MS, () -> runOnUIThread(stage::show));
        }

        log.info("Created debug window.");

        if (Debug.ENABLE_INFO_WINDOW) {
            AsyncService.executeDelayed(Debug.DELAY_UI_MS, () -> runOnUIThread(() -> new InfoWindow().init()));
        }
    }

    public void showWindow() {
        runOnUIThread(() -> {
            stage.show();
            initStaticVariables();
            initPeers();
        });
    }

    @Override
    public void startupComplete() {
        initStaticVariables();
    }

    public void initStaticVariables() {
        runOnUIThread(() -> {
            controller.versionLabel.setText("Version: %s".formatted(IceAdapter.getVersion()));
            controller.userLabel.setText("User: %s(%d)".formatted(IceAdapter.getLogin(), IceAdapter.getId()));
            controller.rpcPortLabel.setText("RPC_PORT: %d".formatted(Debug.RPC_PORT));
            controller.gpgnetPortLabel.setText("GPGNET_PORT: %d".formatted(GPGNetServer.getGpgnetPort()));
            controller.lobbyPortLabel.setText("LOBBY_PORT: %d".formatted(GPGNetServer.getLobbyPort()));
        });
    }

    public void initPeers() {
        runOnUIThread(() -> {
            synchronized (peers) {
                peers.clear();
                for (Peer peer : IceAdapter.getGameSession().getPeers().values()) {
                    DebugPeer p = new DebugPeer(peer);
                    p.stateChangedUpdate(peer);
                    p.connectivityUpdate(peer);
                    peers.add(p);
                }
                peers.sort(DebugPeer::compareTo);
            }
        });
    }

    @Override
    public void rpcStarted(CompletableFuture<JJsonPeer> peerFuture) {
        runOnUIThread(() -> {
            controller.rpcServerStatus.setText("RPCServer: started");
        });
        peerFuture.thenAccept(peer -> runOnUIThread(() -> {
            controller.rpcClientStatus.setText(
                    "RPCClient: %s".formatted(peer.getSocket().getInetAddress()));
        }));
    }

    @Override
    public void gpgnetStarted() {
        runOnUIThread(() -> {
            controller.gpgnetServerStatus.setText("GPGNetServer: started");
        });
    }

    @Override
    public void gpgnetConnectedDisconnected() {
        runOnUIThread(() -> {
            controller.gpgnetServerStatus.setText(
                    "GPGNetClient: %s".formatted(GPGNetServer.isConnected() ? "connected" : "-"));
            gameStateChanged();
        });
    }

    @Override
    public void gameStateChanged() {
        runOnUIThread(() -> {
            controller.gameState.setText(String.format(
                    "GameState: %s",
                    GPGNetServer.getGameState().map(GameState::getName).orElse("-")));
        });
    }

    @Override
    public void connectToPeer(int id, String login, boolean localOffer) {
        runOnUIThread(() -> {
            synchronized (peers) {
                peers.add(new DebugPeer(id, login, localOffer)); // Might callback into jfx
                peers.sort(DebugPeer::compareTo);
            }
        });
    }

    @Override
    public void disconnectFromPeer(int id) {
        runOnUIThread(() -> {
            synchronized (peers) {
                peers.removeIf(peer -> peer.id.get() == id); // Might callback into jfx
                peers.sort(DebugPeer::compareTo);
            }
        });
    }

    @Override
    public void peerStateChanged(Peer peer) {
        runOnUIThread(() -> {
            synchronized (peers) {
                peers.stream().filter(p -> p.id.get() == peer.getRemoteId()).forEach(p -> {
                    p.stateChangedUpdate(peer);
                });
            }
        });
    }

    @Override
    public void peerConnectivityUpdate(Peer peer) {
        runOnUIThread(() -> {
            synchronized (peers) {
                peers.stream().filter(p -> p.id.get() == peer.getRemoteId()).forEach(p -> {
                    p.connectivityUpdate(peer);
                });
            }
        });
    }

    private void runOnUIThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    public static void launchApplication() {
        launch(DebugWindow.class, null);
    }

    @NoArgsConstructor
    @AllArgsConstructor
    // @Getter //PropertyValueFactory will attempt to access fieldNameProperty(), then getFieldName() (expecting value,
    // not property) and then isFieldName() methods
    public static class DebugPeer implements Comparable<DebugPeer> {
        public SimpleIntegerProperty id = new SimpleIntegerProperty(-1);
        public SimpleStringProperty login = new SimpleStringProperty("");
        public SimpleBooleanProperty localOffer = new SimpleBooleanProperty(false);
        public SimpleBooleanProperty connected = new SimpleBooleanProperty(false);
        public SimpleStringProperty state = new SimpleStringProperty("");
        public SimpleIntegerProperty averageRtt = new SimpleIntegerProperty(-1);
        public SimpleIntegerProperty lastReceived = new SimpleIntegerProperty(-1);
        public SimpleIntegerProperty echosReceived = new SimpleIntegerProperty(-1);
        public SimpleIntegerProperty invalidEchosReceived = new SimpleIntegerProperty(-1);
        public SimpleStringProperty localCandidate = new SimpleStringProperty("");
        public SimpleStringProperty remoteCandidate = new SimpleStringProperty("");

        public DebugPeer(Peer peer) {
            this(peer.getRemoteId(), peer.getRemoteLogin(), peer.isLocalOffer());
        }

        public DebugPeer(int id, String login, boolean localOffer) {
            this.id.set(id);
            this.login.set(login);
            this.localOffer.set(localOffer);
        }

        public int getId() {
            return id.get();
        }

        public SimpleIntegerProperty idProperty() {
            return id;
        }

        public String getLogin() {
            return login.get();
        }

        public SimpleStringProperty loginProperty() {
            return login;
        }

        public boolean isLocalOffer() {
            return localOffer.get();
        }

        public SimpleBooleanProperty localOfferProperty() {
            return localOffer;
        }

        public boolean isConnected() {
            return connected.get();
        }

        public SimpleBooleanProperty connectedProperty() {
            return connected;
        }

        public String getState() {
            return state.get();
        }

        public SimpleStringProperty stateProperty() {
            return state;
        }

        public double getAverageRtt() {
            return averageRtt.get();
        }

        public SimpleIntegerProperty averageRttProperty() {
            return averageRtt;
        }

        public int getLastReceived() {
            return lastReceived.get();
        }

        public SimpleIntegerProperty lastReceivedProperty() {
            return lastReceived;
        }

        public int getEchosReceived() {
            return echosReceived.get();
        }

        public int getInvalidEchosReceived() {
            return invalidEchosReceived.get();
        }

        public SimpleIntegerProperty echosReceivedProperty() {
            return echosReceived;
        }

        public SimpleIntegerProperty invalidEchosReceivedProperty() {
            return invalidEchosReceived;
        }

        public String getLocalCandidate() {
            return localCandidate.get();
        }

        public SimpleStringProperty localCandidateProperty() {
            return localCandidate;
        }

        public String getRemoteCandidate() {
            return remoteCandidate.get();
        }

        public SimpleStringProperty remoteCandidateProperty() {
            return remoteCandidate;
        }

        public void stateChangedUpdate(Peer peer) {
            connected.set(peer.getIce().isConnected());
            state.set(peer.getIce().getIceState().getMessage());
            localCandidate.set(Optional.ofNullable(peer.getIce().getComponent())
                    .map(Component::getSelectedPair)
                    .map(CandidatePair::getLocalCandidate)
                    .map(Candidate::getType)
                    .map(CandidateType::toString)
                    .orElse(""));
            remoteCandidate.set(Optional.ofNullable(peer.getIce().getComponent())
                    .map(Component::getSelectedPair)
                    .map(CandidatePair::getRemoteCandidate)
                    .map(Candidate::getType)
                    .map(CandidateType::toString)
                    .orElse(""));
        }

        public void connectivityUpdate(Peer peer) {
            Optional<PeerConnectivityCheckerModule> connectivityChecker =
                    Optional.ofNullable(peer.getIce().getConnectivityChecker());
            averageRtt.set(connectivityChecker
                    .map(PeerConnectivityCheckerModule::getAverageRTT)
                    .orElse(-1.0f)
                    .intValue());
            lastReceived.set(connectivityChecker
                    .map(PeerConnectivityCheckerModule::getLastPacketReceived)
                    .map(last -> System.currentTimeMillis() - last)
                    .orElse(-1L)
                    .intValue());
            echosReceived.set(connectivityChecker
                    .map(PeerConnectivityCheckerModule::getEchosReceived)
                    .orElse(-1L)
                    .intValue());
            echosReceived.set(connectivityChecker
                    .map(PeerConnectivityCheckerModule::getEchosReceived)
                    .orElse(-1L)
                    .intValue());
        }

        @Override
        public int compareTo(@NotNull DebugPeer o) {
            return Comparator.comparingLong(DebugPeer::getId).compare(this, o);
        }
    }
}
