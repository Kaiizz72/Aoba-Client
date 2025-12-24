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
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text; // Dùng để chat
import java.lang.reflect.Field;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final BooleanSetting autoEsc = BooleanSetting.builder()
            .id("autototem_autoesc")
            .displayName("Auto ESC")
            .description("Tự động đóng túi đồ sau khi refill")
            .defaultValue(true)
            .build();

    private boolean isRefilling = false;
    private long lastTime = 0;
    
    private enum Step {
        NONE,
        STEP_1_SELECT_SLOT_8,   // Chọn slot 8
        STEP_2_PRESS_F,         // Ấn F (Swap)
        STEP_3_OPEN_INV,        // Mở túi
        STEP_4_REFILL,          // Lấy hàng
        STEP_5_CLOSE_INV        // Đóng túi
    }
    private Step currentStep = Step.NONE;

    // Tăng delay lên một chút để mắt thường nhìn thấy kịp animation
    private final long DELAY_COMMON = 100; // 100ms mỗi bước

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Totem Pop -> Chọn Slot 8 -> Ấn F -> Mở túi -> Refill -> Đóng túi");
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

    private void debug(String msg) {
        // Gửi tin nhắn client để biết code đang chạy tới đâu (Xóa dòng này nếu thấy phiền)
        if (mc.player != null) {
            mc.player.sendMessage(Text.of("§7[AutoTotem] " + msg), true);
        }
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { 
                if (packet.getEntity(mc.world) == mc.player) {
                    if (!isRefilling) { // Chỉ kích hoạt nếu chưa chạy
                        debug("§cDetected Pop! Starting sequence...");
                        isRefilling = true;
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
                // Bước 1: Cuộn chuột xuống slot cuối
                if (now - lastTime >= 50) { // Nhanh
                    setSlotVisual(8); // Set hình ảnh
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8)); // Báo server
                    }
                    
                    currentStep = Step.STEP_2_PRESS_F;
                    lastTime = now;
                }
                break;

            case STEP_2_PRESS_F:
                // Bước 2: Giả lập ấn F (Swap)
                if (now - lastTime >= DELAY_COMMON) {
                    // Trong container của người chơi (không mở túi), slot 45 là Offhand
                    // Click vào slot 45 với button 8 (Hotbar slot 9) dùng kiểu SWAP
                    // Đây chính xác là hành động ấn F
                    mc.interactionManager.clickSlot(0, 45, 8, SlotActionType.SWAP, mc.player);
                    
                    currentStep = Step.STEP_3_OPEN_INV;
                    lastTime = now;
                }
                break;

            case STEP_3_OPEN_INV:
                // Bước 3: Mở túi
                if (now - lastTime >= DELAY_COMMON) {
                    if (mc.player.getInventory().getStack(8).isEmpty()) {
                        mc.setScreen(new InventoryScreen(mc.player));
                        currentStep = Step.STEP_4_REFILL;
                    } else {
                        debug("§eSlot 8 not empty? Resetting.");
                        reset(); 
                    }
                    lastTime = now;
                }
                break;

            case STEP_4_REFILL:
                // Bước 4: Refill
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_COMMON + 50) { // Chờ load túi
                        boolean success = refillSlot8(); 
                        if (success && autoEsc.getValue()) {
                            currentStep = Step.STEP_5_CLOSE_INV;
                        } else {
                            reset();
                        }
                        lastTime = now;
                    }
                } else {
                    if (now - lastTime > 2000) reset();
                }
                break;

            case STEP_5_CLOSE_INV:
                // Bước 5: Đóng túi
                if (now - lastTime >= DELAY_COMMON) {
                    mc.setScreen(null);
                    debug("§aDone!");
                    reset();
                }
                break;
                
            default:
                break;
        }
    }

    @Override
    public void onTick(TickEvent.Post event) {}

    // --- HELPER FIX VISUAL ---

    // Hàm này thử mọi cách để set slot 8 hiển thị lên màn hình
    private void setSlotVisual(int slotIndex) {
        PlayerInventory inv = mc.player.getInventory();
        boolean success = false;
        
        // 1. Thử tên chuẩn "selectedSlot"
        try {
            Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true);
            f.setInt(inv, slotIndex);
            success = true;
        } catch (Exception ignored) {}

        // 2. Thử tên mapping cũ "currentItem"
        if (!success) {
            try {
                Field f = PlayerInventory.class.getDeclaredField("currentItem");
                f.setAccessible(true);
                f.setInt(inv, slotIndex);
                success = true;
            } catch (Exception ignored) {}
        }
        
        // 3. Thử tên obfuscated "field_7545" (Tên gốc của Minecraft)
        if (!success) {
            try {
                Field f = PlayerInventory.class.getDeclaredField("field_7545");
                f.setAccessible(true);
                f.setInt(inv, slotIndex);
                success = true;
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
