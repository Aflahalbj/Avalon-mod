package id.avalon.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Collections;

/**
 * Entity mirip player (pengganti org.bukkit.entity.Mannequin di Paper 1.21).
 * Dipakai untuk Ratu Amaryn (tidur) dan "Bot" player yang sedang offline.
 */
public class MannequinEntity extends LivingEntity {

    private static final EntityDataAccessor<CompoundTag> DATA_PROFILE =
            SynchedEntityData.defineId(MannequinEntity.class, EntityDataSerializers.COMPOUND_TAG);

    /** Setara Entity#setPersistent: kalau false, entity tidak disimpan ke disk. */
    private boolean persistent = true;

    @Nullable
    private GameProfile cachedProfile;
    @Nullable
    private CompoundTag cachedProfileTag;

    public MannequinEntity(EntityType<? extends MannequinEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_PROFILE, new CompoundTag());
    }

    // ── Profile / skin ────────────────────────────────────────────────────────

    public void setProfile(GameProfile profile) {
        CompoundTag tag = profile == null ? new CompoundTag() : NbtUtils.writeGameProfile(new CompoundTag(), profile);
        this.entityData.set(DATA_PROFILE, tag);
    }

    @Nullable
    public GameProfile getProfile() {
        CompoundTag tag = this.entityData.get(DATA_PROFILE);
        if (tag != cachedProfileTag) {
            cachedProfileTag = tag;
            cachedProfile = tag.isEmpty() ? null : NbtUtils.readGameProfile(tag);
        }
        return cachedProfile;
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    /** Hadapkan badan + kepala ke yaw tertentu. */
    public void setFacing(float yaw) {
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
        this.yHeadRot = yaw;
        this.yHeadRotO = yaw;
    }

    // ── Perilaku: diam, kebal, tidak bisa didorong ────────────────────────────

    @Override
    public boolean shouldBeSaved() {
        return persistent && super.shouldBeSaved();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) return false;
        return super.hurt(source, amount);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public void knockback(double strength, double x, double z) {
    }

    @Override
    public void travel(Vec3 input) {
        // Immovable: tidak bergerak sama sekali
    }

    @Override
    public boolean isAffectedByPotions() {
        return false;
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return Collections.emptyList();
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    // ── Save / load ───────────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("Profile", this.entityData.get(DATA_PROFILE).copy());
        tag.putBoolean("Sleeping", this.getPose() == Pose.SLEEPING);
        tag.putBoolean("AvalonPersistent", persistent);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Profile")) {
            this.entityData.set(DATA_PROFILE, tag.getCompound("Profile"));
        }
        if (tag.getBoolean("Sleeping")) {
            this.setPose(Pose.SLEEPING);
        }
        if (tag.contains("AvalonPersistent")) {
            this.persistent = tag.getBoolean("AvalonPersistent");
        }
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }
}
