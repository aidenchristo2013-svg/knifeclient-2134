package com.example.knifeclient;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sus Chunks: flags loaded chunks that contain a lot of player-made blocks (storage, furnaces,
 * beds, planks, glass, lanterns and so on) and outlines them through walls.
 *
 * It is a heuristic. Villages, mineshafts, strongholds and other generated structures also
 * contain these blocks, so raise "Min Score" if you get too many false positives.
 */
public final class SusChunks {
    private SusChunks() { }

    private record Result(int score, int minY, int maxY) { }

    private static final Map<Long, Result> RESULTS = new HashMap<>();
    private static final ArrayDeque<WorldChunk> QUEUE = new ArrayDeque<>();
    private static final Map<Block, Integer> WEIGHTS = new HashMap<>();

    // ---------------------------------------------------------------- block weights
    private static final String[] HEAVY = {
            "chest", "barrel", "shulker_box", "furnace", "smoker", "hopper", "dropper", "dispenser",
            "crafting_table", "_bed", "enchanting_table", "anvil", "brewing_stand", "beacon", "cauldron"};
    private static final String[] LIGHT = {
            "planks", "bricks", "concrete", "glass", "torch", "lantern", "_door", "sign", "trapdoor",
            "ladder", "rail", "wool", "carpet", "_stairs", "_slab", "fence", "bookshelf", "campfire"};

    private static int weightOf(Block block) {
        return WEIGHTS.computeIfAbsent(block, b -> {
            String path = Registries.BLOCK.getId(b).getPath();
            for (String k : HEAVY) if (path.contains(k)) return 4;
            for (String k : LIGHT) if (path.contains(k)) return 1;
            return 0;
        });
    }

    private static boolean interesting(BlockState s) {
        return weightOf(s.getBlock()) > 0;
    }

    // ---------------------------------------------------------------- scanning
    private static Result scan(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        int score = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int si = 0; si < sections.length; si++) {
            ChunkSection sec = sections[si];
            if (sec == null || sec.isEmpty() || !sec.hasAny(SusChunks::interesting)) continue;
            int baseY = bottom + si * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int w = weightOf(sec.getBlockState(x, y, z).getBlock());
                        if (w > 0) {
                            score += w;
                            int wy = baseY + y;
                            if (wy < minY) minY = wy;
                            if (wy > maxY) maxY = wy;
                        }
                    }
                }
            }
        }
        return new Result(score, minY, maxY);
    }

    private static long key(ChunkPos p) {
        return ChunkPos.toLong(p.x, p.z);
    }

    public static void init() {
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (Modules.SUSCHUNKS.enabled) QUEUE.add(chunk);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> RESULTS.remove(key(chunk.getPos())));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(SusChunks::tick);
        WorldRenderEvents.LAST.register(SusChunks::draw);
    }

    private static void clear() {
        RESULTS.clear();
        QUEUE.clear();
    }

    private static void tick(MinecraftClient mc) {
        if (!Modules.SUSCHUNKS.enabled || mc.world == null) {
            QUEUE.clear();
            return;
        }
        // a couple of chunks per tick keeps the scan from causing lag spikes
        for (int i = 0; i < 2 && !QUEUE.isEmpty(); i++) {
            WorldChunk c = QUEUE.poll();
            Result r = scan(c);
            RESULTS.put(key(c.getPos()), r);
        }
    }

    /** Re-scan everything that is currently loaded (called when the module is switched on). */
    public static void rescanAll() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        int r = mc.options.getClampedViewDistance();
        ChunkPos c = mc.player.getChunkPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                WorldChunk wc = mc.world.getChunkManager().getWorldChunk(c.x + dx, c.z + dz);
                if (wc != null) QUEUE.add(wc);
            }
        }
    }

    // ---------------------------------------------------------------- drawing
    private static boolean wasOn = false;

    private static void draw(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext ctx) {
        boolean on = Modules.SUSCHUNKS.enabled;
        if (on && !wasOn) rescanAll();
        wasOn = on;
        if (!on || RESULTS.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        Matrix4f m = ctx.positionMatrix();
        Vec3d cam = ctx.camera().getPos();
        ChunkPos pc = mc.player.getChunkPos();
        int range = (int) Modules.SC_RANGE.value;
        int minScore = (int) Modules.SC_MIN.value;
        int color = 0xFF000000 | Modules.SC_COLOR.rgb(0.0);

        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        int count = 0;
        for (Map.Entry<Long, Result> e : RESULTS.entrySet()) {
            Result r = e.getValue();
            if (r.score() < minScore || r.minY() > r.maxY()) continue;
            int cx = ChunkPos.getPackedX(e.getKey());
            int cz = ChunkPos.getPackedZ(e.getKey());
            if (Math.abs(cx - pc.x) > range || Math.abs(cz - pc.z) > range) continue;
            box(b, m,
                    (float) (cx * 16 - cam.x), (float) (r.minY() - cam.y), (float) (cz * 16 - cam.z),
                    (float) (cx * 16 + 16 - cam.x), (float) (r.maxY() + 1 - cam.y), (float) (cz * 16 + 16 - cam.z),
                    color);
            count++;
        }
        if (count == 0) return;

        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth((float) Modules.SC_LINE.value);
        if (Modules.SC_THROUGH.value) RenderSystem.disableDepthTest();
        BufferRenderer.drawWithGlobalProgram(b.end());
        if (Modules.SC_THROUGH.value) RenderSystem.enableDepthTest();
        RenderSystem.lineWidth(1f);
        RenderSystem.disableBlend();
    }

    private static void line(BufferBuilder b, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2, int c) {
        b.vertex(m, x1, y1, z1).color(c);
        b.vertex(m, x2, y2, z2).color(c);
    }

    private static void box(BufferBuilder b, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2, int c) {
        line(b, m, x1, y1, z1, x2, y1, z1, c); line(b, m, x2, y1, z1, x2, y1, z2, c);
        line(b, m, x2, y1, z2, x1, y1, z2, c); line(b, m, x1, y1, z2, x1, y1, z1, c);
        line(b, m, x1, y2, z1, x2, y2, z1, c); line(b, m, x2, y2, z1, x2, y2, z2, c);
        line(b, m, x2, y2, z2, x1, y2, z2, c); line(b, m, x1, y2, z2, x1, y2, z1, c);
        line(b, m, x1, y1, z1, x1, y2, z1, c); line(b, m, x2, y1, z1, x2, y2, z1, c);
        line(b, m, x2, y1, z2, x2, y2, z2, c); line(b, m, x1, y1, z2, x1, y2, z2, c);
    }
}
