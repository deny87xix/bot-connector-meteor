# bot-connector-meteor

A Meteor Client addon: headless bots for Minecraft 1.21.11 (offline-mode servers) with a GUI tab, Meteor and Baritone as a bot.

Аддон для Meteor Client: headless-боты для Minecraft 1.21.11 (offline-mode серверы), вкладка GUI, Meteor и Baritone от имени бота. Русская версия ниже.

---

# English

## Features

- **Headless bots** join a server with their own nick and stand on the ground like normal players (gravity, collisions, steps, water).
- **GUI tab "Bots"** in the Meteor GUI (also `.bot gui`):
  - a table of bots with Select, Start / Stop, Chat on/off, Enter / Back, Delete;
  - Start all / Stop all and a global "All bots chat" switch;
  - add a bot: nick + address, Save or Save and start;
  - Baritone command box with Tab suggestions (`go` -> `goto`) and Enter to send;
  - Meteor as the selected bot and a button to return to your account;
  - per-bot logs (state changes, server chat, Baritone commands).
  A Russian help block with all features is at the bottom of the window.
- **Saved bots**: stored in `config/bots-addon/bots.json`, loaded at game start as stopped bots. Start / Stop keep the profile, Delete removes it.
- **Chat mute**: hide a bot's server chat from your own chat (`.bot mute <nick|all>`); messages still go to the bot's log. Per-bot setting is saved, the "all" switch is not.
- **Meteor as a bot**: `.bot <nick> meteor` enters the bot and opens Meteor as that bot. Enabled modules are remembered per bot (`config/bots-addon/modules.json`).
- **Baritone as a bot**: `.bot <nick> baritone <command>` with Tab suggestions. If you are not inside the bot yet, the command enters it first and runs after the world has loaded. Needs the Baritone jar in `mods`.

## Commands

| Command | What it does |
|---|---|
| `.bot gui` | open the Bots tab |
| `.bot connect <nick> <ip[:port]>` | connect a headless bot (and save it) |
| `.bot save <nick> <ip[:port]>` | save a bot without starting it |
| `.bot start <nick>` / `.bot stop <nick>` | start / stop a saved bot (the profile stays) |
| `.bot remove <nick>` / `.bot clear` | delete a bot / all bots |
| `.bot list` | list bots, state, coordinates, chat state |
| `.bot log <nick> [lines]` | print the last lines of a bot's log |
| `.bot mute <nick\|all>` / `.bot unmute <nick\|all>` | hide / show bot chat for you |
| `.bot chat <nick> <text>` | say something as the bot (inside the bot, `#command` runs Baritone) |
| `.bot enter <nick>` / `.bot back` | enter the bot / return to your account |
| `.bot <nick> meteor` | enter the bot and open Meteor as that bot |
| `.bot <nick> baritone <command>` | run a Baritone command as the bot |

## How "Meteor as a bot" works

Meteor and Baritone are tied to one client player, so a headless bot cannot run them. `.bot <nick> meteor` swaps bodies: your client reconnects with the bot's nick (a full client: Killaura and any Meteor module, Baritone, inventory), and your previous nick stays on the server as a headless bot. `.bot back` swaps everything back.

The swap currently needs a reconnect. A design for entering a bot and running Meteor/Baritone without reconnecting is in `docs/NO_RECONNECT_SPEC.md` (not implemented yet).

- Works only on offline-mode servers. Your nick must match `[A-Za-z0-9_]{1,16}`, otherwise your body cannot be left behind.

## Build

1. Install **JDK 21** (for example Temurin 21).
2. Download the repository (Code -> Download ZIP) or `git clone`, open the folder in a terminal.
3. Run `gradlew.bat build` (Windows) or `./gradlew build` (Linux/macOS).
4. The jar is `build/libs/bot-connector-meteor-0.1.0.jar`. Put it into `mods` together with Meteor and Baritone.

Targets Minecraft 1.21.11, Yarn `1.21.11+build.3`, Meteor `1.21.11-SNAPSHOT` (see `gradle/libs.versions.toml`).

Use bots only on your own servers or where the rules allow it.

## Source layout

```
src/main/java/com/example/addon/
  AddonTemplate.java               addon entry point
  bots/Bot.java                    bot state
  bots/BotLog.java                 per-bot log buffer
  bots/BotManager.java             registry, lifecycle, chat mute
  bots/BotService.java             delayed tasks, GUI opening, Baritone as a bot
  bots/BotSwap.java                enter / back, Meteor and Baritone as a bot
  bots/BotModules.java             per-bot set of enabled Meteor modules
  bots/BotBaritone.java            Baritone commands and suggestions via reflection
  bots/net/BotConnector.java       network: login -> config -> play
  bots/sim/BotSim.java             bot physics: gravity, collisions, movement packets
  bots/sim/BotWorld.java           bot chunks
  bots/store/BotProfileStore.java  saved bots in config/bots-addon/bots.json
  gui/BotsTab.java, BotsScreen.java  the Bots GUI tab
  commands/BotCommand.java         .bot ... commands
```

