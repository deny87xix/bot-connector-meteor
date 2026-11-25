package com.example.addon.commands;

import com.example.addon.bots.real.BotSession;
import com.example.addon.bots.real.BotSessions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

import java.util.List;

/** Экспериментально: боты с настоящей клиентской сессией и вход в них без перезахода. */
public class RBotCommand extends Command {
    public RBotCommand() {
        super("rbot", "Экспериментально: боты с настоящей клиентской сессией (offline-mode серверы), вход без перезахода.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // .rbot connect <ник> <ip[:порт]>
        builder.then(literal("connect")
            .then(argument("nick", StringArgumentType.word())
                .then(argument("address", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String nick = StringArgumentType.getString(ctx, "nick");
                        String addr = StringArgumentType.getString(ctx, "address");
                        String err = BotSessions.start(nick, addr);
                        if (err != null) error(err); else info("подключаю rbot (highlight)%s(default) -> %s", nick, addr);
                        return SINGLE_SUCCESS;
                    }))));

        // .rbot enter <ник>: стать этим ботом (без перезахода)
        builder.then(literal("enter")
            .then(argument("nick", StringArgumentType.word())
                .executes(ctx -> {
                    String nick = StringArgumentType.getString(ctx, "nick");
                    String err = BotSessions.enter(nick);
                    if (err != null) error(err); else info("вы в боте (highlight)%s(default), вернуться: .rbot leave", nick);
                    return SINGLE_SUCCESS;
                })));

        // .rbot leave: вернуться в свою сессию
        builder.then(literal("leave")
            .executes(ctx -> {
                String err = BotSessions.leave();
                if (err != null) error(err); else info("вы вернулись в свою сессию");
                return SINGLE_SUCCESS;
            }));

        // .rbot stop <ник>
        builder.then(literal("stop")
            .then(argument("nick", StringArgumentType.word())
                .executes(ctx -> {
                    String nick = StringArgumentType.getString(ctx, "nick");
                    if (BotSessions.stop(nick)) info("останавливаю rbot (highlight)%s", nick); else error("нет такой сессии: %s", nick);
                    return SINGLE_SUCCESS;
                })));

        // .rbot list
        builder.then(literal("list")
            .executes(ctx -> {
                List<BotSession> list = BotSessions.all();
                if (list.isEmpty()) info("rbot-сессий нет");
                for (BotSession s : list) {
                    info("(highlight)%s(default): %s, %s%s", s.nick, s.state, s.describe(), s.isEntered() ? " [вы в нём]" : "");
                }
                return SINGLE_SUCCESS;
            }));
    }
}
