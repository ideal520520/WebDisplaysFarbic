package net.montoyo.wd.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ScreenActionPayload(BlockPos pos, int sideOrdinal, String action, String extraData) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ScreenActionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("webdisplays", "screen_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenActionPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ScreenActionPayload::pos,
                    ByteBufCodecs.INT, ScreenActionPayload::sideOrdinal,
                    ByteBufCodecs.STRING_UTF8, ScreenActionPayload::action,
                    ByteBufCodecs.STRING_UTF8, ScreenActionPayload::extraData,
                    ScreenActionPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final String ACTION_ADD_SCREEN = "add_screen";
    public static final String ACTION_REMOVE_SCREEN = "remove_screen";
    public static final String ACTION_SET_URL = "set_url";
    public static final String ACTION_SET_RESOLUTION = "set_resolution";
    public static final String ACTION_SET_ROTATION = "set_rotation";

    public static ScreenActionPayload addScreen(BlockPos pos, int sideOrdinal, String owner) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_ADD_SCREEN, owner);
    }

    public static ScreenActionPayload removeScreen(BlockPos pos, int sideOrdinal) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_REMOVE_SCREEN, "");
    }

    public static ScreenActionPayload setUrl(BlockPos pos, int sideOrdinal, String url) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_URL, url);
    }

    public static ScreenActionPayload setResolution(BlockPos pos, int sideOrdinal, int width, int height) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_RESOLUTION, width + "," + height);
    }

    public static ScreenActionPayload setRotation(BlockPos pos, int sideOrdinal, int rotationOrdinal) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_ROTATION, String.valueOf(rotationOrdinal));
    }
}
