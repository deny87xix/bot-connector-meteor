package com.example.addon.bots.real;

import com.example.addon.AddonTemplate;
import com.example.addon.bots.Bot;
import com.example.addon.bots.BotLog;
import io.netty.channel.ChannelFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.Address;
import net.minecraft.client.network.AllowedAddressResolver;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientChunkLoadProgress;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkingBackend;
import net.minecraft.network.OffThreadException;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.state.LoginStates;
import net.minecraft.text.Text;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Бот с настоящей клиентской сессией: ванильные обработчики login/config/play, свой мир, игрок и менеджер
 * взаимодействия. Login и config обрабатываются на сетевом потоке (как в ванили), play-пакеты складываются в очередь
 * и применяются на главном потоке внутри окна контекста (BotContext).
 */
public final class BotSession {
    private record Pending(Packet<?> packet, PacketListener listener) {}

    public final String nick;
    public final String address;
    public final UUID uuid;
    public final BotLog log = new BotLog();
    public volatile Bot.State state = Bot.State.CONNECTING;

    volatile ClientConnection connection;
    // Состояние бота, подставляется BotContext
    ClientWorld world;
    ClientPlayerEntity player;
    ClientPlayerInteractionManager interactionManager;

    private final Queue<Pending> inbox = new ConcurrentLinkedQueue<>();
    private volatile boolean finished;
    private ClientPlayerEntity inputFixedFor;

    BotSession(String nick, String address) {
        this.nick = nick;
        this.address = address;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nick).getBytes(StandardCharsets.UTF_8));
    }

    public boolean isFinished() {
        return finished;
    }

    void start() {
        Thread t = new Thread(this::connectBlocking, "bot-rconnect-" + nick);
        t.setDaemon(true);
        t.start();
    }

    private void setState(Bot.State s, String text) {
        this.state = s;
        log.add(s + (text == null || text.isEmpty() ? "" : ": " + text));
    }

    void fail(String reason) {
        if (finished) return;
        finished = true;
        setState(Bot.State.ERROR, reason);
        BotSessions.remove(this);
    }

    /** Повторяет ванильное подключение ConnectScreen (1.21.11), но без экрана и без ресурспаков. */
    private void connectBlocking() {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            setState(Bot.State.CONNECTING, address);
            Optional<Address> resolved = AllowedAddressResolver.DEFAULT.resolve(ServerAddress.parse(address));
            if (resolved.isEmpty()) {
                fail("не удалось определить адрес сервера");
                return;
            }
            InetSocketAddress isa = resolved.get().getInetSocketAddress();
            ClientConnection conn = new ClientConnection(NetworkSide.CLIENTBOUND);
            connection = conn;
            ChannelFuture f = ClientConnection.connect(isa, NetworkingBackend.remote(mc.options.shouldUseNativeTransport()), conn);
            f.syncUninterruptibly();

            ServerInfo info = new ServerInfo(nick, address, ServerInfo.ServerType.OTHER);
            CookieStorage cookies = new CookieStorage(new HashMap<>(), new HashMap<>(), false);
            ClientLoginNetworkHandler login = new ClientLoginNetworkHandler(conn, mc, info, null, false, null,
                text -> setState(Bot.State.LOGIN, text.getString()), new ClientChunkLoadProgress(), cookies);
            setState(Bot.State.LOGIN, "");
            conn.connect(isa.getHostName(), isa.getPort(), LoginStates.C2S, LoginStates.S2C, login, false);
            conn.send(new LoginHelloC2SPacket(nick, uuid));
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    /** Вызывается с сетевого потока вместо ванильной обработки пакета. */
    void onPacket(Packet<?> p) {
        ClientConnection c = connection;
        if (c == null) return;
        PacketListener l = c.getPacketListener();
        if (l == null) return;
        if (l instanceof ClientPlayNetworkHandler) {
            inbox.add(new Pending(p, l));
        } else {
            if (l instanceof ClientConfigurationNetworkHandler && state == Bot.State.LOGIN) setState(Bot.State.CONFIG, "");
            apply(p, l);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void apply(Packet<?> p, PacketListener l) {
        try {
            if (l.accepts(p)) ((Packet) p).apply(l);
        } catch (OffThreadException ignored) {
        } catch (Throwable t) {
            log.add("ошибка пакета " + p.getClass().getSimpleName() + ": " + t);
            AddonTemplate.LOG.warn("[rbot {}] packet {} failed", nick, p.getClass().getSimpleName(), t);
        }
    }

    /** Пакеты, которые иначе попали бы в ваш чат, HUD, звук и частицы. */
    private boolean dropped(Packet<?> p) {
        if (p instanceof GameMessageS2CPacket m) {
            try {
                log.add("чат: " + m.content().getString());
            } catch (Throwable ignored) {
            }
            return true;
        }
        return p instanceof ChatMessageS2CPacket || p instanceof ProfilelessChatMessageS2CPacket
            || p instanceof OverlayMessageS2CPacket || p instanceof TitleS2CPacket || p instanceof SubtitleS2CPacket
            || p instanceof TitleFadeS2CPacket || p instanceof ClearTitleS2CPacket
            || p instanceof PlaySoundS2CPacket || p instanceof PlaySoundFromEntityS2CPacket
            || p instanceof ParticleS2CPacket;
    }

    /** Главный поток, раз в тик. */
    void tick() {
        ClientConnection c = connection;
        if (finished || c == null) return;

        boolean playPhase = c.getPacketListener() instanceof ClientPlayNetworkHandler;
        if (!playPhase && inbox.isEmpty() && world == null) {
            if (!c.isOpen()) closed(c);
            return;
        }

        BotContext.run(this, () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            Pending pd;
            int n = 0;
            while (n++ < 1000 && (pd = inbox.poll()) != null) {
                if (!dropped(pd.packet())) apply(pd.packet(), pd.listener());
            }
            fixInput(mc.player);
            c.tick();
            ClientWorld w = mc.world;
            if (w != null) {
                if (mc.interactionManager != null) mc.interactionManager.tick();
                w.tick(() -> true);
                w.tickEntities();
            }
        });

        if (player != null && state != Bot.State.ONLINE) setState(Bot.State.ONLINE, describe());
        if (!c.isOpen()) closed(c);
    }

    /** Управление игроком бота не должно идти с вашей клавиатуры. */
    private void fixInput(ClientPlayerEntity p) {
        if (p != null && p != inputFixedFor) {
            p.input = new Input();
            inputFixedFor = p;
        }
    }

    private void closed(ClientConnection c) {
        if (finished) return;
        finished = true;
        String reason = "";
        try {
            reason = c.getDisconnectionInfo() == null ? "" : c.getDisconnectionInfo().reason().getString();
        } catch (Throwable ignored) {
        }
        setState(Bot.State.DISCONNECTED, reason);
        world = null;
        player = null;
        interactionManager = null;
        BotSessions.remove(this);
    }

    public void stop() {
        ClientConnection c = connection;
        if (c != null && c.isOpen()) {
            c.disconnect(Text.literal("bot stopped"));
        } else if (!finished) {
            finished = true;
            setState(Bot.State.DISCONNECTED, "остановлен");
            BotSessions.remove(this);
        }
    }

    public String describe() {
        ClientPlayerEntity p = player;
        ClientWorld w = world;
        if (p == null || w == null) return "игрока ещё нет";
        return String.format("%s x=%.1f y=%.1f z=%.1f", w.getRegistryKey().getValue(), p.getX(), p.getY(), p.getZ());
    }
}
