package scot.massie.mc.itemcopy;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.function.Supplier;

public final class Sharer
{
    static record ShareOffer(int offerId,
                             UUID sender,
                             UUID recipient,
                             ResourceLocation itemId,
                             CompoundTag data,
                             CopyPath copyPath,
                             CopyPath recipientCopyPath,
                             long timeStamp)
    {
        private static int nextId = 0;
        private static final Object idLock = new Object();

        private static int getNewId()
        {
            synchronized(idLock)
            { return nextId++; }
        }

        public ShareOffer(UUID sender,
                          UUID recipient,
                          ResourceLocation itemId,
                          CopyPath copyPath,
                          CopyPath recipientCopyPath,
                          long timeStamp)
        { this(getNewId(), sender, recipient, itemId, null, copyPath, recipientCopyPath, timeStamp); }

        public ShareOffer(UUID sender,
                          UUID recipient,
                          ResourceLocation itemId,
                          CompoundTag data,
                          CopyPath recipientCopyPath,
                          long timeStamp)
        { this(getNewId(), sender, recipient, itemId, data, null, recipientCopyPath, timeStamp); }

        public boolean hasExpired(long currentTimeInMillis)
        { return currentTimeInMillis > (timeStamp + offerLifespanInMilliseconds); }

        public boolean hasDataAlready()
        { return data != null; }

        public ShareOffer withNewTimeStamp(long timeStamp)
        { return new ShareOffer(offerId, sender, recipient, itemId, data, copyPath, recipientCopyPath, timeStamp); }

        public ShareOffer withNewTimeStamp()
        { return this.withNewTimeStamp(System.currentTimeMillis()); }

        public ShareOffer withRecipientCopyPath(CopyPath newRecipientCopyPath)
        { return new ShareOffer(offerId, sender, recipient, itemId, data, copyPath, newRecipientCopyPath, timeStamp); }
    }

    static record ShareOfferBeingProcessed(ShareOffer offer,
                                           Map<Integer, FulfilOfferPacket> receivedPackets)
    {
        public ShareOfferBeingProcessed(ShareOffer offer)
        { this(offer, new HashMap<>()); }

        public int getPacketCountRequired()
        {
            for(FulfilOfferPacket p : receivedPackets.values())
                return p.totalExpectedPackets;

            return -1; // If there have been no packets received yet.
        }

        public boolean hasAllPackets()
        { return receivedPackets.size() >= getPacketCountRequired(); }
    }

    static class FulfilOfferRequestPacket
    {
        // Sent from server to client to request that the client provide item data

        public static final int messageId = 225;

        public final int offerId;

        @SuppressWarnings("PublicField")
        public final ResourceLocation itemId;

        @SuppressWarnings("PublicField")
        public final CopyPath copyPath;

        public FulfilOfferRequestPacket(ShareOffer offer)
        { this(offer.offerId, offer.itemId, offer.copyPath); }

        public FulfilOfferRequestPacket(int offerId, ResourceLocation itemId, CopyPath copyPath)
        {
            this.offerId = offerId;
            this.itemId = itemId;
            this.copyPath = copyPath;
        }

        public void encode(FriendlyByteBuf buf)
        {
            buf.writeInt(offerId);
            buf.writeResourceLocation(itemId);
            copyPath.writeToBuf(buf);
        }

        public static FulfilOfferRequestPacket decode(FriendlyByteBuf buf)
        {
            int offerId = buf.readInt();
            ResourceLocation itemId = buf.readResourceLocation();
            CopyPath copyPath = CopyPath.readFromBuf(buf);
            return new FulfilOfferRequestPacket(offerId, itemId, copyPath);
        }
    }

    static class FulfilOfferPacket
    {
        // Sent from the client to the server to provide item data

        public static final int messageId = 521;

        public final int offerId;
        public final int packetNumber;
        public final int totalExpectedPackets;
        public final String dataPart;

        public FulfilOfferPacket(FulfilOfferRequestPacket requestPacket,
                                 int packetNumber,
                                 int totalExpectedPackets,
                                 String dataPart)
        { this(requestPacket.offerId, packetNumber, totalExpectedPackets, dataPart); }

