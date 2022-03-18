package scot.massie.mc.itemcopy;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

public final class Copier
{
    private static class CopyPacket
    {
        // Sent from server to client with data to save.

        public static final int messageId = 287;

        @SuppressWarnings("PublicField")
        public final ResourceLocation itemId;

        @SuppressWarnings("PublicField")
        public final CopyPath copyPath;

        @SuppressWarnings("PublicField")
        public final CompoundNBT data;

        public CopyPacket(ResourceLocation itemId, CopyPath copyPath, CompoundNBT data)
        {
            this.itemId = itemId;
            this.copyPath = copyPath;
            this.data = data;
        }

        public void encode(PacketBuffer buf)
        {
            buf.writeResourceLocation(itemId);
            copyPath.writeToBuf(buf);
            buf.writeNbt(data);
        }

        public static CopyPacket decode(PacketBuffer buf)
        {
            ResourceLocation itemId = buf.readResourceLocation();
            CopyPath copyPath = CopyPath.readFromBuf(buf);
            CompoundNBT data = buf.readNbt();
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
    public static void copyItem(ServerPlayerEntity player, ItemStack itemStack, CopyPath copyPath)
    {
        CompoundNBT nbt = itemStack.getTag();

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
