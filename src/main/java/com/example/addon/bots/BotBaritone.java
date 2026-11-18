package com.example.addon.bots;

import com.example.addon.AddonTemplate;

import java.lang.reflect.Method;

/**
 * Запуск команд Baritone «от имени» бота, в которого вы вошли.
 *
 * Baritone в зависимости аддона не подключён (он лежит в mods отдельно), поэтому вызываем его API через рефлексию:
 *   BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(String)
 * Primary-экземпляр Baritone привязан к текущему клиентскому игроку, то есть после .bot &lt;ник&gt; meteor
 * это именно игрок-бот.
 */
public final class BotBaritone {
    private BotBaritone() {}

    public static boolean available() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Префикс команд Baritone (по умолчанию «#»). */
    public static String prefix() {
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object settings = api.getMethod("getSettings").invoke(null);
            Object setting = settings.getClass().getField("prefix").get(settings);
            Object v = setting.getClass().getField("value").get(setting);
            if (v instanceof String s && !s.isEmpty()) return s;
        } catch (Throwable ignored) {
        }
        return "#";
    }

    /** Убирает префикс Baritone, если он есть. */
    public static String strip(String text) {
        String p = prefix();
        String t = text.trim();
        return t.startsWith(p) ? t.substring(p.length()).trim() : t;
    }

    /** Возвращает null при успехе, иначе текст ошибки. Вызывать на главном потоке. */
    public static String run(String command) {
        if (command == null || command.isBlank()) return "пустая команда Baritone";
        if (!available()) return "Baritone не найден: положите его jar в папку mods рядом с Meteor";
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object provider = api.getMethod("getProvider").invoke(null);

            Method getPrimary = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone");
            Object baritone = getPrimary.invoke(provider);

            Method getCm = Class.forName("baritone.api.IBaritone").getMethod("getCommandManager");
            Object cm = getCm.invoke(baritone);

            Method exec = Class.forName("baritone.api.command.manager.ICommandManager").getMethod("execute", String.class);
            Object ok = exec.invoke(cm, command.trim());
            if (ok instanceof Boolean b && !b) return "Baritone не знает такую команду: " + command;
            return null;
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[BotBaritone] run failed", t);
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "ошибка Baritone: " + c.getClass().getSimpleName() + ": " + c.getMessage();
        }
    }
}
