package com.example.addon.bots;

import com.example.addon.AddonTemplate;
import com.example.addon.bots.net.BotConnector;
import com.example.addon.bots.store.BotProfileStore;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.ClientConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Реестр ботов, жизненный цикл, тик соединений. */
public class BotManager {
    public static final BotManager INSTANCE = new BotManager();
    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final AtomicLong SEQ = new AtomicLong();

    private final Map<String, Bot> bots = new ConcurrentHashMap<>();

    private static String key(String nick) { return nick.toLowerCase(); }

    public static boolean validNick(String nick) { return NICK.matcher(nick).matches(); }

    /** Возвращает null при успехе, иначе текст ошибки. */
    public String connect(String nick, String address) {
        if (!validNick(nick)) return "ник: 1-16 латинских букв, цифр или _";
        if (address == null || address.isBlank()) return "пустой адрес сервера";

        Bot existing = bots.get(key(nick));
        if (existing != null && existing.isActive()) return "бот с таким ником уже подключается или онлайн";

        Bot bot = new Bot(nick, address.trim(), SEQ.incrementAndGet());
        bots.put(key(nick), bot);
        BotProfileStore.put(nick, bot.address);

        Thread t = new Thread(() -> BotConnector.run(bot), "bot-connect-" + nick);
        t.setDaemon(true);
        t.start();
        return null;
    }

    public boolean remove(String nick) {
        Bot b = bots.remove(key(nick));
        BotProfileStore.remove(nick);
        if (b == null) return false;
        close(b);
        return true;
    }

    public void clear() {
        for (Bot b : new ArrayList<>(bots.values())) close(b);
        bots.clear();
        BotProfileStore.clear();
    }

    public Bot get(String nick) { return bots.get(key(nick)); }

    public List<Bot> all() { return new ArrayList<>(bots.values()); }

    private void close(Bot b) {
        ClientConnection c = b.connection;
        if (c != null && c.isOpen()) {
            try { c.disconnect(net.minecraft.text.Text.literal("bot removed")); } catch (Throwable ignored) {}
        }
        b.set(Bot.State.DISCONNECTED, "отключён");
    }

    /** Тикаем соединения ботов: flush + обработка закрытия. */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        for (Bot b : bots.values()) {
            ClientConnection c = b.connection;
            if (c == null) continue;
            try {
                if (c.isOpen()) c.tick(); else c.handleDisconnection();
            } catch (Throwable t) {
                AddonTemplate.LOG.warn("[Bot {}] tick error: {}", b.name, t.toString());
            }
        }
    }

    private boolean isBot(ClientConnection c) {
        if (c == null) return false;
        for (Bot b : bots.values()) if (b.connection == c) return true;
        return false;
    }

    /**
     * Meteor шлёт PacketEvent.Receive для ВСЕХ соединений, и его модули (например, ServerCommand) приводят
     * слушатель соединения к классу ClientPlayNetworkHandler -> ClassCastException -> «invalid packet».
     * Поэтому пакеты ботов перехватываем первыми: отдаём их слушателю бота сами и отменяем событие,
     * чтобы остальные обработчики Meteor их не видели.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isBot(event.connection)) return;
        event.cancel();
        BotConnector.dispatch(event.connection, event.packet);
    }
}
