package net.montoyo.wd.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import org.joml.Matrix4f;

public class ScreenRenderer implements BlockEntityRenderer<ScreenBlockEntity> {

    public ScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ScreenBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!MCEFHelper.isMCEFAvailable()) return;
        if (!ClientInit.isMCEFRenderingEnabled()) return;

        for (int i = 0; i < blockEntity.screenCount(); i++) {
            ScreenData screen = blockEntity.getScreen(i);
            if (screen == null || screen.browser == null) continue;

            int texId = MCEFHelper.getBrowserTextureId(screen.browser);
            if (texId <= 0) continue;

            if (isBlockedByOpaque(blockEntity, screen)) continue;

            renderScreen(blockEntity, screen, texId, poseStack);
        }
    }

    private boolean isBlockedByOpaque(ScreenBlockEntity blockEntity, ScreenData screen) {
        BlockPos bp = blockEntity.getBlockPos();
        Direction dir = screen.side.direction;
        BlockPos adjacent = bp.relative(dir);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(adjacent);
        return state.isSolid();
    }

    private void renderScreen(ScreenBlockEntity blockEntity, ScreenData screen, int texId, PoseStack poseStack) {
        BlockSide side = screen.side;
        Rotation rot = screen.rotation;

        float w = screen.size.x;
        float h = screen.size.y;

        // "Cover" mode: zoom the browser content to fill the entire screen surface
        float screenAspect = w / h;
        float browserAspect = (float) screen.resolution.x / (float) screen.resolution.y;

        float uvU0 = 0f, uvV0 = 0f, uvU1 = 1f, uvV1 = 1f;

        if (screenAspect > browserAspect) {
            float ratio = screenAspect / browserAspect;
            float offset = (1.0f - 1.0f / ratio) / 2.0f;
            uvU0 = offset;
            uvU1 = 1.0f - offset;
        } else if (screenAspect < browserAspect) {
            float ratio = browserAspect / screenAspect;
            float offset = (1.0f - 1.0f / ratio) / 2.0f;
            uvV0 = offset;
            uvV1 = 1.0f - offset;
        }

        float u0 = uvU0, v0 = uvV0, u1 = uvU1, v1 = uvV1;
        switch (rot) {
            case ROT_90 -> { u0 = uvU1; v0 = uvV0; u1 = uvU0; v1 = uvV1; }
            case ROT_180 -> { u0 = uvU1; v0 = uvV1; u1 = uvU0; v1 = uvV0; }
            case ROT_270 -> { u0 = uvU0; v0 = uvV1; u1 = uvU1; v1 = uvV0; }
        }

        float eps = 0.001f;

        poseStack.pushPose();

        switch (side) {
            case BOTTOM -> poseStack.translate(0, -eps, 0);
            case TOP -> poseStack.translate(0, 1.0 + eps, 0);
            case NORTH -> poseStack.translate(0, 0, -eps);
            case SOUTH -> poseStack.translate(0, 0, 1.0 + eps);
            case WEST -> poseStack.translate(-eps, 0, 0);
            case EAST -> poseStack.translate(1.0 + eps, 0, 0);
        }

        Matrix4f matrix = poseStack.last().pose();

        // Match Forge exactly: use POSITION_TEX_COLOR format with explicit white color per vertex
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        switch (side) {
            case NORTH -> {
                builder.addVertex(matrix, 0, 0, 0).setUv(u1, v1).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, h, 0).setUv(u1, v0).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, w, h, 0).setUv(u0, v0).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, w, 0, 0).setUv(u0, v1).setColor(1f, 1f, 1f, 1f);
            }
            case SOUTH -> {
                builder.addVertex(matrix, w, 0, 0).setUv(u1, v1).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, w, h, 0).setUv(u1, v0).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, h, 0).setUv(u0, v0).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, 0, 0).setUv(u0, v1).setColor(1f, 1f, 1f, 1f);
            }
            case WEST -> {
                builder.addVertex(matrix, 0, 0, 0).setUv(u0, v1).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, 0, w).setUv(u1, v1).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, h, w).setUv(u1, v0).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, h, 0).setUv(u0, v0).setColor(1f, 1f, 1f, 1f);
            }
            case EAST -> {
                builder.addVertex(matrix, 0, 0, w).setUv(u0, v1).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, 0, 0).setUv(u1, v1).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, h, 0).setUv(u1, v0).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, h, w).setUv(u0, v0).setColor(1f, 1f, 1f, 1f);
            }
            case BOTTOM -> {
                builder.addVertex(matrix, 0, 0, 0).setUv(u0, v1).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, w, 0, 0).setUv(u1, v1).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, w, 0, h).setUv(u1, v0).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, 0, h).setUv(u0, v0).setColor(1f, 1f, 1f, 1f);
            }
            case TOP -> {
                builder.addVertex(matrix, 0, 0, 0).setUv(u0, v1).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, w, 0, 0).setUv(u1, v1).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, w, 0, h).setUv(u1, v0).setColor(1f, 1f, 1f, 1f);
                builder.addVertex(matrix, 0, 0, h).setUv(u0, v0).setColor(1f, 1f, 1f, 1f);
            }
        }

        BufferUploader.drawWithShader(builder.build());
        RenderSystem.disableDepthTest();

        // Restore blend state after screen rendering
        RenderSystem.enableBlend();

        // --- Render cursor overlay ---
        ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
        if (ScreenCursorTracker.isCursorVisible() && cursor != null && cursor.pos.equals(blockEntity.getBlockPos()) && cursor.side == side) {
            float cs = 0.04f;
            float lx = (float) cursor.localX;
            float ly = (float) cursor.localY;
            float lz = (float) cursor.localZ;

            switch (side) {
                case SOUTH -> lz -= 1.0f;
                case EAST -> lx -= 1.0f;
                case TOP -> ly -= 1.0f;
            }

            float rx = (float) side.right.x * cs;
            float ry = (float) side.right.y * cs;
            float rz = (float) side.right.z * cs;
            float ux = (float) side.up.x * cs;
            float uy = (float) side.up.y * cs;
            float uz = (float) side.up.z * cs;

            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.setShaderTexture(0, 0);
            RenderSystem.disableCull();
            BufferBuilder cb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            cb.addVertex(matrix, lx - rx - ux, ly - ry - uy, lz - rz - uz).setColor(1.0f, 0.2f, 0.2f, 0.9f);
            cb.addVertex(matrix, lx - rx + ux, ly - ry + uy, lz - rz + uz).setColor(1.0f, 0.2f, 0.2f, 0.9f);
            cb.addVertex(matrix, lx + rx + ux, ly + ry + uy, lz + rz + uz).setColor(1.0f, 0.2f, 0.2f, 0.9f);
            cb.addVertex(matrix, lx + rx - ux, ly + ry - uy, lz + rz - uz).setColor(1.0f, 0.2f, 0.2f, 0.9f);
            BufferUploader.drawWithShader(cb.build());
            RenderSystem.enableCull();
        }

        poseStack.popPose();
    }

    @Override
    public int getViewDistance() {
        return 16;
    }

    @Override
    public boolean shouldRenderOffScreen(ScreenBlockEntity blockEntity) {
        return false;
    }
}