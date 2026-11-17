# bot-connector-meteor

Аддон для Meteor Client: headless-боты для Minecraft 1.21.11.

## Структура

```
src/main/java/com/example/addon/
  AddonTemplate.java          точка входа аддона
  bots/Bot.java               состояние бота
  bots/BotManager.java        реестр и жизненный цикл ботов
  bots/net/BotConnector.java  сетевая часть: login -> config -> play
  bots/store/BotProfileStore.java  сохранение ботов в config/bots-addon/bots.json
  commands/BotCommand.java    команды .bot ...
```

## Описание

Что умеет: команды `.bot connect <ник> <ip[:порт]>`, `.bot list`, `.bot chat <ник> <текст>`,
`.bot remove <ник>`, `.bot clear`. Только offline-mode серверы. Боты не вселяются в мир (это этап 4).

## Как собрать

1. Установите **JDK 21** (например, Temurin 21).
2. Скачайте репозиторий (Code → Download ZIP) или `git clone`, откройте папку в терминале.
3. Выполните:
   - Windows: `gradlew.bat build`
   - Linux/macOS: `./gradlew build`
4. Готовый файл: `build/libs/bot-connector-meteor-0.1.0.jar`. Положите его в `mods` вместе с Meteor и Baritone.

Сборка нацелена на Minecraft 1.21.11, Yarn `1.21.11+build.3`, Meteor `1.21.11-SNAPSHOT` (см. `gradle/libs.versions.toml`).

## Если сборка упала

Скопируйте ВЕСЬ текст ошибки из консоли и пришлите мне. Самые вероятные места ошибок —
файл `bots/net/BotConnector.java`, строки с пометкой `[VERSION-SENSITIVE]` (я писал их по памяти, без проверки компилятором).

## Безопасность

Используйте ботов только на своих серверах или там, где это разрешено правилами.