        public FulfilOfferPacket(int offerId,
                                 int packetNumber,
                                 int totalExpectedPackets,
                                 String dataPart)
        {
            this.offerId = offerId;
            this.packetNumber = packetNumber;
            this.totalExpectedPackets = totalExpectedPackets;
            this.dataPart = dataPart;
        }

        public void encode(FriendlyByteBuf buf)
        {
            buf.writeInt(offerId);
            buf.writeInt(packetNumber);
            buf.writeInt(totalExpectedPackets);
            buf.writeUtf(dataPart);

        }

        public static FulfilOfferPacket decode(FriendlyByteBuf buf)
        {
            int offerId = buf.readInt();
            int packetNumber = buf.readInt();
            int totalExpectedPackets = buf.readInt();
            String dataPart = buf.readUtf();
            return new FulfilOfferPacket(offerId, packetNumber, totalExpectedPackets, dataPart);
        }
    }

    @SuppressWarnings("ClassCanBeRecord")
    static class ProvideOfferItemDataPacket
    {
        // Sent from server to client with data from retrieved from the sender

        public static final int messageId = 199;

        @SuppressWarnings("PublicField")
        public final CompoundTag itemData;

        @SuppressWarnings("PublicField")
        public final ResourceLocation itemId;

        @SuppressWarnings("PublicField")
        public final CopyPath copyPath;

        public ProvideOfferItemDataPacket(CompoundTag itemData, ResourceLocation itemId, CopyPath copyPath)
        {
            this.itemData = itemData;
            this.itemId = itemId;
            this.copyPath = copyPath;
        }

        public void encode(FriendlyByteBuf buf)
        {
            buf.writeNbt(itemData);
            buf.writeResourceLocation(itemId);
            copyPath.writeToBuf(buf);
        }

        public static ProvideOfferItemDataPacket decode(FriendlyByteBuf buf)
        {
            CompoundTag itemData = buf.readNbt();
            ResourceLocation itemId = buf.readResourceLocation();
            CopyPath copyPath = CopyPath.readFromBuf(buf);
            return new ProvideOfferItemDataPacket(itemData, itemId, copyPath);
        }
    }

    // Map<recipient, Map<sender, ShareOffer>>
    private static final Map<UUID, Map<UUID, ShareOffer>> offers = new HashMap<>();
    private static final Map<Integer, ShareOfferBeingProcessed> offersBeingProcessed = new HashMap<>();
    private static SimpleChannel packetChannel;
    private static Timer oldOfferPurger;


    static final long purgeOldOffersPeriod = 60000L; // 1 minute
    static final long offerLifespanInMilliseconds = 600000L; // 10 minutes
    static final int serverToClientMaxPacketSize = 2097151; // 2MB - 1B
    static final int clientToServerMaxPacketSize = 32767; // 32KB - 1B

    private Sharer()
    { }

