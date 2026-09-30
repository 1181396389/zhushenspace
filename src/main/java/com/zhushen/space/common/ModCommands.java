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
                // ===== 不良状态 / 倒地 / 生存需求 / 眼睛（测试用） =====
                .then(Commands.literal("status")
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                        java.util.Arrays.stream(com.zhushen.space.data.StatusType.values()).map(t -> t.key), b))
                                                .then(Commands.argument("points", IntegerArgumentType.integer(1, 999))
                                                        .executes(ctx -> statusAdd(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "type"), IntegerArgumentType.getInteger(ctx, "points"), "natural"))
                                                        .then(Commands.argument("kind", StringArgumentType.word())
                                                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                                        java.util.List.of("natural", "malicious", "magic"), b))
                                                                .executes(ctx -> statusAdd(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                                        StringArgumentType.getString(ctx, "type"), IntegerArgumentType.getInteger(ctx, "points"),
                                                                        StringArgumentType.getString(ctx, "kind"))))))))
                        .then(Commands.literal("limb")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("part", StringArgumentType.word())
                                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                        java.util.Arrays.stream(com.zhushen.space.data.LimbPart.values())
                                                                .filter(com.zhushen.space.data.LimbPart::severable).map(com.zhushen.space.data.LimbPart::key), b))
                                                .then(Commands.argument("points", IntegerArgumentType.integer(1, 999))
                                                        .executes(ctx -> statusLimb(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "part"), IntegerArgumentType.getInteger(ctx, "points")))))))
                        .then(Commands.literal("state")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("condition", StringArgumentType.word())
                                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                        java.util.Arrays.stream(com.zhushen.space.data.Condition.values()).map(t -> t.key), b))
                                                .executes(ctx -> statusState(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "condition"), 0))
                                                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                                                        .executes(ctx -> statusState(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "condition"), IntegerArgumentType.getInteger(ctx, "seconds")))))))
                        .then(Commands.literal("unstate")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("condition", StringArgumentType.word())
                                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                        java.util.Arrays.stream(com.zhushen.space.data.Condition.values()).map(t -> t.key), b))
                                                .executes(ctx -> {
                                                    var c = com.zhushen.space.data.Condition.byKey(StringArgumentType.getString(ctx, "condition"));
                                                    if (c == null) { ctx.getSource().sendFailure(Component.literal("未知的固有不良状态")); return 0; }
                                                    StatusManager.removeCondition(EntityArgument.getPlayer(ctx, "player"), c);
                                                    ctx.getSource().sendSuccess(() -> Component.literal("已移除 ").append(Component.translatable(c.nameKey())), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            StatusManager.clear(EntityArgument.getPlayer(ctx, "player"), null, true);
                                            ctx.getSource().sendSuccess(() -> Component.literal("已清除全部不良状态与毁灭性后果"), true);
                                            return 1;
                                        })
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                        java.util.Arrays.stream(com.zhushen.space.data.StatusType.values()).map(t -> t.key), b))
                                                .executes(ctx -> {
                                                    var t = com.zhushen.space.data.StatusType.byKey(StringArgumentType.getString(ctx, "type"));
                                                    if (t == null) { ctx.getSource().sendFailure(Component.literal("未知的不良状态类型")); return 0; }
                                                    StatusManager.clear(EntityArgument.getPlayer(ctx, "player"), t, true);
                                                    ctx.getSource().sendSuccess(() -> Component.literal("已清除 ").append(Component.translatable(t.nameKey())), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("info")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> statusInfo(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("prone")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.literal("on").executes(ctx -> {
                                    StatusManager.setProne(EntityArgument.getPlayer(ctx, "player"), true, "msg.zhushenspace.prone.knocked");
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    StatusManager.setProne(EntityArgument.getPlayer(ctx, "player"), false, null);
                                    return 1;
                                }))))
                .then(Commands.literal("survival")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("what", StringArgumentType.word())
                                        .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                java.util.List.of("thirst", "stamina", "sleep"), b))
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0, 200))
                                                .executes(ctx -> survivalSet(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "what"), DoubleArgumentType.getDouble(ctx, "value")))))))
                .then(Commands.literal("eye")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("side", StringArgumentType.word())
                                        .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                java.util.List.of("right", "left"), b))
                                        .then(Commands.literal("lose").executes(ctx -> {
                                            LimbManager.loseEye(EntityArgument.getPlayer(ctx, "player"),
                                                    "left".equals(StringArgumentType.getString(ctx, "side"))
                                                            ? com.zhushen.space.data.PlayerLimbData.LEFT_EYE : com.zhushen.space.data.PlayerLimbData.RIGHT_EYE);
                                            return 1;
                                        }))
                                        .then(Commands.literal("restore").executes(ctx -> {
                                            LimbManager.restoreEye(EntityArgument.getPlayer(ctx, "player"),
                                                    "left".equals(StringArgumentType.getString(ctx, "side"))
                                                            ? com.zhushen.space.data.PlayerLimbData.LEFT_EYE : com.zhushen.space.data.PlayerLimbData.RIGHT_EYE);
                                            return 1;
                                        })))))
                // ===== 快速医疗 / 休息（测试用） =====
                .then(Commands.literal("quickheal")
                        .then(Commands.literal("grant")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("source", StringArgumentType.word())
                                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 99))
                                                        .executes(ctx -> quickHealGrant(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "source"),
                                                                IntegerArgumentType.getInteger(ctx, "level"), 0))
                                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                                                .executes(ctx -> quickHealGrant(ctx.getSource(),
                                                                        EntityArgument.getPlayer(ctx, "player"),
                                                                        StringArgumentType.getString(ctx, "source"),
                                                                        IntegerArgumentType.getInteger(ctx, "level"),
                                                                        IntegerArgumentType.getInteger(ctx, "seconds"))))))))
                        .then(Commands.literal("revoke")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("source", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                                    QuickHealManager.revoke(p, StringArgumentType.getString(ctx, "source"));
                                                    ctx.getSource().sendSuccess(() -> Component.literal("已移除快速医疗来源"), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            QuickHealManager.clear(EntityArgument.getPlayer(ctx, "player"));
                                            ctx.getSource().sendSuccess(() -> Component.literal("已清空快速医疗来源"), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("info")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> quickHealInfo(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("rest")
                        .then(Commands.literal("resetlong")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            RestManager.resetLongRest(EntityArgument.getPlayer(ctx, "player"));
                                            ctx.getSource().sendSuccess(() -> Component.literal("已重置长休冷却"), true);
                                            return 1;
                                        }))))
                // ===== 教程书（帕秋莉手册） =====
                .then(Commands.literal("guide")
                        .executes(ctx -> com.zhushen.space.common.GuideBook.give(ctx.getSource().getPlayerOrException()) ? 1 : 0)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> com.zhushen.space.common.GuideBook.give(EntityArgument.getPlayer(ctx, "player")) ? 1 : 0)))
                // ===== 重置加点与全部购买 =====
                .then(Commands.literal("resetall")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> resetAll(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), true))
                                .then(Commands.literal("norefund")
                                        .executes(ctx -> resetAll(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), false)))))
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

    private static int resetAll(CommandSourceStack src, ServerPlayer p, boolean refund) {
        int score = com.zhushen.space.common.ArtManager.resetAll(p, refund);
        src.sendSuccess(() -> Component.literal("已重置 " + p.getName().getString() + " 的加点与全部购买"
                + (refund ? "（已退还 " + score + " 积分及对应支线 / XP）" : "（不退款）")), true);
        p.sendSystemMessage(Component.literal("§e你的加点与全部购买已被重置，请重新建卡。"));
        return 1;
    }

    private static int quickHealGrant(CommandSourceStack src, ServerPlayer p, String source, int level, int seconds) {
        QuickHealManager.grant(p, source, level, seconds * 20);
        src.sendSuccess(() -> Component.literal("已给予 " + p.getName().getString() + " 快速医疗 " + level
                + "（来源 " + source + (seconds > 0 ? "，" + seconds + " 秒" : "，直到移除") + "）"), true);
        return 1;
    }

    private static int quickHealInfo(CommandSourceStack src, ServerPlayer p) {
        var act = QuickHealManager.active(p);
        double[] rg = QuickHealManager.regrowProgress(p);
        long wait = RestManager.longRestReadyIn(p);
        src.sendSuccess(() -> Component.literal(p.getName().getString() + " 快速医疗：" + (act.isEmpty() ? "无" : act)
                + "；断肢再生进度 " + String.format("%.1f/%.0f", rg[0], rg[1])
                + "；长休" + (wait <= 0 ? "可用" : "冷却 " + (wait + 19) / 20 + " 秒")), false);
        return 1;
    }

    private static int statusAdd(CommandSourceStack src, ServerPlayer p, String type, int points, String kind) {
        com.zhushen.space.data.StatusType t = com.zhushen.space.data.StatusType.byKey(type);
        if (t == null) {
            src.sendFailure(Component.literal("未知的不良状态类型：" + type));
            return 0;
        }
        StatusManager.Source k = "magic".equals(kind) ? StatusManager.Source.MAGIC
                : "malicious".equals(kind) ? StatusManager.Source.MALICIOUS : StatusManager.Source.NATURAL;
        int got = StatusManager.add(p, t, points, false, src.getEntity(), k, 20 * 60);
        src.sendSuccess(() -> Component.literal("已给予 " + p.getName().getString() + " ").append(Component.translatable(t.nameKey()))
                .append(" 点数 " + got + "（当前 " + StatusManager.data(p).points(t) + "）"), true);
        return 1;
    }

    private static int statusLimb(CommandSourceStack src, ServerPlayer p, String part, int points) {
        var lp = com.zhushen.space.data.LimbPart.byKey(part);
        if (lp == null || !lp.severable()) {
            src.sendFailure(Component.literal("未知的肢体：" + part));
            return 0;
        }
        int got = StatusManager.add(p, com.zhushen.space.data.StatusType.LIMB, points, false, src.getEntity(),
                StatusManager.Source.NATURAL, 0, true, lp);
        src.sendSuccess(() -> Component.literal("已给予 " + p.getName().getString() + " ").append(Component.translatable(lp.nameKey()))
                .append(" 肢体妨害 " + got + "（当前 " + StatusManager.data(p).limb[lp.ordinal()] + "）"), true);
        return 1;
    }

    private static int statusState(CommandSourceStack src, ServerPlayer p, String key, int seconds) {
        var c = com.zhushen.space.data.Condition.byKey(key);
        if (c == null) {
            src.sendFailure(Component.literal("未知的固有不良状态：" + key));
            return 0;
        }
        StatusManager.addCondition(p, c, seconds * 20, src.getEntity());
        src.sendSuccess(() -> Component.literal("已使 " + p.getName().getString() + " 陷入 ").append(Component.translatable(c.nameKey()))
                .append(seconds > 0 ? "（" + seconds + " 秒）" : "（直到解除）"), true);
        return 1;
    }

    private static int statusInfo(CommandSourceStack src, ServerPlayer p) {
        var d = StatusManager.data(p);
        StringBuilder sb = new StringBuilder(p.getName().getString()).append("：");
        for (com.zhushen.space.data.StatusType t : com.zhushen.space.data.StatusType.values()) {
            if (t == com.zhushen.space.data.StatusType.LIMB) {
                for (var lp : com.zhushen.space.data.LimbPart.values()) {
                    if (!lp.severable() || d.limb[lp.ordinal()] <= 0) continue;
                    sb.append(Component.translatable(lp.nameKey()).getString()).append(Component.translatable(t.nameKey()).getString())
                            .append(' ').append(d.limb[lp.ordinal()]).append('/').append(StatusManager.heavyAt(p, t))
                            .append('/').append(StatusManager.destructiveAt(p, t)).append("  ");
                }
                continue;
            }
            if (d.points(t) <= 0 && !d.isPermanent(t)) continue;
            sb.append(Component.translatable(t.nameKey()).getString()).append(' ').append(d.points(t)).append('/')
                    .append(StatusManager.heavyAt(p, t)).append('/').append(StatusManager.destructiveAt(p, t))
                    .append(d.isPermanent(t) ? "[毁灭]" : "").append("  ");
        }
        sb.append("\n状态：");
        for (var c : com.zhushen.space.data.Condition.values())
            if (StatusEffects.has(p, c)) sb.append(Component.translatable(c.nameKey()).getString()).append(' ');
        sb.append(" 开放性创口 ").append(d.openWounds);
        sb.append("\n水分 ").append(Math.round(d.thirst)).append(" 体力 ").append(Math.round(d.stamina)).append('/')
                .append(Math.round(SurvivalManager.maxStamina(p))).append(" 精力 ").append(Math.round(d.sleep))
                .append(d.prone ? " 倒地" : "").append(d.exhausted ? " 体力透支" : "").append(d.collapsed ? " 昏睡" : "")
                .append(" 失去眼睛 ").append(LimbManager.eyesLost(p));
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int survivalSet(CommandSourceStack src, ServerPlayer p, String what, double value) {
        var d = StatusManager.data(p);
        switch (what) {
            case "thirst" -> d.thirst = (float) Math.min(100, value);
            case "sleep" -> { d.sleep = (float) Math.min(100, value); if (d.sleep >= 20) d.collapsed = false; }
            case "stamina" -> { d.stamina = (float) Math.min(SurvivalManager.maxStamina(p), value); if (d.stamina > 0) d.exhausted = false; }
            default -> {
                src.sendFailure(Component.literal("可选：thirst / stamina / sleep"));
                return 0;
            }
        }
        StatusManager.sync(p);
        src.sendSuccess(() -> Component.literal("已设置 " + what + " = " + value), true);
        return 1;
    }
}
