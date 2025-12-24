package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.events.TickEvent;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.event.listeners.TickListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.aoba.settings.types.IntegerSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack; // Thêm cái này để check item
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

    private final IntegerSetting delayMs = IntegerSetting.builder()
            .id("autototem_delay")
            .displayName("Tốc độ (ms)")
            .description("Delay hành động")
            .defaultValue(100)
            .build();

    private final BooleanSetting autoEsc = BooleanSetting.builder()
            .id("autototem_autoesc")
            .displayName("Auto ESC")
            .description("Tự đóng túi")
            .defaultValue(true)
            .build();

    private boolean isWorking = false;
    private long lastTime = 0;
    
    // Lưu vị trí Totem tìm được để chuột bay tới đó
    private int targetTotemSlot = -1;

    private enum Step {
        NONE,
        STEP_1_SELECT_SLOT,
        STEP_2_OPEN_INV,
        STEP_3_FIND_AND_AIM,  // Bước mới: Tìm Totem rồi mới Aim
        STEP_4_SWAP_OFFHAND,
        STEP_5_CLOSE
    }
    private Step currentStep = Step.NONE;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Smart Aim: Chỉ aim vào đúng ô có Totem");
        addSetting(delayMs);
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
        isWorking = false;
        targetTotemSlot = -1;
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { 
                if (packet.getEntity(mc.world) == mc.player) {
                    if (!isWorking) { 
                        isWorking = true;
                        currentStep = Step.STEP_1_SELECT_SLOT;
                        lastTime = System.currentTimeMillis();
                    }
                }
            }
        }
    }

    @Override
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || !isWorking) return;

        long now = System.currentTimeMillis();
        long delay = Math.max(0, delayMs.getValue());

        switch (currentStep) {
            case STEP_1_SELECT_SLOT:
                if (now - lastTime >= 50) {
                    forceSetSlot(8); 
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8));
                    }
                    currentStep = Step.STEP_2_OPEN_INV;
                    lastTime = now;
                }
                break;

            case STEP_2_OPEN_INV:
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                
                if (now - lastTime >= delay) {
                    currentStep = Step.STEP_3_FIND_AND_AIM;
                    lastTime = now;
                }
                break;

            case STEP_3_FIND_AND_AIM:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= delay) {
                        // --- LOGIC MỚI: TÌM CHÍNH XÁC TOTEM ---
                        targetTotemSlot = findAnyTotemSlot(); // Tìm vị trí totem

                        if (targetTotemSlot != -1) {
                            // Nếu thấy Totem -> Aim vào đó
                            aimAtSlot(targetTotemSlot);
                            currentStep = Step.STEP_4_SWAP_OFFHAND;
                        } else {
                            // Nếu không thấy Totem nào -> Hủy luôn (Không aim lung tung)
                            reset();
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_4_SWAP_OFFHAND:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= delay) {
                        // Vì bước 3 đã aim chuẩn rồi, giờ chỉ cần nhấn Swap (F)
                        // targetTotemSlot là slot ta vừa tìm thấy ở bước 3
                        if (targetTotemSlot != -1) {
                            int syncId = mc.player.currentScreenHandler.syncId;
                            // Swap Totem ở vị trí targetTotemSlot vào Offhand
                            mc.interactionManager.clickSlot(syncId, targetTotemSlot, 40, SlotActionType.SWAP, mc.player);
                        }
                        
                        if (autoEsc.getValue()) {
                            currentStep = Step.STEP_5_CLOSE;
                        } else {
                            reset(); 
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_5_CLOSE:
                if (now - lastTime >= delay) {
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

    private void forceSetSlot(int slotIndex) {
        PlayerInventory inv = mc.player.getInventory();
        try {
            Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true);
            f.setInt(inv, slotIndex);
        } catch (Exception e1) {
            try {
                Field f = PlayerInventory.class.getDeclaredField("currentItem");
                f.setAccessible(true);
                f.setInt(inv, slotIndex);
            } catch (Exception e2) {
                try {
                     Field f = PlayerInventory.class.getDeclaredField("field_7545");
                     f.setAccessible(true);
                     f.setInt(inv, slotIndex);
                } catch (Exception ignored) {}
            }
        }
    }

    private void aimAtSlot(int slotId) {
        if (!(mc.currentScreen instanceof InventoryScreen)) return;
        try {
            InventoryScreen screen = (InventoryScreen) mc.currentScreen;
            Slot slot = mc.player.currentScreenHandler.slots.get(slotId);
            
            // --- CHECK AN TOÀN CUỐI CÙNG ---
            // Trước khi di chuyển chuột, kiểm tra lại 1 lần nữa xem slot này có đúng là Totem không
            // Nếu item trong slot không phải Totem, return luôn -> Chuột đứng im.
            if (slot.getStack().getItem() != Items.TOTEM_OF_UNDYING) {
                return; 
            }

            int guiLeft = (screen.width - 176) / 2;
            int guiTop = (screen.height - 166) / 2;
            int targetX = guiLeft + slot.x + 8;
            int targetY = guiTop + slot.y + 8;

            int jitterX = ThreadLocalRandom.current().nextInt(-3, 4);
            int jitterY = ThreadLocalRandom.current().nextInt(-3, 4);
            double scale = mc.getWindow().getScaleFactor();
            
            GLFW.glfwSetCursorPos(
                mc.getWindow().getHandle(), 
                (targetX + jitterX) * scale, 
                (targetY + jitterY) * scale
            );
        } catch (Exception ignored) {}
    }

    // Hàm này quét TẤT CẢ các slot (kể cả Hotbar và Kho) để tìm Totem
    private int findAnyTotemSlot() {
        // Slot Container ID:
        // 9 -> 35: Kho chính
        // 36 -> 44: Hotbar
        
        // Ưu tiên tìm ở Hotbar trước (36-44) để thao tác nhanh hơn
        for (int i = 36; i <= 44; i++) {
             if (checkSlot(i)) return i;
        }
        
        // Nếu hotbar không có, tìm trong kho chính (9-35)
        for (int i = 9; i <= 35; i++) {
            if (checkSlot(i)) return i;
        }
        
        return -1; // Không tìm thấy
    }

    private boolean checkSlot(int id) {
        try {
            ItemStack stack = mc.player.currentScreenHandler.slots.get(id).getStack();
            return stack.getItem() == Items.TOTEM_OF_UNDYING;
        } catch (Exception e) {
            return false;
        }
    }
}
