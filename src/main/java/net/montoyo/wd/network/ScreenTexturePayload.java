package net.montoyo.wd.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public record ScreenTexturePayload(BlockPos pos, int sideOrdinal, int width, int height, byte[] pixels) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ScreenTexturePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("webdisplays", "screen_texture"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenTexturePayload> CODEC =
            new StreamCodec<>() {
                @Override
                public ScreenTexturePayload decode(RegistryFriendlyByteBuf buf) {
                    BlockPos pos = buf.readBlockPos();
                    int sideOrdinal = buf.readVarInt();
                    int width = buf.readVarInt();
                    int height = buf.readVarInt();
                    int uncompressedLen = buf.readVarInt();
                    int compressedLen = buf.readVarInt();
                    byte[] compressed = new byte[compressedLen];
                    buf.readBytes(compressed);
                    byte[] pixels = decompress(compressed, uncompressedLen);
                    return new ScreenTexturePayload(pos, sideOrdinal, width, height, pixels);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ScreenTexturePayload value) {
                    buf.writeBlockPos(value.pos);
                    buf.writeVarInt(value.sideOrdinal);
                    buf.writeVarInt(value.width);
                    buf.writeVarInt(value.height);
                    byte[] compressed = compress(value.pixels);
                    buf.writeVarInt(value.pixels.length);
                    buf.writeVarInt(compressed.length);
                    buf.writeBytes(compressed);
                }
            };

    private static byte[] compress(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length / 2);
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int len = deflater.deflate(buffer);
            baos.write(buffer, 0, len);
        }
        deflater.end();
        return baos.toByteArray();
    }

    private static byte[] decompress(byte[] compressed, int uncompressedLen) {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] result = new byte[uncompressedLen];
        try {
            int offset = 0;
            while (!inflater.finished() && offset < uncompressedLen) {
                offset += inflater.inflate(result, offset, uncompressedLen - offset);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to decompress screen texture", e);
        } finally {
            inflater.end();
        }
        return result;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
