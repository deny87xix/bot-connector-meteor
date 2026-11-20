# Заметки по реализации: вход без перезахода, Meteor и Baritone за бота без входа

Дополняет `docs/NO_RECONNECT_SPEC.md`. Статус: **этап 1 не реализован**. Писать нужно там, где есть Gradle,
`genSources` и возможность запускать клиент (`./gradlew runClient`), потому что почти каждое место ниже
зависит от точных сигнатур 1.21.11.

## Почему нельзя «просто дописать» к текущему коду

Текущий `BotConnector` собирает login -> config -> play вручную и обрабатывает пакеты своим `Proxy`-слушателем.
У бота нет `ClientWorld`, `ClientPlayerEntity`, `ClientPlayNetworkHandler`, `ClientPlayerInteractionManager`,
поэтому Meteor и Baritone (они читают `mc.player`, `mc.world`, `mc.interactionManager`) за него работать не могут.
Нужен настоящий клиентский слой, созданный **ванильными** обработчиками.

## Ключевые находки по дизайну

1. **Пакеты фоновых сессий исполняются не там, где нужно.** Ванильный обработчик ставит пакет в очередь главного
   потока (`NetworkThreadUtils.forceMainThread`), и он выполняется вне нашего «окна контекста», то есть когда в `mc`
   лежит состояние другой сессии. Решение: перехватывать пакеты фоновой сессии на `PacketEvent.Receive`
   (в `BotManager.onPacketReceive` это уже сделано для текущих ботов: `event.cancel()` + свой dispatch),
   складывать в очередь сессии и применять `packet.apply(handler)` **внутри окна** на главном потоке. То же самое
   нужно для вашей собственной сессии, пока вы «внутри бота».
2. **`joinWorld` ломает чужое состояние.** При `GameJoin`/`Respawn` ванильный клиент вызывает
   `MinecraftClient.joinWorld(...)`: он сбрасывает экран, чат, рендерер и частицы. Для фонового бота это надо
   перехватить mixin-ом с флагом `BotContext.active` (не вызывать оригинал, а сохранить мир в сессию бота).
   Mixin-конфиг: `"required": false` и `"injectors": {"defaultRequire": 0}`, чтобы неверная цель не роняла игру
   при старте (тогда функция просто не заработает, а клиент запустится).
3. **Не использовать `ConnectScreen.connect`**: он первым делом отключает текущий мир. Нужно воспроизвести тело его
   потока (`ClientConnection.connect` -> `ClientLoginNetworkHandler` -> `LoginHelloC2SPacket`) без `disconnect`
   и без смены экрана. Сигнатуры брать из `genSources` (`ConnectScreen`, `ClientLoginNetworkHandler`,
   `ClientConfigurationNetworkHandler`, `ClientConnectionState`).
4. **Окно контекста.** `BotContext.run(session, action)`: сохранить `mc.world/player/interactionManager/cameraEntity`,
   подставить поля сессии, выполнить, забрать изменения (после `onGameJoin` в `mc` появляются новые объекты),
   вернуть поля в `finally`. Не вкладывается, только главный поток.
5. **Тик фоновой сессии.** В своём окне: применить очередь пакетов, `handler.tick()`, `connection.tick()`,
   `world.tick`/`player.tick` (нужно для пакетов движения и keep-alive). Проверить по `genSources`, что делает
   `MinecraftClient.tick` для активной сессии, и повторить только нужное.
6. **`enter` / `back`.** Это подмена полей `mc.world/player/interactionManager/cameraEntity`,
   `worldRenderer.setWorld`, `particleManager.setWorld`, ввод (`player.input = new KeyboardInput(mc.options)`).
   Ваша обычная сессия при этом остаётся фоновой и тикается в своём окне, иначе сервер её кикнет.
7. **Baritone за бота.** Экземпляр на бота (`getBaritoneForPlayer`/`getBaritoneForConnection`, проверить в jar);
   тик в окне бота. **Meteor за бота:** модули глобальные, начинать с тиковых модулей и одного бота.

## Что из текущего репозитория переиспользуется

`BotManager.onPacketReceive` (перехват), `BotLog`, профили и GUI, `BotService` (отложенные задачи),
`BotBaritone` (префикс, подсказки). Старый headless-режим оставить как «лёгкий» для многих ботов.

## Промпт для сессии со сборкой (Claude Code)

> Репозиторий `deny87xix/bot-connector-meteor`, ветка `main`. Прочитай `docs/NO_RECONNECT_SPEC.md` и
> `docs/IMPLEMENTATION_NOTES.md`. Работай в ветке `feature/no-reconnect`.
> 1. Этап 0: сделай `genSources`, выпиши в `docs/api-notes.md` реальные сигнатуры 1.21.11 (Yarn) для
>    `ConnectScreen`, `ClientLoginNetworkHandler`, `ClientConfigurationNetworkHandler`, `ClientConnectionState`,
>    `ClientPlayNetworkHandler` (`onGameJoin`, `onPlayerRespawn`), `MinecraftClient` (`joinWorld`, поля `world`,
>    `player`, `interactionManager`, `cameraEntity`), а также API Baritone (`getBaritoneForPlayer`, `tabComplete`).
> 2. Этап 1: настоящая фоновая сессия бота (`BotSession`) + `BotContext.run` + mixin на `joinWorld` с защитой
>    (`defaultRequire: 0`). За экспериментальной командой `.bot rconnect <ник> <адрес>`, старый режим не трогать.
>    Критерий: два бота и ваш клиент на локальном сервере (`online-mode=false`), миры не пересекаются.
> 3. Этап 2: `.bot renter <ник>` / `.bot rback` без перезахода (подмена полей, рендерер, ввод, тик вашей сессии
>    в своём окне). Критерий: 20 переключений подряд без кика.
> После каждого шага `./gradlew clean build` и запуск `./gradlew runClient`; коммить маленькими шагами.
