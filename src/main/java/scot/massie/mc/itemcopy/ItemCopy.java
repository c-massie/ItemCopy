package scot.massie.mc.itemcopy;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.File;

@Mod("itemcopy")
public class ItemCopy
{
    private static final File clientSaveDirectory = FMLLoader.getGamePath().resolve("saveditems").toFile();
    static final String itemFileExtension = ".itemnbt";

    public ItemCopy()
    {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::doClientStuff);

        MinecraftForge.EVENT_BUS.register(new CopyNamesServerStore.Events.Server());

        if(FMLLoader.getDist() == Dist.CLIENT)
            MinecraftForge.EVENT_BUS.register(new CopyNamesServerStore.Events.Client());

        //noinspection ThisEscapedInObjectConstruction
        MinecraftForge.EVENT_BUS.register(this);
    }


    public static File getClientSaveDirectory()
    { return clientSaveDirectory; }

    private void setup(final FMLCommonSetupEvent event)
    { setupPacketChannel(); }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(CommandHandler.copyCommand);
        event.getDispatcher().register(CommandHandler.pasteCommand);
    }

    private void doClientStuff(final FMLClientSetupEvent event)
    {
        CopyNamesServerStore.setupClient();
    }

    @SubscribeEvent
    public void onServerStarting(@SuppressWarnings("unused") ServerStartingEvent event)
    { ItemWhitelist.setup(); }

    public void setupPacketChannel()
    {
        final String protocolVersion = "1";

        SimpleChannel packetChannel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation("itemcopy", "copy"),
                () -> protocolVersion,
                protocolVersion::equals,
                protocolVersion::equals);

        Copier.setup(packetChannel);
        Paster.setup(packetChannel);
        CopyNamesServerStore.setup(packetChannel);
    }
}
