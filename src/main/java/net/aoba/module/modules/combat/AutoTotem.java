package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.PlayerHealthEvent;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.events.TickEvent; // Đổi từ UpdateEvent thành TickEvent
import net.aoba.event.listeners.PlayerHealthListener;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.event.listeners.TickListener;   // Đổi từ UpdateListener thành TickListener
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.aoba.settings.types.FloatSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

import java.util.LinkedList;
import java.util.Queue;

// Sử dụng TickListener thay vì UpdateListener
public class AutoTotem extends Module implements PlayerHealthListener, ReceivePacketListener, TickListener {

	private final FloatSetting healthTrigger = FloatSetting.builder().id("autototem_health").displayName("Health")
			.description("The health at which the totem will be placed into your hand.").defaultValue(6.0f)
			.minValue(1.0f).maxValue(20.0f).step(1.0f).build();

	private final FloatSetting crystalRadiusTrigger = FloatSetting.builder().id("autototem_crystal_radius")
			.displayName("Crystal Radius")
			.description("The radius at which a placed end crystal will trigger autototem.").defaultValue(6.0f)
			.minValue(1.0f).maxValue(10.0f).step(1.0f).build();

	private final FloatSetting delaySetting = FloatSetting.builder().id("autototem_delay").displayName("Action Delay")
			.description("Ticks to wait between inventory actions").defaultValue(2.0f)
			.minValue(0.0f).maxValue(10.0f).step(1.0f).build();

	private final Queue<Runnable> taskQueue = new LinkedList<>();
	private int ticksWaited = 0;
	private boolean isSwapping = false;

	public AutoTotem() {
		super("AutoTotem");
		setCategory(Category.of("Combat"));
		setDescription("Automatically replaces totems like a real player.");
		addSetting(healthTrigger);
		addSetting(crystalRadiusTrigger);
		addSetting(delaySetting);
	}

	@Override
	public void onDisable() {
		Aoba.getInstance().eventManager.RemoveListener(PlayerHealthListener.class, this);
		Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
		Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
		taskQueue.clear();
		isSwapping = false;
	}

	@Override
	public void onEnable() {
		Aoba.getInstance().eventManager.AddListener(PlayerHealthListener.class, this);
		Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
		Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
		taskQueue.clear();
		isSwapping = false;
	}

    // Sửa hàm onUpdate thành onTick cho đúng Interface
	@Override
	public void onTick(TickEvent event) { 
		if (taskQueue.isEmpty()) return;

		if (ticksWaited < delaySetting.getValue()) {
			ticksWaited++;
			return;
		}

		Runnable task = taskQueue.poll();
		if (task != null) task.run();
		ticksWaited = 0;
	}

	@Override
	public void onHealthChanged(PlayerHealthEvent readPacketEvent) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || (mc.screen instanceof ContainerScreen && !(mc.screen instanceof InventoryScreen))) return;
		if (mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) return;

		if (readPacketEvent.getHealth() <= healthTrigger.getValue() && !isSwapping) {
			QueueTotemSwap();
		}
	}

	@Override
	public void onReceivePacket(ReceivePacketEvent readPacketEvent) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;

		if (readPacketEvent.GetPacket() instanceof ClientboundAddEntityPacket spawnEntityPacket) {
			if (spawnEntityPacket.getType() == EntityType.END_CRYSTAL) {
				if (mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) return;
				if (mc.player.distanceToSqr(spawnEntityPacket.getX(), spawnEntityPacket.getY(),
						spawnEntityPacket.getZ()) < Math.pow(crystalRadiusTrigger.getValue(), 2)) {
					if (!isSwapping) QueueTotemSwap();
				}
			}
		}
	}

	private void QueueTotemSwap() {
		Minecraft mc = Minecraft.getInstance();
		Inventory inventory = mc.player.getInventory();
		int totemSlot = -1;

		for (int i = 0; i <= 36; i++) {
			if (inventory.getItem(i).getItem() == Items.TOTEM_OF_UNDYING) {
				totemSlot = i;
				break;
			}
		}

		if (totemSlot != -1) {
			isSwapping = true;
			final int finalTotemSlot = totemSlot;

			taskQueue.add(() -> {
				// FIX LỖI PRIVATE: Sử dụng biến mc.player.getInventory().selected (nếu là Official) 
                // hoặc sử dụng field cụ thể của Inventory. 
                // Trong hầu hết client Aoba/Fabric Official Mapping, nó là 'selected' nhưng cần truy cập đúng.
				mc.player.getInventory().selected = 8; 
				mc.setScreen(new InventoryScreen(mc.player));
			});

			taskQueue.add(() -> {
				mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, finalTotemSlot, 40, ClickType.SWAP, mc.player);
			});

			taskQueue.add(() -> {
				mc.player.closeContainer();
				mc.setScreen(null);
				isSwapping = false;
			});
		}
	}
}
