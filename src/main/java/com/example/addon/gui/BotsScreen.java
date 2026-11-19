package com.example.addon.gui;

import com.example.addon.bots.Bot;
import com.example.addon.bots.BotBaritone;
import com.example.addon.bots.BotManager;
import com.example.addon.bots.BotService;
import com.example.addon.bots.BotSwap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.input.KeyInput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;

/**
 * "Bots" screen: saved bots, start/stop, enter, per-bot chat on/off, Meteor/Baritone as a bot, logs.
 * The UI is in English; a Russian help block with all features is at the bottom of the window.
 *
 * The table and the logs are rebuilt only when their data changes (by signature), input fields are never touched,
 * so focus and typed text are not lost. Coordinates are updated in place.
 */
public class BotsScreen extends WindowTabScreen {
    private static final int LOG_LINES = 30;
    private static final int WRAP = 120;

    /** Справка на русском: все актуальные функции (отображается в низу окна). */
    private static final String[] HELP_RU = {
        "Таблица ботов:",
        "  Select - выбрать бота (для Baritone, Meteor и логов); Start / Stop - запустить или остановить (профиль остаётся).",
        "  Chat: on/off - показывать или скрывать вам чат этого бота (в логе бота сообщения остаются).",
        "  Enter / Back - войти в бота или вернуться в свой аккаунт; Delete - удалить бота и его профиль.",
        "  Вверху: Start all / Stop all - все боты сразу; кнопка «All bots chat» - общий выключатель чата (при перезапуске игры сбрасывается).",
        "Добавление: ник + адрес. Save - сохранить без запуска; Save and start - сохранить и сразу подключить.",
        "  Боты хранятся в config/bots-addon/bots.json и загружаются при старте игры (в списке как остановленные).",
        "Baritone: Tab - подсказки (go -> goto), Enter или Send - отправить, Stop - команда stop.",
        "  Если вы не внутри бота, команда сама войдёт в него (с перезаходом) и выполнится после загрузки мира.",
        "Meteor: Open Meteor as bot - войти в бота и открыть Meteor от его имени (модули бота запоминаются отдельно).",
        "  Return to my account - вернуться в свой аккаунт (то же, что .bot back).",
        "Logs: последние 30 строк выбранного бота (состояния, чат сервера, команды Baritone); Clear - очистить.",
        "Команды в чате: .bot gui, connect, save, start, stop, remove, clear, list, log <ник> [N], mute/unmute <ник|all>,",
        "  chat <ник> <текст>, enter <ник>, back, <ник> meteor, <ник> baritone <команда> (с подсказками по Tab).",
        "Ограничения: только offline-mode серверы; вход в бота пока идёт с перезаходом клиента.",
        "  Сообщения об ошибках в строке статуса пока могут быть на русском."
    };

    private String selected;

    private WVerticalList tableBox;
    private WVerticalList logBox;
    private WLabel statusLabel;
    private WLabel selectedLabel;
    private WTextBox nickBox;
    private WTextBox addrBox;
    private WTextBox cmdBox;

    private final Map<String, WLabel> posLabels = new HashMap<>();
    private int tableSig;
    private int logSig;

    public BotsScreen(GuiTheme theme, Tab tab) {
        super(theme, tab);
    }

