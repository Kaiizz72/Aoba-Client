package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.events.TickEvent;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.event.listeners.TickListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.aoba.settings.types.IntegerSetting; // Import thanh kéo số
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    // --- CÀI ĐẶT (SETTINGS) ---
    
    // Thanh kéo tốc độ (Delay)
    private final IntegerSetting delayMs = IntegerSetting.builder()
            .id("autototem_delay")
            .displayName("Tốc độ (ms)")
            .description("Thời gian nghỉ giữa các bước (Thấp = Nhanh)")
            .defaultValue(100) // Mặc định 100ms
            .min(0)            // Min 0ms (Siêu tốc)
            .max(500)          // Max 500ms
            .build();

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
        STEP_1_SELECT_SLOT_8,   // Chọn Slot 8
        STEP_2_OPEN_INV,        // Mở E
        STEP_3_AIM_AND_SWAP_F,  // Aim chuột vào Totem + Ấn F
        STEP_4_REFILL_HOTBAR,   // Bù hàng
        STEP_5_CLOSE_INV        // Đóng E
    }
    private Step currentStep = Step.NONE;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Mở E -> Aim chuột vào Totem -> Ấn F -> Refill");
        addSetting(delayMs); // Thêm thanh kéo vào Menu
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

    // --- LOGIC AIM CHUỘT (Humanized) ---
    private void aimAtSlot(int slotId) {
        if (!(mc.currentScreen instanceof InventoryScreen)) return;
        InventoryScreen screen = (InventoryScreen) mc.currentScreen;
        if (mc.player == null || mc.player.currentScreenHandler == null) return;

        try {
            Slot slot = mc.player.currentScreenHandler.slots.get(slotId);
            
            int guiWidth = 176;
            int guiHeight = 166;
            int winWidth = mc.getWindow().getScaledWidth();
            int winHeight = mc.getWindow().getScaledHeight();
            int guiLeft = (winWidth - guiWidth) / 2;
            int guiTop = (winHeight - guiHeight) / 2;
            
            // Random Jitter: Lệch 1 chút cho giống người thật
            int jitterX = ThreadLocalRandom.current().nextInt(-3, 4);
            int jitterY = ThreadLocalRandom.current().nextInt(-3, 4);

            int targetX = guiLeft + slot.x + 8 + jitterX;
            int targetY = guiTop + slot.y + 8 + jitterY;
            
            double scaleFactor = mc.getWindow().getScaleFactor();
            GLFW.glfwSetCursorPos(
                mc.getWindow().getHandle(), 
                targetX * scaleFactor, 
                targetY * scaleFactor
            );
        } catch (Exception e) {}
    }

    // --- EVENT PACKET ---
    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { 
                if (packet.getEntity(mc.world) == mc.player) {
                    if (!isRefilling) { 
                        isRefilling = true;
                        currentStep = Step.STEP_1_SELECT_SLOT_8;
                        lastTime = System.currentTimeMillis();
                    }
                }
            }
        }
    }

    // --- EVENT TICK (LOGIC CHÍNH) ---
    @Override
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || !isRefilling) return;

        long now = System.currentTimeMillis();
        // Lấy giá trị từ thanh kéo
        long currentDelay = delayMs.getValue(); 

        switch (currentStep) {
            case STEP_1_SELECT_SLOT_8:
                // Bước 1: Cuộn Slot 8
                if (now - lastTime >= 50) { // Delay tối thiểu để server nhận
                    setSlotVisual(8);
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8));
                    }
                    currentStep = Step.STEP_2_OPEN_INV;
                    lastTime = now;
                }
                break;

            case STEP_2_OPEN_INV:
                // Bước 2: Mở túi
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                
                if (now - lastTime >= currentDelay) {
                    currentStep = Step.STEP_3_AIM_AND_SWAP_F;
                    lastTime = now;
                }
                break;

            case STEP_3_AIM_AND_SWAP_F:
                // Bước 3: Aim vào Totem + Ấn F
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= currentDelay) {
                        int syncId = mc.player.currentScreenHandler.syncId;
                        
                        // 1. Aim chuột vào Slot 44 (Totem)
                        aimAtSlot(44);
                        
                        // 2. Thực hiện lệnh SWAP (F)
                        // Button 40 trong lệnh SWAP có nghĩa là "Swap với Offhand"
                        // Đây chính là hành động đưa chuột vào đồ rồi ấn F
                        mc.interactionManager.clickSlot(syncId, 44, 40, SlotActionType.SWAP, mc.player);
                        
                        currentStep = Step.STEP_4_REFILL_HOTBAR;
                        lastTime = now;
                    }
                }
                break;

            case STEP_4_REFILL_HOTBAR:
                // Bước 4: Refill (Vì Slot 8 giờ đang chứa cái khiên/đồ cũ của tay trái)
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= currentDelay) {
                        // Aim chuột vào kho cho ngầu (Optional)
                        int totemSlot = findTotemSlot();
                        if (totemSlot != -1) aimAtSlot(totemSlot);

                        boolean success = refillSlot8(); 
                        
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
                if (now - lastTime >= currentDelay) {
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

    private int findTotemSlot() {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) return i;
        }
        return -1;
    }

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
        int sourceSlot = findTotemSlot();
        if (sourceSlot != -1) {
            int syncId = mc.player.currentScreenHandler.syncId;
            mc.interactionManager.clickSlot(syncId, sourceSlot, 8, SlotActionType.SWAP, mc.player);
            return true;
        }
        return false;
    }
}
