package com.example.addon.bots.sim;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.chunk.ChunkSection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Лёгкий «мир» бота: только блоки (секции чанков), без сущностей и рендера.
 * Нужен, чтобы считать коллизии и гравитацию так же, как у обычного игрока.
 */
public final class BotWorld implements BlockView {
    private final Map<Long, ChunkSection[]> chunks = new ConcurrentHashMap<>();
    private volatile int minY = -64;
    private volatile int height = 384;

    private static long key(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    /** Новое измерение: забываем все чанки. */
    public void setDimension(int minY, int height) {
        this.minY = minY;
        this.height = height;
        chunks.clear();
    }

    public int sectionCount() {
        return height >> 4;
    }

    public void putChunk(int cx, int cz, ChunkSection[] sections) {
        chunks.put(key(cx, cz), sections);
    }

    public void removeChunk(int cx, int cz) {
        chunks.remove(key(cx, cz));
    }

    public boolean isLoaded(int cx, int cz) {
        return chunks.containsKey(key(cx, cz));
    }

    public void setBlock(int x, int y, int z, BlockState state) {
        ChunkSection[] s = chunks.get(key(x >> 4, z >> 4));
        if (s == null) return;
        int i = (y - minY) >> 4;
        if (i < 0 || i >= s.length) return;
        s[i].setBlockState(x & 15, y & 15, z & 15, state);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        ChunkSection[] s = chunks.get(key(pos.getX() >> 4, pos.getZ() >> 4));
        if (s == null) return Blocks.AIR.getDefaultState();
        int i = (pos.getY() - minY) >> 4;
        if (i < 0 || i >= s.length) return Blocks.AIR.getDefaultState();
        return s[i].getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getBottomY() {
        return minY;
    }
}