    @Override
    public void initWidgets() {
        List<Bot> bots = sorted();
        if (selected == null && !bots.isEmpty()) selected = bots.get(0).name;

        add(theme.label("Bots", true));

        WHorizontalList top = add(theme.horizontalList()).expandX().widget();
        WButton startAll = top.add(theme.button("Start all")).widget();
        startAll.action = () -> status("started bots: " + BotManager.INSTANCE.startAll());
        WButton stopAll = top.add(theme.button("Stop all")).widget();
        stopAll.action = () -> status("stopped bots: " + BotManager.INSTANCE.stopAll());

        statusLabel = add(theme.label("Ready")).expandX().widget();

        tableBox = add(theme.verticalList()).expandX().minWidth(760).widget();

        add(theme.horizontalSeparator()).expandX();

        // Add a bot
        add(theme.label("Add bot", true));
        WHorizontalList form = add(theme.horizontalList()).expandX().widget();
        nickBox = form.add(theme.textBox("", "nick", (text, c) -> (c < 128 && Character.isLetterOrDigit(c)) || c == '_'))
            .minWidth(140).widget();
        addrBox = form.add(theme.textBox("", "address ip[:port]")).expandX().minWidth(220).widget();
        WButton save = form.add(theme.button("Save")).widget();
        save.action = () -> addBot(false);
        WButton run = form.add(theme.button("Save and start")).widget();
        run.action = () -> addBot(true);

        add(theme.horizontalSeparator()).expandX();

        selectedLabel = add(theme.label("Selected bot: -", true)).expandX().widget();

        // Baritone
        add(theme.label("Baritone (Tab - suggestions, Enter - send)"));
        WHorizontalList cmdRow = add(theme.horizontalList()).expandX().widget();
        cmdBox = cmdRow.add(theme.textBox("", "e.g.: goto 100 70 100", (text, c) -> true, BaritoneRenderer.class))
            .expandX().minWidth(420).widget();
        WButton send = cmdRow.add(theme.button("Send")).widget();
        send.action = this::sendCommand;
        WButton stopBaritone = cmdRow.add(theme.button("Stop")).widget();
        stopBaritone.action = () -> runQuick("stop");

        // Meteor
        add(theme.label("Meteor"));
        WHorizontalList meteorRow = add(theme.horizontalList()).expandX().widget();
        WButton meteor = meteorRow.add(theme.button("Open Meteor as bot")).widget();
        meteor.action = () -> {
            if (selected == null) { status("select a bot first"); return; }
            String err = BotSwap.meteor(selected);
            status(err == null ? "opening Meteor as " + selected : err);
        };
        WButton back = meteorRow.add(theme.button("Return to my account")).widget();
        back.action = () -> {
            String err = BotSwap.back();
            status(err == null ? "returning" : err);
        };

        add(theme.horizontalSeparator()).expandX();

        // Logs
        WHorizontalList logHead = add(theme.horizontalList()).expandX().widget();
        logHead.add(theme.label("Logs", true));
        WButton clear = logHead.add(theme.button("Clear")).widget();
        clear.action = () -> {
            Bot b = selectedBot();
            if (b != null) b.log.clear();
        };
        logBox = add(theme.verticalList()).expandX().widget();

        // Справка на русском
        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Справка (RU): все функции", true));
        for (String line : HELP_RU) add(theme.label(line));

        tableSig = tableSignature();
        logSig = logSignature();
        buildTable();
        buildLog();
        updateSelectedLabel();
    }

    // ------------------------------------------------------------------ refresh

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (tableBox == null || logBox == null) return;

        int ts = tableSignature();
        if (ts != tableSig) {
            tableSig = ts;
            if (selectedBot() == null) {
                List<Bot> bots = sorted();
                selected = bots.isEmpty() ? null : bots.get(0).name;
            }
            buildTable();
            updateSelectedLabel();
        }

        int ls = logSignature();
        if (ls != logSig) {
            logSig = ls;
            buildLog();
        }

