# bot-connector-meteor

Аддон для Meteor Client: боты для Minecraft 1.21.11 (offline-mode серверы).

## Структура

```
src/main/java/com/example/addon/
  AddonTemplate.java               точка входа аддона
  bots/Bot.java                    состояние бота
  bots/BotManager.java             реестр и жизненный цикл ботов
  bots/BotSwap.java                вход в бота / возврат, Meteor и Baritone за бота
  bots/BotModules.java             свой набор включённых модулей Meteor у каждого бота
  bots/BotBaritone.java            команды Baritone через рефлексию
  bots/net/BotConnector.java       сетевая часть: login -> config -> play
  bots/sim/BotSim.java             физика бота: гравитация, коллизии, пакеты движения
  bots/sim/BotWorld.java           чанки бота
  bots/store/BotProfileStore.java  сохранение ботов в config/bots-addon/bots.json
  commands/BotCommand.java         команды .bot ...
```

## Команды

| Команда | Что делает |
|---|---|
| `.bot connect <ник> <ip[:порт]>` | подключить headless-бота |
| `.bot list` | список ботов, координаты, в каком вы сейчас |
| `.bot chat <ник> <текст>` | написать от имени бота (если вы внутри него: `#команда` выполняет Baritone) |
| `.bot remove <ник>` / `.bot clear` | отключить бота / всех |
| `.bot <ник> meteor` | **войти в бота и открыть Meteor от его имени** |
| `.bot <ник> baritone <команда>` | выполнить команду Baritone за бота, в которого вы вошли |
| `.bot enter <ник>` | войти в бота без открытия GUI |
| `.bot back` | вернуться в свой аккаунт |

Headless-боты стоят на земле как обычные игроки (гравитация, коллизии, ступеньки, вода).

## Как работает «Meteor от имени бота»

Meteor и Baritone привязаны к одному клиентскому игроку, поэтому headless-бот запустить их не может.
`.bot <ник> meteor` делает обмен телами: ваш клиент перезаходит на сервер под ником бота (дальше это полноценный
клиент: Killaura и любые модули Meteor, Baritone, инвентарь), а ваш прежний ник остаётся на сервере headless-ботом.
`.bot back` возвращает всё обратно.

- Включённые модули запоминаются **отдельно для каждого бота** (`config/bots-addon/modules.json`): при `.bot back`
  ваши модули восстанавливаются, Killaura бота не «протекает» в основной аккаунт. При первом входе остаётся то,
  что у вас уже включено.
- Baritone: внутри бота работает обычный `#goto ...` в чате, а также `.bot <ник> baritone goto ...`.
  Нужен jar Baritone в `mods`.
- Работает только на offline-mode серверах. Ваш ник должен быть вида `[A-Za-z0-9_]{1,16}`, иначе тело не оставится.

## Как собрать

1. Установите **JDK 21** (например, Temurin 21).
2. Скачайте репозиторий (Code → Download ZIP) или `git clone`, откройте папку в терминале.
3. Выполните:
   - Windows: `gradlew.bat build`
   - Linux/macOS: `./gradlew build`
4. Готовый файл: `build/libs/bot-connector-meteor-0.1.0.jar`. Положите его в `mods` вместе с Meteor и Baritone.

Сборка нацелена на Minecraft 1.21.11, Yarn `1.21.11+build.3`, Meteor `1.21.11-SNAPSHOT` (см. `gradle/libs.versions.toml`).

## Если сборка упала

Скопируйте ВЕСЬ текст ошибки из консоли и пришлите мне. Самые вероятные места ошибок: `bots/net/BotConnector.java`
(строки `[VERSION-SENSITIVE]`), `bots/sim/BotSim.java` и `bots/BotSwap.java` (код писался без проверки компилятором).

## Безопасность

Используйте ботов только на своих серверах или там, где это разрешено правилами.
