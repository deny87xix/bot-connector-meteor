package com.example.addon.bots.sim;

import com.example.addon.AddonTemplate;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityPosition;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRotationS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettesFactory;
import net.minecraft.world.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Клиентская симуляция тела бота: чанки, гравитация, коллизии, ступеньки, вода,
 * и отправка пакетов движения как у настоящего клиента. Без этого сервер считает,
 * что игрок «завис» в точке спавна (позицию определяет клиент).
 *
 * Все публичные методы synchronized: пакеты приходят из сетевого потока, тик — из главного.
 */
public final class BotSim {
    private static final double GRAVITY = 0.08;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double STEP_HEIGHT = 0.6;
    private static final double WIDTH = 0.6;
    private static final double HEIGHT = 1.8;

    private final ClientConnection conn;
    private final PalettesFactory palettes;
    public final BotWorld world = new BotWorld();

    private double x, y, z;
    private double vx, vy, vz;
    private float yaw, pitch;
    private boolean onGround, horizontalCollision;

    private boolean spawned;
    private boolean loadedSent;
    private int loadWait;
    private int entityId = -1;

    private int ticksSinceMovePacket;
    private double lastX, lastY, lastZ;
    private float lastYaw, lastPitch;
    private boolean lastOnGround, lastHorizontalCollision;

    // Управление (по умолчанию бот стоит на месте, но подчиняется физике).
    public volatile boolean forward, back, left, right, jump, sneak, sprint;

    public BotSim(ClientConnection conn, PalettesFactory palettes) {
        this.conn = conn;
        this.palettes = palettes;
    }

    // ---------------------------------------------------------------- события от сервера

    public synchronized void onJoin(int entityId, DimensionType dim) {
        this.entityId = entityId;
        resetWorld(dim);
    }

    public synchronized void onRespawn(DimensionType dim) {
        resetWorld(dim);
    }

    private void resetWorld(DimensionType dim) {
        world.setDimension(dim.minY(), dim.height());
        spawned = false;
        loadedSent = false;
        loadWait = 0;
        vx = vy = vz = 0;
        onGround = false;
    }

    public synchronized void onChunk(ChunkDataS2CPacket p) {
        try {
            ChunkData data = p.getChunkData();
            PacketByteBuf buf = data.getSectionsDataBuf();
            int n = world.sectionCount();
            ChunkSection[] sections = new ChunkSection[n];
            for (int i = 0; i < n; i++) {
                ChunkSection s = new ChunkSection(palettes);
                s.readDataPacket(buf);
                sections[i] = s;
            }
            world.putChunk(p.getChunkX(), p.getChunkZ(), sections);
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[BotSim] chunk {} {} parse failed", p.getChunkX(), p.getChunkZ(), t);
        }
    }

    public synchronized void onUnload(ChunkPos pos) {
        world.removeChunk(pos.x, pos.z);
    }

