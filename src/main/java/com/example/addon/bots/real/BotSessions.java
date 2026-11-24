package com.example.addon.bots.real;

import com.example.addon.bots.Bot;
import com.example.addon.bots.BotManager;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Реестр ботов с настоящей клиентской сессией (экспериментально, команда .rbot). */
public final class BotSessions {
    public static final BotSessions INSTANCE = new BotSessions();
    private static final Map<String, BotSession> SESSIONS = new ConcurrentHashMap<>();

    private BotSessions() {}

    private static String key(String nick) {
        return nick.toLowerCase();
    }

    /** Возвращает null при успехе, иначе текст ошибки. */
    public static String start(String nick, String address) {
        if (!BotManager.validNick(nick)) return "ник: 1-16 латинских букв, цифр или _";
        if (address == null || address.isBlank()) return "пустой адрес сервера";
        Bot headless = BotManager.INSTANCE.get(nick);
        if (headless != null && headless.isActive()) return "бот с таким ником уже запущен через .bot";
        BotSession s = new BotSession(nick, address.trim());
        if (SESSIONS.putIfAbsent(key(nick), s) != null) return "сессия с таким ником уже есть";
        s.start();
        return null;
    }

    public static boolean stop(String nick) {
        BotSession s = SESSIONS.get(key(nick));
        if (s == null) return false;
        s.stop();
        return true;
    }

    public static List<BotSession> all() {
        return new ArrayList<>(SESSIONS.values());
    }

    static void remove(BotSession s) {
        SESSIONS.remove(key(s.nick), s);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        for (BotSession s : SESSIONS.values()) {
            try {
                s.tick();
            } catch (Throwable t) {
                s.fail("tick: " + t);
            }
        }
    }

    /** Пакеты соединения бота забираем первыми: Meteor-модули и ваш клиент их не увидят. */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacketReceive(PacketEvent.Receive event) {
        for (BotSession s : SESSIONS.values()) {
            if (s.connection != null && s.connection == event.connection) {
                event.cancel();
                s.onPacket(event.packet);
                return;
            }
        }
    }
}
