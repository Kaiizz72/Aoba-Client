package net.aoba.mixin; //hi

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;

@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin extends PlayerEntityMixin {

}
