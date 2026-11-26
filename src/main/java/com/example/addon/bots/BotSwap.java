package com.example.addon.bots;

import com.example.addon.AddonTemplate;
import com.example.addon.bots.real.BotSession;
import com.example.addon.bots.real.BotSessions;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Вход в бота и возврат обратно БЕЗ перезахода.
 *
 * Каждый бот — настоящая клиентская сессия (см. {@link BotSession}): своё соединение, мир и игрок.
 * «Вход» подменяет в MinecraftClient мир/игрока/менеджер бота, ваша сессия паркуется и продолжает
 * жить на сервере (ваш персонаж не исчезает). Meteor и Baritone видят бота как mc.player.
 * Включённые модули Meteor запоминаются отдельно для каждого бота (см. {@link BotModules}).
 */
public final class BotSwap {
    private BotSwap() {}

    private static final class Task {
        int left;
        final Runnable run;
        Task(int left, Runnable run) { this.left = left; this.run = run; }
    }

    private static final List<Task> TASKS = new ArrayList<>();

    private static String botNick;
    private static String originName;

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    public static boolean isInside() { return botNick != null; }

    public static boolean isInside(String nick) { return botNick != null && botNick.equalsIgnoreCase(nick); }

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

    // ---------------------------------------------------------------- meteor / baritone

    /**
     * .bot &lt;ник&gt; meteor — войти в бота (если ещё не внутри) и открыть Meteor от его имени.
     * Возвращает null при успехе, иначе текст ошибки.
     */
    public static String meteor(String nick) {
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
            return "Baritone работает только за бота, в которого вы вошли: сначала .bot enter " + nick;
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
        if (botNick != null) return "вы уже внутри бота " + botNick + ": сначала .bot back";

        Bot bot = BotManager.INSTANCE.get(nick);
        if (bot == null) return "бот не найден: " + nick;
        BotSession session = bot.session;
        if (session == null || !bot.isActive()) return "бот не запущен: сначала .bot start " + bot.name;

        String me = mc().getSession().getUsername();
        if (me.equalsIgnoreCase(bot.name)) return "это ваш текущий ник";

        // Запоминаем ваши модули, потом подменяем сессию и включаем набор бота.
        BotModules.rememberOrigin();
        String err = BotSessions.enter(session);
        if (err != null) {
            BotModules.forgetOrigin();
            return err;
        }

        botNick = bot.name;
        originName = me;
        int n = 0;
        try {
            n = BotModules.applyForBot(bot.name);
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[BotSwap] apply modules failed", t);
        }
        say("вы в боте " + bot.name + " (модулей включено: " + n + "). Вернуться: .bot back. Baritone: .bot " + bot.name + " baritone <команда> или #команда в чате");
        if (openGui) after(3, BotSwap::openGui);
        return null;
    }

    // ---------------------------------------------------------------- back

    /** Возвращает null при успехе, иначе текст ошибки. */
    public static String back() {
        if (botNick == null) return "вы не внутри бота";
        leaveAndRestore();
        return null;
    }

    /** Вернуться, если вы в боте: кик бота, остановка бота, обрыв вашего соединения. */
    public static void forceBack() {
        if (!BotSessions.isEntered()) {
            botNick = null;
            return;
        }
        leaveAndRestore();
    }

    private static void leaveAndRestore() {
        String nick = botNick;
        // Пока мы ещё внутри бота: сохранить его набор модулей.
        if (nick != null) {
            try {
                BotModules.saveBot(nick);
            } catch (Throwable t) {
                AddonTemplate.LOG.warn("[BotSwap] save modules failed", t);
            }
        }
        BotSessions.leave();
        botNick = null;
        // Теперь вы снова в своём мире: вернуть ваши модули.
        try {
            BotModules.restoreOrigin();
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[BotSwap] restore modules failed", t);
        }
        say("вы снова в своёй сессии, ваши модули восстановлены");
    }
}
