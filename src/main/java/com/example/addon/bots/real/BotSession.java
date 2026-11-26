package com.example.addon.bots.real;

import com.example.addon.AddonTemplate;
import com.example.addon.bots.Bot;
import com.example.addon.bots.BotManager;
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
import net.minecraft.text.TranslatableTextContent;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Сессия с настоящим клиентским состоянием: ванильные обработчики login/config/play, свой мир, игрок
 * и менеджер взаимодействия. Это и есть бот ({@link Bot#session}). Такой же объект (host) описывает вашу
 * собственную сессию, пока вы «внутри» бота (.bot enter).
 *
 * Припаркованная сессия: play-пакеты кладутся в очередь и применяются в окне контекста (BotContext) на главном
 * потоке; активная (entered) живёт как обычная сессия клиента.
 */
public final class BotSession {
    private record Pending(Packet<?> packet, PacketListener listener) {}

    public final Bot bot;
    public final String nick;
    public final String address;
    public final UUID uuid;

    final boolean isHost;
    volatile ClientConnection connection;
    // Состояние сессии, подставляется BotContext
    ClientWorld world;
    ClientPlayerEntity player;
    ClientPlayerInteractionManager interactionManager;

    private final Queue<Pending> inbox = new ConcurrentLinkedQueue<>();
    private volatile boolean finished;
    private volatile boolean stopped;
    private volatile boolean entered;
    private ClientPlayerEntity inputFixedFor;

    BotSession(Bot bot, boolean isHost) {
        this.bot = bot;
        this.nick = bot.name;
        this.address = bot.address;
        this.uuid = bot.uuid;
        this.isHost = isHost;
    }

    /** Ваша собственная текущая сессия, захваченная перед входом в бота (вызывать только на главном потоке). */
    static BotSession host(MinecraftClient mc) {
        BotSession h = new BotSession(new Bot("(вы)", "", 0), true);
        h.connection = mc.getNetworkHandler().getConnection();
        h.world = mc.world;
        h.player = mc.player;
        h.interactionManager = mc.interactionManager;
        h.bot.state = Bot.State.ONLINE;
        h.fixInput(h.player);
        return h;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isEntered() {
        return entered;
    }

    void setEntered(boolean v) {
        entered = v;
    }

    void start() {
        BotSessions.register(this);
        Thread t = new Thread(this::connectBlocking, "bot-connect-" + nick);
        t.setDaemon(true);
        t.start();
    }

    private void setState(Bot.State s, String text) {
        if (stopped && s != Bot.State.DISCONNECTED) return;
        bot.set(s, text);
    }

    /** Ошибка: бот переходит в ERROR (loop, если включён, зайдёт снова), соединение закрывается. */
    void fail(String reason) {
        if (finished) return;
        finished = true;
        setState(Bot.State.ERROR, reason);
        ClientConnection c = connection;
        if (c == null) {
            BotSessions.unregister(this);
        } else if (c.isOpen()) {
            try {
                c.disconnect(Text.literal(reason));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Статус входа от ванильного обработчика: «Encrypting...» значит, сервер в online-mode. */
    private void onLoginStatus(Text text) {
        if (text.getContent() instanceof TranslatableTextContent t && "connect.encrypting".equals(t.getKey())) {
            fail("Боты работают только на offline-mode серверах (сервер запросил шифрование)");
            return;
        }
        setState(Bot.State.LOGIN, text.getString());
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
            if (stopped) {
                conn.disconnect(Text.literal("bot stopped"));
                return;
            }

            ServerInfo info = new ServerInfo(nick, address, ServerInfo.ServerType.OTHER);
            CookieStorage cookies = new CookieStorage(new HashMap<>(), new HashMap<>(), false);
            ClientLoginNetworkHandler login = new ClientLoginNetworkHandler(conn, mc, info, null, false, null,
                this::onLoginStatus, new ClientChunkLoadProgress(), cookies);
            setState(Bot.State.LOGIN, "");
            conn.connect(isa.getHostName(), isa.getPort(), LoginStates.C2S, LoginStates.S2C, login, false);
            conn.send(new LoginHelloC2SPacket(nick, uuid));
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    /** Вызывается с сетевого потока вместо ванильной обработки пакета (только для припаркованных сессий). */
    void onPacket(Packet<?> p) {
        if (finished || stopped) return;
        ClientConnection c = connection;
        if (c == null) return;
        PacketListener l = c.getPacketListener();
        if (l == null) return;
        if (l instanceof ClientPlayNetworkHandler) {
            inbox.add(new Pending(p, l));
        } else {
            if (l instanceof ClientConfigurationNetworkHandler && bot.state == Bot.State.LOGIN) setState(Bot.State.CONFIG, "");
            apply(p, l);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void apply(Packet<?> p, PacketListener l) {
        try {
            if (l.accepts(p)) ((Packet) p).apply(l);
        } catch (OffThreadException ignored) {
        } catch (Throwable t) {
            bot.log.add("ошибка пакета " + p.getClass().getSimpleName() + ": " + t);
            AddonTemplate.LOG.warn("[bot {}] packet {} failed", nick, p.getClass().getSimpleName(), t);
        }
    }

    /**
     * Пакеты, которые иначе попали бы в чат, HUD, звук и частицы той сессии, которую вы сейчас смотрите.
     * Сообщения сервера пишутся в лог бота и (если чат бота включён) выводятся вам с пометкой [Bot ник].
     */
    private boolean dropped(Packet<?> p) {
        if (p instanceof GameMessageS2CPacket m) {
            try {
                Text content = m.content();
                bot.log.add("чат: " + content.getString());
                if (!isHost && !BotManager.INSTANCE.isMuted(bot)) {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc.inGameHud != null) {
                        mc.inGameHud.getChatHud().addMessage(Text.literal("§7[Bot §a" + nick + "§7] ").append(content));
                    }
                }
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

    /** Главный поток, раз в тик (только для припаркованных сессий). */
    void tick() {
        if (entered) return; // активную сессию тикает сам клиент
        ClientConnection c = connection;
        if (c == null) return;
        if (finished) {
            // Сессия завершена: ждём, пока закроется канал, и перестаём перехватывать её пакеты.
            if (!c.isOpen()) BotSessions.unregister(this);
            return;
        }

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

        if (player != null && bot.isActive() && bot.state != Bot.State.ONLINE) setState(Bot.State.ONLINE, describe());
        if (!c.isOpen()) closed(c);
    }

    /** Дочитать оставшиеся play-пакеты, когда сессия снова стала активной (главный поток, без окна). */
    void drainRemaining() {
        Pending pd;
        while ((pd = inbox.poll()) != null) apply(pd.packet(), pd.listener());
    }

    /** Управление игроком припаркованной сессии не должно идти с вашей клавиатуры. */
    private void fixInput(ClientPlayerEntity p) {
        if (p != null && p != inputFixedFor) {
            p.input = new Input();
            inputFixedFor = p;
        }
    }

    /** Сессия перестаёт быть активной: её игрок больше не читает вашу клавиатуру. */
    void releaseInput() {
        if (player != null) {
            player.input = new Input();
            inputFixedFor = player;
        }
    }

    private void closed(ClientConnection c) {
        if (finished) return;
        finished = true;
        if (isHost) {
            BotSessions.onHostClosed();
            return;
        }
        String reason = "";
        try {
            reason = c.getDisconnectionInfo() == null ? "" : c.getDisconnectionInfo().reason().getString();
        } catch (Throwable ignored) {
        }
        setState(Bot.State.DISCONNECTED, reason);
        world = null;
        player = null;
        interactionManager = null;
        BotSessions.unregister(this);
    }

    /** Остановить бота (главный поток). Состояние DISCONNECTED выставит tick, когда канал закроется. */
    public void stop() {
        stopped = true;
        ClientConnection c = connection;
        if (c != null && c.isOpen()) {
            try {
                c.disconnect(Text.literal("bot stopped"));
            } catch (Throwable ignored) {
            }
        } else if (!finished) {
            finished = true;
            setState(Bot.State.DISCONNECTED, "остановлен");
            BotSessions.unregister(this);
        }
    }

    public String describe() {
        ClientPlayerEntity p = player;
        ClientWorld w = world;
        if (p == null || w == null) return "игрока ещё нет";
        return String.format("%s x=%.1f y=%.1f z=%.1f", w.getRegistryKey().getValue(), p.getX(), p.getY(), p.getZ());
    }
}
