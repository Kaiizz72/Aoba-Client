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
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket; // Gói tin chọn slot
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW; // Thư viện chuột
import java.lang.reflect.Field; // Thư viện Reflection

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
        STEP_1_SELECT_SLOT_8,   // Cuộn sang Slot 8
        STEP_2_OPEN_INV,        // Mở E + Giấu chuột
        STEP_3_PICKUP_TOTEM,    // Cầm Totem lên
        STEP_4_PLACE_OFFHAND,   // Đặt vào tay trái
        STEP_5_REFILL_HOTBAR,   // Bù hàng vào Hotbar
        STEP_6_CLOSE_INV        // Đóng E + Hiện chuột
    }
    private Step currentStep = Step.NONE;

    // Delay: 100ms là tốc độ đẹp, mượt mà chuẩn cơm mẹ nấu
    private final long DELAY_COMMON = 100; 

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Cuộn Slot 8 -> Mở E (Giấu chuột) -> Refill -> Đóng E");
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
        restoreCursor(); // Đảm bảo trả lại chuột khi tắt module
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        reset();
    }

    private void reset() {
        currentStep = Step.NONE;
        isRefilling = false;
        restoreCursor();
    }

    // --- QUẢN LÝ CHUỘT ---
    private void hideCursor() {
        long windowHandle = mc.getWindow().getHandle();
        GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
    }

    private void restoreCursor() {
        long windowHandle = mc.getWindow().getHandle();
        GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
    }

    // --- LOGIC CHÍNH ---
    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { 
                if (packet.getEntity(mc.world) == mc.player) {
                    if (!isRefilling) { 
                        isRefilling = true;
                        // Bắt đầu bằng việc chọn Slot 8 trước
                        currentStep = Step.STEP_1_SELECT_SLOT_8;
                        lastTime = System.currentTimeMillis();
                    }
                }
            }
        }
    }

    @Override
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || !isRefilling) return;

        long now = System.currentTimeMillis();

        switch (currentStep) {
            case STEP_1_SELECT_SLOT_8:
                // Bước 1: Cuộn slot 8
                if (now - lastTime >= 50) {
                    setSlotVisual(8); // Chỉnh visual trên màn hình
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8)); // Báo server
                    }
                    currentStep = Step.STEP_2_OPEN_INV;
                    lastTime = now;
                }
                break;

            case STEP_2_OPEN_INV:
                // Bước 2: Mở túi + Giấu chuột
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                hideCursor(); // Tàng hình chuột ngay
                
                if (now - lastTime >= DELAY_COMMON) {
                    currentStep = Step.STEP_3_PICKUP_TOTEM;
                    lastTime = now;
                }
                break;

            case STEP_3_PICKUP_TOTEM:
                // Bước 3: Cầm Totem từ Slot 8 lên
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_COMMON) {
                        int syncId = mc.player.currentScreenHandler.syncId;
                        // Slot 44 = Hotbar slot 8
                        mc.interactionManager.clickSlot(syncId, 44, 0, SlotActionType.PICKUP, mc.player);
                        
                        currentStep = Step.STEP_4_PLACE_OFFHAND;
                        lastTime = now;
                    }
                } else {
                    mc.setScreen(new InventoryScreen(mc.player));
                    hideCursor();
                }
                break;

            case STEP_4_PLACE_OFFHAND:
                // Bước 4: Thả vào Offhand
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_COMMON) {
                        int syncId = mc.player.currentScreenHandler.syncId;
                        // Slot 45 = Offhand
                        mc.interactionManager.clickSlot(syncId, 45, 0, SlotActionType.PICKUP, mc.player);
                        
                        currentStep = Step.STEP_5_REFILL_HOTBAR;
                        lastTime = now;
                    }
                }
                break;

            case STEP_5_REFILL_HOTBAR:
                // Bước 5: Bù hàng
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_COMMON) {
                        boolean success = refillSlot8(); 
                        
                        if (autoEsc.getValue()) {
                            currentStep = Step.STEP_6_CLOSE_INV;
                        } else {
                            restoreCursor();
                            reset(); 
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_6_CLOSE_INV:
                // Bước 6: Đóng túi + Trả lại chuột
                if (now - lastTime >= DELAY_COMMON) {
                    mc.setScreen(null);
                    restoreCursor(); 
                    reset();
                }
                break;
                
            default:
                break;
        }
    }

    @Override
    public void onTick(TickEvent.Post event) {}

    // --- HELPER FIX VISUAL SLOT ---
    private void setSlotVisual(int slotIndex) {
        PlayerInventory inv = mc.player.getInventory();
        boolean success = false;
        try {
            Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true);
            f.setInt(inv, slotIndex);
            success = true;
        } catch (Exception ignored) {}

        if (!success) {
            try {
                Field f = PlayerInventory.class.getDeclaredField("currentItem");
                f.setAccessible(true);
                f.setInt(inv, slotIndex);
            } catch (Exception ignored) {}
        }
    }

    private boolean refillSlot8() {
        PlayerInventory inv = mc.player.getInventory();
        int sourceSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                sourceSlot = i;
                break;
            }
        }
        if (sourceSlot != -1) {
            int syncId = mc.player.currentScreenHandler.syncId;
            mc.interactionManager.clickSlot(syncId, sourceSlot, 8, SlotActionType.SWAP, mc.player);
            return true;
        }
        return false;
    }
}
