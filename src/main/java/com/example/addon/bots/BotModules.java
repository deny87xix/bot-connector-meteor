package com.example.addon.bots;

import com.example.addon.AddonTemplate;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Свой набор включённых модулей Meteor у каждого бота.
 *
 * Модули Meteor глобальные: они работают на текущем клиентском игроке. Когда вы входите в бота, клиент и есть бот,
 * поэтому включённые там Killaura и т.п. работают за него. Чтобы они не «протекали» в ваш основной аккаунт:
 *  - при входе запоминаем, что было включено у вас (origin);
 *  - на боте включается его сохранённый набор (при первом входе остаётся то, что уже включено);
 *  - при выходе набор бота сохраняется, а ваш origin восстанавливается.
 */
public final class BotModules {
    private BotModules() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Set<String>> SAVED = new HashMap<>();
    private static boolean loaded;
    private static Set<String> origin;

    private static String key(String nick) { return nick.toLowerCase(); }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("bots-addon").resolve("modules.json");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Map<String, List<String>> m = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8),
                new TypeToken<Map<String, List<String>>>() {}.getType());
            if (m != null) m.forEach((k, v) -> SAVED.put(k, new LinkedHashSet<>(v)));
        } catch (Exception e) {
            AddonTemplate.LOG.warn("[BotModules] load failed", e);
        }
    }

    private static synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Map<String, List<String>> m = new HashMap<>();
            SAVED.forEach((k, v) -> m.put(k, new ArrayList<>(v)));
            Path tmp = f.resolveSibling("modules.json.tmp");
            Files.writeString(tmp, GSON.toJson(m), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            AddonTemplate.LOG.warn("[BotModules] save failed", e);
        }
    }

    public static Set<String> activeNow() {
        Set<String> out = new LinkedHashSet<>();
        for (Module m : new ArrayList<>(Modules.get().getActive())) out.add(m.name);
        return out;
    }

    /** Запомнить набор модулей вашего аккаунта перед входом в бота. */
    public static void rememberOrigin() {
        origin = activeNow();
    }

    public static void forgetOrigin() {
        origin = null;
    }

    /** Включить набор бота (вызывать, когда новый мир уже загружен: модули требуют игрока). */
    public static int applyForBot(String nick) {
        ensureLoaded();
        Set<String> want = SAVED.get(key(nick));
        if (want == null) want = origin != null ? origin : activeNow();
        apply(want);
        return want.size();
    }

    /** Сохранить набор бота (вызывать, пока вы ещё внутри него). */
    public static void saveBot(String nick) {
        ensureLoaded();
        SAVED.put(key(nick), activeNow());
        save();
    }

    /** Вернуть набор модулей вашего аккаунта. */
    public static void restoreOrigin() {
        if (origin == null) return;
        apply(origin);
        origin = null;
    }

    private static void apply(Set<String> want) {
        for (Module m : new ArrayList<>(Modules.get().getActive())) {
            if (!want.contains(m.name)) {
                try { m.disable(); } catch (Throwable t) { AddonTemplate.LOG.warn("[BotModules] disable {} failed", m.name, t); }
            }
        }
        for (String name : want) {
            Module m = Modules.get().get(name);
            if (m == null || m.isActive()) continue;
            try { m.enable(); } catch (Throwable t) { AddonTemplate.LOG.warn("[BotModules] enable {} failed", name, t); }
        }
    }
}
