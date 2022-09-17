package com.stal111.forbidden_arcanus.common.entity.lostsoul;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Dynamic;
import com.stal111.forbidden_arcanus.ForbiddenArcanus;
import com.stal111.forbidden_arcanus.core.init.ModItems;
import com.stal111.forbidden_arcanus.core.init.ModMemoryModules;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.BlockPathTypes;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Function;

/**
 * @author stal111
 * @since 2022-09-14
 */
public class LostSoul extends PathfinderMob {

    protected static final ImmutableList<MemoryModuleType<?>> MEMORY_TYPES = ImmutableList.of(MemoryModuleType.LOOK_TARGET, MemoryModuleType.NEAREST_LIVING_ENTITIES, MemoryModuleType.WALK_TARGET, MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, MemoryModuleType.PATH, MemoryModuleType.HURT_BY, MemoryModuleType.HURT_BY_ENTITY, MemoryModuleType.IS_IN_WATER, MemoryModuleType.IS_PANICKING, ModMemoryModules.SCARED_TIME.get());
    protected static final ImmutableList<SensorType<? extends Sensor<? super LostSoul>>> SENSOR_TYPES = ImmutableList.of(SensorType.NEAREST_LIVING_ENTITIES, SensorType.HURT_BY, SensorType.IS_IN_WATER);

    private static final EntityDataAccessor<Integer> DATA_VARIANT = SynchedEntityData.defineId(LostSoul.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Boolean> DATA_SCARED = SynchedEntityData.defineId(LostSoul.class, EntityDataSerializers.BOOLEAN);

    private static final UniformInt SCARED_TIME = UniformInt.of(200, 500);

    public final AnimationState stillAnimationState = new AnimationState();
    public final AnimationState fearAnimationState = new AnimationState();

    public LostSoul(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.setPathfindingMalus(BlockPathTypes.BLOCKED, 16.0F);
        this.setPathfindingMalus(BlockPathTypes.DANGER_FIRE, 16.0F);
        this.setPathfindingMalus(BlockPathTypes.DAMAGE_FIRE, -1.0F);
        this.moveControl = new FlyingMoveControl(this, 20, true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 14.0D).add(Attributes.FLYING_SPEED, 0.5D).add(Attributes.MOVEMENT_SPEED, 0.3D);
    }

    @Override
    protected void actuallyHurt(@Nonnull DamageSource source, float amount) {
        if (source == DamageSource.IN_FIRE) {
            return;
        }
        super.actuallyHurt(source, amount);
    }

    @Nonnull
    @Override
    protected Brain.Provider<LostSoul> brainProvider() {
        return Brain.provider(MEMORY_TYPES, SENSOR_TYPES);
    }

    @Nonnull
    @Override
    protected Brain<?> makeBrain(@Nonnull Dynamic<?> dynamic) {
        return LostSoulAi.makeBrain(this.brainProvider().makeBrain(dynamic));
    }

    @Nonnull
    public Brain<LostSoul> getBrain() {
        return (Brain<LostSoul>) super.getBrain();
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(@Nonnull ServerLevelAccessor level, @Nonnull DifficultyInstance difficulty, @Nonnull MobSpawnType reason, @Nullable SpawnGroupData spawnData, @Nullable CompoundTag dataTag) {
        //this.setVariant(Variant.values()[level.getRandom().nextInt(Variant.values().length)]);

        return super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
    }

    @Override
    public float getWalkTargetValue(@Nonnull BlockPos pos, @Nonnull LevelReader level) {
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        BlockState state = level.getBlockState(pos);

        return pos.getY() >= height + 10 || pos.getY() + 1 <= height ? -25.0F : state.isAir() ? 10.0F : 0.0F;
    }

    @Nonnull
    @Override
    protected PathNavigation createNavigation(@Nonnull Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);

        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(true);
        navigation.setCanPassDoors(true);

        return navigation;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_VARIANT, 0);
        this.entityData.define(DATA_SCARED, false);
    }

