package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerAttributeData;
import com.zhushen.space.data.PlayerCurrencyData;
import com.zhushen.space.data.PlayerSkillData;
import com.zhushen.space.data.PlayerSchoolData;
import com.zhushen.space.data.SchoolType;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.network.SyncProgressPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 主神空间进度服务端逻辑：货币（支线/分数/经验）同步、流派购买与技能购买。
 *
 * 购买流派 = 获得对应流派饰品物品（装备到「流派」饰品栏后才生效），
 * 并赠送流派自带的免费技能（太极拳：听劲）；八式等招式需在商城详情页单独购买。
 */
public class ProgressManager {

    /** 流派招式购买价格：C 支线 ×1 */
    public static final int MOVE_BRANCH_TIER = 3;
    public static final int MOVE_BRANCH_COST = 1;
    /** 流派招式购买价格：奖励点数 800 */
    public static final int MOVE_SCORE_COST = 800;
    /** 八劲合一 / 引手 / 揽雀尾 / 云手 / 海底针 / 缠丝劲：C 支线 ×1 + 奖励点数 1000 */
    public static final int EP_BRANCH_TIER = MOVE_BRANCH_TIER;
    public static final int EP_BRANCH_COST = MOVE_BRANCH_COST;
    public static final int EP_SCORE_COST = 1000;
    /** 太极化劲：B 支线 ×1 + 奖励点数 2000 */
    public static final int DISSOLVE_BRANCH_TIER = 2;
    public static final int DISSOLVE_BRANCH_COST = 1;
    public static final int DISSOLVE_SCORE_COST = 2000;

    /** 太极八劲（八式，不含听劲与被动八劲合一） */
    public static final List<SkillAbility> TAI_CHI_EIGHT_MOVES = List.of(
            SkillAbility.WARD_OFF, SkillAbility.ROLL_BACK, SkillAbility.PRESS, SkillAbility.PUSH,
            SkillAbility.PULL, SkillAbility.SPLIT, SkillAbility.ELBOW, SkillAbility.SHOULDER);

    /**
     * 八劲合一购买前提：全部太极八劲已购（客户端与服务端共用判断，传入已购位集合）。
     */
    public static boolean eightPowersPrereqMet(java.util.function.IntPredicate purchased) {
        for (SkillAbility move : TAI_CHI_EIGHT_MOVES) {
            if (!purchased.test(move.ordinal())) return false;
        }
        return true;
    }

    /** 同步进度到客户端（货币 + 流派购买记录 + 已购技能） */
    public static void sync(ServerPlayer player) {
        PlayerCurrencyData currency = player.getData(ModAttachments.PLAYER_CURRENCY);
        PlayerSchoolData schools = player.getData(ModAttachments.PLAYER_SCHOOLS);
        PacketDistributor.sendToPlayer(player, new SyncProgressPayload(
                currency.branches(), currency.score(), schools.unlocked(), schools.skillBits()));
    }

    /** 发放支线（指令） */
    public static void grantBranch(ServerPlayer player, int tier, int amount) {
        player.getData(ModAttachments.PLAYER_CURRENCY).addBranch(tier, amount);
        sync(player);
    }

    /** 发放奖励点数（指令） */
    public static void grantScore(ServerPlayer player, int amount) {
        player.getData(ModAttachments.PLAYER_CURRENCY).addScore(amount);
        sync(player);
    }

    /** 支线拼合：3 个低级 → 1 个高级 */
    public static boolean combine(ServerPlayer player, int tier) {
        boolean ok = player.getData(ModAttachments.PLAYER_CURRENCY).combine(tier);
        if (ok) sync(player);
        return ok;
    }

    /** 支线拆解：1 个高级 → 3 个低级 */
    public static boolean split(ServerPlayer player, int tier) {
        boolean ok = player.getData(ModAttachments.PLAYER_CURRENCY).split(tier);
        if (ok) sync(player);
        return ok;
    }

    /**
     * 支线兑换（货币界面拖拽）：相邻等级间拼合或拆解。
     * toTier = fromTier - 1：拼合（3 个 from → 1 个 to）
     * toTier = fromTier + 1：拆解（1 个 from → 3 个 to）
     * 返回是否成功，失败发客户端提示。
     */
    public static boolean exchange(ServerPlayer player, int fromTier, int toTier) {
        PlayerCurrencyData currency = player.getData(ModAttachments.PLAYER_CURRENCY);
        boolean ok = false;
        if (fromTier >= 1 && toTier == fromTier - 1) {
            ok = currency.combine(fromTier);
        } else if (fromTier <= PlayerCurrencyData.TIER_COUNT - 2 && toTier == fromTier + 1) {
            ok = currency.split(fromTier);
        }
        if (ok) {
            sync(player);
        } else {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.currency.fail"), true);
        }
        return ok;
    }

