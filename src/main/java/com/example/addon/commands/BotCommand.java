package com.example.addon.commands;

import com.example.addon.bots.Bot;
import com.example.addon.bots.BotBaritone;
import com.example.addon.bots.BotManager;
import com.example.addon.bots.BotService;
import com.example.addon.bots.BotSwap;
import com.example.addon.bots.net.BotConnector;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BotCommand extends Command {
    public BotCommand() {
        super("bot", "Боты: GUI, headless, вход в бота, Meteor и Baritone от его имени (offline-mode серверы).");
    }

    /** Подсказка ников ботов. */
    private static CompletableFuture<Suggestions> names(CommandContext<CommandSource> ctx, SuggestionsBuilder b) {
        String rest = b.getRemaining().toLowerCase();
        for (Bot bot : BotManager.INSTANCE.all()) {
            if (bot.name.toLowerCase().startsWith(rest)) b.suggest(bot.name);
        }
        return b.buildFuture();
    }

    private int printLog(String nick, int n) {
        Bot b = BotManager.INSTANCE.get(nick);
        if (b == null) {
            error("бот не найден: %s", nick);
            return SINGLE_SUCCESS;
        }
        List<String> lines = b.log.last(n);
        if (lines.isEmpty()) info("лог пуст");
        for (String l : lines) info("%s", l);
        return SINGLE_SUCCESS;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // .bot gui — открыть вкладку Bots
        builder.then(literal("gui").executes(ctx -> {
            BotService.openGui();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("connect")
            .then(argument("nick", StringArgumentType.word())
                .then(argument("address", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String nick = StringArgumentType.getString(ctx, "nick");
                        String addr = StringArgumentType.getString(ctx, "address");
                        String err = BotManager.INSTANCE.connect(nick, addr);
                        if (err != null) error(err); else info("подключаю бота (highlight)%s(default) -> %s", nick, addr);
                        return SINGLE_SUCCESS;
                    }))));

        // .bot save <ник> <ip[:порт]> — сохранить без запуска
        builder.then(literal("save")
            .then(argument("nick", StringArgumentType.word())
                .then(argument("address", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String nick = StringArgumentType.getString(ctx, "nick");
                        String addr = StringArgumentType.getString(ctx, "address");
                        String err = BotManager.INSTANCE.save(nick, addr);
                        if (err != null) error(err); else info("бот (highlight)%s(default) сохранён", nick);
                        return SINGLE_SUCCESS;
                    }))));

        // .bot start <ник> / .bot stop <ник> — запуск и остановка сохранённого бота (профиль остаётся)
        builder.then(literal("start")
            .then(argument("nick", StringArgumentType.word()).suggests(BotCommand::names).executes(ctx -> {
                String nick = StringArgumentType.getString(ctx, "nick");
                String err = BotManager.INSTANCE.start(nick);
                if (err != null) error(err); else info("запускаю бота %s", nick);
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("stop")
            .then(argument("nick", StringArgumentType.word()).suggests(BotCommand::names).executes(ctx -> {
                String nick = StringArgumentType.getString(ctx, "nick");
                if (BotManager.INSTANCE.stop(nick)) info("бот %s остановлен (профиль сохранён)", nick);
                else error("бот не найден: %s", nick);
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("remove")
            .then(argument("nick", StringArgumentType.word()).suggests(BotCommand::names).executes(ctx -> {
                String nick = StringArgumentType.getString(ctx, "nick");
                if (BotManager.INSTANCE.remove(nick)) info("бот %s удалён", nick);
                else error("бот не найден: %s", nick);
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("clear").executes(ctx -> {
            BotManager.INSTANCE.clear();
            info("все боты отключены");
            return SINGLE_SUCCESS;
        }));

        // .bot log <ник> [строк] — последние строки лога бота
        builder.then(literal("log")
            .then(argument("nick", StringArgumentType.word()).suggests(BotCommand::names)
                .executes(ctx -> printLog(StringArgumentType.getString(ctx, "nick"), 15))
                .then(argument("lines", IntegerArgumentType.integer(1, 200)).executes(ctx ->
                    printLog(StringArgumentType.getString(ctx, "nick"), IntegerArgumentType.getInteger(ctx, "lines"))))));

        builder.then(literal("list").executes(ctx -> {
            List<Bot> bots = BotManager.INSTANCE.all();
            if (BotSwap.isInside()) {
                info("вы внутри бота %s (ваш прежний ник: %s). Вернуться: .bot back", BotSwap.insideNick(), BotSwap.originName());
            }
            if (bots.isEmpty()) { info("ботов нет"); return SINGLE_SUCCESS; }
            for (Bot b : bots) {
                if (BotSwap.isInside(b.name)) {
                    info("%s @ %s [вы внутри]", b.name, b.address);
                    continue;
                }
                String pos = (b.state == Bot.State.ONLINE && b.sim != null) ? " " + b.sim.describe() : "";
                info("%s @ %s [%s] %s%s", b.name, b.address, b.state, b.status, pos);
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("chat")
            .then(argument("nick", StringArgumentType.word()).suggests(BotCommand::names)
                .then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                    String nick = StringArgumentType.getString(ctx, "nick");
                    String text = StringArgumentType.getString(ctx, "text");
                    if (BotSwap.isInside(nick)) {
                        // Вы внутри этого бота: пишем от его имени, строки с префиксом Baritone (#...) выполняет Baritone.
                        String err = BotSwap.sendInside(text);
                        if (err != null) error(err);
                        return SINGLE_SUCCESS;
                    }
                    Bot b = BotManager.INSTANCE.get(nick);
                    if (b == null) error("бот не найден: %s", nick);
                    else if (!BotConnector.chat(b, text)) error("бот ещё не зашёл, отключён, или текст пустой/длиннее 256 символов");
                    return SINGLE_SUCCESS;
                }))));

        // Войти в бота: ваш клиент перезаходит под его ником, а ваш прежний ник остаётся на сервере ботом.
        builder.then(literal("enter")
            .then(argument("nick", StringArgumentType.word()).suggests(BotCommand::names).executes(ctx -> {
                String nick = StringArgumentType.getString(ctx, "nick");
                String err = BotSwap.enter(nick);
                if (err != null) error(err);
                return SINGLE_SUCCESS;
            })));

        // Вернуться в свой аккаунт; бот снова становится headless.
        builder.then(literal("back").executes(ctx -> {
            String err = BotSwap.back();
            if (err != null) error(err);
            return SINGLE_SUCCESS;
        }));

        // .bot <ник> meteor — войти в бота и открыть Meteor от его имени (киллаура и любые модули работают за него).
        // .bot <ник> baritone <команда> — Baritone за бота (с подсказками по Tab). Если вы ещё не в боте, команда сама
        // входит в него и выполняется после загрузки мира.
        // Служебные слова (connect, remove, ...) выше имеют приоритет, поэтому ники с такими именами здесь недоступны.
        builder.then(argument("nick", StringArgumentType.word()).suggests(BotCommand::names)
            .then(literal("meteor").executes(ctx -> {
                String nick = StringArgumentType.getString(ctx, "nick");
                String err = BotSwap.meteor(nick);
                if (err != null) error(err);
                return SINGLE_SUCCESS;
            }))
            .then(literal("baritone")
                .then(argument("command", StringArgumentType.greedyString())
                    .suggests((ctx, sb) -> BotBaritone.suggest(sb))
                    .executes(ctx -> {
                        String nick = StringArgumentType.getString(ctx, "nick");
                        String cmd = StringArgumentType.getString(ctx, "command");
                        String err = BotService.baritone(nick, cmd);
                        if (err != null) error(err);
                        return SINGLE_SUCCESS;
                    }))));
    }
}
