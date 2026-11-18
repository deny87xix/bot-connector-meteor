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
 * Экран «Bots»: сохранённые боты, запуск/остановка, вход, Meteor/Baritone от имени бота, логи.
 *
 * Таблица и логи перестраиваются только когда меняются данные (по сигнатуре), поля ввода не трогаются,
 * поэтому фокус и введённый текст не пропадают. Координаты обновляются на месте.
 */
public class BotsScreen extends WindowTabScreen {
    private static final int LOG_LINES = 30;
    private static final int WRAP = 120;

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

        add(theme.label("Боты", true));

        WHorizontalList top = add(theme.horizontalList()).expandX().widget();
        WButton startAll = top.add(theme.button("Запустить всех")).widget();
        startAll.action = () -> status("запущено ботов: " + BotManager.INSTANCE.startAll());
        WButton stopAll = top.add(theme.button("Остановить всех")).widget();
        stopAll.action = () -> status("остановлено ботов: " + BotManager.INSTANCE.stopAll());

        statusLabel = add(theme.label("Готово")).expandX().widget();

        tableBox = add(theme.verticalList()).expandX().minWidth(700).widget();

        add(theme.horizontalSeparator()).expandX();

        // Добавление бота
        add(theme.label("Добавить бота", true));
        WHorizontalList form = add(theme.horizontalList()).expandX().widget();
        nickBox = form.add(theme.textBox("", "ник", (text, c) -> (c < 128 && Character.isLetterOrDigit(c)) || c == '_'))
            .minWidth(140).widget();
        addrBox = form.add(theme.textBox("", "адрес ip[:порт]")).expandX().minWidth(220).widget();
        WButton save = form.add(theme.button("Сохранить")).widget();
        save.action = () -> addBot(false);
        WButton run = form.add(theme.button("Сохранить и запустить")).widget();
        run.action = () -> addBot(true);

        add(theme.horizontalSeparator()).expandX();

        selectedLabel = add(theme.label("Выбран бот: -", true)).expandX().widget();

        // Baritone
        add(theme.label("Baritone (Tab - подсказки, Enter - отправить)"));
        WHorizontalList cmdRow = add(theme.horizontalList()).expandX().widget();
        cmdBox = cmdRow.add(theme.textBox("", "например: goto 100 70 100", (text, c) -> true, BaritoneRenderer.class))
            .expandX().minWidth(420).widget();
        WButton send = cmdRow.add(theme.button("Отправить")).widget();
        send.action = this::sendCommand;
        WButton stopBaritone = cmdRow.add(theme.button("Стоп")).widget();
        stopBaritone.action = () -> runQuick("stop");

        // Meteor
        add(theme.label("Meteor"));
        WHorizontalList meteorRow = add(theme.horizontalList()).expandX().widget();
        WButton meteor = meteorRow.add(theme.button("Открыть Meteor от имени бота")).widget();
        meteor.action = () -> {
            if (selected == null) { status("выберите бота"); return; }
            String err = BotSwap.meteor(selected);
            status(err == null ? "открываю Meteor за " + selected : err);
        };
        WButton back = meteorRow.add(theme.button("Вернуться в свой аккаунт")).widget();
        back.action = () -> {
            String err = BotSwap.back();
            status(err == null ? "возвращаюсь" : err);
        };

        add(theme.horizontalSeparator()).expandX();

        // Логи
        WHorizontalList logHead = add(theme.horizontalList()).expandX().widget();
        logHead.add(theme.label("Логи", true));
        WButton clear = logHead.add(theme.button("Очистить")).widget();
        clear.action = () -> {
            Bot b = selectedBot();
            if (b != null) b.log.clear();
        };
        logBox = add(theme.verticalList()).expandX().widget();

        tableSig = tableSignature();
        logSig = logSignature();
        buildTable();
        buildLog();
        updateSelectedLabel();
    }

    // ------------------------------------------------------------------ обновление

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
            h = h * 31 + b.status.hashCode();
        }
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
        selectedLabel.set("Выбран бот: " + (selected == null ? "-" : selected));
    }

    // ------------------------------------------------------------------ таблица и логи

    private void buildTable() {
        tableBox.clear();
        posLabels.clear();

        if (BotSwap.isInside()) {
            tableBox.add(theme.label("Вы внутри бота " + BotSwap.insideNick() + " (ваш ник: " + BotSwap.originName() + ")"));
        }

        List<Bot> bots = sorted();
        if (bots.isEmpty()) {
            tableBox.add(theme.label("Ботов нет: добавьте ниже"));
            return;
        }

        WTable table = tableBox.add(theme.table()).expandX().widget();
        for (Bot b : bots) {
            boolean inside = BotSwap.isInside(b.name);
            boolean isSelected = b.name.equalsIgnoreCase(selected);

            table.add(theme.label((isSelected ? "> " : "") + b.name));
            table.add(theme.label(b.address));
            table.add(theme.label(inside ? "ВЫ ВНУТРИ" : b.state.name()));
            WLabel pos = table.add(theme.label("")).widget();
            posLabels.put(b.name, pos);

            WButton select = table.add(theme.button("Выбрать")).widget();
            select.action = () -> selected = b.name;

            if (b.isActive()) {
                WButton stop = table.add(theme.button("Стоп")).widget();
                stop.action = () -> {
                    BotManager.INSTANCE.stop(b.name);
                    status("бот " + b.name + " остановлен");
                };
            } else {
                WButton start = table.add(theme.button("Старт")).widget();
                start.action = () -> {
                    String err = BotManager.INSTANCE.start(b.name);
                    status(err == null ? "бот " + b.name + " запускается" : err);
                };
            }

            if (inside) {
                WButton back = table.add(theme.button("Назад")).widget();
                back.action = () -> {
                    String err = BotSwap.back();
                    status(err == null ? "возвращаюсь" : err);
                };
            } else {
                WButton enter = table.add(theme.button("Войти")).widget();
                enter.action = () -> {
                    String err = BotSwap.enter(b.name);
                    status(err == null ? "вхожу в бота " + b.name : err);
                };
            }

            WButton delete = table.add(theme.button("Удалить")).widget();
            delete.action = () -> {
                if (BotSwap.isInside(b.name)) {
                    status("сначала вернитесь из бота (кнопка Назад)");
                    return;
                }
                BotManager.INSTANCE.remove(b.name);
                status("бот " + b.name + " удалён");
            };

            table.row();
        }
    }

    private void buildLog() {
        logBox.clear();
        Bot b = selectedBot();
        if (b == null) {
            logBox.add(theme.label("Выберите бота"));
            return;
        }
        List<String> lines = b.log.last(LOG_LINES);
        if (lines.isEmpty()) {
            logBox.add(theme.label("Записей пока нет"));
            return;
        }
        for (String line : lines) {
            for (int i = 0; i < line.length(); i += WRAP) {
                logBox.add(theme.label(line.substring(i, Math.min(line.length(), i + WRAP))));
            }
        }
    }

    // ------------------------------------------------------------------ действия

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
        status(start ? "бот " + nick + " запускается" : "бот " + nick + " сохранён");
    }

    private void sendCommand() {
        runQuick(cmdBox.get().trim());
        cmdBox.set("");
    }

    private void runQuick(String command) {
        if (selected == null) {
            status("выберите бота");
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

    // ------------------------------------------------------------------ подсказки Baritone в поле ввода

    /** Подсказки по Tab, как в чате Baritone: «go» -> «goto». Должен быть public с пустым конструктором. */
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
