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
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket; // Gói tin hành động
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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

    // --- CẤU HÌNH DELAY (AN TOÀN) ---
    // Nếu mạng ngon, bạn có thể giảm xuống 150 hoặc 100
    private final long DELAY_SLOT = 50;    // Chọn slot nhanh
    private final long DELAY_SWAP = 250;   // Quan trọng: Chờ server nhận slot 8 xong mới Swap
    private final long DELAY_OPEN = 200;   // Chờ Swap xong mới mở túi
    private final long DELAY_ACTION = 200; // Các thao tác trong túi

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
        if (mc.player != null) {
            mc.player.sendMessage(Text.of("§b[AutoTotem] " + msg), true);
        }
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { 
                if (packet.getEntity(mc.world) == mc.player) {
                    if (!isRefilling) { 
                        // debug("§cPop! Bắt đầu quy trình...");
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
                // Bước 1: Cuộn chuột & Báo server
                if (now - lastTime >= DELAY_SLOT) { 
                    setSlotVisual(8); // Chỉnh hình ảnh Client
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8)); // Báo Server
                    }
                    
                    currentStep = Step.STEP_2_PRESS_F;
                    lastTime = now;
                }
                break;

            case STEP_2_PRESS_F:
                // Bước 2: Gửi gói tin SWAP (Giả lập phím F chuẩn)
                // Phải đợi DELAY_SWAP để chắc chắn Server đã biết mình đang cầm slot 8
                if (now - lastTime >= DELAY_SWAP) {
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, 
                            BlockPos.ORIGIN, 
                            Direction.DOWN
                        ));
                    }
                    
                    currentStep = Step.STEP_3_OPEN_INV;
                    lastTime = now;
                }
                break;

            case STEP_3_OPEN_INV:
                // Bước 3: Mở túi
                if (now - lastTime >= DELAY_OPEN) {
                    // Kiểm tra xem đã thực sự swap chưa (Slot 8 phải trống hoặc khác totem cũ)
                    if (mc.player.getInventory().getStack(8).isEmpty()) {
                        mc.setScreen(new InventoryScreen(mc.player));
                        currentStep = Step.STEP_4_REFILL;
                    } else {
                        // Nếu vẫn còn đồ ở slot 8 -> Swap thất bại do lag -> Thử lại hoặc reset
                        // debug("§eLỗi: Chưa swap được! Reset.");
                        reset(); 
                    }
                    lastTime = now;
                }
                break;

            case STEP_4_REFILL:
                // Bước 4: Refill
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_ACTION) { 
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
                if (now - lastTime >= DELAY_ACTION) {
                    mc.setScreen(null);
                    // debug("§aXong!");
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
                success = true;
            } catch (Exception ignored) {}
        }
        
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
