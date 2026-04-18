package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.PlayerHealthEvent;
import net.aoba.event.events.ReceivePacketEvent;
// Lưu ý: Tên event Update/Tick này có thể khác tùy vào base client của bạn
// Nếu client của bạn dùng TickEvent, hãy đổi UpdateListener thành TickListener
import net.aoba.event.events.UpdateEvent; 
import net.aoba.event.listeners.PlayerHealthListener;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.event.listeners.UpdateListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.aoba.settings.types.FloatSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedList;
import java.util.Queue;

public class AutoTotem extends Module implements PlayerHealthListener, ReceivePacketListener, UpdateListener {

	private final FloatSetting healthTrigger = FloatSetting.builder().id("autototem_health").displayName("Health")
			.description("The health at which the totem will be placed into your hand.").defaultValue(6.0f)
			.minValue(1.0f).maxValue(20.0f).step(1.0f).build();

	private final FloatSetting crystalRadiusTrigger = FloatSetting.builder().id("autototem_crystal_radius")
			.displayName("Crystal Radius")
			.description("The radius at which a placed end crystal will trigger autototem.").defaultValue(6.0f)
			.minValue(1.0f).maxValue(10.0f).step(1.0f).build();

	private final BooleanSetting mainHand = BooleanSetting.builder().id("autototem_mainhand").displayName("Mainhand")
			.description("Places totem in main hand instead of off-hand").defaultValue(false).build();

	// Thêm setting chỉnh Delay (đơn vị: tick, 20 tick = 1 giây)
	private final FloatSetting delaySetting = FloatSetting.builder().id("autototem_delay").displayName("Action Delay")
			.description("Ticks to wait between inventory actions (Legit delay)").defaultValue(2.0f)
			.minValue(0.0f).maxValue(10.0f).step(1.0f).build();

	// Biến dùng cho hệ thống Legit Delay
	private final Queue<Runnable> taskQueue = new LinkedList<>();
	private int ticksWaited = 0;
	private boolean isSwapping = false; // Ngăn chặn spam trigger khi đang tráo đồ

	public AutoTotem() {
		super("AutoTotem");

		setCategory(Category.of("Combat"));
		setDescription("Automatically replaces totems like a real player.");

		addSetting(healthTrigger);
		addSetting(crystalRadiusTrigger);
		addSetting(mainHand);
		addSetting(delaySetting); // Thêm setting delay vào menu
	}

	@Override
	public void onDisable() {
		Aoba.getInstance().eventManager.RemoveListener(PlayerHealthListener.class, this);
		Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
		Aoba.getInstance().eventManager.RemoveListener(UpdateListener.class, this);
		taskQueue.clear();
		isSwapping = false;
	}

	@Override
	public void onEnable() {
		Aoba.getInstance().eventManager.AddListener(PlayerHealthListener.class, this);
		Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
		Aoba.getInstance().eventManager.AddListener(UpdateListener.class, this);
		taskQueue.clear();
		isSwapping = false;
	}

	@Override
	public void onToggle() {
	}

	// Xử lý hàng đợi hành động mỗi tick
	@Override
	public void onUpdate(UpdateEvent event) { // (Đổi thành onTick nếu client bạn dùng TickEvent)
		if (taskQueue.isEmpty()) return;

		if (ticksWaited < delaySetting.getValue()) {
			ticksWaited++;
			return;
		}

		// Đã đủ thời gian delay, chạy hành động tiếp theo
		Runnable task = taskQueue.poll();
		if (task != null) {
			task.run();
		}
		ticksWaited = 0; // Reset bộ đếm cho hành động sau
	}

	@Override
	public void onHealthChanged(PlayerHealthEvent readPacketEvent) {
		Minecraft mc = Minecraft.getInstance();

		if (mc.screen instanceof ContainerScreen && !(mc.screen instanceof InventoryScreen))
			return;

		if (mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING)
			return;

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
				if (mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING)
					return;

				if (mc.player.distanceToSqr(spawnEntityPacket.getX(), spawnEntityPacket.getY(),
						spawnEntityPacket.getZ()) < Math.pow(crystalRadiusTrigger.getValue(), 2)) {
					if (!isSwapping) {
						QueueTotemSwap();
					}
				}
			}
		}
	}

	private void QueueTotemSwap() {
		Minecraft mc = Minecraft.getInstance();
		Inventory inventory = mc.player.getInventory();
		int totemSlot = -1;

		// 1. Tìm Totem trong kho đồ
		for (int i = 0; i <= 36; i++) {
			if (inventory.getItem(i).getItem() == Items.TOTEM_OF_UNDYING) {
				totemSlot = i;
				break;
			}
		}

		if (totemSlot != -1) {
			isSwapping = true;
			final int finalTotemSlot = totemSlot;

			// Hành động 1: Cuộn chuột sang slot 8 và Mở túi đồ (Bấm E)
			taskQueue.add(() -> {
				inventory.selected = 8;
				mc.setScreen(new InventoryScreen(mc.player));
			});

			// Hành động 2: Di chuột vào totem bấm F (Swap sang Offhand)
			taskQueue.add(() -> {
				mc.gameMode.handleInventoryMouseClick(
						mc.player.containerMenu.containerId,
						finalTotemSlot, 
						40, 
						ClickType.SWAP, 
						mc.player
				);
			});

			// Hành động 3 (Tùy chọn): Kéo totem dự phòng xuống slot 8 nếu hết
			if (inventory.getItem(8).getItem() != Items.TOTEM_OF_UNDYING) {
				int backupSlot = -1;
				for (int i = 9; i <= 36; i++) {
					if (i == finalTotemSlot) continue; // Bỏ qua cái vừa lấy
					if (inventory.getItem(i).getItem() == Items.TOTEM_OF_UNDYING) {
						backupSlot = i;
						break;
					}
				}

				if (backupSlot != -1) {
					final int finalBackupSlot = backupSlot;
					taskQueue.add(() -> {
						mc.gameMode.handleInventoryMouseClick(
								mc.player.containerMenu.containerId,
								finalBackupSlot, 
								8, 
								ClickType.SWAP, 
								mc.player
						);
					});
				}
			}

			// Hành động 4: Đóng túi đồ (Bấm ESC) và mở khóa để trigger lần sau
			taskQueue.add(() -> {
				mc.player.closeContainer();
				// Đưa màn hình về null để thực sự tắt GUI (tránh kẹt chuột)
				mc.setScreen(null); 
				isSwapping = false;
			});
		}
	}
}
