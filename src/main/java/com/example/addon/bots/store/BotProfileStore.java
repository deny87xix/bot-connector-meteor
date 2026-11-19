package com.example.addon.bots.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Сохраняет список ботов (ник + адрес + настройки) в config/bots-addon/bots.json. */
public class BotProfileStore {
    public static class Profile {
        public String nickname;
        public String address;
        /** Чат бота отключён (сообщения сервера не показываются вам). Старые файлы без поля читаются как false. */
        public boolean muteChat;
        public Profile(String n, String a) { nickname = n; address = a; }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Profile> PROFILES = new ArrayList<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("bots-addon").resolve("bots.json");
    }

    public static synchronized void load() {
        PROFILES.clear();
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            String json = Files.readString(f, StandardCharsets.UTF_8);
            List<Profile> list = GSON.fromJson(json, new TypeToken<List<Profile>>() {}.getType());
            if (list != null) PROFILES.addAll(list);
        } catch (Exception ignored) {
        }
    }

    /** Копия сохранённых профилей. */
    public static synchronized List<Profile> all() {
        return new ArrayList<>(PROFILES);
    }

    /** Сохраняет бота; ранее выставленная настройка muteChat сохраняется. */
    public static synchronized void put(String nick, String address) {
        boolean mute = false;
        for (Profile p : PROFILES) {
            if (p.nickname != null && p.nickname.equalsIgnoreCase(nick)) mute = p.muteChat;
        }
        PROFILES.removeIf(p -> p.nickname.equalsIgnoreCase(nick));
        Profile np = new Profile(nick, address);
        np.muteChat = mute;
        PROFILES.add(np);
        save();
    }

    public static synchronized void setMute(String nick, boolean mute) {
        for (Profile p : PROFILES) {
            if (p.nickname != null && p.nickname.equalsIgnoreCase(nick)) p.muteChat = mute;
        }
        save();
    }

    public static synchronized void remove(String nick) {
        PROFILES.removeIf(p -> p.nickname.equalsIgnoreCase(nick));
        save();
    }

    public static synchronized void clear() {
        PROFILES.clear();
        save();
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("bots.json.tmp");
            Files.writeString(tmp, GSON.toJson(PROFILES), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
