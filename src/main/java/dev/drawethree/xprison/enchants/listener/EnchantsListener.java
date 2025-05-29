package dev.drawethree.xprison.enchants.listener;

import dev.drawethree.xprison.enchants.XPrisonEnchants;
import dev.drawethree.xprison.enchants.gui.EnchantGUI;
import dev.drawethree.xprison.enchants.managers.EnchantsManager;
import dev.drawethree.xprison.utils.Constants;
import dev.drawethree.xprison.utils.compat.MinecraftVersion;
import dev.drawethree.xprison.utils.inventory.InventoryUtils;
import lombok.Getter;
import me.lucko.helper.Events;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.codemc.worldguardwrapper.flag.IWrappedFlag;
import org.codemc.worldguardwrapper.flag.WrappedState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class EnchantsListener {

	private final XPrisonEnchants plugin;
	@Getter
    private final List<BlockBreakEvent> ignoredEvents = new ArrayList<>();

	public EnchantsListener(XPrisonEnchants plugin) {
		this.plugin = plugin;
	}

	public void register() {

		this.subscribeToPlayerDeathEvent();
		this.subscribeToPlayerRespawnEvent();
		this.subscribeToInventoryClickEvent();
		this.subscribeToPlayerJoinEvent();
		this.subscribeToPickaxeMoveInInventory();
		this.subscribeToPlayerDropItemEvent();
		this.subscribeToPlayerInteractEvent();
		this.subscribeToPlayerItemHeldEvent();
		this.subscribeToBlockBreakEvent();
	}

    private void subscribeToBlockBreakEvent() {
		Events.subscribe(BlockBreakEvent.class, EventPriority.HIGHEST)
				.filter(e -> !e.isCancelled() && !ignoredEvents.contains(e))
				.filter(e -> e.getPlayer().getItemInHand() != null && this.plugin.getCore().isPickaxeSupported(e.getPlayer().getItemInHand()))
				.handler(e -> this.plugin.getEnchantsManager().handleBlockBreak(e, e.getPlayer().getItemInHand())).bindWith(this.plugin.getCore());
	}

	private void subscribeToPlayerItemHeldEvent() {
		// Switching pickaxes
		Events.subscribe(PlayerItemHeldEvent.class, EventPriority.HIGHEST)
				.handler(e -> {

					ItemStack newItem = e.getPlayer().getInventory().getItem(e.getNewSlot());
					ItemStack previousItem = e.getPlayer().getInventory().getItem(e.getPreviousSlot());

					// Old item
					if (this.plugin.getCore().isPickaxeSupported(previousItem)) {
						this.plugin.getCore().debug("Unequipped pickaxe: " + previousItem.getType(), this.plugin);
						this.plugin.getEnchantsManager().handlePickaxeUnequip(e.getPlayer(), previousItem);
					}

					// New item
					if (this.plugin.getCore().isPickaxeSupported(newItem)) {
						this.plugin.getCore().debug("Equipped pickaxe: " + newItem.getType(), this.plugin);
						this.plugin.getEnchantsManager().handlePickaxeEquip(e.getPlayer(), newItem);
					}

				}).bindWith(this.plugin.getCore());
	}

	/**
	 * Subscribes and handles the {@link InventoryClickEvent} to manage equipping and unequipping pickaxes
	 * in the player's inventory.
	 * This method listens for various inventory click actions, processes relevant
	 * interactions with pickaxes, and triggers the appropriate equipped or unequip logic based on the type of action.
	 * <p>
	 * Key behaviors handled by this method:
	 * - Detects when a pickaxe is unequipped by removing it from the held slot via a click.
	 * - Identifies when a pickaxe is equipped by placing it into the held slot using the cursor.
	 * - Handles equipping a pickaxe into the held slot using a hotbar number key.
	 * - Handles unequipping a pickaxe from the held slot using a hotbar number key.
	 * <p>
	 * The appropriate actions for equipping and unequip are managed through the {@link EnchantsManager} of the plugin,
	 * which handles enchantment-specific processes.
	 * Debug information about equipping and unequipping is logged
	 * for the plugin's core if debugging is enabled.
	 * <p>
	 * Event subscription is registered with the highest priority using the {@link EventPriority#HIGHEST}, ensuring
	 * it runs after other event listeners.
	 * The event is filtered to ensure interaction comes from a player.
	 */
	private void subscribeToPickaxeMoveInInventory() {
		Events.subscribe(InventoryClickEvent.class, EventPriority.HIGHEST)
				.filter(e -> e.getWhoClicked() instanceof Player)
				.handler(e -> {
					Player player = (Player) e.getWhoClicked();
					int heldSlot = player.getInventory().getHeldItemSlot();
					int clickedSlot = e.getSlot();
					ClickType clickType = e.getClick();
					ItemStack current = e.getCurrentItem();
					ItemStack cursor = e.getCursor();

					// Unequip inmediato con click
					if (clickedSlot == heldSlot &&
							current != null &&
							this.plugin.getCore().isPickaxeSupported(current)) {
						this.plugin.getCore().debug("Unequipped pickaxe (click): " + current.getType(), this.plugin);
						this.plugin.getEnchantsManager().handlePickaxeUnequip(player, current);
					}

					// Equip inmediato con click
					if (clickedSlot == heldSlot &&
							cursor != null &&
							this.plugin.getCore().isPickaxeSupported(cursor)) {
						this.plugin.getCore().debug("Equipped pickaxe (click): " + cursor.getType(), this.plugin);
						this.plugin.getEnchantsManager().handlePickaxeEquip(player, cursor);
					}

					// Si es NUMBER_KEY, manejar con 1 tick delay
					if (clickType == ClickType.NUMBER_KEY) {
						int hotbarButton = e.getHotbarButton();

						// Guardamos el item actual en la mano ANTES del cambio
						ItemStack oldItemInHand = player.getInventory().getItem(heldSlot);

						Bukkit.getScheduler().runTaskLater(this.plugin.getCore(), () -> {
							ItemStack newItemInHand = player.getInventory().getItem(heldSlot);

							boolean wasPickaxe = oldItemInHand != null && this.plugin.getCore().isPickaxeSupported(oldItemInHand);
							boolean isNowPickaxe = newItemInHand != null && this.plugin.getCore().isPickaxeSupported(newItemInHand);

							if (wasPickaxe && (!isNowPickaxe || oldItemInHand != newItemInHand)) {
								this.plugin.getCore().debug("Unequipped pickaxe (key delayed): " + oldItemInHand.getType(), this.plugin);
								this.plugin.getEnchantsManager().handlePickaxeUnequip(player, oldItemInHand);
							}

							if (isNowPickaxe && (!wasPickaxe || oldItemInHand != newItemInHand)) {
								this.plugin.getCore().debug("Equipped pickaxe (key delayed): " + newItemInHand.getType(), this.plugin);
								this.plugin.getEnchantsManager().handlePickaxeEquip(player, newItemInHand);
							}
						}, 1L);
					}
				}).bindWith(this.plugin.getCore());
	}


	private void subscribeToPlayerInteractEvent() {
		Events.subscribe(PlayerInteractEvent.class)
				.filter(e -> e.getItem() != null
						&& this.plugin.getCore().isPickaxeSupported(e.getItem())
						&& e.getItem().getPersistentDataContainer().has(new NamespacedKey(plugin.getCore(), "prison-pickaxe")))
				.filter(e -> (this.plugin.getEnchantsConfig().getOpenEnchantMenuActions().contains(e.getAction())))
				.handler(e -> {

					e.setCancelled(true);

					ItemStack pickAxe = e.getItem();

					int pickaxeSlot = InventoryUtils.getInventorySlot(e.getPlayer(), pickAxe);
					this.plugin.getCore().debug("Pickaxe slot is: " + pickaxeSlot, this.plugin);

					new EnchantGUI(this.plugin, e.getPlayer(), pickAxe, pickaxeSlot).open();
				}).bindWith(this.plugin.getCore());
	}

	private void subscribeToPlayerDropItemEvent() {
		// Dropping pickaxe
		Events.subscribe(PlayerDropItemEvent.class, EventPriority.HIGHEST)
				.handler(e -> {
					if (this.plugin.getCore().isPickaxeSupported(e.getItemDrop().getItemStack())) {
						this.plugin.getEnchantsManager().handlePickaxeUnequip(e.getPlayer(), e.getItemDrop().getItemStack());
					}
				}).bindWith(this.plugin.getCore());
	}

	private void subscribeToPlayerJoinEvent() {
		//First join pickaxe
		Events.subscribe(PlayerJoinEvent.class)
				.filter(e -> !e.getPlayer().hasPlayedBefore() && this.plugin.getEnchantsConfig().isFirstJoinPickaxeEnabled())
				.handler(e -> {
					ItemStack firstJoinPickaxe = this.plugin.getEnchantsManager().createFirstJoinPickaxe(e.getPlayer());
					e.getPlayer().getInventory().addItem(firstJoinPickaxe);
				}).bindWith(this.plugin.getCore());
	}

	private void subscribeToPlayerRespawnEvent() {
		Events.subscribe(PlayerRespawnEvent.class, EventPriority.LOWEST)
				.handler(e -> this.plugin.getRespawnManager().handleRespawn(e.getPlayer())).bindWith(this.plugin.getCore());
	}

	private void subscribeToInventoryClickEvent() {
		//Grindstone disenchanting - disable
		if (MinecraftVersion.atLeast(MinecraftVersion.V.v1_14)) {
			Events.subscribe(InventoryClickEvent.class)
					.filter(e -> e.getInventory() instanceof GrindstoneInventory)
					.handler(e -> {
						ItemStack item1 = e.getInventory().getItem(0);
						ItemStack item2 = e.getInventory().getItem(1);
						if (e.getSlot() == 2 && (this.plugin.getEnchantsManager().hasEnchants(item1) || this.plugin.getEnchantsManager().hasEnchants(item2))) {
							e.setCancelled(true);
						}
					}).bindWith(this.plugin.getCore());
		}

		Events.subscribe(InventoryClickEvent.class, EventPriority.MONITOR)
				.filter(e -> e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY)
				.filter(e -> e.getWhoClicked() instanceof Player)
				.filter(e -> !e.isCancelled())
				.handler(e -> {
					ItemStack item = e.getCurrentItem();
					if (this.plugin.getCore().isPickaxeSupported(item)) {
						this.plugin.getEnchantsManager().handlePickaxeUnequip((Player) e.getWhoClicked(), item);
					}
				}).bindWith(this.plugin.getCore());
	}

	private void subscribeToPlayerDeathEvent() {
		Events.subscribe(PlayerDeathEvent.class, EventPriority.LOWEST)
				.handler(e -> {

					if (!this.plugin.getEnchantsConfig().isKeepPickaxesOnDeath()) {
						return;
					}

					List<ItemStack> pickaxes = e.getDrops().stream().filter(itemStack -> this.plugin.getCore().isPickaxeSupported(itemStack) &&
							this.plugin.getEnchantsManager().hasEnchants(itemStack)).collect(Collectors.toList());
					e.getDrops().removeAll(pickaxes);

					this.plugin.getRespawnManager().addRespawnItems(e.getEntity(), pickaxes);

					if (!pickaxes.isEmpty()) {
						this.plugin.getCore().debug("Removed " + e.getEntity().getName() + "'s pickaxes from drops (" + pickaxes.size() + "). Will be given back on respawn.", this.plugin);
					} else {
						this.plugin.getCore().debug("No Pickaxes found for player " + e.getEntity().getName() + " (PlayerDeathEvent)", this.plugin);
					}

				}).bindWith(this.plugin.getCore());
	}

	private Optional<IWrappedFlag<WrappedState>> getWGFlag() {
		return this.plugin.getCore().getWorldGuardWrapper().getFlag(Constants.ENCHANTS_WG_FLAG_NAME, WrappedState.class);
	}
}
