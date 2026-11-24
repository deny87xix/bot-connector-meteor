package com.example.addon.mixin;

import com.example.addon.bots.real.BotContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Пока открыто окно контекста бота (BotContext), обработчики пакетов бота не должны менять ваш мир, экран
 * и не должны отключать вас от сервера.
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
        if (BotContext.isActive()) ci.cancel();
    }

    @Inject(method = "disconnect(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), cancellable = true)
    private void bots$disconnectText(Text reason, CallbackInfo ci) {
        if (BotContext.isActive()) ci.cancel();
    }
}
