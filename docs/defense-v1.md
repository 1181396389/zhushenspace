# 主神空间防御与豁免（v1）

原版护甲 / 护甲韧性 / 保护类附魔不再按比例减伤（`Defense.onIncoming`，HIGH，把 ARMOR 与 ENCHANTMENTS 减免置 0）。
护甲条仍挂着各项加值，只作参考显示。

## 结算

- 玩家的攻击：`CombatFormula` 判定公式 `… − 目标防御`（`Defense.of(victim, src, true)`）。
- 非玩家的攻击：浮动（`DamageVariance`，NORMAL）之后 `伤害 − 防御`（`Defense.apply`）；≤0 取消。
- 爆炸：`伤害 − 范围豁免`（不计防御）。
- 模组能力伤害（`DamageRules.deal`）：检定中自行扣防御 / 豁免，这里跳过。
- 无视护甲（BYPASSES_ARMOR）：不计防御。

## 防御 = 基础 + 全力 + 格挡 + 闪避 + 天生 + 盔甲 + 洞察 + 其他 ± 状态

| 部分 | 来源 |
|---|---|
| 基础 | max(敏捷, 感知) + 该属性传奇值（属性 − 4）− 盔甲减值 − 冲锋削弱，最低 0 |
| 全力 | 开启全力防御时 = 基础 |
| 格挡 | 修饰器 `brawl_block`、`blade_block` |
| 闪避 | 体型调整（`FeatEffects.dodgeDefenseBonus`）、`art_basic_palm` |
| 天生 | 巨大身材 +1、`art_breath_outer` |
| 盔甲 | 覆盖命中部位的装备护甲（来源未知时整套） |
| 洞察 | 暂未开放（0） |
| 其他 | 其余 ARMOR 加法修饰器（自我保护、意志守御、初级防护、太极、石化……） |
| 状态 | `StatusEffects.defenseMod`（倒地远程 +3 / 近战 −6、轻度不良 −4、亢奋 −12） |

- 措手不及 / 擒抱中面对组外攻击：基础、全力、格挡归 0，闪避只保留负值。
- 失去天生防御类状态：基础、全力归 0，闪避只保留负值；无法格挡时格挡归 0。
- 意志守御：在防御结算时消耗（`WillpowerManager.consumeGuard`），+9。

## 全力防御

动作轮盘条目（action 23）。开启期间 + 基础防御；发起攻击（AttackEntityEvent、松弦、造成伤害）、无法行动、死亡、下线时解除。
同步标志 `PoolEffects.F_FULL_DEF = 128`。

## 豁免（确定值 × 20%~100% 浮动）

- 意志 = 决心 + 感受 + 传奇决心 + 能量加值 − 状态减值
- 反射 = 敏捷 + 运动 + 传奇敏捷 + 能量加值 ± 状态修正
- 强韧 = 耐力 + 求生 + 传奇耐力 + 能量加值 − 状态减值
- 范围 = 反射 + 对抗范围效果的加值（倒地 +3）

新增技能：感受（FEELING）、求生（SURVIVAL）。自我保护：运动或求生达到 3 点解锁。

## 防御 HUD（艾克赛德风格，取代原版护甲条）

- 服务端 `Defense.onTick` 每 5 tick 计算 `Defense.hud(p)`（整套盔甲、无特定攻击者；能量加值按「若开启」预览，不消耗），
  变化时发送 `DefenseHudPayload`（防御、基础、盔甲、意志、反射、强韧、范围、状态位），每 20 秒强制同步一次。
- 客户端 `DefenseHudRenderer` 取消 `VanillaGuiLayers.ARMOR_LEVEL`，在原护甲条位置绘制并上推 `Gui.leftHeight`；
  拖动后改为固定位置（`RenderGuiEvent.Post`）。状态位：全力防御（金色）、措手不及 / 失去基础（褪色）、无法反射（反射 / 范围熄灭）。
- 配置：`defHudEnabled / defX / defY / defScale`（界面设置中拖动、缩放、开关）。
