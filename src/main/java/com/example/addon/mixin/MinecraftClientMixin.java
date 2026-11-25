package com.example.addon.mixin;

import com.example.addon.bots.real.BotContext;
import com.example.addon.bots.real.BotSessions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Пока открыто окно контекста (BotContext), обработчики пакетов припаркованной сессии не должны
 * менять мир, экран и отключать вас от сервера. Если вы находитесь внутри бота (.rbot enter),
 * ванильный disconnect сначала возвращает вас в вашу сессию и ничего больше не делает, чтобы
 * не оставить открытое соединение «сиротой».
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void bots$joinWorld(ClientWorld world, CallbackInfo ci) {
        if (BotContext.isActive()) ci.cancel();
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void bots$setScreen(Screen screen, CallbackInfo ci) {
        if (BotContext.isActive()) ci.cancel();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;ZZ)V", at = @At("HEAD"), cancellable = true)
    private void bots$disconnect3(Screen screen, boolean a, boolean b, CallbackInfo ci) {
        if (BotContext.isActive()) {
            ci.cancel();
        } else if (BotSessions.isEntered()) {
            BotSessions.leaveFromDisconnect();
            ci.cancel();
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), cancellable = true)
    private void bots$disconnectText(Text reason, CallbackInfo ci) {
        if (BotContext.isActive()) {
            ci.cancel();
        } else if (BotSessions.isEntered()) {
            BotSessions.leaveFromDisconnect();
            ci.cancel();
        }
    }
}
