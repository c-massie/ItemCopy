package scot.massie.mc.itemcopy;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class CopyNamesServerStore
{
    @SuppressWarnings("ClassCanBeRecord")
    private static final class RefreshRequestPacket
    {
        public static final int messageId = 983;

        public final String itemIdNamespace;
        public final String itemIdPath;

        public RefreshRequestPacket(String itemIdNamespace, String itemIdPath)
        {
            this.itemIdNamespace = itemIdNamespace;
            this.itemIdPath = itemIdPath;
        }

        public void encode(FriendlyByteBuf buf)
        {
            buf.writeUtf(itemIdNamespace);
            buf.writeUtf(itemIdPath);
        }

        public static RefreshRequestPacket decode(FriendlyByteBuf buf)
        {
            String itemIdNamespace = buf.readUtf();
            String itemIdPath = buf.readUtf();
            return new RefreshRequestPacket(itemIdNamespace, itemIdPath);
        }
    }

    private static final class WipeNamesPacket
    {
        public static final int messageId = 387;

        public void encode(FriendlyByteBuf buf)
        { }

        public static WipeNamesPacket decode(FriendlyByteBuf buf)
        { return new WipeNamesPacket(); }
    }

    @SuppressWarnings("ClassCanBeRecord")
    private static final class NamesPacket
    {
        public static final int messageId = 662;

        public final String itemIdNamespace;
        public final String itemIdPath;

        @SuppressWarnings("PublicField")
        public final Collection<List<String>> paths;

        private NamesPacket(String itemIdNamespace, String itemIdPath, Collection<List<String>> paths)
        {
            this.itemIdNamespace = itemIdNamespace;
            this.itemIdPath = itemIdPath;
            this.paths = Collections.unmodifiableCollection(paths);
        }

        public void encode(FriendlyByteBuf buf)
        {
            buf.writeUtf(itemIdNamespace);
            buf.writeUtf(itemIdPath);
            buf.writeInt(paths.size());

            for(List<String> path : paths)
            {
                buf.writeInt(path.size());

                for(String step : path)
                    buf.writeUtf(step);
            }
        }

        public static NamesPacket decode(FriendlyByteBuf buf)
        {
            String itemIdNamespace = buf.readUtf();
            String itemIdPath = buf.readUtf();
            int pathCount = buf.readInt();
            List<List<String>> paths = new ArrayList<>(pathCount);

            for(int i = 0; i < pathCount; i++)
            {
                int pathSize = buf.readInt();
                List<String> path = new ArrayList<>(pathSize);

                for(int j = 0; j < pathSize; j++)
                    path.add(buf.readUtf());

                paths.add(path);
            }

            return new NamesPacket(itemIdNamespace, itemIdPath, paths);
        }
    }

    private static final class NameHierarchyNode
    {
        private final String name;
        private boolean isItem = false;
        private final Collection<NameHierarchyNode> subhierarchies = new ArrayList<>();

        public NameHierarchyNode(String name)
        { this.name = name; }

        public NameHierarchyNode getChild(String name)
        {
            for(NameHierarchyNode node : subhierarchies)
                if(node.name.equals(name))
                    return node;

            return null;
        }

        public List<String> getChildNames()
        {
            List<String> names = new ArrayList<>();

            for(NameHierarchyNode node : subhierarchies)
                names.add(node.name);

            return names;
        }

        public List<String> getChildFolderNames()
        {
            List<String> names = new ArrayList<>();

            for(NameHierarchyNode node : subhierarchies)
                if(!node.isItem)
                    names.add(node.name);

            return names;
        }

        public NameHierarchyNode getOrMakeChild(String name)
        {
            for(NameHierarchyNode node : subhierarchies)
                if(node.name.equals(name))
                    return node;

            NameHierarchyNode child = new NameHierarchyNode(name);
            subhierarchies.add(child);
            return child;
        }

        public void makeAt(@SuppressWarnings("TypeMayBeWeakened") List<String> path)
        {
            NameHierarchyNode current = this;

            for(String step : path)
                current = current.getOrMakeChild(step);

            current.isItem = true;
        }

        public NameHierarchyNode getAt(@SuppressWarnings("TypeMayBeWeakened") List<String> path)
        {
            NameHierarchyNode current = this;

            for(String step : path)
            {
                current = current.getChild(step);

                if(current == null)
                    return null;
            }

            return current;
        }
    }


    public static final class Events
    {
        // This is split into separate "Server" and "Client" listener classes because attempting to register anything in
        // ClientPlayerNetworkEvent normally results in dedicated server crashes.* Registrations of these event
        // listeners needs to be behind a side check. Expected functionality is that on server-side, these listeners are
        // simply ignored and don't crash the server.
        //
        // * […].RuntimeException: Attempted to load class […]/MultiPlayerGameMode for invalid dist DEDICATED_SERVER

        private Events()
        { }

        @Mod.EventBusSubscriber
        public static class Server
        {
            @SubscribeEvent
            public void onPlayerLeaveServer(PlayerEvent.PlayerLoggedOutEvent event)
            { clearNames(event.getPlayer().getUUID()); }
        }

        @Mod.EventBusSubscriber
        public static class Client
        {
            @SubscribeEvent()
            public void onPlayerJoinServer(@SuppressWarnings("unused") ClientPlayerNetworkEvent.LoggedInEvent event)
            {
                provideAllAvailableNames();

                try
                { saveFolderAlterationMonitor.start(); }
                catch(Exception e)
                { e.printStackTrace(); }
            }

            @SubscribeEvent
            public void onPlayerLeaveServer(ClientPlayerNetworkEvent.LoggedOutEvent event)
            {
                // This is fired when a player leaves a world, OR when a player quits the game. If the player is not in
                // a world when they quit the game, event.getPlayer() returns null. I'm only interested in when the
                // player leaves a world.
                if(event.getPlayer() == null)
                    return;

                try
                { saveFolderAlterationMonitor.stop(); }
                catch(Exception e)
                { e.printStackTrace(); }
            }
        }
    }

    private static final Map<UUID, Map<ResourceLocation, NameHierarchyNode>> nameHierarchy = new HashMap<>();
    private static SimpleChannel packetChannel;
    private static FileAlterationMonitor saveFolderAlterationMonitor = null;

    private CopyNamesServerStore()
    {}

    //region Shared methods
    public static void setup(SimpleChannel packetChannel)
    {
        CopyNamesServerStore.packetChannel = packetChannel;

        packetChannel.registerMessage(WipeNamesPacket.messageId,
                                      WipeNamesPacket.class,
                                      WipeNamesPacket::encode,
                                      WipeNamesPacket::decode,
                                      CopyNamesServerStore::clearNames,
                                      Optional.of(NetworkDirection.PLAY_TO_SERVER));

        packetChannel.registerMessage(NamesPacket.messageId,
                                      NamesPacket.class,
                                      NamesPacket::encode,
                                      NamesPacket::decode,
                                      CopyNamesServerStore::storeNames,
                                      Optional.of(NetworkDirection.PLAY_TO_SERVER));


        packetChannel.registerMessage(RefreshRequestPacket.messageId,
                                      RefreshRequestPacket.class,
                                      RefreshRequestPacket::encode,
                                      RefreshRequestPacket::decode,
                                      CopyNamesServerStore::provideRefreshedInfo,
                                      Optional.of(NetworkDirection.PLAY_TO_CLIENT));


    }
    //endregion

    //region Server-side methods
    public static List<String> getNameSuggestions(UUID playerId, ResourceLocation itemId, List<String> precedingSteps)
    {
        synchronized(nameHierarchy)
        {
            Map<ResourceLocation, NameHierarchyNode> namesForPlayer = nameHierarchy.get(playerId);

            if(namesForPlayer == null)
                return Collections.emptyList();

            NameHierarchyNode namesForItem = namesForPlayer.get(itemId);

            if(namesForItem == null)
                return Collections.emptyList();

            NameHierarchyNode branch = namesForItem.getAt(precedingSteps);
            return branch == null ? Collections.emptyList() : branch.getChildNames();
        }
    }

    public static List<String> getFolderSuggestions(UUID playerId, ResourceLocation itemId, List<String> precedingSteps)
    {
        synchronized(nameHierarchy)
        {
            Map<ResourceLocation, NameHierarchyNode> namesForPlayer = nameHierarchy.get(playerId);

            if(namesForPlayer == null)
                return Collections.emptyList();

            NameHierarchyNode namesForItem = namesForPlayer.get(itemId);

            if(namesForItem == null)
                return Collections.emptyList();

            NameHierarchyNode branch = namesForItem.getAt(precedingSteps);
            return branch == null ? Collections.emptyList() : branch.getChildFolderNames();
        }
    }

    public static boolean nameExists(UUID playerId, ResourceLocation itemId, List<String> path)
    {
        synchronized(nameHierarchy)
        {
            Map<ResourceLocation, NameHierarchyNode> namesForPlayer = nameHierarchy.get(playerId);

            if(namesForPlayer == null)
                return false;

            NameHierarchyNode namesForItem = namesForPlayer.get(itemId);

            if(namesForItem == null)
                return false;

            NameHierarchyNode node = namesForItem.getAt(path);
            return node != null && node.isItem;
        }
    }

    public static boolean nameExists(@SuppressWarnings("TypeMayBeWeakened") ServerPlayer player,
                                     ResourceLocation itemId,
                                     List<String> path)
    { return nameExists(player.getUUID(), itemId, path); }

    public static void storeNames(NamesPacket pkt, Supplier<? extends NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context ctx = contextSupplier.get();
        assert ctx.getSender() != null; // The packet is sent by players' clients.
        ctx.enqueueWork(() -> storeNames(ctx.getSender().getUUID(), pkt.itemIdNamespace, pkt.itemIdPath, pkt.paths));
        ctx.setPacketHandled(true);
    }

    public static void storeNames(UUID playerId,
                                  String itemIdNamespace,
                                  String itemIdPath,
                                  Iterable<? extends List<String>> paths)
    {
        synchronized(nameHierarchy)
        {
            Map<ResourceLocation, NameHierarchyNode> namesForPlayer
                    = nameHierarchy.computeIfAbsent(playerId, uuid -> new HashMap<>());

            NameHierarchyNode namesForItem = new NameHierarchyNode("###ITEMROOT###");
            namesForPlayer.put(new ResourceLocation(itemIdNamespace, itemIdPath), namesForItem);

            for(List<String> path : paths)
                namesForItem.makeAt(path);
        }
    }

    public static void clearNames(WipeNamesPacket packet, Supplier<? extends NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context ctx = contextSupplier.get();
        assert ctx.getSender() != null; // The packet is sent by players' clients.
        ctx.enqueueWork(() -> clearNames(ctx.getSender().getUUID()));
        ctx.setPacketHandled(true);
    }

    public static void clearNames(UUID playerId)
    {
        synchronized(nameHierarchy)
        { nameHierarchy.remove(playerId); }
    }
    //endregion

    //region Client-side methods
    public static void setupClient()
    {
        FileAlterationObserver saveFolderAlterationObserver
                = new FileAlterationObserver(ItemCopy.getClientSaveDirectory());

        final long folderPollRateInMilliseconds = 5000;

        saveFolderAlterationMonitor = new FileAlterationMonitor(folderPollRateInMilliseconds,
                                                                saveFolderAlterationObserver);

        saveFolderAlterationObserver.addListener(new FileAlterationListenerAdaptor()
        {
            @Override
            public void onFileCreate(File file)
            { provideAllAvailableNames(); }

            @Override
            public void onFileDelete(File file)
            { provideAllAvailableNames(); }
        });
    }

    public static void provideRefreshedInfo(RefreshRequestPacket packet,
                                            Supplier<? extends NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> provideRefreshedInfo(packet.itemIdNamespace, packet.itemIdPath));
        ctx.setPacketHandled(true);
    }

    public static void provideRefreshedInfo(String itemIdNamespace, String itemIdPath)
    {
        File itemFolder = new File(new File(ItemCopy.getClientSaveDirectory(), itemIdNamespace), itemIdPath);

        if(!itemFolder.isDirectory())
        {
            packetChannel.sendToServer(new NamesPacket(itemIdNamespace, itemIdPath, Collections.emptyList()));
            return;
        }

        packetChannel.sendToServer(new NamesPacket(itemIdNamespace, itemIdPath, getAvailablePaths(itemFolder)));
    }

    public static void provideAllAvailableNames()
    {
        packetChannel.sendToServer(new WipeNamesPacket());

        File saveFolder = ItemCopy.getClientSaveDirectory();
        File[] modFolders = saveFolder.listFiles(File::isDirectory);

        if(modFolders == null)
            return;

        for(File modFolder : modFolders)
        {
            File[] itemFolders = modFolder.listFiles(File::isDirectory);

            if(itemFolders == null)
            {
                System.err.println("Error getting items there are available copies for.");
                return;
            }

            for(File itemFolder : itemFolders)
            {
                packetChannel.sendToServer(new NamesPacket(modFolder.getName(),
                                                           itemFolder.getName(),
                                                           getAvailablePaths(itemFolder)));
            }
        }
    }

    public static Collection<List<String>> getAvailablePaths(File itemFolder)
    {
        return getAvailablePathsAsStream(itemFolder).map(p -> p.collect(Collectors.toList()))
                                                    .collect(Collectors.toList());
    }


    public static Stream<Stream<String>> getAvailablePathsAsStream(File itemFolder)
    {
        File[] savedItemFiles
                = itemFolder.listFiles(f ->    f.isFile()
                                               && f.getName().toLowerCase().endsWith(ItemCopy.itemFileExtension));

        File[] subdirectories
                = itemFolder.listFiles(f -> f.isDirectory() && !f.getName().contains("."));

        if(savedItemFiles == null || subdirectories == null)
        {
            System.err.println("Error getting files and subdirectories in " + itemFolder);
            return Stream.empty();
        }

        final int extensionLength = ItemCopy.itemFileExtension.length();

        Stream<Stream<String>> result
                = Arrays.stream(savedItemFiles).map(file ->
        {
            String name = file.getName();
            name = name.substring(0, file.getName().length() - extensionLength); // Remove extension
            name = PathSanitiser.desanitise(name); // Sanitise
            return Stream.of(name);
        });

        for(File folder : subdirectories)
            result = Stream.concat(result, getAvailablePathsAsStream(folder)
                                                .map(s -> Stream.concat(Stream.of(folder.getName()), s)));

        return result;
    }

    //endregion
}
