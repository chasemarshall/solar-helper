package com.solarhelper;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;

public class ChestOutlineRenderer {
    private static int scanCooldown = 0;
    private static final Set<BlockPos> cachedChestPositions = new HashSet<>();

    // Same no-depth-test pipeline as HeadOutlineRenderer — renders through walls
    private static final RenderPipeline LINES_NO_DEPTH_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelines.RENDERTYPE_LINES_SNIPPET})
            .withLocation(Identifier.of("solarhelper", "pipeline/chest_lines_no_depth"))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build()
    );

    private static final RenderLayer LINES_NO_DEPTH_LAYER = RenderLayer.of(
        "solarhelper_chest_lines_no_depth",
        RenderSetup.builder(LINES_NO_DEPTH_PIPELINE)
            .layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .build()
    );

    public static void register() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(ChestOutlineRenderer::onRender);
    }

    private static void onRender(WorldRenderContext context) {
        if (!SolarHelperConfig.get().chestEspEnabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        scanCooldown--;
        if (scanCooldown <= 0) {
            scanCooldown = 20;
            scanForChests(client);
        }

        if (cachedChestPositions.isEmpty()) return;

        Vec3d cam = client.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        VertexConsumer lineConsumer = consumers.getBuffer(LINES_NO_DEPTH_LAYER);

        // Cyan color
        int color = 0xFF00E5FF;

        for (BlockPos pos : cachedChestPositions) {
            double x = pos.getX() - cam.x;
            double y = pos.getY() - cam.y;
            double z = pos.getZ() - cam.z;

            VertexRendering.drawOutline(
                matrices, lineConsumer, VoxelShapes.fullCube(),
                x, y, z, color, 1.0f
            );
        }
    }

    private static void scanForChests(MinecraftClient client) {
        cachedChestPositions.clear();
        BlockPos playerPos = client.player.getBlockPos();
        int chunkX = playerPos.getX() >> 4;
        int chunkZ = playerPos.getZ() >> 4;

        for (int cx = chunkX - 6; cx <= chunkX + 6; cx++) {
            for (int cz = chunkZ - 6; cz <= chunkZ + 6; cz++) {
                WorldChunk chunk = client.world.getChunk(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof ChestBlockEntity
                            || be instanceof TrappedChestBlockEntity
                            || be instanceof BarrelBlockEntity
                            || be instanceof ShulkerBoxBlockEntity) {
                        cachedChestPositions.add(be.getPos());
                    }
                }
            }
        }
    }

    public static void clearCache() {
        cachedChestPositions.clear();
    }
}
