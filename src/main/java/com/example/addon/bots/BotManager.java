package com.example.addon.bots;

import com.example.addon.AddonTemplate;
import com.example.addon.bots.real.BotSessions;
import com.example.addon.bots.store.BotProfileStore;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Реестр ботов, жизненный цикл, автоперезаход (loop). Сетевая часть — в {@link BotSessions}. */
public class BotManager {
    public static final BotManager INSTANCE = new BotManager();
    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final AtomicLong SEQ = new AtomicLong();

    /** Минимальная пауза между отключением и следующей попыткой loop (не даёт рождать потоки на мгновенных отказах). */
    private static final long LOOP_DELAY_MS = 500;

    private final Map<String, Bot> bots = new ConcurrentHashMap<>();

    /** Общий выключатель чата всех ботов (не сохраняется между запусками игры). */
    private volatile boolean muteAll;

    private static String key(String nick) { return nick.toLowerCase(); }

    public static boolean validNick(String nick) { return NICK.matcher(nick).matches(); }

    // ------------------------------------------------------------------ чат ботов

    public boolean isMuteAll() { return muteAll; }

    public void setMuteAll(boolean mute) {
        this.muteAll = mute;
    }

    /** Скрыт ли чат этого бота (лично или общим выключателем). */
    public boolean isMuted(Bot b) {
        return muteAll || b.muteChat;
    }

    /** Включает/выключает показ чата бота вам. Возвращает false, если бота нет. */
    public boolean setMute(String nick, boolean mute) {
        Bot b = bots.get(key(nick));
        if (b == null) return false;
        b.muteChat = mute;
        BotProfileStore.setMute(b.name, mute);
        b.log.add(mute ? "чат бота отключён (сообщения вам не приходят, в лог пишутся)" : "чат бота включён");
        return true;
    }

    // ------------------------------------------------------------------ loop (автоперезаход)

    /**
     * Включает/выключает loop у бота. При включении бот также запускается, если он остановлен
     * (loop = «держать бота онлайн»). Возвращает false, если бота нет.
     */
    public boolean setLoop(String nick, boolean on) {
        Bot b = bots.get(key(nick));
        if (b == null) return false;
        b.loop = on;
        BotProfileStore.setLoop(b.name, on);
        if (on) b.userStopped = false;
        b.log.add(on ? "loop включён: после кика/ошибки бот будет заходить снова" : "loop выключен");
        return true;
    }

