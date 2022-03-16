package scot.massie.mc.itemcopy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

public final class Copier
{
    @SuppressWarnings("ClassCanBeRecord")
    private static class CopyPacket
    {
        // Sent from server to client with data to save.

        public static final int messageId = 287;

        @SuppressWarnings("PublicField")
        public final ResourceLocation itemId;

        @SuppressWarnings("PublicField")
        public final CopyPath copyPath;

        @SuppressWarnings("PublicField")
        public final CompoundTag data;

        public CopyPacket(ResourceLocation itemId, CopyPath copyPath, CompoundTag data)
        {
            this.itemId = itemId;
            this.copyPath = copyPath;
            this.data = data;
        }

        public void encode(FriendlyByteBuf buf)
        {
            buf.writeResourceLocation(itemId);
            copyPath.writeToBuf(buf);
            buf.writeNbt(data);
        }

        public static CopyPacket decode(FriendlyByteBuf buf)
        {
            ResourceLocation itemId = buf.readResourceLocation();
            CopyPath copyPath = CopyPath.readFromBuf(buf);
            CompoundTag data = buf.readNbt();
            return new CopyPacket(itemId, copyPath, data);
        }
    }

    private static SimpleChannel packetChannel;

    private Copier()
    { }

    //region shared methods
    public static void setup(SimpleChannel packetChannel)
    {
        Copier.packetChannel = packetChannel;

        packetChannel.registerMessage(
                CopyPacket.messageId,
                CopyPacket.class,
                CopyPacket::encode,
                CopyPacket::decode,
                Copier::saveItemData,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    //endregion

    //region server-side methods
    public static void copyItem(ServerPlayer player, ItemStack itemStack, CopyPath copyPath)
    {
        CompoundTag nbt = itemStack.getTag();

        if(nbt != null)
        {
            ResourceLocation itemId = itemStack.getItem().getRegistryName();

            if(itemId == null)
                throw new IllegalArgumentException("itemStack had no registry name.");

            packetChannel.send(PacketDistributor.PLAYER.with(() -> player),
                               new CopyPacket(itemId, copyPath, nbt));
        }
    }
    //endregion

    //region client-side methods
    public static void saveItemData(CopyPacket copyPacket, Supplier<? extends NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> saveItemData(copyPacket));
        ctx.setPacketHandled(true);
    }

    public static void saveItemData(CopyPacket copyPacket)
    {
        Path saveLocation = ItemCopy.getClientSaveDirectory().toPath()
                                    .resolve(copyPacket.itemId.getNamespace())
                                    .resolve(copyPacket.itemId.getPath());

        for(String step : copyPacket.copyPath.getStepsSanitised())
            saveLocation = saveLocation.resolve(step);

        saveLocation = saveLocation.resolveSibling(saveLocation.getFileName().toString() + ItemCopy.itemFileExtension);
        File saveFile = saveLocation.toFile();

        if(saveFile.exists())
            if(!saveFile.delete())
            {
                System.err.println("Could not delete pre-existing file at: " + saveFile);
                return;
            }

        try
        { FileUtils.writeStringToFile(saveFile, copyPacket.data.toString(), StandardCharsets.UTF_8); }
        catch(IOException e)
        {
            System.err.println("Error saving file.");
            e.printStackTrace();
        }

        CopyNamesServerStore.provideRefreshedInfo(copyPacket.itemId);
    }
    //endregion
}
