package com.example.addon.bots;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.systems.accounts.Account;
import meteordevelopment.meteorclient.systems.accounts.types.CrackedAccount;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.session.Session;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Вход в бота и возврат обратно.
 *
 * Настоящий клиент Minecraft не умеет держать два мира сразу, а Meteor и Baritone работают только с одним
 * клиентским игроком. Поэтому «вход» сделан как обмен телами:
 *  - .bot X meteor : headless-бот X отключается, ваш клиент перезаходит на сервер под ником X
 *                    (полноценная ванильная физика, Meteor, Baritone, инвентарь), открывается GUI Meteor,
 *                    а ваш прежний ник остаётся на сервере как headless-бот и стоит на месте;
 *  - .bot back     : ваш прежний ник снова становится клиентом, а X опять превращается в headless-бота.
 * Включённые модули Meteor запоминаются отдельно для каждого бота (см. {@link BotModules}).
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

    // Действие «когда НОВЫЙ мир загрузился» (модули нельзя включать, пока нет игрока).
    private static Runnable onReady;
    private static ClientWorld worldAtSwitch;
    private static int readyStable;
    private static int readyTimeout;

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    public static boolean isInside() { return botNick != null; }

    public static boolean isInside(String nick) { return botNick != null && botNick.equalsIgnoreCase(nick); }

    public static String insideNick() { return botNick; }

    public static String originName() { return originName; }

    /** Вызывается каждый клиентский тик из BotManager. */
    public static void tick() {
        if (!TASKS.isEmpty()) {
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

        if (onReady != null) {
            MinecraftClient c = mc();
            if (--readyTimeout <= 0) {
                onReady = null;
                say("мир так и не загрузился вовремя: модули/GUI не применены");
            } else if (c.world != null && c.world != worldAtSwitch && c.player != null
                && c.getNetworkHandler() != null && c.currentScreen == null) {
                // Ждём ~1 секунду стабильного состояния после закрытия экрана загрузки.
                if (++readyStable >= 20) {
                    Runnable r = onReady;
                    onReady = null;
                    try {
                        r.run();
                    } catch (Throwable e) {
                        AddonTemplate.LOG.warn("[BotSwap] ready action failed", e);
                    }
                }
            } else {
                readyStable = 0;
            }
        }
    }

    private static void after(int ticks, Runnable r) { TASKS.add(new Task(ticks, r)); }

    /** Выполнить r, когда клиент зайдёт в новый мир (отличный от текущего). */
    private static void armReady(Runnable r) {
        worldAtSwitch = mc().world;
        onReady = r;
        readyStable = 0;
        readyTimeout = 20 * 90;
    }

    private static void say(String msg) {
        AddonTemplate.LOG.info("[BotSwap] {}", msg);
        MinecraftClient c = mc();
        c.execute(() -> {
            if (c.inGameHud != null) c.inGameHud.getChatHud().addMessage(Text.literal("§7[Bots] §f" + msg));
        });
    }

    // ---------------------------------------------------------------- meteor / baritone

    /**
     * .bot &lt;ник&gt; meteor — войти в бота (если ещё не внутри) и открыть Meteor от его имени.
     * Возвращает null при успехе, иначе текст ошибки.
     */
    public static String meteor(String nick) {
        if (busy) return "идёт переключение, подождите пару секунд";
        if (isInside(nick)) {
            // Команда выполняется из чата, а чат закрывается уже после неё и сбил бы GUI: открываем чуть позже.
            after(3, BotSwap::openGui);
            return null;
        }
        return enter(nick, true);
    }

    /** .bot &lt;ник&gt; baritone &lt;команда&gt; — выполнить команду Baritone за бота, в которого вы вошли. */
    public static String baritone(String nick, String command) {
        if (!isInside(nick)) {
            return "Baritone работает только за бота, в которого вы вошли: сначала .bot " + nick + " meteor";
        }
        return runBaritone(command);
    }

    /**
     * .bot chat &lt;ник&gt; ... для бота, в которого вы вошли: сообщение уходит от его имени,
     * а строки с префиксом Baritone (#...) выполняет Baritone.
     */
    public static String sendInside(String text) {
        MinecraftClient c = mc();
        if (c.player == null || c.getNetworkHandler() == null) return "вы ещё не зашли в мир";
        if (text == null || text.isBlank()) return "пустой текст";
        String p = BotBaritone.prefix();
        if (BotBaritone.available() && text.startsWith(p)) return runBaritone(text);
        if (text.startsWith("/")) c.getNetworkHandler().sendChatCommand(text.substring(1));
        else c.getNetworkHandler().sendChatMessage(text);
        return null;
    }

    private static String runBaritone(String command) {
        MinecraftClient c = mc();
        if (c.player == null || c.world == null) return "вы ещё не зашли в мир";
        return BotBaritone.run(BotBaritone.strip(command));
    }

    private static void openGui() {
        try {
            if (Utils.canOpenGui()) Tabs.get().getFirst().openScreen(GuiThemes.get());
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[BotSwap] open GUI failed", t);
        }
    }

    // ---------------------------------------------------------------- enter

    /** Войти в бота без открытия GUI. Возвращает null при успехе, иначе текст ошибки. */
    public static String enter(String nick) {
        return enter(nick, false);
    }

    /** Возвращает null при успехе, иначе текст ошибки. */
    public static String enter(String nick, boolean openGui) {
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

        // Запоминаем ваши модули и, когда мир бота загрузится, включаем набор бота (+ открываем GUI).
        BotModules.rememberOrigin();
        armReady(() -> {
            int n = BotModules.applyForBot(name);
            say("вы в боте " + name + " (модулей включено: " + n + "). Baritone: .bot " + name + " baritone <команда> или #команда в чате");
            if (openGui) after(2, BotSwap::openGui);
        });

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
        onReady = null;
        BotModules.forgetOrigin();
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

        // Пока мы ещё внутри бота: сохранить его набор модулей.
        try {
            BotModules.saveBot(nick);
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[BotSwap] save modules failed", t);
        }

        // Когда вернёмся в свой мир — восстановить ваши модули.
        if (backAddr != null) {
            armReady(() -> {
                BotModules.restoreOrigin();
                say("вы снова " + name + ", ваши модули восстановлены");
            });
        } else {
            BotModules.forgetOrigin();
        }

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
