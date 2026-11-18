package com.example.addon.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import net.minecraft.client.gui.screen.Screen;

/** Вкладка «Bots» в GUI Meteor. */
public class BotsTab extends Tab {
    public static final BotsTab INSTANCE = new BotsTab();

    private BotsTab() {
        super("Bots");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new BotsScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof BotsScreen;
    }
}
