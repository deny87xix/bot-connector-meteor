package com.example.addon;

import com.example.addon.bots.BotManager;
import com.example.addon.bots.store.BotProfileStore;
import com.example.addon.commands.BotCommand;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandles;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Bots addon loading");

        // Нужно, чтобы работали @EventHandler внутри этого пакета.
        MeteorClient.EVENT_BUS.registerLambdaFactory("com.example.addon",
            (lookupInMethod, klass) -> (MethodHandles.Lookup) lookupInMethod.invoke(null, klass, MethodHandles.lookup()));

        BotProfileStore.load();
        MeteorClient.EVENT_BUS.subscribe(BotManager.INSTANCE);
        Commands.add(new BotCommand());
    }

    @Override
    public void onRegisterCategories() {
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("deny87xix", "bot-connector-meteor");
    }
}
