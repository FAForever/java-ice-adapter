package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.PeerView;
import com.faforever.iceadapter.dto.WebRtcPeerView;
import com.faforever.iceadapter.ice.peer.modules.AllowCombination;
import com.faforever.iceadapter.services.UIAdapter;
import com.faforever.iceadapter.ui.IceServerWindow;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Callback;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NoArgsConstructor
public class WindowController {

    @FXML
    private BorderPane root;

    @FXML
    private Button killAdapterButton;

    @FXML
    private Label versionLabel;

    @FXML
    private Label userLabel;

    @FXML
    private Label rpcPortLabel;

    @FXML
    private Label gpgnetPortLabel;

    @FXML
    private Label lobbyPortLabel;

    @FXML
    private Label peerCountLabel;

    @FXML
    private Label rpcServerStatus;

    @FXML
    private Label rpcClientStatus;

    @FXML
    private Label gpgnetServerStatus;

    @FXML
    private Label gpgnetClientStatus;

    @FXML
    private Label gameState;

    @FXML
    private SplitPane mainSplitPane;

    @FXML
    private TextField searchField;

    @FXML
    private ComboBox<String> statusFilterComboBox;

    @FXML
    private TableView<PeerView> peerTable;

    @FXML
    private TableColumn<PeerView, Integer> idColumn;

    @FXML
    private TableColumn<PeerView, String> loginColumn;

    @FXML
    private TableColumn<PeerView, String> localCandColumn;

    @FXML
    private TableColumn<PeerView, String> remoteCandColumn;

    @FXML
    private TableColumn<PeerView, String> stateColumn;

    @FXML
    private TableColumn<PeerView, String> offerColumn;

    @FXML
    private TableColumn<PeerView, String> directRttColumn;

    @FXML
    private TableColumn<PeerView, String> relayRttColumn;

    @FXML
    private TableColumn<PeerView, String> lastColumn;

    @FXML
    private TableColumn<PeerView, String> lastRelayColumn;

    @FXML
    private TableColumn<PeerView, String> echosRcvColumn;

    @FXML
    private TableColumn<PeerView, Boolean> hostColumn;

    @FXML
    private TableColumn<PeerView, Boolean> reflexiveColumn;

    @FXML
    private TableColumn<PeerView, Boolean> relayColumn;

    @FXML
    private TableColumn<PeerView, Boolean> relaySupport;

    // Inspector Pane Elements
    @FXML
    private VBox inspectorContainer;

    @FXML
    private VBox selectPeerActionPane;

    @FXML
    private VBox peerActionPane;

    @FXML
    private Label peerActionTitle;

    @FXML
    private Label peerActionIdLabel;

    @FXML
    private Label peerHeaderStateBadge;

    @FXML
    private Button reconnectPeerButton;

    @FXML
    private CheckBox additionalPacketForwardingCheckbox;

    @FXML
    private VBox allowCombinationPane;

    @FXML
    private ComboBox<AllowCombination> allowCombinationComboBox;

    // WebRTC Inspector Fields
    @FXML
    private Label webrtcPeerStateLabel;

    @FXML
    private Label webrtcIceStateLabel;

    @FXML
    private Label webrtcDtlsStateLabel;

    @FXML
    private Label webrtcNominatedLabel;

    @FXML
    private Label webrtcRttLabel;

    @FXML
    private Label webrtcEchoRttLabel;

    @FXML
    private Label webrtcRelayRttLabel;

    @FXML
    private Label webrtcLocalCandidateLabel;

    @FXML
    private Label webrtcRemoteCandidateLabel;

    @FXML
    private Label webrtcChannelLabel;

    @FXML
    private Label webrtcChannelStateLabel;

    @FXML
    private Label webrtcBytesSentLabel;

    @FXML
    private Label webrtcBytesRecvLabel;

    @FXML
    private Label webrtcMessagesSentLabel;

    @FXML
    private Label webrtcMessagesRecvLabel;

    @FXML
    private Label webrtcPacketsSentLabel;

    @FXML
    private Label webrtcPacketsRecvLabel;

    @FXML
    private Label webrtcPacketsDiscardedLabel;

    @FXML
    private Label webrtcBitrateOutLabel;

    @FXML
    private Label webrtcBitrateInLabel;

