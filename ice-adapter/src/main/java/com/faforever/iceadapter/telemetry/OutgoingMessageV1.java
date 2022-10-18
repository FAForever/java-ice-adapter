package com.faforever.iceadapter.telemetry;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "messageType")
public interface OutgoingMessageV1 {
    UUID messageId();
}