    /** loop для всех ботов (кроме того, в которого вы вошли). Возвращает количество ботов. */
    public int setLoopAll(boolean on) {
        int n = 0;
        for (Bot b : all()) {
            if (BotSwap.isInside(b.name)) continue;
            if (setLoop(b.name, on)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ профили

    /** Подгружает сохранённых ботов (bots.json) в реестр как остановленных. Вызывается один раз при старте. */
    public void restoreProfiles() {
        for (BotProfileStore.Profile p : BotProfileStore.all()) {
            if (p == null || p.nickname == null || p.address == null) continue;
            if (!validNick(p.nickname) || bots.containsKey(key(p.nickname))) continue;
            Bot b = new Bot(p.nickname, p.address, SEQ.incrementAndGet());
            b.muteChat = p.muteChat;
            b.loop = p.loop;
            // Сохранённые боты не запускаются сами при старте игры: loop начнёт работать после Start / .bot loop <ник> true
            b.userStopped = true;
            b.set(Bot.State.DISCONNECTED, "сохранён (загружен из профиля)");
            bots.put(key(p.nickname), b);
        }
    }

    /** Сохраняет бота в профили без запуска. Возвращает null при успехе, иначе текст ошибки. */
    public String save(String nick, String address) {
        if (!validNick(nick)) return "ник: 1-16 латинских букв, цифр или _";
        if (address == null || address.isBlank()) return "пустой адрес сервера";

        Bot existing = bots.get(key(nick));
        if (existing != null && existing.isActive()) return "бот сейчас запущен: сначала остановите";

        Bot bot = new Bot(nick, address.trim(), SEQ.incrementAndGet(), existing != null ? existing.log : new BotLog());
        bot.muteChat = existing != null && existing.muteChat;
        bot.loop = existing != null && existing.loop;
        bot.userStopped = true;
        bot.set(Bot.State.DISCONNECTED, "сохранён");
        bots.put(key(nick), bot);
        BotProfileStore.put(nick, bot.address);
        return null;
    }

    /** Запускает настоящую сессию бота. Возвращает null при успехе, иначе текст ошибки. */
    public String connect(String nick, String address) {
        if (!validNick(nick)) return "ник: 1-16 латинских букв, цифр или _";
        if (address == null || address.isBlank()) return "пустой адрес сервера";

        Bot existing = bots.get(key(nick));
        if (existing != null && existing.isActive()) return "бот с таким ником уже подключается или онлайн";

        Bot bot = new Bot(nick, address.trim(), SEQ.incrementAndGet(), existing != null ? existing.log : new BotLog());
        bot.muteChat = existing != null && existing.muteChat;
        bot.loop = existing != null && existing.loop;
        bots.put(key(nick), bot);
        BotProfileStore.put(nick, bot.address);
        bot.log.add("запуск: " + bot.address);

        BotSessions.start(bot);
        return null;
    }

    /** Запускает ранее сохранённого бота. Возвращает null при успехе, иначе текст ошибки. */
    public String start(String nick) {
        Bot b = bots.get(key(nick));
        if (b == null) return "бот не найден: " + nick;
        return connect(b.name, b.address);
    }

    /** Останавливает бота, профиль остаётся (loop не переподключает до следующего запуска). */
    public boolean stop(String nick) {
        Bot b = bots.get(key(nick));
        if (b == null) return false;
        close(b);
        return true;
    }

    /** Запускает всех неактивных ботов (кроме того, в которого вы вошли). Возвращает количество запущенных. */
    public int startAll() {
        int n = 0;
        for (Bot b : all()) {
            if (b.isActive() || BotSwap.isInside(b.name)) continue;
            if (start(b.name) == null) n++;
        }
        return n;
    }

    /** Останавливает всех активных ботов (кроме того, в которого вы вошли). Возвращает количество остановленных. */
    public int stopAll() {
        int n = 0;
        for (Bot b : all()) {
            if (!b.isActive() || BotSwap.isInside(b.name)) continue;
            close(b);
            n++;
        }
        return n;
    }

    /** Удаляет бота и его профиль. */
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

    /** Ручное отключение: loop такого бота не переподключает. Если вы в этом боте, вас сначала возвращает в вашу сессию. */
    private void close(Bot b) {
        b.userStopped = true;
        BotSessions.stop(b);
        b.set(Bot.State.DISCONNECTED, "отключён");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        BotSwap.tick();
        BotService.tick();
        loopTick();
    }

    /**
     * loop: если бота кикнули или произошла ошибка (и он не остановлен вручную), перезаходим через полсекунды
     * и так далее, пока loop не выключат. Попытки идут по одной за раз (новая — только когда предыдущая завершилась).
     */
    private void loopTick() {
        long now = System.currentTimeMillis();
        for (Bot b : new ArrayList<>(bots.values())) {
            if (!b.loop || b.userStopped) continue;
            if (b.state != Bot.State.DISCONNECTED && b.state != Bot.State.ERROR) continue;
            if (BotSwap.isInside(b.name)) continue;

            // Сервер требует шифрование (online-mode): бесполезно повторять, выключаем loop.
            if (b.state == Bot.State.ERROR && b.status.contains("offline-mode")) {
                b.loop = false;
                BotProfileStore.setLoop(b.name, false);
                b.log.add("loop выключен: сервер в online-mode, боты работают только на offline-mode");
                continue;
            }

            if (now - b.endedAt < LOOP_DELAY_MS) continue;

            b.log.add("loop: перезаход");
            String err = connect(b.name, b.address);
            if (err != null) b.log.add("loop: " + err);
        }
    }
}