    /**
     * 购买流派：校验前提技能与价格（支线 + 分数），通过则扣除并记录购买，
     * 发放流派饰品物品，并赠送流派自带技能（太极拳：听劲）。
     * 饰品装备到「流派」饰品栏后太极拳才会生效（内力池 / 被动 / 技能）。
     * 返回 null=成功，否则返回失败消息 key。
     */
    public static String purchase(ServerPlayer player, SchoolType school) {
        PlayerSchoolData schools = player.getData(ModAttachments.PLAYER_SCHOOLS);
        if (schools.isUnlocked(school)) return "commands.zhushenspace.school.already";

        PlayerSkillData skills = player.getData(ModAttachments.PLAYER_SKILLS);
        if (skills.get(school.reqSkill().ordinal()) < school.reqLevel()) {
            return "commands.zhushenspace.school.lack_skill";
        }

        PlayerCurrencyData currency = player.getData(ModAttachments.PLAYER_CURRENCY);
        if (currency.branch(school.branchTier()) < school.branchCost()
                || currency.score() < school.scoreCost()) {
            return "commands.zhushenspace.school.lack_currency";
        }

        currency.addBranch(school.branchTier(), -school.branchCost());
        currency.addScore(-school.scoreCost());
        schools.unlock(school);
        grantSchoolBonus(player, school);
        sync(player);
        SkillServer.sync(player);
        return null;
    }

    /** 主神商城：购买装备（物品直接发放到背包，满了则掉落在脚下） */
    public static String purchaseGear(ServerPlayer player, com.zhushen.space.data.ShopGear gear) {
        PlayerCurrencyData currency = player.getData(ModAttachments.PLAYER_CURRENCY);
        if (currency.branch(gear.branchTier) < gear.branchCost || currency.score() < gear.scoreCost) {
            return "commands.zhushenspace.school.lack_currency";
        }
        net.minecraft.world.item.Item item = switch (gear) {
            case MAHORAGA_WHEEL -> com.zhushen.space.ZhuShenSpace.MAHORAGA_WHEEL.get();
        };
        currency.addBranch(gear.branchTier, -gear.branchCost);
        currency.addScore(-gear.scoreCost);
        ItemStack stack = new ItemStack(item);
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        player.displayClientMessage(Component.translatable("msg.zhushenspace.shop.gear_bought", Component.translatable(gear.nameKey())), false);
        sync(player);
        return null;
    }

    /** 商城购买包中「基础冷兵器」的序号偏移（GearPurchasePayload：100 + 模板序号） */
    public static final int WEAPON_OFFSET = 100;

    /** 主神商城：购买基础冷兵器模板（每件 200 奖励点数，可重复购买） */
    public static String purchaseWeapon(ServerPlayer player, com.zhushen.space.data.MeleeWeapon w) {
        PlayerCurrencyData currency = player.getData(ModAttachments.PLAYER_CURRENCY);
        if (currency.score() < com.zhushen.space.data.MeleeWeapon.PRICE) return "commands.zhushenspace.school.lack_currency";
        currency.addScore(-com.zhushen.space.data.MeleeWeapon.PRICE);
        ItemStack stack = new ItemStack(com.zhushen.space.ZhuShenSpace.weaponItem(w));
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        player.displayClientMessage(Component.translatable("msg.zhushenspace.shop.gear_bought", Component.translatable(w.nameKey())), false);
        sync(player);
        return null;
    }

    /** 直接解锁（调试指令，不扣费） */
    public static void grantSchool(ServerPlayer player, SchoolType school) {
        player.getData(ModAttachments.PLAYER_SCHOOLS).unlock(school);
        grantSchoolBonus(player, school);
        sync(player);
        SkillServer.sync(player);
    }

    /** 解锁流派附带的发放：饰品物品 + 赠送技能（太极拳：听劲） */
    private static void grantSchoolBonus(ServerPlayer player, SchoolType school) {
        PlayerSchoolData schools = player.getData(ModAttachments.PLAYER_SCHOOLS);
        if (school == SchoolType.TAI_CHI) {
            // 听劲：购买流派即赠送
            schools.purchaseSkill(school.ordinal(), SkillAbility.TING_JIN.ordinal());
        }
        giveEmblem(player, school);
    }

