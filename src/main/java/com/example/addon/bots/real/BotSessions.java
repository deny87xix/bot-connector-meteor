package com.example.addon.bots.real;

import com.example.addon.bots.Bot;
import com.example.addon.bots.BotSwap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Движок настоящих сессий ботов: запуск, тик припаркованных сессий, перехват их пакетов, вход в бота и выход
 * без перезахода. Реестр ботов (ники, профили, loop) ведёт BotManager, здесь только сетевая часть.
 *
 * Вход в бота (enter): ваша собственная сессия паркуется (host), в MinecraftClient подставляется мир,
 * игрок и менеджер бота, клавиатура переходит к боту. Meteor и Baritone видят бота как mc.player.
 * Ваше соединение с сервером при этом живёт и тикается в своём окне контекста.
 */
public final class BotSessions {
    public static final BotSessions INSTANCE = new BotSessions();

    /** Сессии, которые ещё не завершились (их пакеты мы перехватываем и тикаем сами). */
    private static final Set<BotSession> LIVE = ConcurrentHashMap.newKeySet();

    private static volatile BotSession entered; // бот, в котором вы сейчас
    private static volatile BotSession host;    // ваша припаркованная сессия

    private BotSessions() {}

    static void register(BotSession s) {
        LIVE.add(s);
    }

    static void unregister(BotSession s) {
        LIVE.remove(s);
    }

    public static boolean isEntered() {
        return entered != null;
    }

    public static boolean isEntered(BotSession s) {
        return s != null && entered == s;
    }

    /** Запускает настоящую сессию бота: соединение устанавливается в фоне. */
    public static void start(Bot bot) {
        BotSession s = new BotSession(bot, false);
        bot.session = s;
        s.start();
    }

    /** Останавливает сессию бота; если вы в нём, сначала возвращает вас в вашу сессию. */
    public static void stop(Bot bot) {
        BotSession s = bot.session;
        if (s == null) return;
        if (entered == s) BotSwap.forceBack();
        s.stop();
    }

    /**
     * Сообщение или команда (с «/») от имени бота. Возвращает null при успехе, иначе текст ошибки.
     * Только главный поток.
     */
    public static String chat(Bot bot, String text) {
        BotSession s = bot.session;
        if (s == null || s.isFinished() || s.player == null || s.connection == null) return "бот ещё не зашёл или отключён";
        if (text == null || text.isBlank() || text.length() > 256) return "пустой текст или длиннее 256 символов";
        if (!(s.connection.getPacketListener() instanceof ClientPlayNetworkHandler handler)) return "бот ещё не зашёл или отключён";
        Runnable send = () -> {
            if (text.startsWith("/")) handler.sendChatCommand(text.substring(1));
            else handler.sendChatMessage(text);
        };
        if (s.isEntered()) send.run();
        else BotContext.run(s, send);
        return null;
    }

    private static List<BotSession> candidates() {
        List<BotSession> l = new ArrayList<>(LIVE);
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
    public static String enter(BotSession bot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (entered != null) return "вы уже в боте " + entered.nick + " (.bot back)";
        if (bot.isFinished() || bot.player == null || bot.world == null) return "бот ещё не в игре, смотрите .bot list";
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

    private static void leaveInternal() {
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

    /** Ванильный disconnect пришёл, пока вы в боте (кик бота, обрыв): возвращаемся в свою сессию. */
    public static void leaveFromDisconnect() {
        if (entered != null) BotSwap.forceBack();
    }

    /** Ваша припаркованная сессия потеряла соединение (кик, обрыв). */
    static void onHostClosed() {
        if (entered == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        BotSwap.forceBack();
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