    public synchronized void onBlock(BlockPos pos, BlockState state) {
        world.setBlock(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    public synchronized void onDelta(ChunkDeltaUpdateS2CPacket p) {
        p.visitUpdates((pos, state) -> world.setBlock(pos.getX(), pos.getY(), pos.getZ(), state));
    }

    public synchronized void onPosition(PlayerPositionLookS2CPacket p) {
        EntityPosition cur = new EntityPosition(new Vec3d(x, y, z), new Vec3d(vx, vy, vz), yaw, pitch);
        EntityPosition n = EntityPosition.apply(cur, p.change(), p.relatives());
        x = n.position().x;
        y = n.position().y;
        z = n.position().z;
        vx = n.deltaMovement().x;
        vy = n.deltaMovement().y;
        vz = n.deltaMovement().z;
        yaw = n.yaw();
        pitch = n.pitch();

        conn.send(new TeleportConfirmC2SPacket(p.teleportId()));
        conn.send(new PlayerMoveC2SPacket.Full(new Vec3d(x, y, z), yaw, pitch, false, false));

        lastX = x; lastY = y; lastZ = z;
        lastYaw = yaw; lastPitch = pitch;
        ticksSinceMovePacket = 0;
        spawned = true;
    }

    public synchronized void onRotation(PlayerRotationS2CPacket p) {
        yaw = p.relativeYaw() ? yaw + p.yaw() : p.yaw();
        pitch = p.relativePitch() ? pitch + p.pitch() : p.pitch();
    }

    public synchronized void onVelocity(EntityVelocityUpdateS2CPacket p) {
        if (p.getEntityId() != entityId) return;
        Vec3d v = p.getVelocity();
        vx = v.x; vy = v.y; vz = v.z;
    }

    // ---------------------------------------------------------------- тик

    public synchronized void tick() {
        if (!conn.isOpen() || !spawned) return;

        boolean chunkLoaded = world.isLoaded(floor(x) >> 4, floor(z) >> 4);
        if (!loadedSent && (chunkLoaded || ++loadWait > 100)) {
            conn.send(new PlayerLoadedC2SPacket());
            loadedSent = true;
        }

        if (chunkLoaded) physics();
        sendMovement();
        conn.send(ClientTickEndC2SPacket.INSTANCE);
    }

    public synchronized String describe() {
        return String.format("x=%.1f y=%.1f z=%.1f %s", x, y, z, onGround ? "на земле" : "в воздухе");
    }

    // ---------------------------------------------------------------- физика

    private void physics() {
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        if (Math.abs(vz) < 0.003) vz = 0;

        double ix = 0, iz = 0;
        if (forward) iz += 1;
        if (back) iz -= 1;
        if (left) ix += 1;
        if (right) ix -= 1;
        if (sneak) { ix *= 0.3; iz *= 0.3; }
        ix *= 0.98;
        iz *= 0.98;

        boolean inWater = isFluid(BlockPos.ofFloored(x, y + 0.4, z), true);
        boolean inLava = !inWater && isFluid(BlockPos.ofFloored(x, y + 0.4, z), false);

        if (jump) {
            if (inWater || inLava) {
                vy += 0.04;
            } else if (onGround) {
                vy = JUMP_VELOCITY;
                if (sprint && forward) {
                    float r = yaw * 0.017453292F;
                    vx -= Math.sin(r) * 0.2;
                    vz += Math.cos(r) * 0.2;
                }
            }
        }

        if (inWater) {
            moveRelative(0.02, ix, iz);
            move(vx, vy, vz);
            vx *= 0.8; vy *= 0.8; vz *= 0.8;
            vy -= GRAVITY / 4.0;
        } else if (inLava) {
            moveRelative(0.02, ix, iz);
            move(vx, vy, vz);
            vx *= 0.5; vy *= 0.5; vz *= 0.5;
            vy -= GRAVITY / 4.0;
        } else {
            float slip = 1.0F;
            if (onGround) {
                BlockPos below = BlockPos.ofFloored(x, y - 0.500001, z);
                slip = world.getBlockState(below).getBlock().getSlipperiness();
            }
            double f = slip * 0.91;
            double speed;
            if (onGround) {
                double base = sprint && forward ? 0.13 : 0.1;
                speed = base * (0.21600002 / (f * f * f));
            } else {
                speed = sprint && forward ? 0.026 : 0.02;
            }
            moveRelative(speed, ix, iz);
            move(vx, vy, vz);
            vy = (vy - GRAVITY) * 0.98;
            vx *= f;
            vz *= f;
        }
    }

    private boolean isFluid(BlockPos pos, boolean water) {
        FluidState fs = world.getFluidState(pos);
        if (fs.isEmpty()) return false;
        if (water) return fs.getFluid() == Fluids.WATER || fs.getFluid() == Fluids.FLOWING_WATER;
        return fs.getFluid() == Fluids.LAVA || fs.getFluid() == Fluids.FLOWING_LAVA;
    }

    private void moveRelative(double speed, double ix, double iz) {
        double d = ix * ix + iz * iz;
        if (d < 1.0E-7) return;
        d = Math.sqrt(d);
        if (d < 1.0) d = 1.0;
        d = speed / d;
        ix *= d;
        iz *= d;
        double sin = Math.sin(yaw * 0.017453292F);
        double cos = Math.cos(yaw * 0.017453292F);
        vx += ix * cos - iz * sin;
        vz += iz * cos + ix * sin;
    }

    private Box box() {
        return new Box(x - WIDTH / 2, y, z - WIDTH / 2, x + WIDTH / 2, y + HEIGHT, z + WIDTH / 2);
    }

    private void move(double dx, double dy, double dz) {
        Vec3d adj = collide(box(), dx, dy, dz);
        x += adj.x;
        y += adj.y;
        z += adj.z;

        boolean cx = Math.abs(dx - adj.x) > 1.0E-5;
        boolean cz = Math.abs(dz - adj.z) > 1.0E-5;
        boolean cy = Math.abs(dy - adj.y) > 1.0E-5;
        horizontalCollision = cx || cz;
        onGround = cy && dy < 0;
        if (cx) vx = 0;
        if (cz) vz = 0;
        if (cy) vy = 0;
    }

    /** Порт ванильного Entity.adjustMovementForCollisions (включая подъём на ступеньки). */
    private Vec3d collide(Box box, double dx, double dy, double dz) {
        Vec3d v = collideAxes(box, dx, dy, dz);
        boolean hc = dx != v.x || dz != v.z;
        boolean groundOrFalling = onGround || (dy < 0 && v.y != dy);
        if (hc && groundOrFalling) {
            Vec3d a = collideAxes(box, dx, STEP_HEIGHT, dz);
            Vec3d b = collideAxes(box.stretch(dx, 0, dz), 0, STEP_HEIGHT, 0);
            if (b.y < STEP_HEIGHT) {
                Vec3d c = collideAxes(box.offset(b.x, b.y, b.z), dx, 0, dz).add(b);
                if (c.x * c.x + c.z * c.z > a.x * a.x + a.z * a.z) a = c;
            }
            if (a.x * a.x + a.z * a.z > v.x * v.x + v.z * v.z) {
                return a.add(collideAxes(box.offset(a.x, a.y, a.z), 0, -a.y + dy, 0));
            }
        }
        return v;
    }

    private Vec3d collideAxes(Box box, double dx, double dy, double dz) {
        List<VoxelShape> shapes = shapes(box.stretch(dx, dy, dz));
        double ny = clip(Direction.Axis.Y, box, dy, shapes);
        box = box.offset(0, ny, 0);
        double nx = dx, nz = dz;
        if (Math.abs(dx) < Math.abs(dz)) {
            nz = clip(Direction.Axis.Z, box, dz, shapes);
            box = box.offset(0, 0, nz);
            nx = clip(Direction.Axis.X, box, dx, shapes);
        } else {
            nx = clip(Direction.Axis.X, box, dx, shapes);
            box = box.offset(nx, 0, 0);
            nz = clip(Direction.Axis.Z, box, dz, shapes);
        }
        return new Vec3d(nx, ny, nz);
    }

    private static double clip(Direction.Axis axis, Box box, double d, List<VoxelShape> shapes) {
        if (d == 0) return 0;
        for (VoxelShape s : shapes) d = s.calculateMaxDistance(axis, box, d);
        return d;
    }

    private List<VoxelShape> shapes(Box b) {
        List<VoxelShape> out = new ArrayList<>();
        int x0 = floor(b.minX) - 1, x1 = floor(b.maxX) + 1;
        int y0 = floor(b.minY) - 1, y1 = floor(b.maxY) + 1;
        int z0 = floor(b.minZ) - 1, z1 = floor(b.maxZ) + 1;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    m.set(bx, by, bz);
                    BlockState st = world.getBlockState(m);
                    if (st.isAir()) continue;
                    VoxelShape s = st.getCollisionShape(world, m);
                    if (s.isEmpty()) continue;
                    out.add(s.offset(bx, by, bz));
                }
            }
        }
        return out;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    // ---------------------------------------------------------------- пакеты движения

    private void sendMovement() {
        ticksSinceMovePacket++;
        double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
        boolean moved = dx * dx + dy * dy + dz * dz > 4.0E-8 || ticksSinceMovePacket >= 20;
        boolean rotated = yaw != lastYaw || pitch != lastPitch;

        if (moved && rotated) {
            conn.send(new PlayerMoveC2SPacket.Full(new Vec3d(x, y, z), yaw, pitch, onGround, horizontalCollision));
        } else if (moved) {
            conn.send(new PlayerMoveC2SPacket.PositionAndOnGround(new Vec3d(x, y, z), onGround, horizontalCollision));
        } else if (rotated) {
            conn.send(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, onGround, horizontalCollision));
        } else if (lastOnGround != onGround || lastHorizontalCollision != horizontalCollision) {
            conn.send(new PlayerMoveC2SPacket.OnGroundOnly(onGround, horizontalCollision));
        }

        if (moved) {
            lastX = x; lastY = y; lastZ = z;
            ticksSinceMovePacket = 0;
        }
        lastYaw = yaw;
        lastPitch = pitch;
        lastOnGround = onGround;
        lastHorizontalCollision = horizontalCollision;
    }
}
