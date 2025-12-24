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
import net.minecraft.item.ItemStack;
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
    
    // Biến lưu vị trí để aim chuột
    private int targetSwapSlot = -1;
    private int targetRefillSlot = -1;

    private enum Step {
        NONE,
        STEP_1_SELECT_SLOT_8,       // Cuộn sang Slot 8 để bắt đầu
        STEP_2_OPEN_INV,            // Mở túi
        STEP_3_FIND_AND_AIM_SWAP,   // Tìm & Aim Totem
        STEP_4_ACTION_SWAP,         // Bấm F (Swap Offhand)
        STEP_5_FIND_AND_AIM_REFILL, // Tìm hàng dự trữ
        STEP_6_ACTION_REFILL,       // Refill vào Slot 8
        STEP_7_CLOSE,               // Đóng túi
        STEP_8_SWITCH_TO_SLOT_2     // BƯỚC MỚI: Cuộn về Slot 2 để đánh nhau
    }
    private Step currentStep = Step.NONE;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Pop -> Slot 8 -> Refill -> Về Slot 2");
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
        targetSwapSlot = -1;
        targetRefillSlot = -1;
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { 
                if (packet.getEntity(mc.world) == mc.player) {
                    if (!isWorking) { 
                        isWorking = true;
                        currentStep = Step.STEP_1_SELECT_SLOT_8;
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
            case STEP_1_SELECT_SLOT_8:
                // Bước 1: Cuộn sang Slot 8 (Index 8 = Hotbar số 9)
                // Nếu bạn muốn Slot 8 theo bàn phím thì sửa thành forceSetSlot(7)
                // Ở đây mình giữ nguyên số 8 theo code cũ của bạn.
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
                    currentStep = Step.STEP_3_FIND_AND_AIM_SWAP;
                    lastTime = now;
                }
                break;

            case STEP_3_FIND_AND_AIM_SWAP:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= delay) {
                        targetSwapSlot = findAnyTotemSlot();
                        if (targetSwapSlot != -1) {
                            aimAtSlot(targetSwapSlot);
                            currentStep = Step.STEP_4_ACTION_SWAP;
                        } else {
                            // Không có totem thì đóng túi về Slot 2 luôn
                            if (autoEsc.getValue()) currentStep = Step.STEP_7_CLOSE;
                            else currentStep = Step.STEP_8_SWITCH_TO_SLOT_2;
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_4_ACTION_SWAP:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= delay) {
                        if (targetSwapSlot != -1) {
                            int syncId = mc.player.currentScreenHandler.syncId;
                            mc.interactionManager.clickSlot(syncId, targetSwapSlot, 40, SlotActionType.SWAP, mc.player);
                        }
                        currentStep = Step.STEP_5_FIND_AND_AIM_REFILL;
                        lastTime = now;
                    }
                }
                break;

            case STEP_5_FIND_AND_AIM_REFILL:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= delay) {
                        if (isSlotTotem(44)) {
                            // Đã có hàng ở Slot 8, đóng túi
                            if (autoEsc.getValue()) currentStep = Step.STEP_7_CLOSE;
                            else currentStep = Step.STEP_8_SWITCH_TO_SLOT_2;
                        } else {
                            targetRefillSlot = findTotemInStorage();
                            if (targetRefillSlot != -1) {
                                aimAtSlot(targetRefillSlot);
                                currentStep = Step.STEP_6_ACTION_REFILL;
                            } else {
                                if (autoEsc.getValue()) currentStep = Step.STEP_7_CLOSE;
                                else currentStep = Step.STEP_8_SWITCH_TO_SLOT_2;
                            }
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_6_ACTION_REFILL:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= delay) {
                        if (targetRefillSlot != -1) {
                            int syncId = mc.player.currentScreenHandler.syncId;
                            mc.interactionManager.clickSlot(syncId, targetRefillSlot, 8, SlotActionType.SWAP, mc.player);
                        }
                        
                        if (autoEsc.getValue()) {
                            currentStep = Step.STEP_7_CLOSE;
                        } else {
                            // Nếu không auto close, thì chuyển tay luôn
                            currentStep = Step.STEP_8_SWITCH_TO_SLOT_2; 
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_7_CLOSE:
                if (now - lastTime >= delay) {
                    mc.setScreen(null);
                    // Sau khi đóng túi xong, chuyển sang bước đổi tay
                    currentStep = Step.STEP_8_SWITCH_TO_SLOT_2;
                    lastTime = now;
                }
                break;

            case STEP_8_SWITCH_TO_SLOT_2:
                // BƯỚC CUỐI: Chuyển tay về Slot 2 (Index 1)
                if (now - lastTime >= 50) {
                    // Index 0 = Slot 1
                    // Index 1 = Slot 2
                    forceSetSlot(1); 
                    
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(1));
                    }
                    
                    reset(); // Hoàn tất
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
            f.setAccessible(true); f.setInt(inv, slotIndex);
        } catch (Exception e1) {
            try {
                Field f = PlayerInventory.class.getDeclaredField("currentItem");
                f.setAccessible(true); f.setInt(inv, slotIndex);
            } catch (Exception e2) {
                try {
                     Field f = PlayerInventory.class.getDeclaredField("field_7545");
                     f.setAccessible(true); f.setInt(inv, slotIndex);
                } catch (Exception ignored) {}
            }
        }
    }

    private void aimAtSlot(int slotId) {
        if (!(mc.currentScreen instanceof InventoryScreen)) return;
        try {
            InventoryScreen screen = (InventoryScreen) mc.currentScreen;
            Slot slot = mc.player.currentScreenHandler.slots.get(slotId);
            if (slot.getStack().getItem() != Items.TOTEM_OF_UNDYING) return;

            int guiLeft = (screen.width - 176) / 2;
            int guiTop = (screen.height - 166) / 2;
            int targetX = guiLeft + slot.x + 8;
            int targetY = guiTop + slot.y + 8;

            int jitterX = ThreadLocalRandom.current().nextInt(-3, 4);
            int jitterY = ThreadLocalRandom.current().nextInt(-3, 4);
            double scale = mc.getWindow().getScaleFactor();
            
            GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), (targetX + jitterX) * scale, (targetY + jitterY) * scale);
        } catch (Exception ignored) {}
    }

    private int findAnyTotemSlot() {
        for (int i = 36; i <= 44; i++) if (isSlotTotem(i)) return i;
        for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i;
        return -1;
    }

    private int findTotemInStorage() {
        for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i;
        return -1;
    }

    private boolean isSlotTotem(int id) {
        try {
            ItemStack stack = mc.player.currentScreenHandler.slots.get(id).getStack();
            return stack.getItem() == Items.TOTEM_OF_UNDYING;
        } catch (Exception e) {
            return false;
        }
    }
}