    //region Shared
    public static void setup(SimpleChannel packetChannel)
    {
        Sharer.packetChannel = packetChannel;

        packetChannel.registerMessage(
                FulfilOfferRequestPacket.messageId,
                FulfilOfferRequestPacket.class,
                FulfilOfferRequestPacket::encode,
                FulfilOfferRequestPacket::decode,
                Sharer::fulfilOffer,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        packetChannel.registerMessage(
                FulfilOfferPacket.messageId,
                FulfilOfferPacket.class,
                FulfilOfferPacket::encode,
                FulfilOfferPacket::decode,
                Sharer::storeOfferFulfilmentParts,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        packetChannel.registerMessage(
                ProvideOfferItemDataPacket.messageId,
                ProvideOfferItemDataPacket.class,
                ProvideOfferItemDataPacket::encode,
                ProvideOfferItemDataPacket::decode,
                Sharer::acceptItemData,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    //endregion

    //region Server
    public static void addOffer(ServerPlayer sender,
                                ServerPlayer recipient,
                                ResourceLocation itemId,
                                CopyPath copyPath)
    {
        addOffer(sender, recipient, new ShareOffer(sender.getUUID(),
                                                   recipient.getUUID(),
                                                   itemId,
                                                   copyPath,
                                                   copyPath,
                                                   System.currentTimeMillis()));
    }

    public static void addOffer(ServerPlayer sender, ServerPlayer recipient, ResourceLocation itemId, CompoundTag data)
    {
        addOffer(sender, recipient, new ShareOffer(sender.getUUID(),
                                                   recipient.getUUID(),
                                                   itemId,
                                                   data,
                                                   CopyPath.defaultPath,
                                                   System.currentTimeMillis()));
    }

    private static void addOffer(ServerPlayer sender, ServerPlayer recipient, ShareOffer offer)
    {
        synchronized(offers)
        { offers.computeIfAbsent(recipient.getUUID(), uuid -> new HashMap<>()).put(sender.getUUID(), offer); }

        String senderName = sender.getGameProfile().getName();
        String alertMsg = senderName + " has offered to share a copy of " + offer.itemId + " with you. To accept this, "
                          + "type: /acceptshareditem " + senderName + " <copy name>";

        recipient.displayClientMessage(new TextComponent(alertMsg), false);
    }

    public static void acceptOffer(String senderName, ServerPlayer recipient, CopyPath recipientCopyPath)
    {
        ServerPlayer sender = ServerLifecycleHooks.getCurrentServer()
                                                  .getPlayerList()
                                                  .getPlayerByName(senderName);

        if(sender == null)
        {
            recipient.displayClientMessage(new TextComponent("Sender is not online: " + senderName), false);
            return;
        }

        acceptOffer(sender, recipient, recipientCopyPath);
    }

    public static void acceptOffer(ServerPlayer sender, ServerPlayer recipient, CopyPath recipientCopyPath)
    {
        ShareOffer offer;
        String senderName = sender.getGameProfile().getName();
        String recipientName = sender.getGameProfile().getName();

        synchronized(offers)
        {
            Map<UUID, ShareOffer> offersAgainstSenders = offers.get(recipient.getUUID());
            offer = (offersAgainstSenders == null) ? null : offersAgainstSenders.remove(sender.getUUID());
        }

        if(offer == null)
        {
            recipient.displayClientMessage(new TextComponent("You have no offers from " + senderName + "."), false);
            return;
        }


        sender.displayClientMessage(new TextComponent(recipientName + " accepted your offer."), false);

        if(recipientCopyPath != null)
            offer = offer.withRecipientCopyPath(recipientCopyPath);

        if(offer.hasDataAlready())
        {
            deliverOfferFulfilmentToRecipient(sender, offer, offer.data);
            return;
        }

        synchronized(offersBeingProcessed)
        { offersBeingProcessed.put(offer.offerId, new ShareOfferBeingProcessed(offer)); }

        packetChannel.send(PacketDistributor.PLAYER.with(() -> sender),
                           new FulfilOfferRequestPacket(offer));
    }

    public static void storeOfferFulfilmentParts(FulfilOfferPacket packet,
                                                 Supplier<? extends NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> storeOfferFulfilmentParts(packet, ctx.getSender()));
        ctx.setPacketHandled(true);
    }

    public static void storeOfferFulfilmentParts(FulfilOfferPacket packet, ServerPlayer sender)
    {
        ShareOfferBeingProcessed shareOfferBeingProcessed;

        synchronized(offersBeingProcessed)
        {
            shareOfferBeingProcessed = offersBeingProcessed.get(packet.offerId);

            if(shareOfferBeingProcessed == null)
            {
                System.err.println("Received item copy offer fulfilment that wasn't requested, id: " + packet.offerId);
                return;
            }

            UUID senderId = shareOfferBeingProcessed.offer.sender;

            if(sender == null || !(senderId.equals(sender.getUUID())))
            {
                String actualSenderId = (sender == null) ? "(null sender)" : sender.getUUID().toString();

                System.err.println("Received offer fulfilment packet from wrong player?"
                                   + "\n  -> Expected: " + senderId
                                   + "\n  -> Received: " + actualSenderId);
                return;
            }

            shareOfferBeingProcessed.receivedPackets.put(packet.packetNumber, packet);

            if(!shareOfferBeingProcessed.hasAllPackets())
                return;

            offersBeingProcessed.remove(packet.offerId);

        }

        StringBuilder sb = new StringBuilder();
        int packetCount = shareOfferBeingProcessed.getPacketCountRequired();

        for(int i = 0; i < packetCount; i++)
            sb.append(shareOfferBeingProcessed.receivedPackets.get(i).dataPart);

        String itemData = sb.toString();
        ShareOffer offer = shareOfferBeingProcessed.offer;
        CompoundTag itemNbt;

        try
        { itemNbt = NbtUtils.snbtToStructure(itemData); }
        catch(CommandSyntaxException e)
        {
            System.err.println("Error parsing sent item NBT. It should have been checked on the client-side to ensure "
                               + "that it's valid. was:\n" + itemData);

            return;
        }

        deliverOfferFulfilmentToRecipient(sender, offer, itemNbt);
    }

    public static void deliverOfferFulfilmentToRecipient(ServerPlayer sender,
                                                         ShareOffer shareOffer,
                                                         CompoundTag itemNbt)
    {
        ServerPlayer recipient = ServerLifecycleHooks.getCurrentServer()
                                                     .getPlayerList()
                                                     .getPlayer(shareOffer.recipient);

        if(recipient == null)
        {
            sender.displayClientMessage(new TextComponent("Recipient no longer online to receive item."), false);
            return;
        }

        packetChannel.send(PacketDistributor.PLAYER.with(() -> recipient),
                           new ProvideOfferItemDataPacket(itemNbt, shareOffer.itemId, shareOffer.recipientCopyPath));
    }

    public static List<String> getNamesOfPlayersOffering(UUID forPlayer)
    {
        List<String> names = new ArrayList<>();
        PlayerList plist = ServerLifecycleHooks.getCurrentServer().getPlayerList();

        for(UUID id : getIdsOfPlayersOffering(forPlayer))
        {
            ServerPlayer sender = plist.getPlayer(id);

            if(sender != null)
                names.add(sender.getGameProfile().getName());
        }

        return names;
    }

    public static List<UUID> getIdsOfPlayersOffering(UUID forPlayer)
    {
        synchronized(offers)
        {
            Map<UUID, ShareOffer> offersAgainstSenders = offers.get(forPlayer);

            if(offersAgainstSenders == null)
                return Collections.emptyList();

            return new ArrayList<>(offersAgainstSenders.keySet());
        }
    }

    public static void purgeOldOffers()
    {
        synchronized(offers)
        {
            long currentTime = System.currentTimeMillis();

            for(var offersAgainstSenders : offers.values())
            {
                Iterator<ShareOffer> offersIterator = offersAgainstSenders.values().iterator();

                for(ShareOffer offer = offersIterator.next(); offersIterator.hasNext(); offer = offersIterator.next())
                    if(offer.hasExpired(currentTime))
                        offersIterator.remove();
            }
        }
    }

    public static void startPurgeRoutine()
    {
        oldOfferPurger = new Timer("old_item_share_offer_purger", true);
        oldOfferPurger.schedule(new TimerTask() { @Override public void run() { purgeOldOffers(); } },
                                offerLifespanInMilliseconds,
                                purgeOldOffersPeriod);
    }

    public static void stopPurgeRoutine()
    { oldOfferPurger.cancel(); }
    //endregion

    //region Client
    public static void fulfilOffer(FulfilOfferRequestPacket packet,
                                   Supplier<? extends NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> fulfilOffer(packet));
        ctx.setPacketHandled(true);
    }

    public static void fulfilOffer(FulfilOfferRequestPacket packet)
    {
        Path fileLocation = ItemCopy.getClientSaveDirectory()
                                    .toPath()
                                    .resolve(packet.itemId.getNamespace())
                                    .resolve(packet.itemId.getPath());

        for(String step : packet.copyPath.getStepsSanitised())
            fileLocation = fileLocation.resolve(step);

        fileLocation = fileLocation.resolveSibling(fileLocation.getFileName().toString() + ".itemnbt");
        File saveFile = fileLocation.toFile();

        if(!saveFile.isFile())
        {
            // Should not be null on client-side while connected to a world.
            assert Minecraft.getInstance().player != null;

            Minecraft.getInstance().player.displayClientMessage(
                    new TextComponent("Offered file no longer exists: " + fileLocation), false);

            return;
        }

        String data;

        try
        { data = FileUtils.readFileToString(saveFile, StandardCharsets.UTF_8); }
        catch(IOException e)
        {
            // Should not be null on client-side while connected to a world.
            assert Minecraft.getInstance().player != null;

            Minecraft.getInstance().player.displayClientMessage(
                    new TextComponent("Error reading offered item file: " + fileLocation), false);

            e.printStackTrace();
            return;
        }

        try
        {
            // Not doing anything with the result of this, just ensuring that it *can* be parsed.
            NbtUtils.snbtToStructure(data);
        }
        catch(CommandSyntaxException e)
        {
            // Should not be null on client-side while connected to a world.
            assert Minecraft.getInstance().player != null;

            Minecraft.getInstance().player.displayClientMessage(
                    new TextComponent("Error parsing offered item file: " + fileLocation), false);

            e.printStackTrace();
            return;
        }

        for(var p : createOfferFulfilmentPackets(packet.offerId, data))
            packetChannel.sendToServer(p);
    }

    private static Collection<FulfilOfferPacket> createOfferFulfilmentPackets(int offerId, String itemData)
    {
        @SuppressWarnings("MagicNumber") // No it's not.
        int otherOrUnusedSpacePerPacket = 1024; // 1KB buffer to fit other stuff + make abso. sure I don't hit the cap.
        int bytesPerChar = 4; // Max possible bytes per character with any encoding
        int maxDataStringLengthPerPacket = (clientToServerMaxPacketSize - otherOrUnusedSpacePerPacket) / bytesPerChar;

        List<String> dataParts = new ArrayList<>();

        for(int startIndex = 0; startIndex < itemData.length(); startIndex += maxDataStringLengthPerPacket)
        {
            int endIndex = startIndex + maxDataStringLengthPerPacket;

            if(endIndex > itemData.length())
                endIndex = itemData.length();

            dataParts.add(itemData.substring(startIndex, endIndex));
        }

        int packetCount = dataParts.size();
        Collection<FulfilOfferPacket> packets = new ArrayList<>(packetCount);

        for(int i = 0; i < packetCount; i++)
            packets.add(new FulfilOfferPacket(offerId, i, packetCount, dataParts.get(i)));

        return packets;
    }

    public static void acceptItemData(ProvideOfferItemDataPacket packet,
                                      Supplier<? extends NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> acceptItemData(packet));
        ctx.setPacketHandled(true);
    }

    public static void acceptItemData(ProvideOfferItemDataPacket packet)
    {
        Path saveLocation = ItemCopy.getClientSaveDirectory()
                                    .toPath()
                                    .resolve(packet.itemId.getNamespace())
                                    .resolve(packet.itemId.getPath());

        for(String step : packet.copyPath.getStepsSanitised())
            saveLocation = saveLocation.resolve(step);

        saveLocation = saveLocation.resolveSibling(saveLocation.getFileName().toString() + ItemCopy.itemFileExtension);
        File saveFile = saveLocation.toFile();

        if(saveFile.exists())
            if(!saveFile.delete())
            {
                String errorMsg = "Could not delete pre-existing file at: " + saveLocation;
                assert Minecraft.getInstance().player != null;
                Minecraft.getInstance().player.displayClientMessage(new TextComponent(errorMsg), false);
                System.err.println(errorMsg);
                return;
            }

        try
        { FileUtils.writeStringToFile(saveFile, packet.itemData.toString(), StandardCharsets.UTF_8); }
        catch(IOException e)
        {
            System.err.println("Error saving file.");
            e.printStackTrace();
        }

        CopyNamesServerStore.provideRefreshedInfo(packet.itemId);
    }
    //endregion
}
