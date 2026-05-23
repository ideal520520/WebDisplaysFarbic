package net.montoyo.wd.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.montoyo.wd.client.gui.GuiScreenConfig;
import net.montoyo.wd.client.gui.InputScreen;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.KeyboardBlockEntity;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.network.ScreenTexturePayload;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

public class ClientInit implements ClientModInitializer {

    private static int previousHotbarSlot = -1;
    private static boolean wasTabDown = false;
    private static long lastUrlCheckTime = 0;
    private static final long URL_CHECK_INTERVAL_MS = 1000;
    private static boolean mcefRenderingEnabled = true;
    private static boolean wasF6Down = false;

    public static boolean isMCEFRenderingEnabled() {
        return mcefRenderingEnabled;
    }

    @Override
    public void onInitializeClient() {
        Log.info("WebDisplays client initializing...");

        // Register block entity renderers
        net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                WDRegistries.SCREEN_BLOCK_ENTITY, ScreenRenderer::new);

        // Register ScreenTexturePayload for receiving broadcast textures
        ClientPlayNetworking.registerGlobalReceiver(ScreenTexturePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                net.minecraft.client.multiplayer.ClientLevel level = context.client().level;
                if (level == null) return;
                BlockEntity be = level.getBlockEntity(payload.pos());
                if (!(be instanceof ScreenBlockEntity screen)) return;
                ScreenData data = screen.getScreen(payload.sideOrdinal());
                if (data == null) return;
                if (data.broadcastTexId <= 0) {
                    data.broadcastTexId = GL11.glGenTextures();
                }
                int texId = data.broadcastTexId;
                int w = payload.width();
                int h = payload.height();
                byte[] pixels = payload.pixels();
                ByteBuffer buffer = ByteBuffer.allocateDirect(pixels.length);
                buffer.put(pixels);
                buffer.rewind();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                // Reset pixel store state for Minecraft's rendering
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            });
        });

        // Schedule MCEF initialization callback (uses reflection)
        MCEFHelper.scheduleInit(success -> {
            if (success) {
                Log.info("MCEF initialized successfully for WebDisplays");
            } else {
                Log.info("MCEF not available for WebDisplays");
            }
        });

        // TEMPORARILY DISABLED: Reset GL pixel store state - testing if this causes noise
        // net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register(context -> {
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
        // });
        // net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(context -> {
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
        // });

        // Periodically retry browser creation for screens that were created before MCEF initialized
        // Also detect page navigation and re-inject window.open override (throttled to 1/sec)
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null) return;
            long now = System.currentTimeMillis();
            boolean shouldCheckUrl = (now - lastUrlCheckTime) >= URL_CHECK_INTERVAL_MS;
            if (shouldCheckUrl) lastUrlCheckTime = now;

            for (ScreenBlockEntity screen : ScreenBlockEntity.getClientScreens()) {
                if (screen.retryCreateBrowsers()) {
                    Log.info("Browser retry succeeded for screen at {}", screen.getBlockPos());
                }
                // Detect page navigation and re-inject window.open override (throttled)
                if (shouldCheckUrl) {
                    for (int i = 0; i < screen.screenCount(); i++) {
                        ScreenData data = screen.getScreen(i);
                        if (data == null || data.browser == null) continue;
                        String currentUrl = MCEFHelper.getBrowserUrl(data.browser);
                        if (!currentUrl.isEmpty() && !currentUrl.equals(data.lastUrl)) {
                            data.lastUrl = currentUrl;
                            ScreenBlockEntity.ensureWindowOpenOverride(data.browser);
                        }
                    }
                }
            }
        });

        // Track cursor position on screen planes via raycasting
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            ScreenCursorTracker.update(client);
        });

        // Detect left-click on screen surfaces
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            ScreenCursorTracker.handleLeftClick(client);
        });

        // Handle Shift+scroll for browser scrolling, Ctrl+scroll for zoom
        // Only intercept when Shift is held AND the hotbar slot actually changed
        // due to scroll wheel (not just pressing Shift alone)
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            if (!ScreenCursorTracker.isScreenFocused()) {
                previousHotbarSlot = -1;
                return;
            }

            boolean isShift = client.player.isShiftKeyDown();
            boolean isCtrl = net.minecraft.client.gui.screens.Screen.hasControlDown();

            int currentSlot = client.player.getInventory().selected;

            if (previousHotbarSlot < 0) {
                // Initialize tracking - just record current slot, don't trigger
                previousHotbarSlot = currentSlot;
                return;
            }

            if (currentSlot != previousHotbarSlot) {
                // Slot changed - only convert to scroll if Shift or Ctrl was already held
                // This prevents false triggers when just pressing Shift
                if (isShift || isCtrl) {
                    int delta = currentSlot - previousHotbarSlot;
                    if (delta > 4) delta -= 9;
                    else if (delta < -4) delta += 9;
                    // Only handle single-step scroll wheel changes (±1)
                    // Ignore multi-step changes (likely number key presses)
                    if (Math.abs(delta) == 1) {
                        client.player.getInventory().selected = previousHotbarSlot;
                        ScreenCursorTracker.handleScroll(delta > 0 ? 1.0 : -1.0);
                        return;
                    }
                }
                // For non-scroll changes or non-shift states, just update tracking
                previousHotbarSlot = currentSlot;
            }
        });

        // Toggle cursor visibility with Tab key
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            boolean isTabDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    client.getWindow().getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_TAB);
            if (isTabDown && !wasTabDown) {
                ScreenCursorTracker.toggleCursorVisible();
            }
            wasTabDown = isTabDown;
        });

        // Toggle MCEF screen rendering with F6 key
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            boolean isF6Down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    client.getWindow().getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_F6);
            if (isF6Down && !wasF6Down) {
                mcefRenderingEnabled = !mcefRenderingEnabled;
                client.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "WebDisplays 渲染: " + (mcefRenderingEnabled ? "开启" : "关闭")),
                        true);
            }
            wasF6Down = isF6Down;
        });

        // Cancel block breaking ONLY when the actual targeted block IS the screen block
        // If a closer opaque block exists, Minecraft's pick will target that block instead
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!ScreenCursorTracker.isCursorVisible()) return InteractionResult.PASS;
            if (!ScreenCursorTracker.isScreenFocused()) return InteractionResult.PASS;

            // Get Minecraft's actual targeted block (respects occlusion)
            Minecraft mc = Minecraft.getInstance();
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                // Only cancel if the targeted block position matches the screen's block position
                ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
                if (cursor != null && blockHit.getBlockPos().equals(cursor.pos)) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // Open config GUI when using configurator on screen (client-side only)
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            BlockEntity be = world.getBlockEntity(hitResult.getBlockPos());
            if (be instanceof ScreenBlockEntity screen) {
                if (player.getItemInHand(hand).getItem() == WDRegistries.CONFIGURATOR) {
                    BlockPos pos = hitResult.getBlockPos();
                    BlockSide side = BlockSide.fromDirection(hitResult.getDirection());
                    Minecraft.getInstance().setScreen(
                            new GuiScreenConfig(pos, side, !screen.hasScreen(side)));
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        // Open keyboard InputScreen when right-clicking keyboard blocks (empty hand only)
        // If holding Linker, let KeyboardBlockLeft.useItemOn handle the linking first
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            BlockEntity be = world.getBlockEntity(hitResult.getBlockPos());
            if (be instanceof KeyboardBlockEntity kb) {
                if (player.getItemInHand(hand).getItem() == WDRegistries.LINKER) return InteractionResult.PASS;
                BlockPos screenPos = kb.getLinkedPos();
                BlockSide screenSide = kb.getLinkedSide();
                if (screenPos != null && screenSide != null) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof InputScreen && ((InputScreen) mc.screen).isFor(screenPos, screenSide)) {
                        mc.setScreen(null);
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Input mode: OFF"), true);
                    } else {
                        mc.setScreen(new InputScreen(screenPos, screenSide));
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Input mode: ON (ESC to exit)"), true);
                    }
                    return InteractionResult.SUCCESS;
                } else {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("webdisplays.message.notLinked"), true);
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        // Send pending broadcast textures to server every client tick
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            String playerName = client.player.getName().getString();
            for (ScreenBlockEntity screen : ScreenBlockEntity.getClientScreens()) {
                for (int i = 0; i < screen.screenCount(); i++) {
                    ScreenData data = screen.getScreen(i);
                    if (data == null) continue;
                    byte[] pixels = data.pendingBroadcastPixels;
                    if (pixels != null) {
                        data.pendingBroadcastPixels = null;
                        if (data.owner != null && data.owner.equals(playerName)) {
                            try {
                                ScreenTexturePayload payload = new ScreenTexturePayload(
                                        screen.getBlockPos(), data.side.id,
                                        data.resolution.x, data.resolution.y, pixels);
                                ClientPlayNetworking.send(payload);
                            } catch (Exception e) {
                                Log.warning("Failed to send screen texture: {}", e.getMessage());
                            }
                        }
                    }
                }
            }
        });

        Log.info("WebDisplays client initialized!");
    }
}