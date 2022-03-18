package scot.massie.mc.itemcopy;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLLoader;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ItemWhitelist
{
    private static final Object syncLock = new Object();
    private static Set<String> allowedItems;

    private static final File whitelistFile = FMLLoader.getGamePath()
                                                       .resolve("config")
                                                       .resolve("item-whitelist.txt")
                                                       .toFile();

    private static final List<String> defaultAllowedItems = Arrays.asList(
            "chiselsandbits:pattern_multi_use",
            "chiselsandbits:pattern_single_use",
            "computercraft:disk",
            "krate:filter",
            "minecraft:black_banner",
            "minecraft:blue_banner",
            "minecraft:brown_banner",
            "minecraft:cyan_banner",
            "minecraft:gray_banner",
            "minecraft:green_banner",
            "minecraft:light_blue_banner",
            "minecraft:light_gray_banner",
            "minecraft:lime_banner",
            "minecraft:magenta_banner",
            "minecraft:orange_banner",
            "minecraft:pink_banner",
            "minecraft:purple_banner",
            "minecraft:red_banner",
            "minecraft:white_banner",
            "minecraft:writable_book",
            "minecraft:yellow_banner",
            "modularrouters:bulk_item_filter",
            "modularrouters:inspection_filter",
            "modularrouters:mod_filter",
            "modularrouters:regex_filter",
            "pneumaticcraft:tag_filter",
            "prettypipes:filter_increase_modifier",
            "prettypipes:high_crafting_module",
            "prettypipes:high_extraction_module",
            "prettypipes:high_filter_module",
            "prettypipes:high_retrieval_module",
            "prettypipes:low_crafting_module",
            "prettypipes:low_extraction_module",
            "prettypipes:low_filter_module",
            "prettypipes:low_retrieval_module",
            "prettypipes:medium_crafting_module",
            "prettypipes:medium_extraction_module",
            "prettypipes:medium_filter_module",
            "prettypipes:medium_retrieval_module",
            "prettypipes:stack_size_module",
            "prettypipes:tag_filter_modifier",
            "refinedstorage:filter",
            "rftoolsbase:crafting_card",
            "rftoolsbase:filter_module",
            "rftoolscontrol:program_card",
            "rftoolscontrol:token");

    private ItemWhitelist()
    {}

    public static void setup()
    {
        synchronized(syncLock)
        { allowedItems = new HashSet<>(); }

        load();
    }

    public static boolean itemIsAllowed(String itemId)
    {
        synchronized(syncLock)
        { return allowedItems.contains(itemId); }
    }

    public static boolean itemIsAllowed(ResourceLocation itemId)
    {
        synchronized(syncLock)
        { return allowedItems.contains(itemId.toString()); }
    }

    @SuppressWarnings("TypeMayBeWeakened")
    public static boolean itemIsAllowed(Item item)
    { return itemIsAllowed(Objects.requireNonNull(item.getRegistryName())); }

    public static boolean itemIsAllowed(ItemStack itemStack)
    { return itemIsAllowed(Objects.requireNonNull(itemStack.getItem().getRegistryName())); }

    private static void createDefaultFile()
    {
        try
        { FileUtils.writeLines(whitelistFile, defaultAllowedItems); }
        catch(IOException e)
        {
            System.err.println("Error writing default item whitelist.");
            e.printStackTrace();
        }
    }

    public static void load()
    {
        if(!whitelistFile.isFile())
        {
            createDefaultFile();

            synchronized(syncLock)
            { allowedItems = new HashSet<>(defaultAllowedItems); }

            return;
        }

        List<String> lines;

        try
        { lines = FileUtils.readLines(whitelistFile, StandardCharsets.UTF_8); }
        catch(IOException e)
        {
            synchronized(syncLock)
            { allowedItems = new HashSet<>(); }
            System.err.println("Error reading item whitelist - no items will be pastable by non-operators.");
            e.printStackTrace();
            return;
        }

        synchronized(syncLock)
        { allowedItems = new HashSet<>(lines); }
    }
}
