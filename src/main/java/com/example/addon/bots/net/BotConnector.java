package com.example.addon.bots.net;

import com.example.addon.AddonTemplate;
import com.example.addon.bots.Bot;
import com.example.addon.bots.sim.BotSim;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientRegistries;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkingBackend;
import net.minecraft.network.packet.BrandCustomPayload;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.listener.ClientConfigurationPacketListener;
import net.minecraft.network.listener.ClientLoginPacketListener;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.message.LastSeenMessageList;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.*;
import net.minecraft.network.packet.c2s.config.ReadyC2SPacket;
import net.minecraft.network.packet.c2s.config.SelectKnownPacksC2SPacket;
import net.minecraft.network.packet.c2s.login.EnterConfigurationC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.common.*;
import net.minecraft.network.packet.s2c.config.*;
import net.minecraft.network.packet.s2c.login.*;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.network.state.LoginStates;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.PalettesFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;

/**
 * Headless-подключение бота: login -> configuration -> play, без создания мира клиента.
 *
 * Слушатели пакетов сделаны через java.lang.reflect.Proxy и диспетчеризуются по ТИПУ пакета
 * (instanceof), а не по имени метода, чтобы не реализовывать десятки методов интерфейсов
 * и чтобы работало в боевом (intermediary) окружении.
 *
 * Тело бота (чанки, гравитация, коллизии, пакеты движения) живёт в {@link BotSim}.
 *
 * ВАЖНО: эта часть сильнее всего зависит от версии Minecraft. Места, где вероятны ошибки
 * компиляции при первой сборке, помечены // [VERSION-SENSITIVE].
 */
public final class BotConnector {
    private BotConnector() {}

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    // ---------------------------------------------------------------- public API