    // Matrix Tables
    @FXML
    private TableView<WebRtcPeerView> matrixConnectionTable;

    @FXML
    private TableColumn<WebRtcPeerView, Integer> mConnPeerIdCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mConnLoginCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mConnPeerConnCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mConnIceConnCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mConnDtlsCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mConnRttCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mConnEchoRttCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mConnPairStateCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mConnNominatedCol;

    @FXML
    private TableView<WebRtcPeerView> matrixCandidatesTable;

    @FXML
    private TableColumn<WebRtcPeerView, Integer> mCandPeerIdCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mCandLoginCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mCandLocalTypeCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mCandLocalAddrCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mCandRemoteTypeCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mCandRemoteAddrCol;

    @FXML
    private TableView<WebRtcPeerView> matrixDataChannelTable;

    @FXML
    private TableColumn<WebRtcPeerView, Integer> mChanPeerIdCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mChanLoginCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mChanLabelCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mChanStateCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mChanMsgSentCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mChanMsgRecvCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mChanBytesSentCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mChanBytesRecvCol;

    @FXML
    private TableView<WebRtcPeerView> matrixTransportTable;

    @FXML
    private TableColumn<WebRtcPeerView, Integer> mTransPeerIdCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mTransLoginCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mTransOutBitrateCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mTransInBitrateCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mTransPacketsSentCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mTransPacketsRecvCol;

    @FXML
    private TableColumn<WebRtcPeerView, String> mTransPacketsDiscardedCol;

    private UIAdapter adapter;
    private ScheduledExecutorService updateScheduler;

    private FilteredList<PeerView> filteredPeers;
    private PeerView selectedPeer;

    private String lastVersion;
    private String lastUser;
    private int lastRpcPort = Integer.MIN_VALUE;
    private int lastGpgnetPort = Integer.MIN_VALUE;
    private int lastLobbyPort = Integer.MIN_VALUE;
    private String lastRpcServerStatus;
    private String lastRpcClientStatus;
    private String lastGpgnetServerStatus;
    private String lastGpgnetClientStatus;
    private String lastGameState;

    public void openSettingsStunAndTurn() {
        CompletableFuture.runAsync(() -> runOnUIThread(IceServerWindow::launch));
    }

    public void initialize() {
        setupButtonActions();
        setupFilters();
        setupPeerTable();
        setupMatrixTables();
        startPeriodicUpdates();
    }

