# Itemcopy

This mod adds two commands: `/copyitem` and `/pasteitem`.

The intent of this mod is to give players the ability to copy items they may have created in a way that doesn't give them the ability to create new items. It was inspired by the desire to easily store, recall, and share Chisels & Bits patterns.

`/copyitem <name>` lets you copy the current item in your hand, with a given name, to a file. (see below) This name may be multiple words long. You can use the same name for copies of different items. (e.g. for a book and for a banner)

`/pasteitem <name>` lets you paste a previously copied item onto the item in your hand, as long as it's an item of the same type. As you type, possible copies will be suggested. This allows you to copy and paste items between worlds and even onto servers.

## Whitelist

On first run, a whitelist file is created in the config folder (`config/itemcopy-whitelist.txt`) where each line is an item ID that's whitelisted for this mod. You can add or remove whitelisted items by adding them to, or removing them from, this file.

You can copy any item, but unless you're an operator, (or in single-player with cheats enabled) you'll only be able to paste whitelisted items.

As a server admin, you can modify this file to control which items are and are not pastable on your server without having to have your users make the same changes in their own whitelist files - the whitelist is server-side.

## Files

Copied items are stored in the "saveditems" folder in your client's minecraft folder. This contains subfolders for items of each mod, which contain subfolders for individual items. (These folder names are based on item IDs)

The subfolders of those folders are arranged according to the multi-word names given to copied items, where folders are named for the words in the name.

The copies are stored in those folders as nbt files with the file extension `.itemnbt`. These folders can be freely moved, renamed or deleted, and this will be reflected in the mod.

For example, if you hold a written book in your hand and use the command `/copyitem lore fiction garg`, it will be saved in `saveditems/minecraft/writable_book/lore/fiction/garg.itemnbt`.

## Notes

Some modded items don't store all of their information as NBT data on the item. Refined storage, for example, stores the items held on storage disks separately.

Some items that seem conceptually variants of the same item may be entirely separate items. For instance, banners have 16 separate item IDs, corresponding to their base colours.

## Technical

Built against Forge 36.2.22 for Minecraft 1.16.5.
