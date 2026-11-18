package com.example.addon.bots;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Кольцевой буфер логов одного бота (потокобезопасный). Переживает переподключения бота. */
public final class BotLog {
    private static final int MAX = 500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern FORMATTING = Pattern.compile("§.");

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final AtomicInteger version = new AtomicInteger();

    public void add(String text) {
        if (text == null) return;
        String clean = FORMATTING.matcher(text).replaceAll("");
        String line = "[" + LocalTime.now().format(TIME) + "] " + clean;
        synchronized (lines) {
            lines.addLast(line);
            while (lines.size() > MAX) lines.removeFirst();
        }
        version.incrementAndGet();
    }

    /** Последние n строк (копия). */
    public List<String> last(int n) {
        synchronized (lines) {
            List<String> all = new ArrayList<>(lines);
            return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
        }
    }

    /** Меняется при каждой новой строке: по нему GUI понимает, что пора перерисовать. */
    public int version() {
        return version.get();
    }

    public void clear() {
        synchronized (lines) {
            lines.clear();
        }
        version.incrementAndGet();
    }
}
