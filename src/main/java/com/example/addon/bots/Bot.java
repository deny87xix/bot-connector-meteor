package com.example.addon.bots;

import com.example.addon.bots.real.BotSession;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
    /** Настоящая клиентская сессия бота (соединение, мир, игрок). null, пока бот не запускался. */
    public volatile BotSession session;
    /** Если true, сообщения сервера, которые видит бот, не выводятся вам в чат (в лог бота попадают всегда). */
    public volatile boolean muteChat;
    /** Автоперезаход: после кика/ошибки бот сразу заходит снова, пока loop не выключен. */
    public volatile boolean loop;
    /** Бота остановил пользователь (Stop и т. п.): loop его не переподключает, пока его не запустят снова. */
    public volatile boolean userStopped;
    /** Когда бот последний раз перешёл в DISCONNECTED/ERROR (мс). */
    public volatile long endedAt;
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
        if (s == State.DISCONNECTED || s == State.ERROR) endedAt = System.currentTimeMillis();
        log.add(s + (status.isEmpty() ? "" : ": " + status));
    }
}
