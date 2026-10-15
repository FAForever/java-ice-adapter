package com.faforever.iceadapter.ui.controller;

import com.faforever.iceadapter.dto.IceServerView;
import com.faforever.iceadapter.services.UIAdapter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NoArgsConstructor
public class IceServerController {

    @FXML
    private VBox root;

    @FXML
    private Label serverCountLabel;

    @FXML
    private Button refreshButton;

    @FXML
    private TableView<IceServerView> tableView;

    @FXML
    private TableColumn<IceServerView, String> typeColumn;

    @FXML
    private TableColumn<IceServerView, String> transportColumn;

    @FXML
    private TableColumn<IceServerView, String> addressColumn;

    @FXML
    private TableColumn<IceServerView, String> rttColumn;

    @FXML
    private TableColumn<IceServerView, Boolean> enabledColumn;

    private UIAdapter adapter;
    private ScheduledExecutorService updateScheduler;

    public void setAdapter(UIAdapter adapter) {
        this.adapter = adapter;
        updateAllInfo();
    }

    public void initialize() {
        initColumns();
        setupRowContextMenu();
        startPeriodicUpdates();
    }

    @FXML
    public void onRefreshClicked() {
        updateAllInfo();
    }

    @FXML
    public void onCloseClicked() {
        if (root != null && root.getScene() != null && root.getScene().getWindow() != null) {
            Stage stage = (Stage) root.getScene().getWindow();
            stage.hide();
        }
    }

    private void initColumns() {
        typeColumn.setCellValueFactory(data -> data.getValue().getType());
        typeColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label badge = new Label(item.toUpperCase());
                    badge.getStyleClass().add("badge");
                    if (item.equalsIgnoreCase("STUN")) {
                        badge.getStyleClass().add("badge-info");
                    } else if (item.equalsIgnoreCase("TURN")) {
                        badge.getStyleClass().add("badge-warning");
                    } else {
                        badge.getStyleClass().add("badge-neutral");
                    }
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        transportColumn.setCellValueFactory(data -> data.getValue().getTransport());
        transportColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label label = new Label(item.toUpperCase());
                    label.getStyleClass().addAll("mono-text", "badge", "badge-neutral");
                    setGraphic(label);
                    setText(null);
                }
            }
        });

        addressColumn.setCellValueFactory(data -> data.getValue().getAddress());
        addressColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle("-fx-font-family: Consolas, monospace; -fx-text-fill: #f8fafc;");
                }
            }
        });

        rttColumn.setCellValueFactory(data -> data.getValue().getRtt());
        rttColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.trim().isEmpty() || item.equals("-")) {
                    setText("-");
                    setStyle("-fx-text-fill: #64748b; -fx-font-family: Consolas, monospace;");
                } else {
                    setText(item);
                    try {
                        String clean = item.replaceAll("[^0-9.]", "");
                        if (!clean.isEmpty()) {
                            double val = Double.parseDouble(clean);
                            if (val < 100) {
                                setStyle(
                                        "-fx-text-fill: #4caf50; -fx-font-weight: bold; -fx-font-family: Consolas, monospace;");
                            } else if (val < 250) {
                                setStyle(
                                        "-fx-text-fill: #ff9800; -fx-font-weight: bold; -fx-font-family: Consolas, monospace;");
                            } else {
                                setStyle(
                                        "-fx-text-fill: #f44336; -fx-font-weight: bold; -fx-font-family: Consolas, monospace;");
                            }
                        } else {
                            setStyle("-fx-font-family: Consolas, monospace;");
                        }
                    } catch (NumberFormatException ignored) {
                        setStyle("-fx-font-family: Consolas, monospace;");
                    }
                }
            }
        });

        enabledColumn.setCellValueFactory(data -> data.getValue().getEnabled());
        enabledColumn.setEditable(true);
        enabledColumn.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private final Label autoBadge = new Label("Auto");

            {
                checkBox.getStyleClass().add("check-box");
                autoBadge.getStyleClass().addAll("badge", "badge-neutral");
                checkBox.setOnAction(event -> {
                    IceServerView item = getTableView().getItems().get(getIndex());
                    if (adapter != null && item != null) {
                        adapter.setEnabledIceServer(item, checkBox.isSelected());
                    }
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(null);
                setText(null);

                if (!empty && item != null) {
                    IceServerView server = getTableView().getItems().get(getIndex());
                    if (server != null
                            && server.getServer() != null
                            && server.getServer().isTurn()) {
                        checkBox.setSelected(item);
                        setGraphic(checkBox);
                    } else {
                        setGraphic(autoBadge);
                    }
                }
            }
        });
    }

    private void setupRowContextMenu() {
        tableView.setRowFactory(tv -> {
            TableRow<IceServerView> row = new TableRow<>();
            ContextMenu contextMenu = new ContextMenu();

            MenuItem copyAddressItem = new MenuItem("Copy Server Address");
            copyAddressItem.setOnAction(e -> {
                IceServerView server = row.getItem();
                if (server != null && server.getAddress() != null) {
                    copyToClipboard(server.getAddress().get());
                }
            });

            MenuItem copyTypeItem = new MenuItem("Copy Type & Transport");
            copyTypeItem.setOnAction(e -> {
                IceServerView server = row.getItem();
                if (server != null) {
                    String info = "%s (%s)"
                            .formatted(
                                    server.getType().get(),
                                    server.getTransport().get());
                    copyToClipboard(info);
                }
            });

            contextMenu.getItems().addAll(copyAddressItem, copyTypeItem, new SeparatorMenuItem());

            row.contextMenuProperty()
                    .bind(Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(contextMenu));
            return row;
        });
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

        var iceServerList = adapter.getIceServersList();
        if (tableView.getItems() != iceServerList) {
            tableView.setItems(iceServerList);
        }

        int count = iceServerList != null ? iceServerList.size() : 0;
        if (serverCountLabel != null) {
            serverCountLabel.setText(count + (count == 1 ? " server active" : " servers active"));
        }
    }

    private void startPeriodicUpdates() {
        updateScheduler = Executors.newSingleThreadScheduledExecutor();
        updateScheduler.scheduleAtFixedRate(
                () -> {
                    if (root.getScene() != null
                            && root.getScene().getWindow() != null
                            && root.getScene().getWindow().isShowing()) {
                        Platform.runLater(this::updateAllInfo);
                    }
                },
                2,
                15,
                TimeUnit.SECONDS);
    }

    public void dispose() {
        if (updateScheduler != null && !updateScheduler.isShutdown()) {
            updateScheduler.shutdownNow();
        }
    }
}
