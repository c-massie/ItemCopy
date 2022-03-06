package scot.massie.mc.itemcopy;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class Paster
{
    private static class PasteRequestPacket
    {
        // Sent from server to client to request a paste.

        private static final int messageId = 394;

        public final String itemIdNamespace;
        public final String itemIdPath;

        @SuppressWarnings("PublicField")
        public final List<String> copyPath;

        public PasteRequestPacket(String itemIdNamespace, String itemIdPath, List<String> copyPath)
        {
            this.itemIdNamespace = itemIdNamespace;
            this.itemIdPath = itemIdPath;
            this.copyPath = Collections.unmodifiableList(copyPath);
        }

        public PasteRequestPacket(ResourceLocation itemId, List<String> copyPath)
        { this(itemId.getNamespace(), itemId.getPath(), copyPath); }

        public void encode(PacketBuffer buf)
        {
            buf.writeUtf(itemIdNamespace);
            buf.writeUtf(itemIdPath);
            buf.writeInt(copyPath.size());

            for(String step : copyPath)
                buf.writeUtf(step);
        }

        public static PasteRequestPacket decode(PacketBuffer buf)
        {
            String itemIdNamespace = buf.readUtf();
            String itemIdPath = buf.readUtf();
            int copyPathLength = buf.readInt();
            List<String> copyPath = new ArrayList<>();

            for(int i = 0; i < copyPathLength; i++)
                copyPath.add(buf.readUtf());

            return new PasteRequestPacket(itemIdNamespace, itemIdPath, copyPath);
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
    public static void pasteItem(ServerPlayerEntity player, ItemStack currentItemInHand, List<String> copyPath)
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
        handlePasteRequest(packet);
        contextSupplier.get().setPacketHandled(true);
    }

    public static void handlePasteRequest(PasteRequestPacket packet)
    {
        File saveLocation = new File(new File(ItemCopy.getClientSaveDirectory(),
                                              packet.itemIdNamespace),
                                     packet.itemIdPath);

        List<String> sanitisedCopyPath = PathSanitiser.sanitise(packet.copyPath);

        for(String s : sanitisedCopyPath)
            saveLocation = new File(saveLocation, s);

        saveLocation = new File(saveLocation.getParentFile(), saveLocation.getName() + ItemCopy.itemFileExtension);

        if(!saveLocation.isFile())
        {
            System.out.println("Copied item didn't exist: " + packet.itemIdNamespace + ":" + packet.itemIdPath
                               + " - " + String.join("/", packet.copyPath));

            return;
        }

        String data;

        try
        { data = FileUtils.readFileToString(saveLocation, StandardCharsets.UTF_8); }
        catch(IOException e)
        {
            System.err.println("Error reading item file at: " + saveLocation);
            e.printStackTrace();
            return;
        }

        ClientPlayerEntity player = Minecraft.getInstance().player;
        assert player != null; // This method is only called on the client-side.
        ItemStack itemInHand = player.getItemInHand(Hand.MAIN_HAND);
        ResourceLocation itemId = itemInHand.getItem().getRegistryName();

        if(   itemId == null
           || !packet.itemIdNamespace.equals(itemId.getNamespace())
           || !packet.itemIdPath.equals(itemId.getPath()))
        {
            System.err.println("Item in hand (" + itemId + ") differed from expected item. ("
                               + packet.itemIdNamespace + ":" + packet.itemIdPath +  ") Item not pasted.");

            return;
        }

        try
        { itemInHand.setTag(JsonToNBT.parseTag(data)); }
        catch(CommandSyntaxException e)
        {
            System.err.println("???");
            e.printStackTrace();
        }
    }
    //endregion
}
