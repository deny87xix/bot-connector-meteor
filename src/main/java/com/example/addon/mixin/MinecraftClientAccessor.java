package com.example.addon.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к приватному cameraEntity без побочных эффектов setCameraEntity. */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Accessor("cameraEntity")
    Entity bots$getCamera();

    @Accessor("cameraEntity")
    void bots$setCamera(Entity entity);
}