    @Override
    public void tick() {
        if (!this.level.isClientSide()) {
            boolean isPanicking = this.brain.getMemory(MemoryModuleType.IS_PANICKING).orElse(false);
            boolean isScared = this.getBrain().checkMemory(ModMemoryModules.SCARED_TIME.get(), MemoryStatus.VALUE_PRESENT);

//            if (isPanicking) {
//                int time = SCARED_TIME.sample(this.random);
//
//                if (isScared) {
//                    if (time > this.getBrain().getMemory(ModMemoryModules.SCARED_TIME.get()).get()) {
//                      //  this.getBrain().setMemory(ModMemoryModules.SCARED_TIME.get(), time);
//                    }
//                } else {
//                   // this.getBrain().setMemory(ModMemoryModules.SCARED_TIME.get(), time);
//
//                    this.entityData.set(DATA_SCARED, true);
//                    isScared = true;
//                }
//            }

            if (this.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY) && !this.isScared()) {
                this.entityData.set(DATA_SCARED, true);

                this.setPathfindingMalus(BlockPathTypes.BLOCKED, 0.0F);

                this.getBrain().setActiveActivityIfPossible(Activity.PANIC);
            }
//
//            if (this.isScared()) {
//                if (!this.getBrain().checkMemory(MemoryModuleType.HURT_BY_ENTITY, MemoryStatus.VALUE_PRESENT)) {
//                    this.entityData.set(DATA_SCARED, false);
//                }
//
//                this.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES).ifPresent(nearestVisibleLivingEntities -> {
//                    if (!nearestVisibleLivingEntities.contains(this.getBrain().getMemory(MemoryModuleType.HURT_BY_ENTITY).get())) {
//                        this.entityData.set(DATA_SCARED, false);
//                    }
//                });
//            }

//            if (!isScared && this.isScared()) {
//                this.entityData.set(DATA_SCARED, false);
//            }
        }

        this.noPhysics = true;
        super.tick();
        this.noPhysics = false;
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, @Nonnull BlockState state, @Nonnull BlockPos pos) {
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, @Nonnull DamageSource source) {
        return false;
    }

    @Override
    public void onSyncedDataUpdated(@Nonnull EntityDataAccessor<?> key) {
        if (key.equals(DATA_SCARED)) {
            if (this.isScared()) {
                this.fearAnimationState.startIfStopped(this.tickCount);
                this.stillAnimationState.stop();
            } else {
                this.stillAnimationState.startIfStopped(this.tickCount);
                this.fearAnimationState.stop();
            }
        }
        super.onSyncedDataUpdated(key);
    }

    @Override
    protected void customServerAiStep() {
        this.level.getProfiler().push("lostSoulBrain");
        this.getBrain().tick((ServerLevel) this.level, this);
        this.level.getProfiler().pop();

        super.customServerAiStep();
    }

    @Override
    public void addAdditionalSaveData(@Nonnull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Variant", this.getVariant().getId());
        tag.putBoolean("Scared", this.entityData.get(DATA_SCARED));
    }

    @Override
    public void readAdditionalSaveData(@Nonnull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setVariant(LostSoul.Variant.FROM_ID.apply(tag.getInt("Variant")));
        this.entityData.set(DATA_SCARED, tag.getBoolean("Scared"));
    }

    @Nonnull
    @Override
    protected InteractionResult mobInteract(@Nonnull Player player, @Nonnull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Variant variant = this.getVariant();

        if (variant != Variant.CORRUPT_LOST_SOUL && stack.is(ModItems.CORRUPTI_DUST.get())) {
            this.setVariant(Variant.CORRUPT_LOST_SOUL);
            return InteractionResult.sidedSuccess(this.level.isClientSide());
        } else if (variant == Variant.CORRUPT_LOST_SOUL && stack.is(ModItems.AUREAL_BOTTLE.get())) {
            this.setVariant(Variant.LOST_SOUL);
            return InteractionResult.sidedSuccess(this.level.isClientSide());
        } else if (variant == Variant.LOST_SOUL && stack.is(ModItems.AUREAL_BOTTLE.get())) {
            this.setVariant(Variant.ENCHANTED_LOST_SOUL);
            return InteractionResult.sidedSuccess(this.level.isClientSide());
        }

        return super.mobInteract(player, hand);
    }

    public LostSoul.Variant getVariant() {
        return Variant.FROM_ID.apply(this.entityData.get(DATA_VARIANT));
    }

    private void setVariant(LostSoul.Variant variant) {
        this.entityData.set(DATA_VARIANT, variant.getId());
    }

    public boolean isScared() {
        return this.entityData.get(DATA_SCARED);
    }

    public enum Variant {
        LOST_SOUL(0, "lost_soul"),
        CORRUPT_LOST_SOUL(1, "corrupt_lost_soul"),
        ENCHANTED_LOST_SOUL(2, "enchanted_lost_soul");

        public static final Function<Integer, Variant> FROM_ID = integer -> {
            return Arrays.stream(Variant.values()).filter(variant -> variant.id == integer).findFirst().orElse(LOST_SOUL);
        };

        private final int id;
        private final String name;

        Variant(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return this.id;
        }

        public String getName() {
            return this.name;
        }

        public ResourceLocation getTexture() {
            return new ResourceLocation(ForbiddenArcanus.MOD_ID, "textures/entity/lost_soul/" + this.name + ".png");
        }
    }
}