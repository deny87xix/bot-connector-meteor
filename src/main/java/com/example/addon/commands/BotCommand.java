package com.example.addon.commands;

import com.example.addon.bots.Bot;
import com.example.addon.bots.BotManager;
import com.example.addon.bots.BotSwap;
import com.example.addon.bots.net.BotConnector;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

import java.util.List;

public class BotCommand extends Command {
    public BotCommand() {
        super("bot", "Боты: headless, вход в бота, Meteor и Baritone от его имени (offline-mode серверы).");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
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

        builder.then(literal("remove")
            .then(argument("nick", StringArgumentType.word()).executes(ctx -> {
                String nick = StringArgumentType.getString(ctx, "nick");
                if (BotManager.INSTANCE.remove(nick)) info("бот %s отключён", nick);
                else error("бот не найден: %s", nick);
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("clear").executes(ctx -> {
            BotManager.INSTANCE.clear();
            info("все боты отключены");
            return SINGLE_SUCCESS;
        }));

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
            .then(argument("nick", StringArgumentType.word())
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
            .then(argument("nick", StringArgumentType.word()).executes(ctx -> {
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
        // .bot <ник> baritone <команда> — выполнить команду Baritone за бота, в которого вы вошли.
        // Служебные слова (connect, remove, ...) выше имеют приоритет, поэтому ники с такими именами здесь недоступны.
        builder.then(argument("nick", StringArgumentType.word())
            .then(literal("meteor").executes(ctx -> {
                String nick = StringArgumentType.getString(ctx, "nick");
                String err = BotSwap.meteor(nick);
                if (err != null) error(err);
                return SINGLE_SUCCESS;
            }))
            .then(literal("baritone")
                .then(argument("command", StringArgumentType.greedyString()).executes(ctx -> {
                    String nick = StringArgumentType.getString(ctx, "nick");
                    String cmd = StringArgumentType.getString(ctx, "command");
                    String err = BotSwap.baritone(nick, cmd);
                    if (err != null) error(err);
                    return SINGLE_SUCCESS;
                }))));
    }
}
