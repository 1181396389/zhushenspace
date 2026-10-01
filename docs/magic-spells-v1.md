# 魔法·专业法术 v1 + 目标规则 + 研发修复

## 目标规则
- 「目标」= 技艺能影响多少个敌人；「一个目标」只表示不能范围攻击。
- 所有技艺 / 太极八式 / 擒抱都**无需锁定**：没有对准目标时照常施放（消耗、冷却、动作、特效）并打空（提示「没有命中任何目标」）。
- 只有 `ArtSkill.lockOn()` 为 true（标注【锁定】）的技艺必须先锁定；目前没有这种技艺。

## 研发修复
- 旧实现从 `PlayerCurrencyData.xp` 扣 XP，但这个值从未发放，所以研发永远「XP 不足」。
- 现在研发 / 额外选项花费建卡的**未分配 XP**：`BuildServer.freeXp` = 建卡总 XP − 属性 − 技能 − 专长 − 已用于技艺的 XP（`PlayerBuildData.artXp`，NBT `ArtXp`）。
- 建卡界面看到的总 XP = `totalXp − artXp`；重置建卡时清零 artXp。技艺页显示的 XP 跟随刷新。

## 四个新法术（魔法池，500 积分，无需分支）
| 法术 | 学派 | 魔力 | 成分 | 距离 | 目标 / 范围 | 持续 |
|---|---|---|---|---|---|---|
| 轰雷剑【光】 | 塑能 | 1 | 言语 | 触及 | 一个目标 | 1 轮 |
| 光亮术【光】 | 咒法 | 0 | 言语 | 触及 | 一件物品 | 10 分钟 |
| 照明术【光】 | 咒法 | 1 | 言语、姿势 | 触及 | 半径 10 米球形 | 10 分钟 |
| 冻寒骨爪【暗】【水】 | 死灵 | 0 | 言语、姿势 | 智力 米 | 一个目标 | 立即 |

- 1 轮 = 3 秒（60 tick）。
- **轰雷剑**：近战施法攻击。手持白刃时用武器伤害代替威力，继承力量前提 / 专业减值与攻击后附魔效果。命中不造成伤害，只标记并记录伤害。被标记者在一轮内主动移动（水平位移或向上位移；受击击退、乘骑不算）或传送（末影珍珠 / 紫颂果 / 末影人）时，立即受到等量雷电严重伤害。
- **光亮术**：在物品上写入数据组件 `zhushenspace:light_until`。物品被手持、穿戴、掉落或放进物品展示框时都会发光；提示框显示剩余时间。
- **照明术**：4 枚光球（`ArtVfx.LIGHT_ORBS`，跟随施法者，无碰撞、不可攻击）。`MagicSpells.dispelIllumination` 供解除魔法调用。范围内带实体标签 `zhushenspace:spirit` 的生物（默认恼鬼、悦灵，可用数据包扩充）会被移除隐身并发光。`MagicSpells.spiritRevealed(entity)` 供以后的灵体系统查询。
- **冻寒骨爪**：寒冰严重伤害。研发「镇亡」6XP：命中不死生物后，其攻击检定 −6，持续一轮（怪物的攻击在 `Defense.apply` 中扣除，玩家在 `StatusEffects.attackPenalty` 中扣除）。研发「凋寒」12XP：伤害变为寒冰 + 亵渎。

## 动态光源（客户端，只改画面）
- `client/DynamicLights`：光源半径内为 15 级方块光，边缘 4 米内渐暗。光源移动超过阈值（物品 2 格 / 光球 1.25 格）时，重建与光照球相交的区块段。
- Mixin：`LevelRenderer.getLightColor(BlockAndTintGetter, BlockState, BlockPos)`（方块网格、粒子、方块实体）和 `EntityRenderer.getBlockLightLevel`（实体）在返回时取较亮者。
- 不改世界光照，不影响刷怪。Sodium / Embeddium 自带网格构建，在它们之下地形不会显示动态光（实体仍会被照亮）。

## 特效与动作
- ArtVfx 新增 6 种：THUNDER_BLADE（剑身聚雷 + 斜向雷光斩）、THUNDER_MARK（足下雷纹阵 + 头顶倒计时雷印）、THUNDER_STRIKE（天降落雷 + 地面冲击环 + 屏幕震动）、LUMEN（物品点亮星芒）、LIGHT_ORBS（四枚浮空光球）、FROST_CLAW（飞掠骨爪 + 三道爪痕 + 冰晶；凋寒版带紫黑色）。
- 动作：`art_thunder_sword`（举刃、左手抹刃、斜斩）、`art_light`（高举物品）、`art_illumination`（合捧后上托展开）、`art_frost_claw`（沉身由下反撩）。