    public static void run(Bot bot) {
        try {
            String host = bot.address;
            int port = 25565;
            int idx = host.lastIndexOf(':');
            if (idx > 0 && host.indexOf(':') == idx) { // host:port (не IPv6)
                port = Integer.parseInt(host.substring(idx + 1).trim());
                host = host.substring(0, idx).trim();
            }

            bot.set(Bot.State.CONNECTING, "resolving " + host + " ...");
            InetSocketAddress addr = new InetSocketAddress(host, port);
            if (addr.isUnresolved()) {
                bot.set(Bot.State.ERROR, "cannot resolve address: " + host);
                return;
            }

            ClientConnection conn = new ClientConnection(NetworkSide.CLIENTBOUND);
            bot.connection = conn;

            // [VERSION-SENSITIVE] статический connect(addr, useEpoll, connection)
            ClientConnection.connect(addr, NetworkingBackend.remote(false), conn).syncUninterruptibly();

            Session s = new Session(bot, conn);
            bot.set(Bot.State.LOGIN, "handshake sent, waiting for login ...");

            // [VERSION-SENSITIVE] connect(host, port, outboundState, inboundState, listener, transferred)
            conn.connect(host, port, LoginStates.C2S, LoginStates.S2C, s.loginListener(), false);
            conn.send(new LoginHelloC2SPacket(bot.name, bot.uuid));
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[Bot {}] connect failed", bot.name, t);
            bot.set(Bot.State.ERROR, "connect error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Тик тела бота (физика + пакеты движения). Вызывается из BotManager на главном потоке. */
    public static void tick(Bot bot) {
        BotSim sim = bot.sim;
        if (sim == null || bot.state != Bot.State.ONLINE) return;
        try {
            sim.tick();
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[Bot {}] sim tick error", bot.name, t);
        }
    }

    /** Передать входящий пакет слушателю соединения бота (вызывается из BotManager, минуя обработчики Meteor). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void dispatch(ClientConnection conn, Packet<?> packet) {
        net.minecraft.network.listener.PacketListener listener = conn.getPacketListener();
        if (listener == null) return;
        try {
            ((Packet) packet).apply(listener);
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[Bots] dispatch error for {}", packet.getClass().getSimpleName(), t);
        }
    }

    /** Отправить сообщение/команду от имени бота. */
    public static boolean chat(Bot bot, String text) {
        ClientConnection c = bot.connection;
        if (c == null || !c.isOpen() || bot.state != Bot.State.ONLINE) return false;
        if (text == null || text.isBlank() || text.length() > 256) return false;
        if (text.startsWith("/")) {
            // [VERSION-SENSITIVE]
            c.send(new CommandExecutionC2SPacket(text.substring(1)));
        } else {
            // [VERSION-SENSITIVE] сигнатура ChatMessageC2SPacket / Acknowledgment
            c.send(new ChatMessageC2SPacket(text, Instant.now(), 0L, null,
                new LastSeenMessageList.Acknowledgment(0, new BitSet(), (byte) 0)));
        }
        return true;
    }

    // ---------------------------------------------------------------- session

    private static final class Session {
        final Bot bot;
        final ClientConnection conn;
        // [VERSION-SENSITIVE] накопитель динамических реестров из configuration-фазы
        final ClientRegistries registries = new ClientRegistries();

        Session(Bot bot, ClientConnection conn) { this.bot = bot; this.conn = conn; }

        // Трассировка пакетов: последние N штук печатаются при отключении (для диагностики кика).
        private final java.util.ArrayDeque<String> trail = new java.util.ArrayDeque<>();

        void note(String dir, Packet<?> p) {
            synchronized (trail) {
                trail.addLast(dir + p.getClass().getSimpleName());
                while (trail.size() > 24) trail.removeFirst();
            }
        }

        String trailText(int last) {
            synchronized (trail) {
                java.util.List<String> all = new java.util.ArrayList<>(trail);
                return String.join(", ", all.subList(Math.max(0, all.size() - last), all.size()));
            }
        }

        void send(Packet<?> p) {
            note("->", p);
            conn.send(p);
        }

        ClientLoginPacketListener loginListener() {
            return proxy(ClientLoginPacketListener.class, NetworkPhase.LOGIN, this::onLogin);
        }

        ClientConfigurationPacketListener configListener() {
            return proxy(ClientConfigurationPacketListener.class, NetworkPhase.CONFIGURATION, this::onConfig);
        }

        ClientPlayPacketListener playListener() {
            return proxy(ClientPlayPacketListener.class, NetworkPhase.PLAY, this::onPlay);
        }

        @SuppressWarnings("unchecked")
        <T> T proxy(Class<T> iface, NetworkPhase phase, PacketSink sink) {
            InvocationHandler h = (self, m, args) -> {
                if (m.getDeclaringClass() == Object.class) {
                    return switch (m.getName()) {
                        case "hashCode" -> System.identityHashCode(self);
                        case "equals" -> self == args[0];
                        default -> "BotListener(" + bot.name + "/" + phase + ")";
                    };
                }
                Class<?> rt = m.getReturnType();
                int n = args == null ? 0 : args.length;

                if (rt == NetworkSide.class) return NetworkSide.CLIENTBOUND;
                if (rt == NetworkPhase.class) return phase;

                // accepts(Packet) -> boolean
                if (rt == boolean.class && n == 1 && args[0] instanceof Packet) return conn.isOpen();
                // isConnectionOpen() (абстрактный) / shouldCrashOnException() (default)
                if (rt == boolean.class && n == 0) return m.isDefault() ? Boolean.FALSE : (Boolean) conn.isOpen();

                // createDisconnectionInfo(Text, Throwable) вызывается Netty при ошибках
                if (rt == DisconnectionInfo.class && n >= 1 && args[0] instanceof Text t) {
                    if (n >= 2 && args[1] instanceof Throwable th) {
                        AddonTemplate.LOG.warn("[Bot {}] network exception in phase {}", bot.name, phase, th);
                        if (bot.state != Bot.State.ERROR) bot.set(Bot.State.ERROR, "network exception: " + th);
                    }
                    return new DisconnectionInfo(t);
                }
                if (n >= 1 && args[0] instanceof DisconnectionInfo info) {
                    onClosed(info.reason().getString());
                    return null;
                }
                if (n == 1 && args[0] instanceof Packet<?> p) {
                    note("<-", p);
                    try {
                        sink.accept(p);
                    } catch (Throwable t) {
                        AddonTemplate.LOG.warn("[Bot {}] packet handler error", bot.name, t);
                    }
                }
                return defaultValue(rt);
            };
            return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h);
        }

        // ------------------------------------------------------------ LOGIN

        void onLogin(Packet<?> p) {
            if (p instanceof LoginHelloS2CPacket) {
                fail("Боты работают только на offline-mode серверах (сервер запросил шифрование)");
            } else if (p instanceof LoginSuccessS2CPacket) {
                bot.set(Bot.State.CONFIG, "configuring session ...");
                // [VERSION-SENSITIVE] порядок и состояния
                // Порядок как у ванильного клиента: inbound -> send(ack в login-кодеке) -> outbound
                conn.transitionInbound(ConfigurationStates.S2C, configListener());
                send(EnterConfigurationC2SPacket.INSTANCE);
                conn.transitionOutbound(ConfigurationStates.C2S);
                send(new CustomPayloadC2SPacket(new BrandCustomPayload("vanilla")));
            } else if (p instanceof LoginCompressionS2CPacket c) {
                conn.setCompressionThreshold(c.getCompressionThreshold(), false);
            } else if (p instanceof LoginQueryRequestS2CPacket q) {
                send(new LoginQueryResponseC2SPacket(q.queryId(), null));
            } else if (p instanceof LoginDisconnectS2CPacket d) {
                fail("login rejected: " + d.reason().getString());
            } else if (p instanceof CookieRequestS2CPacket c) {
                send(new CookieResponseC2SPacket(c.key(), null));
            }
        }

        // ------------------------------------------------------------ CONFIG

        void onConfig(Packet<?> p) {
            if (p instanceof KeepAliveS2CPacket k) {
                send(new KeepAliveC2SPacket(k.getId()));
            } else if (p instanceof CommonPingS2CPacket pg) {
                send(new CommonPongC2SPacket(pg.getParameter()));
            } else if (p instanceof SelectKnownPacksS2CPacket) {
                send(new SelectKnownPacksC2SPacket(List.of()));
            } else if (p instanceof ResourcePackSendS2CPacket rp) {
                acceptPack(rp);
            } else if (p instanceof CookieRequestS2CPacket c) {
                send(new CookieResponseC2SPacket(c.key(), null));
            } else if (p instanceof DynamicRegistriesS2CPacket d) {
                // [VERSION-SENSITIVE]
                registries.putDynamicRegistry(d.registry(), d.entries());
            } else if (p instanceof SynchronizeTagsS2CPacket t) {
                // [VERSION-SENSITIVE]
                registries.putTags(t.getGroups());
            } else if (p instanceof ReadyS2CPacket) {
                toPlay();
            } else if (p instanceof DisconnectS2CPacket d) {
                fail("config disconnect: " + d.reason().getString());
            } else if (p instanceof ServerTransferS2CPacket) {
                fail("server transfer is not supported");
            }
        }

        void toPlay() {
            try {
                // [VERSION-SENSITIVE] сборка менеджера реестров и привязка play-кодеков
                DynamicRegistryManager.Immutable regs = registries.createRegistryManager(
                    mc().getResourceManager(), DynamicRegistryManager.of(Registries.REGISTRIES), false);

                // Сначала готовим ОБА состояния: если что-то упадёт, ничего ещё не отправлено
                var inbound = PlayStateFactories.S2C.bind(RegistryByteBuf.makeFactory(regs));
                var outbound = bindC2S(RegistryByteBuf.makeFactory(regs));

                // Тело бота: чанки, гравитация, коллизии. Создаём ДО перехода, чтобы не потерять первые пакеты.
                bot.sim = new BotSim(conn, PalettesFactory.fromRegistryManager(regs));

                conn.transitionInbound(inbound, playListener());
                send(ReadyC2SPacket.INSTANCE);
                conn.transitionOutbound(outbound);
                bot.set(Bot.State.CONFIG, "entering world ...");
            } catch (Throwable t) {
                AddonTemplate.LOG.warn("[Bot {}] toPlay failed", bot.name, t);
                fail("toPlay failed: " + t);
            }
        }

        // ------------------------------------------------------------ PLAY

        void onPlay(Packet<?> p) {
            BotSim sim = bot.sim;

            if (p instanceof BundleS2CPacket b) {
                // Пакеты, приходящие пачкой (bundle), обрабатываем по одному в том же порядке.
                for (Packet<?> q : b.getPackets()) onPlay(q);
            } else if (p instanceof KeepAliveS2CPacket k) {
                send(new KeepAliveC2SPacket(k.getId()));
            } else if (p instanceof CommonPingS2CPacket pg) {
                send(new CommonPongC2SPacket(pg.getParameter()));
            } else if (p instanceof GameJoinS2CPacket j) {
                if (sim != null) sim.onJoin(j.playerEntityId(), j.commonPlayerSpawnInfo().dimensionType().value());
                bot.set(Bot.State.ONLINE, "joined the game as " + bot.name);
                echo("§7joined the game");
            } else if (p instanceof PlayerRespawnS2CPacket r) {
                if (sim != null) sim.onRespawn(r.commonPlayerSpawnInfo().dimensionType().value());
            } else if (p instanceof PlayerPositionLookS2CPacket pos) {
                // [VERSION-SENSITIVE] sim сам шлёт TeleportConfirm + Full-позицию, как ванильный клиент
                if (sim != null) sim.onPosition(pos);
                else send(new TeleportConfirmC2SPacket(pos.teleportId()));
            } else if (p instanceof PlayerRotationS2CPacket rot) {
                if (sim != null) sim.onRotation(rot);
            } else if (p instanceof ChunkDataS2CPacket cd) {
                if (sim != null) sim.onChunk(cd);
            } else if (p instanceof UnloadChunkS2CPacket u) {
                if (sim != null) sim.onUnload(u.pos());
            } else if (p instanceof BlockUpdateS2CPacket bu) {
                if (sim != null) sim.onBlock(bu.getPos(), bu.getState());
            } else if (p instanceof ChunkDeltaUpdateS2CPacket cdu) {
                if (sim != null) sim.onDelta(cdu);
            } else if (p instanceof ChunkSentS2CPacket) {
                // Без подтверждения батча сервер перестаёт присылать новые чанки.
                send(new AcknowledgeChunksC2SPacket(25.0f));
            } else if (p instanceof EntityVelocityUpdateS2CPacket vel) {
                if (sim != null) sim.onVelocity(vel);
            } else if (p instanceof HealthUpdateS2CPacket h) {
                if (h.getHealth() <= 0f) send(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.PERFORM_RESPAWN));
            } else if (p instanceof ResourcePackSendS2CPacket rp) {
                acceptPack(rp);
            } else if (p instanceof GameMessageS2CPacket m) {
                echo("§f" + m.content().getString());
            } else if (p instanceof DisconnectS2CPacket d) {
                fail("disconnected: " + d.reason().getString());
            }
        }

        // ------------------------------------------------------------ helpers

        void acceptPack(ResourcePackSendS2CPacket rp) {
            // [VERSION-SENSITIVE]
            send(new ResourcePackStatusC2SPacket(rp.id(), ResourcePackStatusC2SPacket.Status.ACCEPTED));
            send(new ResourcePackStatusC2SPacket(rp.id(), ResourcePackStatusC2SPacket.Status.SUCCESSFULLY_LOADED));
        }

        void echo(String msg) {
            MinecraftClient c = mc();
            c.execute(() -> {
                if (c.inGameHud != null) {
                    c.inGameHud.getChatHud().addMessage(Text.literal("§7[Bot §a" + bot.name + "§7] " + msg));
                }
            });
        }

        void fail(String reason) {
            bot.set(Bot.State.ERROR, reason);
            echo("§c" + reason);
            AddonTemplate.LOG.info("[Bot {}] last packets: {}", bot.name, trailText(24));
            echo("§7последние пакеты: " + trailText(10));
            try { conn.disconnect(Text.literal(reason)); } catch (Throwable ignored) {}
        }

        void onClosed(String reason) {
            AddonTemplate.LOG.info("[Bot {}] connection closed in state {}: {}", bot.name, bot.state, reason);
            if (bot.state != Bot.State.ERROR) bot.set(Bot.State.DISCONNECTED, reason);
            echo("§cdisconnected: " + reason);
        }
    }


    /**
     * C2S-фабрика play-состояния требует второй аргумент (контекст кодеков).
     * Тип контекста определяем по реальной сигнатуре метода bind (не bridge-варианту с Object)
     * или, если такой не нашёлся, по вложенным типам PlayStateFactories.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraft.network.state.NetworkState bindC2S(java.util.function.Function factory) throws Exception {
        Object f = PlayStateFactories.C2S;
        Method best = null;
        for (Method m : f.getClass().getMethods()) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != 2 || pt[0] != java.util.function.Function.class) continue;
            if (best == null
                || (best.getParameterTypes()[1] == Object.class && pt[1] != Object.class)
                || (best.isBridge() && !m.isBridge() && pt[1] != Object.class)) {
                best = m;
            }
        }
        if (best == null) throw new IllegalStateException("bind(Function, Context) not found on " + f.getClass());
        best.setAccessible(true);

        Class<?> ctxType = best.getParameterTypes()[1];
        Object ctx = ctxType == Object.class ? makeContextFromNested() : makeDefault(ctxType);
        AddonTemplate.LOG.info("[Bots] play C2S bind uses context {} ({})", ctxType.getName(), ctx.getClass().getName());
        return (net.minecraft.network.state.NetworkState) best.invoke(f, factory, ctx);
    }

    /** Контекст неизвестного типа: собираем из вложенных типов PlayStateFactories. */
    private static Object makeContextFromNested() throws Exception {
        java.util.List<Class<?>> ifaces = new java.util.ArrayList<>();
        Class<?> concrete = null;
        for (Class<?> c : PlayStateFactories.class.getDeclaredClasses()) {
            if (c.isInterface()) ifaces.add(c);
            else if (concrete == null && !c.isAnonymousClass()) concrete = c;
        }
        if (!ifaces.isEmpty()) {
            Class<?>[] arr = ifaces.toArray(new Class<?>[0]);
            return Proxy.newProxyInstance(arr[0].getClassLoader(), arr, (self, m, a) -> defaultFor(self, m, a));
        }
        if (concrete != null) return makeDefault(concrete);
        throw new IllegalStateException("cannot build codec context");
    }

    private static Object defaultFor(Object self, Method m, Object[] a) {
        if (m.getDeclaringClass() == Object.class) {
            return switch (m.getName()) {
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == a[0];
                default -> "ctx";
            };
        }
        return defaultValue(m.getReturnType());
    }

    private static Object makeDefault(Class<?> type) throws Exception {
        if (type.isInterface()) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, m, a) -> defaultFor(self, m, a));
        }
        java.lang.reflect.Constructor<?> c = type.getDeclaredConstructors()[0];
        c.setAccessible(true);
        Object[] args = new Object[c.getParameterCount()];
        Class<?>[] pt = c.getParameterTypes();
        for (int i = 0; i < args.length; i++) args[i] = defaultValue(pt[i]);
        return c.newInstance(args);
    }

    @FunctionalInterface
    private interface PacketSink { void accept(Packet<?> p) throws Throwable; }

    private static Object defaultValue(Class<?> rt) {
        if (!rt.isPrimitive() || rt == void.class) return null;
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == byte.class) return (byte) 0;
        if (rt == short.class) return (short) 0;
        if (rt == char.class) return (char) 0;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        return 0d;
    }
}
