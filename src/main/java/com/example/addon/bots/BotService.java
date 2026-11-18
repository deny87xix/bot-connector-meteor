package com.example.addon.bots;

import com.example.addon.AddonTemplate;
import com.example.addon.gui.BotsTab;
import meteordevelopment.meteorclient.gui.GuiThemes;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Общие операции для команд и GUI: отложенные задачи, открытие GUI, Baritone за бота.
 *
 * Пока бот не умеет запускать Baritone без входа (см. docs/NO_RECONNECT_SPEC.md), команда для бота, в которого вы
 * ещё не вошли, сначала входит в него, ждёт загрузки мира и только потом выполняет команду Baritone.
 */
public final class BotService {
    private BotService() {}

    private static final class Task {
        int left;
        final Runnable run;
        Task(int left, Runnable run) { this.left = left; this.run = run; }
    }

    private static final class Pending {
        final String nick;
        final String command;
        int waited;
        int stable;
        Pending(String nick, String command) { this.nick = nick; this.command = command; }
    }

    private static final List<Task> TASKS = new ArrayList<>();
    private static Pending pending;

    /** Выполнить r через ticks клиентских тиков (на главном потоке). */
    public static void later(int ticks, Runnable r) {
        TASKS.add(new Task(ticks, r));
    }

    /** Открыть вкладку Bots (чуть позже: чат закрывается уже после выполнения команды). */
    public static void openGui() {
        later(3, () -> {
            try {
                BotsTab.INSTANCE.openScreen(GuiThemes.get());
            } catch (Throwable t) {
                AddonTemplate.LOG.warn("[BotService] open GUI failed", t);
            }
        });
    }

    /**
     * Выполнить команду Baritone за бота. Возвращает null при успехе (в том числе когда команда поставлена в очередь
     * до входа в бота), иначе текст ошибки.
     */
    public static String baritone(String nick, String command) {
        Bot b = BotManager.INSTANCE.get(nick);
        if (b == null) return "бот не найден: " + nick;
        if (command == null || command.isBlank()) return "пустая команда Baritone";

        b.log.add("baritone: " + command.trim());

        if (BotSwap.isInside(nick)) return BotSwap.baritone(nick, command);
        if (BotSwap.isInside()) return "вы внутри бота " + BotSwap.insideNick() + ": сначала .bot back";
        if (pending != null) return "уже жду входа в бота " + pending.nick + ", подождите";

        String err = BotSwap.enter(nick, false);
        if (err != null) return err;
        pending = new Pending(nick, command);
        b.log.add("вхожу в бота, команда Baritone выполнится после загрузки мира");
        return null;
    }

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
                    AddonTemplate.LOG.warn("[BotService] task failed", e);
                }
            }
        }

        Pending p = pending;
        if (p == null) return;

        Bot bot = BotManager.INSTANCE.get(p.nick);
        if (++p.waited > 20 * 120) {
            pending = null;
            if (bot != null) bot.log.add("команда Baritone не выполнена: мир бота не загрузился за 2 минуты");
            return;
        }

        MinecraftClient c = MinecraftClient.getInstance();
        boolean ready = BotSwap.isInside(p.nick)
            && c.world != null
            && c.player != null
            && c.currentScreen == null
            && c.getNetworkHandler() != null
            && c.player.getName().getString().equalsIgnoreCase(p.nick);
        if (!ready) {
            p.stable = 0;
            return;
        }

        // Ждём ~3 секунды стабильного состояния: BotSwap за это время успевает применить модули бота.
        if (++p.stable < 60) return;

        pending = null;
        String err = BotSwap.baritone(p.nick, p.command);
        if (bot != null) bot.log.add(err == null ? "Baritone: выполнено " + p.command : "Baritone: " + err);
    }
}
