package scot.massie.mc.itemcopy;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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

public final class Paster
{
    @SuppressWarnings("ClassCanBeRecord")
    private static class PasteRequestPacket
    {
        // Sent from server to client to request a paste.

        private static final int messageId = 394;

        @SuppressWarnings("PublicField")
        public final ResourceLocation itemId;

        @SuppressWarnings("PublicField")
        public final CopyPath copyPath;

        public PasteRequestPacket(ResourceLocation itemId, CopyPath copyPath)
        {
            this.itemId = itemId;
            this.copyPath = copyPath;
        }

        public void encode(FriendlyByteBuf buf)
        {
            buf.writeResourceLocation(itemId);
            copyPath.writeToBuf(buf);
        }

        public static PasteRequestPacket decode(FriendlyByteBuf buf)
        {
            ResourceLocation itemId = buf.readResourceLocation();
            CopyPath copyPath = CopyPath.readFromBuf(buf);
            return new PasteRequestPacket(itemId, copyPath);
        }
    }

    private static SimpleChannel packetChannel;

    private Paster()
    { }

    //region Shared methods
    public static void setup(SimpleChannel packetChannel)
    {
        Paster.packetChannel = packetChannel;

        packetChannel.registerMessage(
                PasteRequestPacket.messageId,
                PasteRequestPacket.class,
                PasteRequestPacket::encode,
                PasteRequestPacket::decode,
                Paster::handlePasteRequest,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    //endregion

    //region Server-side methods
    public static void pasteItem(ServerPlayer player, ItemStack currentItemInHand, CopyPath copyPath)
    {
        ResourceLocation itemId = currentItemInHand.getItem().getRegistryName();

        if(itemId == null)
            throw new IllegalArgumentException("The given item stack had no registry name.");

        packetChannel.send(PacketDistributor.PLAYER.with(() -> player), new PasteRequestPacket(itemId, copyPath));
    }
    //endregion

    //region Client-side methods
    public static void handlePasteRequest(PasteRequestPacket packet,
                                          Supplier<? extends NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> handlePasteRequest(packet));
        ctx.setPacketHandled(true);
    }

    public static void handlePasteRequest(PasteRequestPacket packet)
    {
        Path saveLocation = ItemCopy.getClientSaveDirectory().toPath()
                                    .resolve(packet.itemId.getNamespace())
                                    .resolve(packet.itemId.getPath());

        for(String step : packet.copyPath.getStepsSanitised())
            saveLocation = saveLocation.resolve(step);

        saveLocation = saveLocation.resolveSibling(saveLocation.getFileName().toString() + ItemCopy.itemFileExtension);
        File saveFile = saveLocation.toFile();

        if(!saveFile.isFile())
        {
            System.out.println("Copied item didn't exist: " + packet.itemId + " - "
                               + String.join("/", packet.copyPath));

            return;
        }

        String data;

        try
        { data = FileUtils.readFileToString(saveFile, StandardCharsets.UTF_8); }
        catch(IOException e)
        {
            System.err.println("Error reading item file at: " + saveFile);
            e.printStackTrace();
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        assert player != null; // This method is only called on the client-side.
        ItemStack itemInHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        ResourceLocation itemId = itemInHand.getItem().getRegistryName();

        if(!packet.itemId.equals(itemId))
        {
            System.err.println("Item in hand (" + itemId + ") differed from expected item. (" + packet.itemId
                               + ") Item not pasted.");

            return;
        }

        try
        { itemInHand.setTag(NbtUtils.snbtToStructure(data)); }
        catch(CommandSyntaxException e)
        { System.err.println("Invalid item NBT file at: " + saveFile); }
    }
    //endregion
}
