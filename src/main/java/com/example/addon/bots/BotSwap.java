package com.example.addon.bots;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.systems.accounts.Account;
import meteordevelopment.meteorclient.systems.accounts.types.CrackedAccount;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.session.Session;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Вход в бота и возврат обратно.
 *
 * Настоящий клиент Minecraft не умеет держать два мира сразу, поэтому «вход» сделан как обмен телами:
 *  - .bot enter X : headless-бот X отключается, ваш клиент перезаходит на сервер под ником X
 *                   (полноценная ванильная физика, управление, инвентарь), а ваш прежний ник
 *                   остаётся на сервере как headless-бот и стоит на месте;
 *  - .bot back    : ваш прежний ник снова становится клиентом, а X опять превращается в headless-бота.
 * Работает на offline-mode серверах (там ник однозначно задаёт игрока и его сохранённое состояние).
 */
public final class BotSwap {
    private BotSwap() {}

    private static final class Task {
        int left;
        final Runnable run;
        Task(int left, Runnable run) { this.left = left; this.run = run; }
    }

    private static final List<Task> TASKS = new ArrayList<>();

    private static Session originSession;
    private static String originName;
    private static String originAddress;
    private static String botNick;
    private static String botAddress;
    private static boolean busy;

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    public static boolean isInside() { return botNick != null; }

    public static String insideNick() { return botNick; }

    public static String originName() { return originName; }

    /** Вызывается каждый клиентский тик из BotManager. */
    public static void tick() {
        if (TASKS.isEmpty()) return;
        List<Task> due = new ArrayList<>();
        for (Task t : TASKS) {
            if (--t.left <= 0) due.add(t);
        }
        TASKS.removeAll(due);
        for (Task t : due) {
            try {
                t.run.run();
            } catch (Throwable e) {
                AddonTemplate.LOG.warn("[BotSwap] task failed", e);
            }
        }
    }

    private static void after(int ticks, Runnable r) { TASKS.add(new Task(ticks, r)); }

    private static void say(String msg) {
        AddonTemplate.LOG.info("[BotSwap] {}", msg);
        MinecraftClient c = mc();
        c.execute(() -> {
            if (c.inGameHud != null) c.inGameHud.getChatHud().addMessage(Text.literal("§7[Bots] §f" + msg));
        });
    }

    // ---------------------------------------------------------------- enter

    /** Возвращает null при успехе, иначе текст ошибки. */
    public static String enter(String nick) {
        if (busy) return "идёт переключение, подождите пару секунд";
        if (botNick != null) return "вы уже внутри бота " + botNick + ": сначала .bot back";

        Bot bot = BotManager.INSTANCE.get(nick);
        if (bot == null) return "бот не найден: " + nick;

        Session cur = mc().getSession();
        String me = cur.getUsername();
        if (me.equalsIgnoreCase(bot.name)) return "это ваш текущий ник";

        ServerInfo here = mc().getCurrentServerEntry();
        originSession = cur;
        originName = me;
        originAddress = here != null ? here.address : null;
        botNick = bot.name;
        botAddress = bot.address;
        busy = true;

        final String name = bot.name;
        final String addr = bot.address;
        final boolean keepBody = originAddress != null
            && originAddress.equalsIgnoreCase(addr)
            && BotManager.validNick(me);
        final String body = me;

        // 1) освобождаем ник бота
        BotManager.INSTANCE.suspend(name);

        // 2) меняем сессию и заходим этим ником настоящим клиентом
        after(10, () -> MeteorExecutor.execute(() -> {
            try {
                new CrackedAccount(name).login();
                mc().execute(() -> connectReal(addr, name));
            } catch (Throwable t) {
                AddonTemplate.LOG.warn("[BotSwap] enter failed", t);
                mc().execute(() -> rollbackEnter(name, addr));
            }
        }));

        // 3) прежний ник остаётся на сервере телом-ботом
        if (keepBody) {
            after(70, () -> {
                String err = BotManager.INSTANCE.connect(body, addr);
                if (err != null) say("не удалось оставить тело " + body + ": " + err);
                busy = false;
            });
        } else {
            after(70, () -> busy = false);
        }
        say("вхожу в бота " + name + " ...");
        return null;
    }

    private static void rollbackEnter(String name, String addr) {
        Session s = originSession;
        botNick = null;
        busy = false;
        TASKS.clear();
        if (s != null) MeteorExecutor.execute(() -> Account.setSession(s));
        BotManager.INSTANCE.connect(name, addr);
        say("не удалось войти в бота " + name);
    }

    // ---------------------------------------------------------------- back

    /** Возвращает null при успехе, иначе текст ошибки. */
    public static String back() {
        if (busy) return "идёт переключение, подождите пару секунд";
        if (botNick == null) return "вы не внутри бота";

        busy = true;
        final String nick = botNick;
        final String addr = botAddress;
        final String name = originName;
        final Session session = originSession;
        final String backAddr = originAddress;

        // 1) убираем тело прежнего ника (headless), если оно есть
        BotManager.INSTANCE.remove(name);

        // 2) возвращаем свою сессию и заходим обратно
        after(20, () -> MeteorExecutor.execute(() -> {
            try {
                if (session != null) Account.setSession(session);
                mc().execute(() -> {
                    if (backAddr != null) {
                        connectReal(backAddr, name);
                    } else {
                        mc().disconnect(Text.literal("back"));
                    }
                });
            } catch (Throwable t) {
                AddonTemplate.LOG.warn("[BotSwap] back failed", t);
            }
        }));

        // 3) бот снова headless
        after(100, () -> {
            String err = BotManager.INSTANCE.connect(nick, addr);
            if (err != null) say("не удалось вернуть бота " + nick + ": " + err);
            botNick = null;
            originSession = null;
            busy = false;
        });
        say("возвращаюсь в " + name + " ...");
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static void connectReal(String address, String label) {
        MinecraftClient c = mc();
        ServerAddress sa = ServerAddress.parse(address);
        ServerInfo info = new ServerInfo(label, address, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), c, sa, info, false, null);
    }
}
