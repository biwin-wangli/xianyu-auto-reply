package com.xianyu.autoreply.model;

public enum ConnectionState {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    RECONNECTING("reconnecting"),
    FAILED("failed"),
    CLOSED("closed");

    private final String value;

    ConnectionState(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
