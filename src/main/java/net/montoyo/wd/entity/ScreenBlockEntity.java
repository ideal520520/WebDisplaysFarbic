package net.montoyo.wd.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;
import org.joml.Vector3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ScreenBlockEntity extends BlockEntity {

    private static final List<ScreenBlockEntity> clientScreens = new ArrayList<>();
    private static volatile boolean clientScreensDirty = true;
    private static List<ScreenBlockEntity> clientScreensSnapshot = List.of();

    public static List<ScreenBlockEntity> getClientScreens() {
        if (clientScreensDirty) {
            synchronized (clientScreens) {
                clientScreensSnapshot = new ArrayList<>(clientScreens);
                clientScreensDirty = false;
            }
        }
        return clientScreensSnapshot;
    }
    private final ArrayList<ScreenData> screens = new ArrayList<>();
    private boolean loaded = false;
    private AABB renderBB;
    private float ytVolume = 1.0f;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(WDRegistries.SCREEN_BLOCK_ENTITY, pos, state);
    }

    // === Network Sync ===

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (level.isClientSide) {
            synchronized (clientScreens) {
                if (!clientScreens.contains(this)) {
                    clientScreens.add(this);
                    clientScreensDirty = true;
                }
            }
            load();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide) {
            for (ScreenData screen : screens) {
                if (screen.browser != null) {
                    screen.unload();
                }
            }
            synchronized (clientScreens) {
                clientScreens.remove(this);
                clientScreensDirty = true;
            }
        }
        super.setRemoved();
    }

    // === Screen Management ===

    public int screenCount() { return screens.size(); }

    public ScreenData getScreen(int index) {
        return (index >= 0 && index < screens.size()) ? screens.get(index) : null;
    }

    public ScreenData getScreen(BlockSide side) {
        for (ScreenData sc : screens) if (sc.side == side) return sc;
        return null;
    }

    public boolean hasScreen(BlockSide side) { return getScreen(side) != null; }

    public void addScreen(BlockSide side, Vector2i resolution, Vector2i size, String owner) {
        if (getScreen(side) != null) return;
        screens.add(new ScreenData(side, resolution, size, owner));
        updateAABB();
        setChanged();
        if (level != null && level.isClientSide) {
            load();
        }
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void removeScreen(BlockSide side) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.unload();
        screens.remove(screen);
        updateAABB();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // === Screen Configuration ===

    public void setScreenURL(BlockSide side, String url) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.url = WebDisplays.applyBlacklist(url);
        if (level != null && level.isClientSide && screen.browser != null) {
            MCEFHelper.loadBrowserUrl(screen.browser, screen.url);
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setResolution(BlockSide side, Vector2i res) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        res.x = Math.max(64, Math.min(res.x, 32000));
        res.y = Math.max(64, Math.min(res.y, 32000));
        screen.resolution.set(res.x, res.y);
        screen.size.x = Math.max(1, (int) Math.ceil(res.x / 320.0f));
        screen.size.y = Math.max(1, (int) Math.ceil(res.y / 320.0f));
        if (level != null && level.isClientSide && screen.browser != null) {
            MCEFHelper.resizeBrowser(screen.browser, res.x, res.y);
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        updateAABB();
    }

    public void setRotation(BlockSide side, Rotation rot) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.rotation = rot;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setAutoVolume(BlockSide side, boolean av) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.autoVolume = av;
        setChanged();
    }

    public void setOwner(BlockSide side, String owner) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.owner = owner;
        setChanged();
    }

    // === Mouse Interaction ===

    public void click(Player player, BlockHitResult hit) {
        BlockSide side = BlockSide.fromDirection(hit.getDirection());
        clickAt(player, side, hit);
    }

    public void hitToScreenCoords(ScreenData screen, double localX, double localY, double localZ, Vector2i out) {
        float w = screen.size.x;
        float h = screen.size.y;
        double u, v;
        switch (screen.side) {
            case NORTH -> {
                u = 1.0 - localX / w;
                v = 1.0 - localY / h;
            }
            case SOUTH -> {
                u = localX / w;
                v = 1.0 - localY / h;
            }
            case WEST -> {
                u = localZ / w;
                v = 1.0 - localY / h;
            }
            case EAST -> {
                u = 1.0 - localZ / w;
                v = 1.0 - localY / h;
            }
            case BOTTOM -> {
                u = localX / w;
                v = 1.0 - localZ / h;
            }
            case TOP -> {
                u = localX / w;
                v = 1.0 - localZ / h;
            }
            default -> {
                u = 0; v = 0;
            }
        }
        switch (screen.rotation) {
            case ROT_90 -> { double tu = u; u = v; v = 1.0 - tu; }
            case ROT_180 -> { u = 1.0 - u; v = 1.0 - v; }
            case ROT_270 -> { double tu = u; u = 1.0 - v; v = tu; }
        }
        out.x = Math.max(0, Math.min((int) (u * screen.resolution.x), screen.resolution.x - 1));
        out.y = Math.max(0, Math.min((int) (v * screen.resolution.y), screen.resolution.y - 1));
    }

    public void clickAt(Player player, BlockSide side, BlockHitResult hit) {
        ScreenData screen = getScreen(side);
        if (screen == null || screen.browser == null) return;

        Vec3 hitLoc = hit.getLocation();
        double localX = hitLoc.x - worldPosition.getX();
        double localY = hitLoc.y - worldPosition.getY();
        double localZ = hitLoc.z - worldPosition.getZ();

        Vector2i clickPos = new Vector2i();
        hitToScreenCoords(screen, localX, localY, localZ, clickPos);

        long now = System.currentTimeMillis();
        int clickCount = (now - screen.lastClickTime < 500) ? 2 : 1;
        screen.lastClickTime = now;

        MCEFHelper.sendMouseClick(screen.browser, clickPos.x, clickPos.y, 0, false, clickCount);
        MCEFHelper.sendMouseClick(screen.browser, clickPos.x, clickPos.y, 0, true, clickCount);
    }

    public boolean handleMouseEvent(BlockSide side, double hitX, double hitY, double hitZ) {
        ScreenData screen = getScreen(side);
        if (screen == null || screen.browser == null) return false;

        double localX = hitX - worldPosition.getX();
        double localY = hitY - worldPosition.getY();
        double localZ = hitZ - worldPosition.getZ();

        Vector2i mousePos = new Vector2i();
        hitToScreenCoords(screen, localX, localY, localZ, mousePos);

        MCEFHelper.sendMouseMove(screen.browser, mousePos.x, mousePos.y, false);
        return true;
    }

    // === Keyboard Input ===

    public void type(String text) {
        for (ScreenData screen : screens) {
            if (screen.browser != null) {
                for (char c : text.toCharArray()) MCEFHelper.sendKeyEvent(screen.browser, c);
            }
        }
    }

    // === URL Processing ===

    public static String url(String input) throws IOException {
        if (input.startsWith("mod://") || input.startsWith("webdisplays://")) return input;
        if (!input.startsWith("http://") && !input.startsWith("https://")) return "https://" + input;
        return input;
    }

    // === Lifecycle ===

    public boolean isLoaded() { return loaded; }

    public void load() {
        if (level != null && level.isClientSide && MCEFHelper.isMCEFAvailable()) {
            if (!MCEFHelper.isMCEFInitialized()) {
                return;
            }
            for (ScreenData screen : screens) {
                if (screen.browser == null) {
                    String loadUrl = "about:blank";
                    if (screen.url != null && !screen.url.isEmpty()) {
                        try { loadUrl = url(screen.url); } catch (IOException e) { Log.warning("Invalid URL: {}", screen.url); }
                    }
                    screen.browser = MCEFHelper.createBrowser(loadUrl, false, screen.resolution.x, screen.resolution.y);
                    if (screen.browser != null) {
                        Log.info("Created browser for screen at {} side {}", worldPosition, screen.side);
                        injectScripts(screen.browser);
                    }
                }
            }
        }
        loaded = true;
    }

    public boolean needsBrowserRetry() {
        if (level == null || !level.isClientSide || !MCEFHelper.isMCEFAvailable()) return false;
        if (!MCEFHelper.isMCEFInitialized()) return true;
        for (ScreenData screen : screens) {
            if (screen.browser == null) return true;
        }
        return false;
    }

    public boolean retryCreateBrowsers() {
        if (level == null || !level.isClientSide || !MCEFHelper.isMCEFAvailable()) return false;
        if (!MCEFHelper.isMCEFInitialized()) return false;
        boolean created = false;
        for (ScreenData screen : screens) {
            if (screen.browser == null) {
                String loadUrl = "about:blank";
                if (screen.url != null && !screen.url.isEmpty()) {
                    try { loadUrl = url(screen.url); } catch (IOException e) { Log.warning("Invalid URL: {}", screen.url); }
                }
                screen.browser = MCEFHelper.createBrowser(loadUrl, false, screen.resolution.x, screen.resolution.y);
                if (screen.browser != null) {
                    Log.info("Created browser for screen at {} side {}", worldPosition, screen.side);
                    injectScripts(screen.browser);
                    created = true;
                }
            }
        }
        return created;
    }

    public void activate() { load(); }
    public void deactivate() {
        for (ScreenData screen : screens) {
            if (screen.browser != null) {
                MCEFHelper.injectJavascript(screen.browser, MUTE_AUDIO_JS);
            }
            screen.unload();
        }
        loaded = false;
    }
    public void unload() { deactivate(); }

    public void disableScreen(BlockSide side) {
        ScreenData screen = getScreen(side);
        if (screen != null) screen.unload();
    }

    public void onDestroy() {
        if (level != null && level.isClientSide) {
            for (ScreenData screen : screens) {
                if (screen.browser != null) {
                    screen.unload();
                }
            }
            synchronized (clientScreens) {
                clientScreens.remove(this);
                clientScreensDirty = true;
            }
        }
        screens.clear();
        loaded = false;
    }

    public static final String WINDOW_OPEN_OVERRIDE_JS = "if(typeof window.__wdPatched==='undefined'){window.__wdPatched=true;window.open=function(u){if(u&&typeof u==='string'&&u.startsWith('http')){window.location.href=u;return window}return null};document.addEventListener('click',function(e){var a=e.target.closest('a');if(a&&a.href&&a.target==='_blank'){e.preventDefault();window.location.href=a.href}},true)}";

    public static final String MUTE_AUDIO_JS = "(function(){try{document.querySelectorAll('video,audio').forEach(function(el){el.pause();el.muted=true;el.src=''});if(window.__wdAudioMuted)return;window.__wdAudioMuted=true;var OrigAC=window.AudioContext||window.webkitAudioContext;if(OrigAC){window.AudioContext=function(){var ctx=new OrigAC();ctx.suspend();return ctx};window.webkitAudioContext=window.AudioContext;try{OrigAC.prototype.resume=function(){return Promise.resolve()}}catch(e){}}}catch(e){}})()";

    private void injectScripts(Object browser) {
        if (browser == null) return;
        MCEFHelper.injectJavascript(browser, WINDOW_OPEN_OVERRIDE_JS);
        MCEFHelper.injectJavascript(browser, MUTE_AUDIO_JS);
    }

    public static void ensureWindowOpenOverride(Object browser) {
        if (browser == null) return;
        MCEFHelper.injectJavascript(browser, WINDOW_OPEN_OVERRIDE_JS);
    }

    // === Sound ===

    public void playSound(float volume) {
        if (level != null) {
            level.playLocalSound(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                    worldPosition.getZ() + 0.5, WDRegistries.KEYBOARD_TYPE, SoundSource.BLOCKS,
                    volume, 1.0f, false);
        }
    }

    // === Render Bounding Box ===

    public AABB getRenderBoundingBox() {
        if (renderBB == null) updateAABB();
        return renderBB != null ? renderBB : new AABB(worldPosition);
    }

    private void updateAABB() {
        if (screens.isEmpty()) {
            renderBB = new AABB(worldPosition);
            return;
        }
        double minX = worldPosition.getX(), minY = worldPosition.getY(), minZ = worldPosition.getZ();
        double maxX = minX + 1, maxY = minY + 1, maxZ = minZ + 1;
        for (ScreenData screen : screens) {
            double endX = worldPosition.getX() + (screen.side.right.x * screen.size.x) + (screen.side.up.x * screen.size.y);
            double endY = worldPosition.getY() + (screen.side.right.y * screen.size.x) + (screen.side.up.y * screen.size.y);
            double endZ = worldPosition.getZ() + (screen.side.right.z * screen.size.x) + (screen.side.up.z * screen.size.y);
            minX = Math.min(minX, endX); minY = Math.min(minY, endY); minZ = Math.min(minZ, endZ);
            maxX = Math.max(maxX, endX); maxY = Math.max(maxY, endY); maxZ = Math.max(maxZ, endZ);
        }
        renderBB = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    // === NBT Serialization ===

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        ListTag screenList = new ListTag();
        for (ScreenData screen : screens) {
            CompoundTag screenTag = new CompoundTag();
            screenTag.putInt("side", screen.side.id);
            screenTag.putInt("resX", screen.resolution.x);
            screenTag.putInt("resY", screen.resolution.y);
            screenTag.putInt("sizeX", screen.size.x);
            screenTag.putInt("sizeY", screen.size.y);
            screenTag.putInt("rotation", screen.rotation.id);
            screenTag.putBoolean("autoVolume", screen.autoVolume);
            if (screen.owner != null) screenTag.putString("owner", screen.owner);
            if (screen.url != null) screenTag.putString("url", screen.url);
            screenList.add(screenTag);
        }
        tag.put("screens", screenList);
        tag.putFloat("ytVolume", ytVolume);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        screens.clear();
        if (tag.contains("screens")) {
            ListTag screenList = tag.getList("screens", Tag.TAG_COMPOUND);
            for (int i = 0; i < screenList.size(); i++) {
                CompoundTag screenTag = screenList.getCompound(i);
                BlockSide side = BlockSide.fromInt(screenTag.getInt("side"));
                Vector2i res = new Vector2i(screenTag.getInt("resX"), screenTag.getInt("resY"));
                Vector2i size = new Vector2i(screenTag.getInt("sizeX"), screenTag.getInt("sizeY"));
                Rotation rot = Rotation.fromInt(screenTag.getInt("rotation"));
                boolean autoVol = screenTag.getBoolean("autoVolume");
                String owner = screenTag.contains("owner") ? screenTag.getString("owner") : null;
                String url = screenTag.contains("url") ? screenTag.getString("url") : null;

                ScreenData screen = new ScreenData(side, res, size, owner);
                screen.rotation = rot;
                screen.autoVolume = autoVol;
                screen.url = url;
                screens.add(screen);
            }
        }
        if (tag.contains("ytVolume")) {
            ytVolume = tag.getFloat("ytVolume");
        }
        updateAABB();
        if (level != null && level.isClientSide) {
            load();
        }
    }

    // === Distance Calculation ===

    public double distanceTo(Vec3 position) {
        double dist = Double.POSITIVE_INFINITY;
        for (ScreenData scrn : screens) {
            Vector3d p = new Vector3d(
                (scrn.side.right.x * scrn.size.x) / 2.0 + (scrn.size.y * scrn.side.up.x) / 2.0,
                (scrn.side.right.y * scrn.size.x) / 2.0 + (scrn.size.y * scrn.side.up.y) / 2.0,
                (scrn.side.right.z * scrn.size.x) / 2.0 + (scrn.size.y * scrn.side.up.z) / 2.0
            ).add(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
            dist = Math.min(dist, position.distanceTo(new Vec3(p.x, p.y, p.z)));
        }
        return dist;
    }

    // === Getters ===

    public List<ScreenData> getScreens() { return screens; }
    public float getYtVolume() { return ytVolume; }
    public void setYtVolume(float vol) { this.ytVolume = vol; setChanged(); }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
