package com.zhushen.space.common;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerCurrencyData;
import com.zhushen.space.data.SchoolType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.zhushen.space.entity.HallBuilder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 调试/管理指令（需 OP 权限）：
 * /zhushen energy give <玩家> <id> <上限>  —— 发放能量池
 * /zhushen energy remove <玩家> <id>       —— 移除能量池
 * /zhushen energy set <玩家> <id> <数值>   —— 设置当前能量
 * /zhushen energy clear <玩家>             —— 清空全部能量池
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public class ModCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("zhushen")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("energy")
                        .then(Commands.literal("give")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("id", StringArgumentType.string())
                                                .then(Commands.argument("max", DoubleArgumentType.doubleArg(1))
                                                        .executes(ctx -> give(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                DoubleArgumentType.getDouble(ctx, "max")))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("id", StringArgumentType.string())
                                                .executes(ctx -> remove(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "id"))))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("id", StringArgumentType.string())
                                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                                        .executes(ctx -> set(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                DoubleArgumentType.getDouble(ctx, "amount")))))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> clear(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"))))))
                // ===== 主神空间货币 =====
                .then(Commands.literal("currency")
                        .then(Commands.literal("give")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.literal("branch")
                                                .then(Commands.argument("tier", StringArgumentType.word())
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                                .executes(ctx -> giveBranch(ctx.getSource(),
                                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                                StringArgumentType.getString(ctx, "tier"),
                                                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                                        .then(Commands.literal("score")
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> giveScore(ctx.getSource(),
                                                                        EntityArgument.getPlayer(ctx, "player"),
                                                                        IntegerArgumentType.getInteger(ctx, "amount")))))))
                        .then(Commands.literal("combine")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("tier", StringArgumentType.word())
                                                .executes(ctx -> combineBranch(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "tier"))))))
                        .then(Commands.literal("split")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("tier", StringArgumentType.word())
                                                .executes(ctx -> splitBranch(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "tier"))))))
                        .then(Commands.literal("show")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> show(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"))))))
                // ===== 流派 =====
                .then(Commands.literal("school")
                        .then(Commands.literal("unlock")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("school", StringArgumentType.word())
                                                .executes(ctx -> unlockSchool(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "school"), true)))))
                        .then(Commands.literal("grant")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("school", StringArgumentType.word())
                                                .executes(ctx -> unlockSchool(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "school"), false))))))
                // ===== 肢体 / 断肢 =====
                .then(Commands.literal("limb")
                        .then(Commands.literal("restore")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> limbRestore(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), null))
                                        .then(Commands.argument("part", StringArgumentType.word())
                                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                        java.util.Arrays.stream(com.zhushen.space.data.LimbPart.values())
                                                                .map(com.zhushen.space.data.LimbPart::key), b))
                                                .executes(ctx -> limbRestore(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "part"))))))
                        .then(Commands.literal("sever")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("part", StringArgumentType.word())
                                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                        java.util.Arrays.stream(com.zhushen.space.data.LimbPart.values())
                                                                .filter(com.zhushen.space.data.LimbPart::severable)
                                                                .map(com.zhushen.space.data.LimbPart::key), b))
                                                .executes(ctx -> limbSever(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "part")))))))
                // ===== 建卡 XP =====
                .then(Commands.literal("build")
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                            com.zhushen.space.common.BuildServer.reset(p);
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    "command.zhushenspace.build.reset", p.getDisplayName()), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("xp")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-1000, 1000))
                                                .executes(ctx -> {
                                                    ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                                    int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount");
                                                    com.zhushen.space.common.BuildServer.addXp(p, n);
                                                    ctx.getSource().sendSuccess(() -> Component.translatable(
                                                            "command.zhushenspace.build.xp", p.getDisplayName(), n), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("info")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                            var b = p.getData(com.zhushen.space.data.ModAttachments.PLAYER_BUILD);
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    "command.zhushenspace.build.info", p.getDisplayName(), b.totalXp,
                                                    b.created, b.pendingItems, b.pendingExchange), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("clear_pending")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                            var b = p.getData(com.zhushen.space.data.ModAttachments.PLAYER_BUILD);
                                            b.pendingItems = 0;
                                            b.pendingExchange = false;
                                            com.zhushen.space.common.BuildServer.sync(p);
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    "command.zhushenspace.build.cleared", p.getDisplayName()), true);
                                            return 1;
                                        }))))
                // ===== 专业 =====
                .then(Commands.literal("profession")
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                            var d = p.getData(com.zhushen.space.data.ModAttachments.PLAYER_SKILLS);
                                            d.clearProfessions();
                                            com.zhushen.space.common.SkillServer.sync(p);
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    "command.zhushenspace.profession.reset", p.getDisplayName()), true);
                                            return 1;
                                        }))))
                // ===== 大厅场景 =====
                .then(Commands.literal("hall")
                        .then(Commands.literal("rebuild")
                                .executes(ctx -> rebuildHall(ctx.getSource())))
                        .then(Commands.literal("clear")
                                .executes(ctx -> clearHall(ctx.getSource())))));
    }

    private static int limbRestore(CommandSourceStack source, ServerPlayer player, String key) {
        com.zhushen.space.data.LimbPart part = null;
        if (key != null && !key.equals("all")) {
            part = com.zhushen.space.data.LimbPart.byKey(key);
            if (part == null) {
                source.sendFailure(Component.translatable("commands.zhushenspace.limb.unknown", key));
                return 0;
            }
        }
        LimbManager.restore(player, part);
        final com.zhushen.space.data.LimbPart fp = part;
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.limb.restore",
                player.getName().getString(),
                fp == null ? Component.translatable("limb.zhushenspace.all") : Component.translatable(fp.nameKey())), true);
        return 1;
    }

    private static int limbSever(CommandSourceStack source, ServerPlayer player, String key) {
        com.zhushen.space.data.LimbPart part = com.zhushen.space.data.LimbPart.byKey(key);
        if (part == null || !part.severable()) {
            source.sendFailure(Component.translatable("commands.zhushenspace.limb.unknown", key));
            return 0;
        }
        LimbManager.sever(player, part);
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.limb.sever",
                player.getName().getString(), Component.translatable(part.nameKey())), true);
        return 1;
    }

    private static int give(CommandSourceStack source, ServerPlayer player, String id, double max) {
        EnergyManager.grantPool(player, id, max);
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.energy.give",
                player.getName().getString(), id, (int) max), true);
        return 1;
    }

    private static int remove(CommandSourceStack source, ServerPlayer player, String id) {
        EnergyManager.removePool(player, id);
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.energy.remove",
                player.getName().getString(), id), true);
        return 1;
    }

    private static int set(CommandSourceStack source, ServerPlayer player, String id, double amount) {
        EnergyManager.setAmount(player, id, amount);
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.energy.set",
                player.getName().getString(), id, (int) amount), true);
        return 1;
    }

    private static int clear(CommandSourceStack source, ServerPlayer player) {
        player.getData(com.zhushen.space.data.ModAttachments.PLAYER_ENERGY).clearPools();
        EnergyManager.sync(player);
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.energy.clear",
                player.getName().getString()), true);
        return 1;
    }

    // ===== 主神空间货币 =====

    private static int giveBranch(CommandSourceStack source, ServerPlayer player, String tier, int count) {
        int t = PlayerCurrencyData.parseTier(tier);
        if (t < 0) {
            source.sendFailure(Component.translatable("commands.zhushenspace.currency.invalid"));
            return 0;
        }
        ProgressManager.grantBranch(player, t, count);
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.currency.give_branch",
                player.getName().getString(), PlayerCurrencyData.tierLetter(t), count), true);
        return 1;
    }

    private static int giveScore(CommandSourceStack source, ServerPlayer player, int amount) {
        ProgressManager.grantScore(player, amount);
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.currency.give_score",
                player.getName().getString(), amount), true);
        return 1;
    }

    private static int combineBranch(CommandSourceStack source, ServerPlayer player, String tier) {
        int t = PlayerCurrencyData.parseTier(tier);
        if (t < 1) {
            source.sendFailure(Component.translatable("commands.zhushenspace.currency.invalid"));
            return 0;
        }
        if (!ProgressManager.combine(player, t)) {
            source.sendFailure(Component.translatable("commands.zhushenspace.currency.lack"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.currency.combine"), true);
        return 1;
    }

    private static int splitBranch(CommandSourceStack source, ServerPlayer player, String tier) {
        int t = PlayerCurrencyData.parseTier(tier);
        if (t < 0 || t >= PlayerCurrencyData.TIER_COUNT - 1) {
            source.sendFailure(Component.translatable("commands.zhushenspace.currency.invalid"));
            return 0;
        }
        if (!ProgressManager.split(player, t)) {
            source.sendFailure(Component.translatable("commands.zhushenspace.currency.lack"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.currency.split"), true);
        return 1;
    }

    private static int show(CommandSourceStack source, ServerPlayer player) {
        PlayerCurrencyData currency = player.getData(ModAttachments.PLAYER_CURRENCY);
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.currency.show",
                player.getName().getString(), currency.branch(0), currency.branch(1),
                currency.branch(2), currency.branch(3), currency.branch(4),
                currency.score(), currency.xp()), false);
        return 1;
    }

    // ===== 流派 =====

    private static int unlockSchool(CommandSourceStack source, ServerPlayer player,
                                    String schoolName, boolean purchase) {
        SchoolType school = SchoolType.parse(schoolName);
        if (school == null) {
            source.sendFailure(Component.literal("未知流派（可用：tai_chi）"));
            return 0;
        }
        if (!purchase) {
            ProgressManager.grantSchool(player, school);
            source.sendSuccess(() -> Component.translatable("commands.zhushenspace.school.granted",
                    Component.translatable(school.nameKey()).getString()), true);
            return 1;
        }
        String fail = ProgressManager.purchase(player, school);
        if (fail != null) {
            source.sendFailure(Component.translatable(fail));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.zhushenspace.school.unlocked",
                Component.translatable(school.nameKey()).getString()), true);
        return 1;
    }

    // ===== 大厅场景 =====

    private static int rebuildHall(CommandSourceStack source) {
        ServerLevel hall = source.getServer().getLevel(HallManager.HALL_DIMENSION);
        if (hall == null) {
            source.sendFailure(Component.literal("大厅维度未加载"));
            return 0;
        }
        HallBuilder.rebuild(hall);
        source.sendSuccess(() -> Component.literal("大厅场景已重置"), true);
        return 1;
    }

    private static int clearHall(CommandSourceStack source) {
        ServerLevel hall = source.getServer().getLevel(HallManager.HALL_DIMENSION);
        if (hall == null) {
            source.sendFailure(Component.literal("大厅维度未加载"));
            return 0;
        }
        HallBuilder.clear(hall);
        source.sendSuccess(() -> Component.literal("大厅场景已清空"), true);
        return 1;
    }
}
