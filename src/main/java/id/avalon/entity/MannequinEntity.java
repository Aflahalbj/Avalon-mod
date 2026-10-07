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
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Entity mirip player (pengganti org.bukkit.entity.Mannequin di Paper 1.21).
 * Dipakai untuk Ratu Amaryn (tidur) dan "Bot" player yang sedang offline.
 * Bawaannya patung: diam, kebal, tidak bisa didorong. {@link #setWalking} membuatnya bisa berjalan
 * sendiri (dipakai bot yang ikut misi).
 */
public class MannequinEntity extends PathfinderMob {

    private static final EntityDataAccessor<CompoundTag> DATA_PROFILE =
            SynchedEntityData.defineId(MannequinEntity.class, EntityDataSerializers.COMPOUND_TAG);

    /** Setara Entity#setPersistent: kalau false, entity tidak disimpan ke disk. */
    private boolean persistent = true;

    @Nullable
    private GameProfile cachedProfile;
    @Nullable
    private CompoundTag cachedProfileTag;

    /** Sedang berjalan sendiri (AI & gravitasi aktif); false = patung. */
    private boolean walking;

    public MannequinEntity(EntityType<? extends MannequinEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.setNoAi(true);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        // Kecepatan jalan sama dengan player; jangkauan pathfinding jauh (gudang ke pilar)
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.1)
                .add(Attributes.FOLLOW_RANGE, 128.0);
    }

    /** Hidupkan / matikan kemampuan berjalan. Saat dimatikan ia kembali jadi patung di tempatnya. */
    public void setWalking(boolean walking) {
        this.walking = walking;
        this.setNoAi(!walking);
        this.setNoGravity(!walking);
        this.noPhysics = false;
        if (!walking) {
            this.getNavigation().stop();
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    public boolean isWalking() {
        return walking;
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
        // Patung tidak bergerak sama sekali. Di client tetap dijalankan: di sana ini hanya
        // menghitung animasi langkah dari perpindahan posisinya.
        if (walking || level().isClientSide) super.travel(input);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    public boolean isAffectedByPotions() {
        return false;
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
        this.setNoAi(true);
    }
}
