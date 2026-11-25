package com.example.addon.bots.real;

import com.example.addon.bots.Bot;
import com.example.addon.bots.BotManager;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр ботов с настоящей клиентской сессией (экспериментально, команда .rbot).
 *
 * Вход в бота (enter): ваша собственная сессия паркуется (host), в MinecraftClient подставляется мир,
 * игрок и менеджер бота, клавиатура переходит к боту. Meteor и Baritone видят бота как mc.player.
 */
public final class BotSessions {
    public static final BotSessions INSTANCE = new BotSessions();
    private static final Map<String, BotSession> SESSIONS = new ConcurrentHashMap<>();

    private static volatile BotSession entered; // бот, в котором вы сейчас
    private static volatile BotSession host;    // ваша припаркованная сессия

    private BotSessions() {}

    private static String key(String nick) {
        return nick.toLowerCase();
    }

    public static boolean isEntered() {
        return entered != null;
    }

    /** Возвращает null при успехе, иначе текст ошибки. */
    public static String start(String nick, String address) {
        if (!BotManager.validNick(nick)) return "ник: 1-16 латинских букв, цифр или _";
        if (address == null || address.isBlank()) return "пустой адрес сервера";
        Bot headless = BotManager.INSTANCE.get(nick);
        if (headless != null && headless.isActive()) return "бот с таким ником уже запущен через .bot";
        BotSession s = new BotSession(nick, address.trim(), false);
        if (SESSIONS.putIfAbsent(key(nick), s) != null) return "сессия с таким ником уже есть";
        s.start();
        return null;
    }

    public static boolean stop(String nick) {
        BotSession s = SESSIONS.get(key(nick));
        if (s == null) return false;
        if (entered == s) leaveInternal();
        s.stop();
        return true;
    }

    public static List<BotSession> all() {
        return new ArrayList<>(SESSIONS.values());
    }

    static void remove(BotSession s) {
        SESSIONS.remove(key(s.nick), s);
    }

    private static List<BotSession> candidates() {
        List<BotSession> l = new ArrayList<>(SESSIONS.values());
        BotSession h = host;
        if (h != null) l.add(h);
        return l;
    }

    // ---- вход в бота / выход (главный поток) ----

    private static void switchTo(MinecraftClient mc, ClientWorld w, ClientPlayerEntity p, ClientPlayerInteractionManager im) {
        mc.world = w;
        mc.worldRenderer.setWorld(w);
        mc.particleManager.setWorld(w);
        mc.player = p;
        mc.interactionManager = im;
        mc.setCameraEntity(p);
    }

    /** Возвращает null при успехе, иначе текст ошибки. */
    public static String enter(String nick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (entered != null) return "вы уже в боте " + entered.nick + " (.rbot leave)";
        BotSession bot = SESSIONS.get(key(nick));
        if (bot == null) return "нет такой сессии: " + nick;
        if (bot.isFinished() || bot.player == null || bot.world == null) return "бот ещё не в игре, смотрите .rbot list";
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return "сначала зайдите на сервер или в мир";

        host = BotSession.host(mc);
        mc.setScreen(null);
        switchTo(mc, bot.world, bot.player, bot.interactionManager);
        bot.player.input = new KeyboardInput(mc.options);
        bot.setEntered(true);
        entered = bot;
        return null;
    }

    /** Возвращает null при успехе, иначе текст ошибки. */
    public static String leave() {
        if (entered == null) return "вы не в боте";
        leaveInternal();
        return null;
    }

    static void leaveInternal() {
        MinecraftClient mc = MinecraftClient.getInstance();
        BotSession bot = entered;
        BotSession h = host;
        if (bot == null || h == null) {
            entered = null;
            host = null;
            return;
        }
        // бот мог возродиться пока вы были в нём: запоминаем актуальные объекты
        bot.world = mc.world;
        bot.player = mc.player;
        bot.interactionManager = mc.interactionManager;
        bot.releaseInput();
        bot.setEntered(false);
        entered = null;

        mc.setScreen(null);
        switchTo(mc, h.world, h.player, h.interactionManager);
        if (h.player != null) h.player.input = new KeyboardInput(mc.options);
        host = null;
        h.drainRemaining();
    }

    /** Ванильный disconnect пришёл, пока вы в боте: сначала возвращаемся в свою сессию. */
    public static void leaveFromDisconnect() {
        if (entered != null) leaveInternal();
    }

    /** Ваша припаркованная сессия потеряла соединение (кик, обрыв). */
    static void onHostClosed() {
        if (entered == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        leaveInternal();
        mc.disconnect(Text.literal("Соединение с сервером закрыто"));
    }

    // ---- события ----

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        for (BotSession s : candidates()) {
            try {
                s.tick();
            } catch (Throwable t) {
                if (s.isHost) {
                    leaveFromDisconnect();
                } else {
                    s.fail("tick: " + t);
                }
            }
        }
    }

    /** Пакеты припаркованных сессий забираем первыми; активная (entered) идёт обычным путём. */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacketReceive(PacketEvent.Receive event) {
        for (BotSession s : candidates()) {
            if (s.connection != null && s.connection == event.connection) {
                if (s.isEntered()) return;
                event.cancel();
                s.onPacket(event.packet);
                return;
            }
        }
    }
}
