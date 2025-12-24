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
            .description("Tự động đóng túi đồ sau khi hoàn thành")
            .defaultValue(true)
            .build();

    private boolean isRefilling = false;
    private long lastTime = 0;
    
    private enum Step {
        NONE,
        STEP_1_OPEN_INV,        // Mở túi E
        STEP_2_PICKUP_SLOT_8,   // Cầm Totem ở Slot 8 lên
        STEP_3_PLACE_OFFHAND,   // Đặt vào tay trái
        STEP_4_REFILL_SLOT_8,   // Lấy hàng trong kho bù vào slot 8
        STEP_5_CLOSE_INV        // Đóng túi E
    }
    private Step currentStep = Step.NONE;

    // Delay an toàn để server kịp load túi đồ
    private final long DELAY_OPEN = 150;   // Chờ túi mở hẳn
    private final long DELAY_CLICK = 120;  // Tốc độ click chuột
    private final long DELAY_CLOSE = 150;  // Đóng túi

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Totem Pop -> Mở E -> Chuyển Slot 8 sang Offhand -> Refill -> Đóng E");
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

    // --- 1. PHÁT HIỆN NỔ TOTEM ---
    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { 
                if (packet.getEntity(mc.world) == mc.player) {
                    if (!isRefilling) { 
                        isRefilling = true;
                        // Ngay lập tức chuyển sang bước mở túi
                        currentStep = Step.STEP_1_OPEN_INV;
                        lastTime = System.currentTimeMillis();
                    }
                }
            }
        }
    }

    // --- 2. THỰC HIỆN CÔNG VIỆC ---
    @Override
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || !isRefilling) return;

        long now = System.currentTimeMillis();

        switch (currentStep) {
            case STEP_1_OPEN_INV:
                // Bước 1: Mở túi đồ (Giống ấn E)
                // Chỉ mở nếu chưa mở
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                
                if (now - lastTime >= DELAY_OPEN) {
                    currentStep = Step.STEP_2_PICKUP_SLOT_8;
                    lastTime = now;
                }
                break;

            case STEP_2_PICKUP_SLOT_8:
                // Bước 2: Click vào Slot 8 để nhấc đồ lên
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_CLICK) {
                        int syncId = mc.player.currentScreenHandler.syncId;
                        // Slot 8 (Hotbar cuối) có ID là 44
                        mc.interactionManager.clickSlot(syncId, 44, 0, SlotActionType.PICKUP, mc.player);
                        
                        currentStep = Step.STEP_3_PLACE_OFFHAND;
                        lastTime = now;
                    }
                } else {
                    // Nếu túi bị đóng đột ngột -> Mở lại hoặc Reset
                    mc.setScreen(new InventoryScreen(mc.player)); 
                }
                break;

            case STEP_3_PLACE_OFFHAND:
                // Bước 3: Click vào Offhand để thả đồ xuống
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_CLICK) {
                        int syncId = mc.player.currentScreenHandler.syncId;
                        // Offhand ID là 45
                        mc.interactionManager.clickSlot(syncId, 45, 0, SlotActionType.PICKUP, mc.player);
                        
                        currentStep = Step.STEP_4_REFILL_SLOT_8;
                        lastTime = now;
                    }
                }
                break;

            case STEP_4_REFILL_SLOT_8:
                // Bước 4: Tìm Totem trong kho bù vào Slot 8 (đang trống vì vừa bốc đi)
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_CLICK) {
                        refillSlot8(); // Hàm này sẽ tìm và ném totem vào slot 8
                        
                        if (autoEsc.getValue()) {
                            currentStep = Step.STEP_5_CLOSE_INV;
                        } else {
                            reset();
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_5_CLOSE_INV:
                // Bước 5: Đóng túi
                if (now - lastTime >= DELAY_CLOSE) {
                    mc.setScreen(null);
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
        
        // Tìm totem trong kho (9 -> 35)
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                sourceSlot = i;
                break;
            }
        }

        if (sourceSlot != -1) {
            int syncId = mc.player.currentScreenHandler.syncId;
            // Dùng SWAP: Click vào totem trong kho, ấn nút số 9 (Hotbar 8)
            // Totem sẽ bay vào Slot 8 (ID 44)
            mc.interactionManager.clickSlot(syncId, sourceSlot, 8, SlotActionType.SWAP, mc.player);
            return true;
        }
        return false;
    }
}
