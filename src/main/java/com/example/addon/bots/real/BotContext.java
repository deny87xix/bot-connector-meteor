package com.example.addon.bots.real;

import com.example.addon.mixin.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;

/**
 * Окно контекста: на время действия подставляет в MinecraftClient мир, игрока и менеджер взаимодействия бота,
 * затем возвращает ваши. Только главный поток, не вкладывается. Пока окно открыто, миксины блокируют
 * joinWorld, setScreen и disconnect, чтобы пакеты бота не трогали ваш экран и соединение.
 */
public final class BotContext {
    private static volatile boolean active;

    private BotContext() {}

    public static boolean isActive() {
        return active;
    }

    public static void run(BotSession s, Runnable action) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!mc.isOnThread()) throw new IllegalStateException("BotContext.run: только главный поток");
        if (active) throw new IllegalStateException("BotContext.run: окно уже открыто");

        MinecraftClientAccessor acc = (MinecraftClientAccessor) mc;
        ClientWorld userWorld = mc.world;
        ClientPlayerEntity userPlayer = mc.player;
        ClientPlayerInteractionManager userIm = mc.interactionManager;
        Entity userCamera = acc.bots$getCamera();

        active = true;
        try {
            mc.world = s.world;
            mc.player = s.player;
            mc.interactionManager = s.interactionManager;
            acc.bots$setCamera(s.player);
            action.run();
        } finally {
            // join/respawn внутри окна создают новые объекты: запоминаем их за ботом
            s.world = mc.world;
            s.player = mc.player;
            s.interactionManager = mc.interactionManager;

            mc.world = userWorld;
            mc.player = userPlayer;
            mc.interactionManager = userIm;
            acc.bots$setCamera(userCamera);
            active = false;
        }
    }
}
