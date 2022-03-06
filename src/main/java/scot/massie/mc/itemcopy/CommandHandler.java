package scot.massie.mc.itemcopy;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.StringTextComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class CommandHandler
{
    // copyitem [name/path]
    // pasteitem [name/path]

    @FunctionalInterface
    private interface TriFunc<T1, T2, T3, R>
    { R apply(T1 val1, T2 val2, T3 val3); }

    private static SuggestionProvider<CommandSource> getCopyPathSuggestionProvider(
            @SuppressWarnings("BoundedWildcard")
            final TriFunc<UUID, ResourceLocation, List<String>, List<String>> suggestionGetterFunction)
    {
        return (context, builder) ->
        {
            if(!(context.getSource().getEntity() instanceof ServerPlayerEntity))
                return builder.buildFuture();

            ServerPlayerEntity sender = (ServerPlayerEntity)context.getSource().getEntity();
            ItemStack itemStack = sender.getItemInHand(Hand.MAIN_HAND);

            if(itemStack == ItemStack.EMPTY || !playerCanPasteItem(sender, itemStack))
                return builder.buildFuture();

            ResourceLocation itemId = itemStack.getItem().getRegistryName();

            if(itemId == null)
                return builder.buildFuture();

            List<String> copyPath = getCopyPath(context, true);
            List<String> suggestions = suggestionGetterFunction.apply(sender.getUUID(), itemId, copyPath);

            if(copyPath.isEmpty())
            {
                for(String suggestion : suggestions)
                    builder.suggest(suggestion);
            }
            else
            {
                String copyPathJoined = String.join(" ", copyPath) + " ";

                for(String suggestion : suggestions)
                    builder.suggest(copyPathJoined + suggestion);
            }

            return builder.buildFuture();
        };
    }

    private static final SuggestionProvider<CommandSource> savedCopyPathsProvider
            = getCopyPathSuggestionProvider(CopyNamesServerStore::getNameSuggestions);

    private static final SuggestionProvider<CommandSource> savedCopyPathDirectoriesProvider
            = getCopyPathSuggestionProvider(CopyNamesServerStore::getFolderSuggestions);


    public static final LiteralArgumentBuilder<CommandSource> copyCommand
            = Commands.literal("copyitem")
                    .then(Commands.argument("copypath", StringArgumentType.greedyString())
                            .suggests(savedCopyPathDirectoriesProvider)
                            .executes(CommandHandler::cmdCopy));

    public static final LiteralArgumentBuilder<CommandSource> pasteCommand
            = Commands.literal("pasteitem")
                    .then(Commands.argument("copypath", StringArgumentType.greedyString())
                            .suggests(savedCopyPathsProvider)
                            .executes(CommandHandler::cmdPaste));


    private CommandHandler()
    { }

    public static boolean playerCanPasteItem(ServerPlayerEntity player, ItemStack itemStack)
    { return player.hasPermissions(4) || ItemWhitelist.itemIsAllowed(itemStack); }

    public static List<String> splitArguments(String args)
    {
        if((args = args.trim()).isEmpty())
            return Collections.emptyList();

        return Arrays.asList(args.split("\\s+"));
    }

    private static List<String> getCopyPath(CommandContext<CommandSource> ctx)
    { return getCopyPath(ctx, false); }

    private static List<String> getCopyPath(CommandContext<CommandSource> ctx,
                                            boolean ignoreLastStepIfNotFollowedBySpace)
    {
        String copyPathUnsplit = null;

        for(ParsedCommandNode<CommandSource> parsedNode : ctx.getNodes())
            if(parsedNode.getNode().getName().equals("copypath"))
                copyPathUnsplit = parsedNode.getRange().get(ctx.getInput());

        if(copyPathUnsplit == null)
            return Collections.emptyList();

        List<String> path = splitArguments(copyPathUnsplit);

        if(ignoreLastStepIfNotFollowedBySpace && !copyPathUnsplit.endsWith(" "))
        {
            List<String> newPath = new ArrayList<>(path.size() - 1);

            for(int i = 0; i < path.size() - 1; i++)
                newPath.add(path.get(i));

            path = newPath;
        }

        return path;
    }

    public static int cmdCopy(CommandContext<CommandSource> context)
    {
        CommandSource src = context.getSource();

        if(!(src.getEntity() instanceof ServerPlayerEntity))
        {
            src.sendFailure(new StringTextComponent("Only players can copy items in their hands."));
            return 1;
        }

        ServerPlayerEntity player = (ServerPlayerEntity)(src.getEntity());
        ItemStack itemInHand = player.getItemInHand(Hand.MAIN_HAND);

        if(itemInHand == ItemStack.EMPTY)
        {
            src.sendFailure(new StringTextComponent("You need to have something in your hand to copy."));
            return 1;
        }

        List<String> copyPath = getCopyPath(context);
        ResourceLocation itemId = itemInHand.getItem().getRegistryName();
        boolean copyAlreadyExisted = CopyNamesServerStore.nameExists(player, itemId, copyPath);
        Copier.copyItem(player, itemInHand, copyPath);

        if(copyAlreadyExisted)
            src.sendSuccess(new StringTextComponent("Copy overwritten: " + String.join(" ", copyPath)), false);
        else
            src.sendSuccess(new StringTextComponent("Copy saved: " + String.join(" ", copyPath)), false);

        return 1;
    }

    public static int cmdPaste(CommandContext<CommandSource> context)
    {
        CommandSource src = context.getSource();

        if(!(src.getEntity() instanceof ServerPlayerEntity))
        {
            src.sendFailure(new StringTextComponent("Only players can paste items."));
            return 1;
        }

        ServerPlayerEntity player = (ServerPlayerEntity)(src.getEntity());
        ItemStack itemInHand = player.getItemInHand(Hand.MAIN_HAND);

        if(itemInHand == ItemStack.EMPTY)
        {
            src.sendFailure(new StringTextComponent("You need to have something in your hand to paste to."));
            return 1;
        }

        if(!playerCanPasteItem(player, itemInHand))
        {
            src.sendFailure(new StringTextComponent("You do not have permission to paste that item."));
            return 1;
        }

        List<String> copyPath = getCopyPath(context);
        Paster.pasteItem(player, itemInHand, copyPath);

        // Edge case: If someone modifies the relevant saved items folder after the last time it was refreshed, and
        // before calling this command, it can report the wrong success message. This doesn't affect whether it actually
        // pastes to the item though.
        if(CopyNamesServerStore.nameExists(player, itemInHand.getItem().getRegistryName(), copyPath))
            src.sendSuccess(new StringTextComponent("Pasted: " + String.join(" ", copyPath)), false);
        else
            src.sendFailure(new StringTextComponent("No copy with the name or path \"" + String.join(" ", copyPath)
                                                    + "\" existed."));

        return 1;
    }
}
