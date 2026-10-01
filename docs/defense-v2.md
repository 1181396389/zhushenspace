# 防御侧 v2：伤害结算重写（对应《防御侧》规则）

实现：`common/DamageRules.java`（`LivingIncomingDamageEvent` **LOWEST**，单独一个处理器完成第 2~11 步）。

## 事件顺序

| 阶段 | 优先级 | 处理器 |
|---|---|---|
| 攻击检定 / 防御 | HIGHEST / HIGH | `CombatFormula`、`DamageCap.onIncomingFirst`（记录基础）、`Defense` 等 |
| 攻击方加值 | NORMAL | `DamageVariance`、`ArtManager.onMelee`、**`TaiChiManager.onOutgoingDamage`（徒手加成 / 听劲 / 八劲，自 Pre 迁入）**、**`EnergyManager.onDamagePre`（内力吐息，自 Pre 迁入）** |
| 伤害上限 + 意志加值 | **LOW**（原 LOWEST） | `DamageCap.onIncomingLast`、`WillpowerManager.onIncomingLast`、`LimbManager.onIncoming` |
| 防御侧 2~11 步 | **LOWEST** | `DamageRules.onIncoming` |

暴击 / 弱点已从 `AttributeEvents` 的 LOW 处理器移除，改为 `DamageRules.addFinalMod` 注册的第 11 步追加（不计入伤害上限）。
`DamageRules.onFinal`（Pre 阶段的易伤）删除，易伤并入第 11 步。原版保护附魔继续无效。

## 伤害部分

一次伤害 = 若干 `Part(amount, severity, kinds, unavoidable)`：主伤害 + `Spec.extra`（命中后的额外伤害，不计上限）。
每个阶段按 **冲击 → 严重 → 恶性** 的顺序抵扣；一个防御值是「每次伤害」的总额，抵扣后余量继续作用于下一部分。
混合类型的部分：每种类型取该阶段可用的最高防御，再取各类型中的最小值（免疫的类型视为无限）。

## 步骤

1. 攻击方伤害（前面的处理器已算好，含上限）。
2. 攻击方伤害转化：`Spec.withHalfUp()`（主伤害一半提升 1 级）；光明 → 黑暗生物 / 黑暗 → 光明生物；`addAttackProvider`。
3. 击破：`Break.points(stage, X[, kind])` 从防御方最高项依次击破（击破为 0 才到下一项）；`Break.all(stage[, kind])` 整类无视。
   旧参数 `ignoreResist` = `Break.points(RESIST, X)`；破甲剩余（破甲 − 盾牌 / 盔甲 / 天生防御）= `Break.points(HARDNESS, 剩余)`。
   `DamageRules.breakNext(victim, b)` 为本刻下一次伤害追加击破（太极「挤」= 无视 6 点吸收）。
4. 免疫：部分的全部类型都免疫，或 `immuneSeverity` 包含其等级 → 移除。
5. 忽略：`ignoreIf`（条件满足则该部分全部忽略，如武器伤害 ≤ X；武器伤害由 `CombatFormula` 经 `noteWeapon` 提供）→ `ignore(X)`。
6. 硬度 / 伤害抵消（同阶段取高；硬度对无视硬度的类型无效）。
7. 能量抗力（只对能量，无类型 = 全能量）/ DR（只对物理；弱点 `Weakness.MAGIC/DIVINE/PIERCE/BLUNT/SLASH` 被 `Trait` 或部分类型穿透）。
8. 吸收（`Scope.PHYSICAL / ENERGY / ALL`，取高）。
9. 阈值：可避免部分合计低于阈值 → 全部无效。
10. 防御方伤害转化：`convert(id, layer, points, when)`；同 id 只取一条；按 盾牌 → 盔甲 → 自身；以转化前数量为准从恶性开始各降 1 级（同一点只降一次），最低冲击。
11. 最终伤害 F：`addFinalMod` 返回系数 k，各自额外造成 F × k；易伤翻倍 = 含该类型的部分 +自身；易伤 X（取最大）加在含该类型的最低等级部分。
    例：F = 10，暴击 ×2、易伤翻倍、暴击 ×1.5 → 10 + 10 + 10 + 5 = 35。
- 伤害转移：`addTransfer(hit -> new Share(to, points))`，从高等级部分取，转移者承受不可避免、无直接来源的伤害（不视为受到攻击，不触发暴击 / 再转移）。

## Profile API（提供者 `addProvider((entity, profile) -> …)`）

```java
pr.immune(FIRE);            pr.immuneSeverity(Severity.B);
pr.ignore(src, 2, FIRE);    pr.ignoreIf(src, h -> h.weapon >= 0 && h.weapon <= 3);
pr.hardness(src, 2);        pr.offset(src, 1);
pr.resist(src, 5);          pr.resist(src, 3, FIRE);
pr.dr(src, 5, Weakness.MAGIC);  pr.dr(src, 3);          // DR 5/魔法、DR 3/-
pr.absorb(src, 3, Scope.ALL);
pr.threshold(4);
pr.convert("trait.x.bulletproof", Layer.ARMOR, ALL, h -> h.ranged);
pr.vuln(FIRE);              pr.vuln(FIRE, 3);
```
各 adder 返回的对象可 `.item()` 标记为物品提供（精神 / 毒素无视）。`src` 是来源翻译键，显示在悬停提示中。

## Spec（模组伤害）

`new Spec(kinds, sev, armorPierce, ignoreResist, ranged)` 与 `Spec.of` 保持兼容；新增
`withTraits(Trait.MAGIC)`、`withBreaks(...)`、`withExtra(amount, sev, kinds...)`、`withWeapon(w)`、`asUnavoidable()`、`withHalfUp()`。
法术（轰雷剑、冻寒骨爪）附带【魔法】。

## 显示

服务端每秒比较一次 `describe(profile(p))`，变化时发送 `DamageKeywordsPayload`（协议 23）。
- 属性页右下「◈ 减伤关键字 N」标签，悬停列出全部关键字；
- 打开聊天栏 / 物品栏时悬停防御 HUD 显示同样内容。

## 现有提供者

- 亡灵 = 黑暗生物；抗火药水 = 免疫火焰；
- 初级防护 = 伤害忽略 1（`ignore`，与其他忽略取高）。