---

# Русский

## Функции

- **Headless-боты** заходят на сервер под своим ником и стоят на земле, как обычные игроки (гравитация, коллизии, ступеньки, вода).
- **GUI-вкладка «Bots»** в GUI Meteor (также `.bot gui`):
  - таблица ботов: Select, Start / Stop, Chat on/off, Enter / Back, Delete;
  - Start all / Stop all и общий выключатель «All bots chat»;
  - добавление бота: ник + адрес, Save или Save and start;
  - поле команд Baritone с подсказками по Tab (`go` -> `goto`) и отправкой по Enter;
  - Meteor от имени выбранного бота и кнопка «вернуться в свой аккаунт»;
  - логи каждого бота (состояния, чат сервера, команды Baritone).
  Внизу окна есть справка на русском со всеми функциями.
- **Сохранённые боты**: хранятся в `config/bots-addon/bots.json`, при старте игры загружаются как остановленные. Start / Stop оставляют профиль, Delete удаляет.
- **Отключение чата бота**: скрыть чат сервера, который видит бот, из вашего чата (`.bot mute <ник|all>`); в лог бота сообщения всё равно пишутся. Настройка отдельного бота сохраняется, общий выключатель — нет.
- **Meteor от имени бота**: `.bot <ник> meteor` входит в бота и открывает Meteor от его имени. Включённые модули запоминаются отдельно для каждого бота (`config/bots-addon/modules.json`).
- **Baritone от имени бота**: `.bot <ник> baritone <команда>` с подсказками по Tab. Если вы ещё не внутри бота, команда сначала входит в него и выполняется после загрузки мира. Нужен jar Baritone в `mods`.

## Команды

| Команда | Что делает |
|---|---|
| `.bot gui` | открыть вкладку Bots |
| `.bot connect <ник> <ip[:порт]>` | подключить headless-бота (и сохранить) |
| `.bot save <ник> <ip[:порт]>` | сохранить бота без запуска |
| `.bot start <ник>` / `.bot stop <ник>` | запустить / остановить сохранённого бота (профиль остаётся) |
| `.bot remove <ник>` / `.bot clear` | удалить бота / всех |
| `.bot list` | список ботов, состояние, координаты, состояние чата |
| `.bot log <ник> [строк]` | последние строки лога бота |
| `.bot mute <ник\|all>` / `.bot unmute <ник\|all>` | скрыть / показать вам чат бота |
| `.bot chat <ник> <текст>` | написать от имени бота (внутри бота `#команда` выполняет Baritone) |
| `.bot enter <ник>` / `.bot back` | войти в бота / вернуться в свой аккаунт |
| `.bot <ник> meteor` | войти в бота и открыть Meteor от его имени |
| `.bot <ник> baritone <команда>` | выполнить команду Baritone за бота |

## Как работает «Meteor от имени бота»

Meteor и Baritone привязаны к одному клиентскому игроку, поэтому headless-бот запустить их не может. `.bot <ник> meteor` делает обмен телами: ваш клиент перезаходит на сервер под ником бота (полноценный клиент: Killaura и любые модули Meteor, Baritone, инвентарь), а ваш прежний ник остаётся на сервере headless-ботом. `.bot back` возвращает всё обратно.

Сейчас этот обмен требует перезахода. Проект входа в бота и запуска Meteor/Baritone без перезахода описан в `docs/NO_RECONNECT_SPEC.md` (пока не реализован).

- Работает только на offline-mode серверах. Ваш ник должен быть вида `[A-Za-z0-9_]{1,16}`, иначе тело не оставится.

## Как собрать

1. Установите **JDK 21** (например, Temurin 21).
2. Скачайте репозиторий (Code -> Download ZIP) или `git clone`, откройте папку в терминале.
3. Выполните `gradlew.bat build` (Windows) или `./gradlew build` (Linux/macOS).
4. Готовый файл: `build/libs/bot-connector-meteor-0.1.0.jar`. Положите его в `mods` вместе с Meteor и Baritone.

Сборка нацелена на Minecraft 1.21.11, Yarn `1.21.11+build.3`, Meteor `1.21.11-SNAPSHOT` (см. `gradle/libs.versions.toml`).

Используйте ботов только на своих серверах или там, где это разрешено правилами.
