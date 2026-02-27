package com.solarhelper;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.LayeringTransform;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;

public class HeadOutlineRenderer {
    private static final Set<BlockPos> dismissedHeads = new HashSet<>();
    private static int scanCooldown = 0;
    private static final Set<BlockPos> cachedHeadPositions = new HashSet<>();

    // Custom pipeline: lines with NO depth test so they render through blocks
    private static final RenderPipeline LINES_NO_DEPTH_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelines.RENDERTYPE_LINES_SNIPPET})
            .withLocation(Identifier.of("solarhelper", "pipeline/lines_no_depth"))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build()
    );

    // RenderLayer using our custom no-depth-test pipeline
    private static final RenderLayer LINES_NO_DEPTH_LAYER = RenderLayer.of(
        "solarhelper_lines_no_depth",
        RenderSetup.builder(LINES_NO_DEPTH_PIPELINE)
            .layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .build()
    );

    public static void register() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(HeadOutlineRenderer::onRender);
    }

    /** Called from the tick handler to check if player clicked a highlighted head. */
    public static void checkClickDismiss() {
        if (!SolarHelperConfig.get().headOutlinesEnabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        if (client.options.attackKey.isPressed() || client.options.useKey.isPressed()) {
            if (client.crosshairTarget instanceof BlockHitResult blockHit
                    && blockHit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = blockHit.getBlockPos();
                if (cachedHeadPositions.contains(pos)) {
                    dismissedHeads.add(pos);
                    cachedHeadPositions.remove(pos);
                }
            }
        }
    }

    private static void onRender(WorldRenderContext context) {
        if (!SolarHelperConfig.get().headOutlinesEnabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        scanCooldown--;
        if (scanCooldown <= 0) {
            scanCooldown = 20;
            scanForHeads(client);
        }

        if (cachedHeadPositions.isEmpty()) return;

        Vec3d cam = client.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = context.matrices();

        // Use the context's vertex consumer provider with our custom no-depth-test layer.
        // The context's consumers handle the actual draw call during the render pipeline flush.
        VertexConsumerProvider consumers = context.consumers();
        VertexConsumer lineConsumer = consumers.getBuffer(LINES_NO_DEPTH_LAYER);

        // Yellow color (ARGB: 0xAARRGGBB)
        int color = 0xFFFFE621;

        for (BlockPos pos : cachedHeadPositions) {
            double x = pos.getX() - cam.x;
            double y = pos.getY() - cam.y;
            double z = pos.getZ() - cam.z;

            VertexRendering.drawOutline(
                matrices, lineConsumer, VoxelShapes.fullCube(),
                x, y, z, color, 1.0f
            );
        }
    }

    private static void scanForHeads(MinecraftClient client) {
        cachedHeadPositions.clear();
        BlockPos playerPos = client.player.getBlockPos();
        int chunkX = playerPos.getX() >> 4;
        int chunkZ = playerPos.getZ() >> 4;

        // Scan a 13x13 chunk area (~200 block radius)
        for (int cx = chunkX - 6; cx <= chunkX + 6; cx++) {
            for (int cz = chunkZ - 6; cz <= chunkZ + 6; cz++) {
                WorldChunk chunk = client.world.getChunk(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof SkullBlockEntity) {
                        BlockPos pos = be.getPos();
                        if (!dismissedHeads.contains(pos)) {
                            cachedHeadPositions.add(pos);
                        }
                    }
                }
            }
        }
    }

    /** Returns a copy of the current cached head positions. */
    public static Set<BlockPos> getHeadPositions() {
        return new HashSet<>(cachedHeadPositions);
    }

    /** Programmatically dismiss a head (used by Head Seeker after interacting). */
    public static void dismissHead(BlockPos pos) {
        dismissedHeads.add(pos);
        cachedHeadPositions.remove(pos);
    }

    /** Clear dismissed heads when changing worlds. */
    public static void clearDismissed() {
        dismissedHeads.clear();
        cachedHeadPositions.clear();
    }
}