    /**
     * 购买流派技能（商城详情页）：校验流派已解锁、能力属于该流派、未购买、货币充足。
     * 返回 null=成功，否则返回失败消息 key。
     */
    public static String purchaseSchoolSkill(ServerPlayer player, int schoolOrdinal, int abilityId) {
        if (schoolOrdinal < 0 || schoolOrdinal >= SchoolType.COUNT) {
            return "commands.zhushenspace.school.invalid";
        }
        SchoolType school = SchoolType.values()[schoolOrdinal];
        if (abilityId < 0 || abilityId >= SkillAbility.COUNT) {
            return "commands.zhushenspace.school.invalid";
        }
        SkillAbility ability = SkillAbility.values()[abilityId];

        PlayerSchoolData schools = player.getData(ModAttachments.PLAYER_SCHOOLS);
        if (!schools.isUnlocked(school)) return "commands.zhushenspace.school.not_owned";
        if (!schools.isSkillPurchased(schoolOrdinal, abilityId)) {
            // 只能购买该流派的能力
            if (!school.key().equals(ability.gate())) return "commands.zhushenspace.school.invalid";
            PlayerCurrencyData currency = player.getData(ModAttachments.PLAYER_CURRENCY);

            // 八劲合一：前提——习得全部太极八劲
            if (ability == SkillAbility.EIGHT_POWERS) {
                for (SkillAbility move : TAI_CHI_EIGHT_MOVES) {
                    if (!schools.isSkillPurchased(schoolOrdinal, move.ordinal())) {
                        return "commands.zhushenspace.school.eight_powers_prereq";
                    }
                }
            }
            int scoreCost = abilityScoreCost(ability);
            int branchTier = abilityBranchTier(ability);
            int branchCost = abilityBranchCost(ability);
            if (currency.branch(branchTier) < branchCost
                    || currency.score() < scoreCost) {
                return "commands.zhushenspace.school.lack_currency";
            }
            currency.addBranch(branchTier, -branchCost);
            currency.addScore(-scoreCost);
            schools.purchaseSkill(schoolOrdinal, abilityId);
            sync(player);
            SkillServer.sync(player);
            return null;
        }
        return "commands.zhushenspace.school.already";
    }

    /**
     * 流派技能价格（奖励点数部分）：
     * 太极化劲 2000，高阶技能（八劲合一 / 引手 / 揽雀尾 / 云手 / 海底针 / 缠丝劲）1000，招式（八式 / 听劲）800。
     */
    public static int abilityScoreCost(SkillAbility ability) {
        return switch (ability) {
            case TAI_CHI_DISSOLVE -> DISSOLVE_SCORE_COST;
            case EIGHT_POWERS, YIN_SHOU, LAN_QUE_WEI, CLOUD_HANDS, SEA_BOTTOM_NEEDLE, COILING_SILK -> EP_SCORE_COST;
            default -> MOVE_SCORE_COST;
        };
    }

    /** 流派技能价格（支线等级）：太极化劲 B，其余 C */
    public static int abilityBranchTier(SkillAbility ability) {
        return ability == SkillAbility.TAI_CHI_DISSOLVE ? DISSOLVE_BRANCH_TIER : EP_BRANCH_TIER;
    }

    /** 流派技能价格（支线数量） */
    public static int abilityBranchCost(SkillAbility ability) {
        return ability == SkillAbility.TAI_CHI_DISSOLVE ? DISSOLVE_BRANCH_COST : EP_BRANCH_COST;
    }

    /** 补发流派饰品（免费）：仅当已购买流派且身上没有饰品时 */
    public static boolean claimEmblem(ServerPlayer player, int schoolOrdinal) {
        if (schoolOrdinal < 0 || schoolOrdinal >= SchoolType.COUNT) return false;
        SchoolType school = SchoolType.values()[schoolOrdinal];
        PlayerSchoolData schools = player.getData(ModAttachments.PLAYER_SCHOOLS);
        if (!schools.isUnlocked(school)) return false;
        if (hasEmblem(player, school)) return false;
        giveEmblem(player, school);
        return true;
    }

    /** 玩家身上（背包 / 饰品栏）是否已有该流派饰品 */
    public static boolean hasEmblem(ServerPlayer player, SchoolType school) {
        if (player.getInventory().contains(stack -> stack.is(emblemOf(school)))) return true;
        // 饰品栏（Curios）
        var found = top.theillusivec4.curios.api.CuriosApi.getCuriosHelper()
                .findFirstCurio(player, emblemOf(school));
        return found.isPresent();
    }

    /** 流派对应的饰品物品 */
    public static net.minecraft.world.item.Item emblemOf(SchoolType school) {
        if (school == SchoolType.TAI_CHI) {
            return ZhuShenSpace.TAI_CHI_EMBLEM.get();
        }
        return null;
    }

    /** 发放流派饰品：优先放入背包，放不下则掉落在脚下 */
    private static void giveEmblem(ServerPlayer player, SchoolType school) {
        net.minecraft.world.item.Item item = emblemOf(school);
        if (item == null) return;
        ItemStack stack = new ItemStack(item);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.displayClientMessage(Component.translatable("msg.zhushenspace.school.emblem_given"), false);
    }

    /** 登录/重生/换维度时同步进度 */
    public static void applyAndSync(ServerPlayer player) {
        PlayerAttributeData unused = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        sync(player);
    }

    /** 消息提示 */
    public static void msg(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
