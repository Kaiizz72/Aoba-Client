package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.events.TickEvent;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.event.listeners.TickListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final BooleanSetting autoEsc = BooleanSetting.builder()
            .id("autototem_autoesc")
            .displayName("Auto ESC")
            .description("Tự động đóng túi sau khi làm xong")
            .defaultValue(true)
            .build();

    private boolean isRefilling = false;
    private long lastTime = 0;
    
    private enum Step {
        NONE,
        STEP_1_OPEN_INV,        // Mở E
        STEP_2_PICKUP_TOTEM,    // Cầm Totem lên
        STEP_3_PLACE_OFFHAND,   // Đặt vào tay trái
        STEP_4_REFILL_HOTBAR,   // Bù hàng vào Hotbar
        STEP_5_CLOSE_INV        // Đóng E
    }
    private Step currentStep = Step.NONE;

    // --- CẤU HÌNH ĐỘ TRỄ (Delay) ---
    // 150ms = 3 ticks game. Đây là tốc độ "Vàng".
    // Đủ chậm để server xử lý chuẩn xác 100%.
    // Đủ nhanh để không bị chết.
    // Đủ mượt để mắt bạn nhìn thấy rõ ràng từng bước, không bị loạn.
    private final long DELAY_COMMON = 150; 

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Visual Stable: Mở E -> Thao tác chuẩn -> Đóng E");
        addSetting(autoEsc);
    }

    @Override public void onToggle() {}

    @Override
    public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        reset();
    }

    @Override
    public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        reset();
    }

    private void reset() {
        currentStep = Step.NONE;
        isRefilling = false;
    }

    // --- PHÁT HIỆN POP ---
    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { 
                if (packet.getEntity(mc.world) == mc.player) {
                    if (!isRefilling) { 
                        isRefilling = true;
                        currentStep = Step.STEP_1_OPEN_INV;
                        lastTime = System.currentTimeMillis();
                        // Không gửi chat debug để đỡ rối mắt
                    }
                }
            }
        }
    }

    // --- XỬ LÝ ---
    @Override
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || !isRefilling) return;

        long now = System.currentTimeMillis();

        switch (currentStep) {
            case STEP_1_OPEN_INV:
                // Bước 1: Mở túi đồ
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                
                // Chờ GUI load xong hẳn
                if (now - lastTime >= DELAY_COMMON) {
                    currentStep = Step.STEP_2_PICKUP_TOTEM;
                    lastTime = now;
                }
                break;

            case STEP_2_PICKUP_TOTEM:
                // Bước 2: Cầm Totem từ Slot 8 lên
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_COMMON) {
                        int syncId = mc.player.currentScreenHandler.syncId;
                        // Click PICKUP vào Slot 44 (Hotbar 8)
                        mc.interactionManager.clickSlot(syncId, 44, 0, SlotActionType.PICKUP, mc.player);
                        
                        currentStep = Step.STEP_3_PLACE_OFFHAND;
                        lastTime = now;
                    }
                } else {
                    // Nếu lỡ tay đóng túi thì mở lại
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                break;

            case STEP_3_PLACE_OFFHAND:
                // Bước 3: Đặt vào Offhand
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_COMMON) {
                        int syncId = mc.player.currentScreenHandler.syncId;
                        // Click PICKUP vào Slot 45 (Offhand)
                        mc.interactionManager.clickSlot(syncId, 45, 0, SlotActionType.PICKUP, mc.player);
                        
                        // Xong phần cứu mạng, chuyển sang phần nạp đạn
                        currentStep = Step.STEP_4_REFILL_HOTBAR;
                        lastTime = now;
                    }
                }
                break;

            case STEP_4_REFILL_HOTBAR:
                // Bước 4: Refill lại Slot 8
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_COMMON) {
                        boolean success = refillSlot8(); 
                        
                        if (autoEsc.getValue()) {
                            currentStep = Step.STEP_5_CLOSE_INV;
                        } else {
                            reset(); // Giữ nguyên túi mở
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_5_CLOSE_INV:
                // Bước 5: Đóng túi nhẹ nhàng
                if (now - lastTime >= DELAY_COMMON) {
                    mc.setScreen(null);
                    mc.setScreen(null); // Double check đóng
                    reset();
                }
                break;
                
            default:
                break;
        }
    }

    @Override
    public void onTick(TickEvent.Post event) {}

    // --- HELPER ---

    private boolean refillSlot8() {
        PlayerInventory inv = mc.player.getInventory();
        int sourceSlot = -1;
        
        // Tìm totem từ inventory chính (9 -> 35)
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                sourceSlot = i;
                break;
            }
        }

        if (sourceSlot != -1) {
            int syncId = mc.player.currentScreenHandler.syncId;
            // Dùng SWAP để ném thẳng vào slot 8 (ID 44)
            mc.interactionManager.clickSlot(syncId, sourceSlot, 8, SlotActionType.SWAP, mc.player);
            return true;
        }
        return false;
    }
}
