package com.example.addon.bots;

import com.example.addon.AddonTemplate;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
            Object cm = commandManager();

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

    private static Object commandManager() throws Exception {
        Class<?> api = Class.forName("baritone.api.BaritoneAPI");
        Object provider = api.getMethod("getProvider").invoke(null);

        Method getPrimary = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone");
        Object baritone = getPrimary.invoke(provider);

        Method getCm = Class.forName("baritone.api.IBaritone").getMethod("getCommandManager");
        return getCm.invoke(baritone);
    }

    /**
     * Подсказки Baritone для текста команды (без префикса): для «go» вернёт «goto» и т. п.
     * Любая ошибка (нет Baritone, нет игрока и т. д.) даёт пустой список.
     */
    public static List<String> tabComplete(String command) {
        if (!available() || command == null) return List.of();
        try {
            Object cm = commandManager();
            Method m = Class.forName("baritone.api.command.manager.ICommandManager").getMethod("tabComplete", String.class);
            Object r = m.invoke(cm, command);
            if (r instanceof Stream<?> s) {
                return s.map(String::valueOf).limit(60).collect(Collectors.toList());
            }
        } catch (Throwable ignored) {
        }
        return List.of();
    }

    /**
     * Подсказки для аргумента chat-команды «.bot &lt;ник&gt; baritone &lt;команда&gt;» (поддерживает ввод с префиксом «#»
     * и без него). Подсказка заменяет только последнее слово.
     */
    public static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        String p = prefix();
        boolean hasPrefix = remaining.startsWith(p);
        String cmd = hasPrefix ? remaining.substring(p.length()) : remaining;

        int lastSpace = remaining.lastIndexOf(' ');
        int offset = lastSpace >= 0 ? lastSpace + 1 : (hasPrefix ? p.length() : 0);

        SuggestionsBuilder b = builder.createOffset(builder.getStart() + offset);
        for (String s : tabComplete(cmd)) b.suggest(s);
        return b.buildFuture();
    }
}