        updatePositions();
    }

    private int tableSignature() {
        int h = 17;
        for (Bot b : sorted()) {
            h = h * 31 + b.name.hashCode();
            h = h * 31 + b.address.hashCode();
            h = h * 31 + b.state.ordinal();
            h = h * 31 + (BotSwap.isInside(b.name) ? 1 : 0);
            h = h * 31 + (b.muteChat ? 1 : 0);
            h = h * 31 + b.status.hashCode();
        }
        h = h * 31 + (BotManager.INSTANCE.isMuteAll() ? 1 : 0);
        h = h * 31 + (selected == null ? 0 : selected.toLowerCase().hashCode());
        h = h * 31 + (BotSwap.insideNick() == null ? 0 : BotSwap.insideNick().hashCode());
        return h;
    }

    private int logSignature() {
        Bot b = selectedBot();
        if (b == null) return 0;
        return b.log.version() * 31 + b.name.hashCode();
    }

    private void updatePositions() {
        for (Map.Entry<String, WLabel> e : posLabels.entrySet()) {
            Bot b = BotManager.INSTANCE.get(e.getKey());
            String t = (b != null && b.state == Bot.State.ONLINE && b.sim != null) ? b.sim.describe() : "";
            if (!t.equals(e.getValue().get())) e.getValue().set(t);
        }
    }

    private void updateSelectedLabel() {
        if (selectedLabel == null) return;
        selectedLabel.set("Selected bot: " + (selected == null ? "-" : selected));
    }

    // ------------------------------------------------------------------ table and logs

    private void buildTable() {
        tableBox.clear();
        posLabels.clear();

        if (BotSwap.isInside()) {
            tableBox.add(theme.label("You are inside bot " + BotSwap.insideNick() + " (your nick: " + BotSwap.originName() + ")"));
        }

        // Global chat switch for all bots
        boolean allMuted = BotManager.INSTANCE.isMuteAll();
        WButton muteAll = tableBox.add(theme.button(allMuted
            ? "All bots chat: OFF (click to turn on)"
            : "All bots chat: ON (click to turn off)")).widget();
        muteAll.action = () -> {
            boolean now = !BotManager.INSTANCE.isMuteAll();
            BotManager.INSTANCE.setMuteAll(now);
            status(now ? "chat of all bots is off" : "chat of all bots is on");
        };

        List<Bot> bots = sorted();
        if (bots.isEmpty()) {
            tableBox.add(theme.label("No bots yet: add one below"));
            return;
        }

        WTable table = tableBox.add(theme.table()).expandX().widget();
        for (Bot b : bots) {
            boolean inside = BotSwap.isInside(b.name);
            boolean isSelected = b.name.equalsIgnoreCase(selected);

            table.add(theme.label((isSelected ? "> " : "") + b.name));
            table.add(theme.label(b.address));
            table.add(theme.label(inside ? "YOU ARE INSIDE" : b.state.name()));
            WLabel pos = table.add(theme.label("")).widget();
            posLabels.put(b.name, pos);

            WButton select = table.add(theme.button("Select")).widget();
            select.action = () -> selected = b.name;

            if (b.isActive()) {
                WButton stop = table.add(theme.button("Stop")).widget();
                stop.action = () -> {
                    BotManager.INSTANCE.stop(b.name);
                    status("bot " + b.name + " stopped");
                };
            } else {
                WButton start = table.add(theme.button("Start")).widget();
                start.action = () -> {
                    String err = BotManager.INSTANCE.start(b.name);
                    status(err == null ? "bot " + b.name + " is starting" : err);
                };
            }

            // Bot chat: on = messages come to you, off = only into the bot's log
            WButton chat = table.add(theme.button(b.muteChat ? "Chat: off" : "Chat: on")).widget();
            chat.action = () -> {
                boolean mute = !b.muteChat;
                BotManager.INSTANCE.setMute(b.name, mute);
                status(mute ? "chat of bot " + b.name + " is off" : "chat of bot " + b.name + " is on");
            };

            if (inside) {
                WButton back = table.add(theme.button("Back")).widget();
                back.action = () -> {
                    String err = BotSwap.back();
                    status(err == null ? "returning" : err);
                };
            } else {
                WButton enter = table.add(theme.button("Enter")).widget();
                enter.action = () -> {
                    String err = BotSwap.enter(b.name);
                    status(err == null ? "entering bot " + b.name : err);
                };
            }

            WButton delete = table.add(theme.button("Delete")).widget();
            delete.action = () -> {
                if (BotSwap.isInside(b.name)) {
                    status("return from the bot first (Back button)");
                    return;
                }
                BotManager.INSTANCE.remove(b.name);
                status("bot " + b.name + " deleted");
            };

            table.row();
        }
    }

    private void buildLog() {
        logBox.clear();
        Bot b = selectedBot();
        if (b == null) {
            logBox.add(theme.label("Select a bot"));
            return;
        }
        List<String> lines = b.log.last(LOG_LINES);
        if (lines.isEmpty()) {
            logBox.add(theme.label("No entries yet"));
            return;
        }
        for (String line : lines) {
            for (int i = 0; i < line.length(); i += WRAP) {
                logBox.add(theme.label(line.substring(i, Math.min(line.length(), i + WRAP))));
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private void addBot(boolean start) {
        String nick = nickBox.get().trim();
        String addr = addrBox.get().trim();
        String err = start ? BotManager.INSTANCE.connect(nick, addr) : BotManager.INSTANCE.save(nick, addr);
        if (err != null) {
            status(err);
            return;
        }
        selected = nick;
        nickBox.set("");
        status(start ? "bot " + nick + " is starting" : "bot " + nick + " saved");
    }

    private void sendCommand() {
        runQuick(cmdBox.get().trim());
        cmdBox.set("");
    }

    private void runQuick(String command) {
        if (selected == null) {
            status("select a bot first");
            return;
        }
        if (command.isEmpty()) return;
        String err = BotService.baritone(selected, command);
        status(err == null ? "Baritone [" + selected + "]: " + command : err);
    }

    private void status(String text) {
        if (statusLabel != null) statusLabel.set(text);
    }

    private Bot selectedBot() {
        return selected == null ? null : BotManager.INSTANCE.get(selected);
    }

    private static List<Bot> sorted() {
        List<Bot> list = new ArrayList<>(BotManager.INSTANCE.all());
        list.sort(Comparator.comparing(b -> b.name.toLowerCase()));
        return list;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        boolean enter = input.key() == GLFW_KEY_ENTER || input.key() == GLFW_KEY_KP_ENTER;
        if (enter && cmdBox != null && cmdBox.isFocused()) {
            sendCommand();
            return true;
        }
        return super.keyPressed(input);
    }

    // ------------------------------------------------------------------ Baritone suggestions in the input box

    /** Tab suggestions like in Baritone chat: "go" -> "goto". Must be public with a public no-arg constructor. */
    public static class BaritoneRenderer implements WTextBox.Renderer {
        @Override
        public void render(GuiRenderer renderer, double x, double y, String text, Color color) {
            renderer.text(text, x, y, color, false);
        }

        @Override
        public List<String> getCompletions(String text, int position) {
            String before = text.substring(0, Math.min(position, text.length()));
            String p = BotBaritone.prefix();
            String cmd = before.startsWith(p) ? before.substring(p.length()) : before;
            List<String> list = BotBaritone.tabComplete(cmd);
            return list.isEmpty() ? null : list;
        }
    }
}
