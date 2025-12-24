/*
 * Aoba Hacked Client
 * Copyright (C) 2019-2024 coltonk9043
 *
 * Licensed under the GNU General Public License, Version 3 or later.
 * See <http://www.gnu.org/licenses/>.
 */

package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.TickEvent.Post;
import net.aoba.event.events.TickEvent.Pre;
import net.aoba.event.listeners.TickListener;
import net.aoba.module.AntiCheat;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.aoba.settings.types.FloatSetting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

public class TriggerBot extends Module implements TickListener {
    private final FloatSetting radius = FloatSetting.builder().id("triggerbot_radius").displayName("Radius")
            .description("Radius that TriggerBot will trigger.").defaultValue(5f).minValue(0.1f).maxValue(10f)
            .step(0.1f).build();

    private final BooleanSetting targetAnimals = BooleanSetting.builder().id("triggerbot_target_animals")
            .displayName("Target Animals").description("Target animals.").defaultValue(false).build();

    private final BooleanSetting targetMonsters = BooleanSetting.builder().id("triggerbot_target_monsters")
            .displayName("Target Monsters").description("Target Monsters.").defaultValue(true).build();

    private final BooleanSetting targetPlayers = BooleanSetting.builder().id("triggerbot_target_players")
            .displayName("Target Players").description("Target Players.").defaultValue(true).build();

    private final BooleanSetting targetFriends = BooleanSetting.builder().id("triggerbot_target_friends")
            .displayName("Target Friends").description("Target Friends.").defaultValue(false).build();

    private final FloatSetting attackDelay = FloatSetting.builder().id("triggerbot_attack_delay")
            .displayName("Attack Delay").description("Delay in milliseconds between attacks.").defaultValue(0f)
            .minValue(0f).maxValue(500f).step(10f).build();

    private final FloatSetting randomness = FloatSetting.builder().id("triggerbot_randomness").displayName("Randomness")
            .description("The randomness of the delay between when TriggerBot will hit a target.").defaultValue(0.0f)
            .minValue(0.0f).maxValue(60.0f).step(1f).build();

    // -- TÍNH NĂNG MỚI: TỈ LỆ HỤT --
    private final FloatSetting missChance = FloatSetting.builder().id("triggerbot_miss_chance")
            .displayName("Miss Chance")
            .description("Chance percentage to miss a hit (simulates human error).")
            .defaultValue(10.0f) // Mặc định 10% hụt
            .minValue(0.0f).maxValue(100.0f).step(1f).build();

    private long lastAttackTime;

    public TriggerBot() {
        super("Triggerbot");

        setCategory(Category.of("Combat"));
        // Cập nhật mô tả cho đúng với tính năng mới
        setDescription("Attacks entity you are looking at while holding attack button.");

        addSetting(attackDelay);
        addSetting(radius);
        addSetting(targetAnimals);
        addSetting(targetMonsters);
        addSetting(targetPlayers);
        addSetting(targetFriends);
        addSetting(randomness);
        addSetting(missChance); // Đăng ký setting mới

        setDetectable(AntiCheat.Grim);

        lastAttackTime = 0L;
    }

    @Override
    public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
    }

    @Override
    public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
    }

    @Override
    public void onToggle() {

    }

    @Override
    public void onTick(Pre event) {
        // -- TÍNH NĂNG MỚI: CHỈ ĐÁNH KHI GIỮ CHUỘT TRÁI --
        if (!MC.options.attackKey.isPressed()) {
            return;
        }

        int randomnessValue = randomness.getValue().intValue();
        boolean state = randomnessValue == 0
                || (Math.round(Math.random() * Math.round(randomness.max_value))) % randomnessValue == 0;

        if (MC.player.getAttackCooldownProgress(0) == 1 && state) {
            HitResult ray = MC.crosshairTarget;

            if (ray != null && ray.getType() == HitResult.Type.ENTITY) {
                EntityHitResult entityResult = (EntityHitResult) ray;
                Entity ent = entityResult.getEntity();

                if (!(ent instanceof LivingEntity)) {
                    return;
                }

                if (ent instanceof AnimalEntity && !targetAnimals.getValue())
                    return;
                if (ent instanceof PlayerEntity && !targetPlayers.getValue())
                    return;
                if (ent instanceof Monster && !targetMonsters.getValue())
                    return;

                // Kiểm tra thời gian delay
                if (System.currentTimeMillis() - lastAttackTime >= attackDelay.getValue()) {
                    
                    // -- TÍNH NĂNG MỚI: LOGIC ĐÁNH HỤT --
                    // Random từ 0-100, nếu dính vào tỉ lệ hụt thì bỏ qua
                    if (Math.random() * 100 < missChance.getValue()) {
                        // Reset timer: Bắt buộc bot phải đợi hết 1 vòng delay nữa mới được thử lại.
                        // Cái này tạo cảm giác bị "lỡ nhịp" giống người thật hơn là chỉ bỏ qua 1 tick.
                        lastAttackTime = System.currentTimeMillis();
                        return;
                    }

                    MC.interactionManager.attackEntity(MC.player, entityResult.getEntity());
                    MC.player.swingHand(Hand.MAIN_HAND);
            
                    lastAttackTime = System.currentTimeMillis();
                }
            }
        }
    }

    @Override
    public void onTick(Post event) {

    }
}
