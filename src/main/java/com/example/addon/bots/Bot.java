package com.example.addon.bots;

import net.minecraft.network.ClientConnection;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Состояние одного бота. */
public class Bot {
    public enum State { CONNECTING, LOGIN, CONFIG, ONLINE, DISCONNECTED, ERROR }

    public final String name;
    public final String address;
    public final UUID uuid;

    public volatile State state = State.CONNECTING;
    public volatile String status = "";
    public volatile ClientConnection connection;
    public final long attemptId;

    public Bot(String name, String address, long attemptId) {
        this.name = name;
        this.address = address;
        this.attemptId = attemptId;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public boolean isActive() {
        return state == State.CONNECTING || state == State.LOGIN || state == State.CONFIG || state == State.ONLINE;
    }

    public void set(State s, String text) {
        this.state = s;
        this.status = text == null ? "" : text;
    }
}