    private void setupFilters() {
        if (statusFilterComboBox != null) {
            statusFilterComboBox.getItems().setAll("All Statuses", "Connected", "Checking", "Disconnected / Failed");
            statusFilterComboBox.getSelectionModel().selectFirst();
            statusFilterComboBox.setOnAction(e -> applyFilterPredicate());
        }

        if (searchField != null) {
            searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilterPredicate());
        }
    }

    private void applyFilterPredicate() {
        if (filteredPeers == null) {
            return;
        }

        String search = searchField != null && searchField.getText() != null
                ? searchField.getText().trim().toLowerCase()
                : "";
        String statusFilter = statusFilterComboBox != null && statusFilterComboBox.getValue() != null
                ? statusFilterComboBox.getValue()
                : "All Statuses";

        filteredPeers.setPredicate(peer -> {
            if (peer == null) {
                return false;
            }

            // Status Filter
            if (!"All Statuses".equals(statusFilter)) {
                String state = peer.getState().get();
                if (state == null) {
                    state = "";
                }
                state = state.toUpperCase();

                if ("Connected".equals(statusFilter) && !state.contains("CONNECTED")) {
                    return false;
                } else if ("Checking".equals(statusFilter) && !state.contains("CHECKING")) {
                    return false;
                } else if ("Disconnected / Failed".equals(statusFilter)
                        && (state.contains("CONNECTED") || state.contains("CHECKING"))) {
                    return false;
                }
            }

            // Search Text Filter
            if (search.isEmpty()) {
                return true;
            }

            String login = peer.getLogin().get();
            if (login != null && login.toLowerCase().contains(search)) {
                return true;
            }

            String idStr = String.valueOf(peer.getId().get());
            return idStr.contains(search);
        });
    }

    public void setAdapter(UIAdapter adapter) {
        this.adapter = adapter;
        Platform.runLater(this::bindDataToTables);
        Platform.runLater(this::updateAllInfo);
    }

    private void bindDataToTables() {
        if (adapter == null) {
            return;
        }

        ObservableList<PeerView> peerList = adapter.getPeerInfoList();
        filteredPeers = new FilteredList<>(peerList, p -> true);
        SortedList<PeerView> sortedPeers = new SortedList<>(filteredPeers);
        sortedPeers.comparatorProperty().bind(peerTable.comparatorProperty());
        peerTable.setItems(sortedPeers);

        ObservableList<WebRtcPeerView> webRtcList = adapter.getWebRtcPeerInfoList();
        if (matrixConnectionTable != null) {
            SortedList<WebRtcPeerView> sortedConn = new SortedList<>(webRtcList);
            sortedConn.comparatorProperty().bind(matrixConnectionTable.comparatorProperty());
            matrixConnectionTable.setItems(sortedConn);
        }
        if (matrixCandidatesTable != null) {
            SortedList<WebRtcPeerView> sortedCand = new SortedList<>(webRtcList);
            sortedCand.comparatorProperty().bind(matrixCandidatesTable.comparatorProperty());
            matrixCandidatesTable.setItems(sortedCand);
            boolean showIp = adapter.isShowIpAddresses();
            if (mCandLocalAddrCol != null) {
                mCandLocalAddrCol.setVisible(showIp);
            }
            if (mCandRemoteAddrCol != null) {
                mCandRemoteAddrCol.setVisible(showIp);
            }
        }
        if (matrixDataChannelTable != null) {
            SortedList<WebRtcPeerView> sortedChan = new SortedList<>(webRtcList);
            sortedChan.comparatorProperty().bind(matrixDataChannelTable.comparatorProperty());
            matrixDataChannelTable.setItems(sortedChan);
        }
        if (matrixTransportTable != null) {
            SortedList<WebRtcPeerView> sortedTrans = new SortedList<>(webRtcList);
            sortedTrans.comparatorProperty().bind(matrixTransportTable.comparatorProperty());
            matrixTransportTable.setItems(sortedTrans);
        }
    }

    private void setupButtonActions() {
        killAdapterButton.setOnAction(event -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Confirm Adapter Termination");
            alert.setHeaderText("Disconnect and Stop ICE Adapter?");
            alert.setContentText(
                    "This will gracefully shut down the network proxy and disconnect your Supreme Commander game session.");
            alert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK && adapter != null) {
                    adapter.shutdown();
                }
            });
        });
    }

    private void setupPeerTable() {
        idColumn.setCellValueFactory(cellData -> cellData.getValue().getId().asObject());
        loginColumn.setCellValueFactory(cellData -> cellData.getValue().getLogin());

        localCandColumn.setCellValueFactory(cellData -> cellData.getValue().getLocalCand());
        remoteCandColumn.setCellValueFactory(cellData -> cellData.getValue().getRemoteCand());
        stateColumn.setCellValueFactory(cellData -> cellData.getValue().getState());
        stateColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label badge = new Label(item);
                    badge.getStyleClass().add("badge");
                    String upper = item.toUpperCase();
                    if (upper.contains("CONNECTED") || upper.contains("COMPLETED")) {
                        badge.getStyleClass().add("badge-success");
                    } else if (upper.contains("CHECKING") || upper.contains("NEW")) {
                        badge.getStyleClass().add("badge-warning");
                    } else if (upper.contains("FAILED") || upper.contains("DISCONNECTED")) {
                        badge.getStyleClass().add("badge-danger");
                    } else {
                        badge.getStyleClass().add("badge-neutral");
                    }
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        offerColumn.setCellValueFactory(cellData -> cellData.getValue().getOffer());
        Comparator<String> rttComparator = (s1, s2) -> {
            if ("–".equals(s1) || "-".equals(s1) || s1 == null || s1.isBlank()) {
                return 1;
            }
            if ("–".equals(s2) || "-".equals(s2) || s2 == null || s2.isBlank()) {
                return -1;
            }
            try {
                return Double.compare(Double.parseDouble(s1), Double.parseDouble(s2));
            } catch (NumberFormatException e) {
                return s1.compareTo(s2);
            }
        };

        directRttColumn.setCellValueFactory(cellData -> cellData.getValue().getDirectRtt());
        directRttColumn.setCellFactory(createRttCellFactory(false));
        directRttColumn.setComparator(rttComparator);

        relayRttColumn.setCellValueFactory(cellData -> cellData.getValue().getRelayRtt());
        relayRttColumn.setCellFactory(createRttCellFactory(true));
        relayRttColumn.setComparator(rttComparator);

        lastColumn.setCellValueFactory(cellData -> cellData.getValue().getLastRecv());
        lastRelayColumn.setCellValueFactory(cellData -> cellData.getValue().getLastRelayRecv());
        echosRcvColumn.setCellValueFactory(cellData -> cellData.getValue().getEchosReceived());

        hostColumn.setCellValueFactory(
                param -> param.getValue().getAdditionalInfo().getAllowHost());
        hostColumn.setCellFactory(createCheckBoxCellFactory());
        reflexiveColumn.setCellValueFactory(
                peer -> peer.getValue().getAdditionalInfo().getAllowReflexive());
        reflexiveColumn.setCellFactory(createCheckBoxCellFactory());
        relayColumn.setCellValueFactory(
                peer -> peer.getValue().getAdditionalInfo().getAllowRelay());
        relayColumn.setCellFactory(createCheckBoxCellFactory());

        relaySupport.setCellValueFactory(peer -> peer.getValue().getPeerRelaySupport());
        relaySupport.setCellFactory(createCheckBoxCellFactory());

        peerTable.setRowFactory(tv -> {
            TableRow<PeerView> row = new TableRow<>();
            ContextMenu contextMenu = new ContextMenu();

            MenuItem reconnectItem = new MenuItem("Reconnect Peer");
            reconnectItem.setOnAction(e -> {
                PeerView peer = row.getItem();
                if (peer != null && adapter != null) {
                    adapter.reconnect(peer);
                }
            });

            MenuItem copyLoginItem = new MenuItem("Copy Login");
            copyLoginItem.setOnAction(e -> {
                PeerView peer = row.getItem();
                if (peer != null) {
                    copyToClipboard(peer.getLogin().get());
                }
            });

            MenuItem copyIdItem = new MenuItem("Copy ID");
            copyIdItem.setOnAction(e -> {
                PeerView peer = row.getItem();
                if (peer != null) {
                    copyToClipboard(String.valueOf(peer.getId().get()));
                }
            });

            contextMenu.getItems().addAll(reconnectItem, new SeparatorMenuItem(), copyLoginItem, copyIdItem);

            row.contextMenuProperty()
                    .bind(Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(contextMenu));
            return row;
        });

        peerTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            setSelectedPeer(newVal);
        });

        reconnectPeerButton.setOnAction(e -> {
            if (selectedPeer != null && adapter != null) {
                adapter.reconnect(selectedPeer);
            }
        });

        additionalPacketForwardingCheckbox.setOnAction(e -> {
            if (selectedPeer != null && adapter != null) {
                adapter.setAdditionalPacketForwarding(selectedPeer, additionalPacketForwardingCheckbox.isSelected());
            }
        });

        if (allowCombinationComboBox != null) {
            allowCombinationComboBox.getItems().setAll(AllowCombination.values());
            allowCombinationComboBox.setOnAction(e -> {
                if (selectedPeer != null && adapter != null) {
                    AllowCombination combination = allowCombinationComboBox.getValue();
                    if (combination != null) {
                        adapter.setCombination(selectedPeer, combination);
                    }
                }
            });
        }
    }

    private void setupMatrixTables() {
        if (matrixConnectionTable == null) {
            return;
        }

        // Connection Tab
        mConnPeerIdCol.setCellValueFactory(
                cellData -> cellData.getValue().getPeerId().asObject());
        mConnLoginCol.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        mConnPeerConnCol.setCellValueFactory(cellData -> cellData.getValue().getPeerConnectionState());
        mConnIceConnCol.setCellValueFactory(cellData -> cellData.getValue().getIceConnectionState());
        mConnDtlsCol.setCellValueFactory(cellData -> cellData.getValue().getDtlsState());
        mConnRttCol.setCellValueFactory(cellData -> cellData.getValue().getRttMs());
        mConnEchoRttCol.setCellValueFactory(cellData -> cellData.getValue().getEchoRttMs());
        mConnPairStateCol.setCellValueFactory(cellData -> cellData.getValue().getSelectedPairState());
        mConnNominatedCol.setCellValueFactory(cellData -> cellData.getValue().getNominated());

        // Candidates Tab
        mCandPeerIdCol.setCellValueFactory(
                cellData -> cellData.getValue().getPeerId().asObject());
        mCandLoginCol.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        mCandLocalTypeCol.setCellValueFactory(cellData -> cellData.getValue().getLocalCandidateType());
        mCandLocalAddrCol.setCellValueFactory(cellData -> cellData.getValue().getLocalAddress());
        mCandRemoteTypeCol.setCellValueFactory(cellData -> cellData.getValue().getRemoteCandidateType());
        mCandRemoteAddrCol.setCellValueFactory(cellData -> cellData.getValue().getRemoteAddress());

        boolean showIp = adapter != null && adapter.isShowIpAddresses();
        mCandLocalAddrCol.setVisible(showIp);
        mCandRemoteAddrCol.setVisible(showIp);

        // DataChannels Tab
        mChanPeerIdCol.setCellValueFactory(
                cellData -> cellData.getValue().getPeerId().asObject());
        mChanLoginCol.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        mChanLabelCol.setCellValueFactory(cellData -> cellData.getValue().getDataChannelLabel());
        mChanStateCol.setCellValueFactory(cellData -> cellData.getValue().getDataChannelState());
        mChanMsgSentCol.setCellValueFactory(cellData -> cellData.getValue().getMessagesSent());
        mChanMsgRecvCol.setCellValueFactory(cellData -> cellData.getValue().getMessagesReceived());
        mChanBytesSentCol.setCellValueFactory(cellData -> cellData.getValue().getBytesSent());
        mChanBytesRecvCol.setCellValueFactory(cellData -> cellData.getValue().getBytesReceived());

        // Transport Tab
        mTransPeerIdCol.setCellValueFactory(
                cellData -> cellData.getValue().getPeerId().asObject());
        mTransLoginCol.setCellValueFactory(cellData -> cellData.getValue().getLogin());
        mTransOutBitrateCol.setCellValueFactory(cellData -> cellData.getValue().getAvailableOutgoingBitrate());
        mTransInBitrateCol.setCellValueFactory(cellData -> cellData.getValue().getAvailableIncomingBitrate());
        mTransPacketsSentCol.setCellValueFactory(cellData -> cellData.getValue().getPacketsSent());
        mTransPacketsRecvCol.setCellValueFactory(cellData -> cellData.getValue().getPacketsReceived());
        mTransPacketsDiscardedCol.setCellValueFactory(
                cellData -> cellData.getValue().getPacketsDiscarded());
    }

    @FXML
    private void closePeerManagerPanel() {
        peerActionPane.setVisible(false);
        peerActionPane.setManaged(false);
        selectPeerActionPane.setVisible(true);
        selectPeerActionPane.setManaged(true);
        selectedPeer = null;
        peerTable.getSelectionModel().clearSelection();
    }

    private void setSelectedPeer(PeerView peer) {
        boolean hasPeer = peer != null;
        peerActionPane.setVisible(hasPeer);
        peerActionPane.setManaged(hasPeer);
        selectPeerActionPane.setVisible(!hasPeer);
        selectPeerActionPane.setManaged(!hasPeer);

        if (peer == null) {
            selectedPeer = null;
            return;
        }

        selectedPeer = peer;

        peerActionTitle.setText(peer.getLogin().get());
        if (peerActionIdLabel != null) {
            peerActionIdLabel.setText("#" + peer.getId().get());
        }

        if (peerHeaderStateBadge != null) {
            String state = peer.getState().get();
            peerHeaderStateBadge.setText(state != null ? state : "UNKNOWN");
            peerHeaderStateBadge.getStyleClass().removeAll("badge-success", "badge-warning", "badge-danger");
            if (state != null && state.toUpperCase().contains("CONNECTED")) {
                peerHeaderStateBadge.getStyleClass().add("badge-success");
            } else if (state != null && state.toUpperCase().contains("CHECKING")) {
                peerHeaderStateBadge.getStyleClass().add("badge-warning");
            } else {
                peerHeaderStateBadge.getStyleClass().add("badge-danger");
            }
        }

        selectCheckBox(
                additionalPacketForwardingCheckbox,
                peer.getAdditionalInfo().getSendDirectAndRelay().get());

        boolean showAllowCombination = adapter != null && adapter.isShowAllowCombination();
        if (allowCombinationPane != null) {
            allowCombinationPane.setVisible(showAllowCombination);
            allowCombinationPane.setManaged(showAllowCombination);
        }

        if (showAllowCombination && allowCombinationComboBox != null && peer.getAdditionalInfo() != null) {
            selectComboBox(allowCombinationComboBox, peer.getAdditionalInfo().getCombination());
        }

        // Live WebRTC Diagnostics update for selected peer
        updateWebRtcDiagnostics(peer.getId().get());
    }

    private void updateWebRtcDiagnostics(int peerId) {
        if (adapter == null || webrtcPeerStateLabel == null) {
            return;
        }

        WebRtcPeerView webRtc = null;
        for (WebRtcPeerView view : adapter.getWebRtcPeerInfoList()) {
            if (view.getPeerId().get() == peerId) {
                webRtc = view;
                break;
            }
        }

        if (webRtc != null) {
            webrtcPeerStateLabel.setText(webRtc.getPeerConnectionState().get());
            webrtcIceStateLabel.setText(webRtc.getIceConnectionState().get());
            webrtcDtlsStateLabel.setText(webRtc.getDtlsState().get());
            webrtcNominatedLabel.setText(webRtc.getNominated().get());

            webrtcRttLabel.setText(webRtc.getRttMs().get() + " ms");
            webrtcEchoRttLabel.setText(webRtc.getEchoRttMs().get() + " ms");
            if (webrtcRelayRttLabel != null) {
                if (selectedPeer != null
                        && !"–".equals(selectedPeer.getRelayRtt().get())
                        && !"-".equals(selectedPeer.getRelayRtt().get())
                        && selectedPeer.getRelayRtt().get() != null
                        && !selectedPeer.getRelayRtt().get().isBlank()) {
                    String rttVal = selectedPeer.getRelayRtt().get();
                    String rLogin = selectedPeer.getRelayLogin().get();
                    webrtcRelayRttLabel.setText(
                            rttVal + " ms" + (rLogin != null && !rLogin.isBlank() ? " (" + rLogin + ")" : ""));
                } else {
                    webrtcRelayRttLabel.setText("-");
                }
            }

            boolean showIp = adapter != null && adapter.isShowIpAddresses();
            String localAddr = webRtc.getLocalAddress().get();
            if (showIp && localAddr != null && !localAddr.isEmpty() && !"-".equals(localAddr)) {
                webrtcLocalCandidateLabel.setText(webRtc.getLocalCandidateType().get() + " (" + localAddr + ")");
            } else {
                webrtcLocalCandidateLabel.setText(webRtc.getLocalCandidateType().get());
            }

            String remoteAddr = webRtc.getRemoteAddress().get();
            if (showIp && remoteAddr != null && !remoteAddr.isEmpty() && !"-".equals(remoteAddr)) {
                webrtcRemoteCandidateLabel.setText(
                        webRtc.getRemoteCandidateType().get() + " (" + remoteAddr + ")");
            } else {
                webrtcRemoteCandidateLabel.setText(
                        webRtc.getRemoteCandidateType().get());
            }

            webrtcChannelLabel.setText(webRtc.getDataChannelLabel().get());
            webrtcChannelStateLabel.setText(webRtc.getDataChannelState().get());
            webrtcBytesSentLabel.setText(webRtc.getBytesSent().get());
            webrtcBytesRecvLabel.setText(webRtc.getBytesReceived().get());
            webrtcMessagesSentLabel.setText(webRtc.getMessagesSent().get());
            webrtcMessagesRecvLabel.setText(webRtc.getMessagesReceived().get());

            webrtcPacketsSentLabel.setText(webRtc.getPacketsSent().get());
            webrtcPacketsRecvLabel.setText(webRtc.getPacketsReceived().get());
            webrtcPacketsDiscardedLabel.setText(webRtc.getPacketsDiscarded().get());

            webrtcBitrateOutLabel.setText(webRtc.getAvailableOutgoingBitrate().get());
            webrtcBitrateInLabel.setText(webRtc.getAvailableIncomingBitrate().get());
        } else {
            webrtcPeerStateLabel.setText("-");
            webrtcIceStateLabel.setText("-");
            webrtcDtlsStateLabel.setText("-");
            webrtcNominatedLabel.setText("-");
            webrtcRttLabel.setText("-");
            webrtcEchoRttLabel.setText("-");
            if (webrtcRelayRttLabel != null) {
                webrtcRelayRttLabel.setText("-");
            }
            webrtcLocalCandidateLabel.setText("-");
            webrtcRemoteCandidateLabel.setText("-");
            webrtcChannelLabel.setText("-");
            webrtcChannelStateLabel.setText("-");
            webrtcBytesSentLabel.setText("-");
            webrtcBytesRecvLabel.setText("-");
            webrtcMessagesSentLabel.setText("-");
            webrtcMessagesRecvLabel.setText("-");
            webrtcPacketsSentLabel.setText("-");
            webrtcPacketsRecvLabel.setText("-");
            webrtcPacketsDiscardedLabel.setText("-");
            webrtcBitrateOutLabel.setText("-");
            webrtcBitrateInLabel.setText("-");
        }
    }

    private void selectCheckBox(CheckBox checkBox, boolean select) {
        var oldGetOnAction = checkBox.getOnAction();
        if (!Objects.equals(checkBox.isSelected(), select)) {
            checkBox.setOnAction(null);
            checkBox.setSelected(select);
            checkBox.setOnAction(oldGetOnAction);
        }
    }

    private <T> void selectComboBox(ComboBox<T> comboBox, T select) {
        var oldGetOnAction = comboBox.getOnAction();
        if (!Objects.equals(comboBox.getValue(), select)) {
            comboBox.setOnAction(null);
            if (select == null) {
                comboBox.getSelectionModel().selectFirst();
            } else {
                comboBox.getSelectionModel().select(select);
            }
            comboBox.setOnAction(oldGetOnAction);
        }
    }

    private void copyToClipboard(String text) {
        if (text != null && !text.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    private void updateAllInfo() {
        if (adapter == null) {
            return;
        }

        String version = adapter.getVersion();
        if (!Objects.equals(version, lastVersion)) {
            lastVersion = version;
            versionLabel.setText("v" + version);
        }

        String user = adapter.getUsername();
        int userId = adapter.getUserId();
        String userKey = user + ":" + userId;
        if (!Objects.equals(userKey, lastUser)) {
            lastUser = userKey;
            userLabel.setText("%s (#%d)".formatted(user, userId));
        }

        int rpcPort = adapter.getRpcPort();
        if (rpcPort != lastRpcPort) {
            lastRpcPort = rpcPort;
            rpcPortLabel.setText(String.valueOf(rpcPort));
        }

        int gpgnetPort = adapter.getGpgNetPort();
        if (gpgnetPort != lastGpgnetPort) {
            lastGpgnetPort = gpgnetPort;
            gpgnetPortLabel.setText(String.valueOf(gpgnetPort));
        }

        int lobbyPort = adapter.getLobbyPort();
        if (lobbyPort != lastLobbyPort) {
            lastLobbyPort = lobbyPort;
            lobbyPortLabel.setText(String.valueOf(lobbyPort));
        }

        String rpcServer = adapter.getRpcServerStatus();
        if (!Objects.equals(rpcServer, lastRpcServerStatus)) {
            lastRpcServerStatus = rpcServer;
            rpcServerStatus.setText("Server: " + rpcServer);
            styleStatusLabel(rpcServerStatus, rpcServer);
        }

        String rpcClient = adapter.getRpcClientStatus();
        if (!Objects.equals(rpcClient, lastRpcClientStatus)) {
            lastRpcClientStatus = rpcClient;
            rpcClientStatus.setText("Client: " + rpcClient);
            styleStatusLabel(rpcClientStatus, rpcClient);
        }

        String gpgServer = adapter.getGpgNetServerStatus();
        if (!Objects.equals(gpgServer, lastGpgnetServerStatus)) {
            lastGpgnetServerStatus = gpgServer;
            gpgnetServerStatus.setText("Server: " + gpgServer);
            styleStatusLabel(gpgnetServerStatus, gpgServer);
        }

        String gpgClient = adapter.getGpgNetClientStatus();
        if (!Objects.equals(gpgClient, lastGpgnetClientStatus)) {
            lastGpgnetClientStatus = gpgClient;
            gpgnetClientStatus.setText("Client: " + gpgClient);
            styleStatusLabel(gpgnetClientStatus, gpgClient);
        }

        String gState = adapter.getGameState();
        if (!Objects.equals(gState, lastGameState)) {
            lastGameState = gState;
            gameState.setText(gState);
            styleStatusLabel(gameState, gState);
        }

        if (filteredPeers == null) {
            bindDataToTables();
        }

        int count =
                adapter.getPeerInfoList() != null ? adapter.getPeerInfoList().size() : 0;
        if (peerCountLabel != null) {
            peerCountLabel.setText(count + (count == 1 ? " peer active" : " peers active"));
        }

        adapter.getWebRtcPeerInfoList();

        if (mCandLocalAddrCol != null) {
            boolean showIp = adapter.isShowIpAddresses();
            if (mCandLocalAddrCol.isVisible() != showIp) {
                mCandLocalAddrCol.setVisible(showIp);
            }
            if (mCandRemoteAddrCol != null && mCandRemoteAddrCol.isVisible() != showIp) {
                mCandRemoteAddrCol.setVisible(showIp);
            }
        }

        PeerView currentlySelected = peerTable.getSelectionModel().getSelectedItem();
        if (currentlySelected != null) {
            boolean found = false;
            for (PeerView p : peerTable.getItems()) {
                if (Objects.equals(p, currentlySelected)) {
                    setSelectedPeer(p);
                    found = true;
                    break;
                }
            }
            if (!found) {
                closePeerManagerPanel();
            }
        }
    }

    private void styleStatusLabel(Label label, String status) {
        if (label == null || status == null) {
            return;
        }
        String upper = status.toUpperCase();
        if (upper.contains("CONNECTED") || upper.contains("RUNNING") || upper.contains("LISTENING")) {
            label.setStyle("-fx-text-fill: #4caf50; -fx-font-weight: bold;");
        } else if (upper.contains("NEW") || upper.contains("CHECKING") || upper.contains("WAITING")) {
            label.setStyle("-fx-text-fill: #ff9800; -fx-font-weight: bold;");
        } else if (upper.contains("DISCONNECTED") || upper.contains("FAILED") || upper.contains("STOPPED")) {
            label.setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
        } else {
            label.setStyle("-fx-text-fill: #9e9e9e;");
        }
    }

    private static Callback<TableColumn<PeerView, String>, TableCell<PeerView, String>> createRttCellFactory(
            boolean isRelay) {
        return col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (isRelay && !"–".equals(item) && !"-".equals(item)) {
                        PeerView peer = getTableRow() != null ? getTableRow().getItem() : null;
                        if (peer != null
                                && peer.getRelayLogin().get() != null
                                && !peer.getRelayLogin().get().isEmpty()) {
                            setTooltip(new Tooltip(
                                    "Relay via " + peer.getRelayLogin().get()));
                        } else {
                            setTooltip(null);
                        }
                    } else {
                        setTooltip(null);
                    }

                    try {
                        double val = Double.parseDouble(item.replace("ms", "").trim());
                        if (val < 80) {
                            setStyle("-fx-text-fill: #4caf50; -fx-font-weight: bold;");
                        } else if (val < 180) {
                            setStyle("-fx-text-fill: #ff9800; -fx-font-weight: bold;");
                        } else {
                            setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
                        }
                    } catch (NumberFormatException ignored) {
                        setStyle("");
                    }
                }
            }
        };
    }

    private static Callback<TableColumn<PeerView, Boolean>, TableCell<PeerView, Boolean>> createCheckBoxCellFactory() {
        return col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();

            {
                checkBox.setDisable(true);
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    checkBox.setSelected(item);
                    setGraphic(checkBox);
                }
            }
        };
    }

    private void startPeriodicUpdates() {
        updateScheduler = Executors.newSingleThreadScheduledExecutor();
        updateScheduler.scheduleAtFixedRate(
                () -> Platform.runLater(this::updateAllInfo), 0, 500, TimeUnit.MILLISECONDS);
    }

    public void dispose() {
        if (updateScheduler != null && !updateScheduler.isShutdown()) {
            updateScheduler.shutdownNow();
        }
    }

    public void close() {
        Stage stage = (Stage) root.getScene().getWindow();
        stage.close();
        dispose();
    }

    private static void runOnUIThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }
}
