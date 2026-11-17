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

## Как собрать (по шагам)

1. Установите **JDK 21** (например, Temurin 21) и скачайте ZIP шаблона:
   https://github.com/MeteorDevelopment/meteor-addon-template -> зелёная кнопка Code -> Download ZIP. Распакуйте.
2. Скопируйте папку `src` из этого архива в папку шаблона **с заменой файлов**.
3. Откройте `gradle/libs.versions.toml` в шаблоне и проверьте, что версии под 1.21.11
   (minecraft, fabric-loader, meteor = ваш билд 1.21.11-86 / SNAPSHOT, loom). Если там другая версия —
   пришлите мне содержимое этого файла, подскажу, что поменять.
4. В папке шаблона выполните:
   - Windows: `gradlew.bat build`
   - Linux/macOS: `./gradlew build`
5. Готовый файл: `build/libs/*.jar`. Положите его в `mods` вместе с Meteor и Baritone.

## Если сборка упала

Скопируйте ВЕСЬ текст ошибки из консоли и пришлите мне. Самые вероятные места ошибок —
файл `bots/net/BotConnector.java`, строки с пометкой `[VERSION-SENSITIVE]` (я писал их по памяти, без проверки компилятором).

## Безопасность

Используйте ботов только на своих серверах или там, где это разрешено правилами.
