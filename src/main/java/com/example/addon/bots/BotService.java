package com.example.addon.bots;

import com.example.addon.AddonTemplate;
import com.example.addon.gui.BotsTab;
import meteordevelopment.meteorclient.gui.GuiThemes;

import java.util.ArrayList;
import java.util.List;

/** Общие операции для команд и GUI: отложенные задачи, открытие GUI, Baritone за бота. */
public final class BotService {
    private BotService() {}

    private static final class Task {
        int left;
        final Runnable run;
        Task(int left, Runnable run) { this.left = left; this.run = run; }
    }

    private static final List<Task> TASKS = new ArrayList<>();

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
     * Выполнить команду Baritone за бота. Если вы ещё не в этом боте, команда сначала входит в него
     * (без перезахода, мгновенно). Возвращает null при успехе, иначе текст ошибки.
     */
    public static String baritone(String nick, String command) {
        Bot b = BotManager.INSTANCE.get(nick);
        if (b == null) return "бот не найден: " + nick;
        if (command == null || command.isBlank()) return "пустая команда Baritone";

        b.log.add("baritone: " + command.trim());

        if (BotSwap.isInside(nick)) return BotSwap.baritone(nick, command);
        if (BotSwap.isInside()) return "вы внутри бота " + BotSwap.insideNick() + ": сначала .bot back";

        String err = BotSwap.enter(nick, false);
        if (err != null) return err;
        return BotSwap.baritone(nick, command);
    }

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
                AddonTemplate.LOG.warn("[BotService] task failed", e);
            }
        }
    }
}
