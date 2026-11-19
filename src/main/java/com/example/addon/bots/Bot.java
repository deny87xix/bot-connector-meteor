package com.example.addon.bots;

import com.example.addon.bots.sim.BotSim;
import net.minecraft.network.ClientConnection;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Состояние одного бота. */
public class Bot {
    public enum State { CONNECTING, LOGIN, CONFIG, ONLINE, DISCONNECTED, ERROR }

    public final String name;
    public final String address;
    public final UUID uuid;
    /** Логи бота (общие для всех подключений с этим ником). */
    public final BotLog log;

    public volatile State state = State.CONNECTING;
    public volatile String status = "";
    public volatile ClientConnection connection;
    /** Физика и чанки тела бота (создаётся при входе в фазу play). */
    public volatile BotSim sim;
    /** Если true, сообщения сервера, которые видит бот, не выводятся вам в чат (в лог бота попадают всегда). */
    public volatile boolean muteChat;
    public final long attemptId;

    public Bot(String name, String address, long attemptId) {
        this(name, address, attemptId, new BotLog());
    }

    public Bot(String name, String address, long attemptId, BotLog log) {
        this.name = name;
        this.address = address;
        this.attemptId = attemptId;
        this.log = log;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public boolean isActive() {
        return state == State.CONNECTING || state == State.LOGIN || state == State.CONFIG || state == State.ONLINE;
    }

    public void set(State s, String text) {
        this.state = s;
        this.status = text == null ? "" : text;
        log.add(s + (status.isEmpty() ? "" : ": " + status));
    }
}
