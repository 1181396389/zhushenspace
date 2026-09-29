# 生成帕秋莉手册《主神空间·轮回者手册》（zh_cn 正文，en_us 回退同内容）
import json, os
ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
BOOK = 'guide'
data_dir = os.path.join(ROOT, 'data', 'zhushenspace', 'patchouli_books', BOOK)
os.makedirs(data_dir, exist_ok=True)
json.dump({
    "name": "book.zhushenspace.guide.name",
    "landing_text": "book.zhushenspace.guide.landing",
    "version": 1,
    "use_resource_pack": True,
    "model": "patchouli:book_purple",
    "book_texture": "patchouli:textures/gui/book_purple.png",
    "show_progress": False,
    "subtitle": "book.zhushenspace.guide.subtitle",
    "creative_tab": "zhushenspace:zhushen_tab",
    "i18n": True
}, open(os.path.join(data_dir, 'book.json'), 'w', encoding='utf-8'), indent=2, ensure_ascii=False)

T = lambda text, title=None: ({"type": "patchouli:text", "title": title, "text": text} if title else {"type": "patchouli:text", "text": text})
S = lambda item, title, text: {"type": "patchouli:spotlight", "item": item, "title": title, "text": text}

CATS = [
 ("basics", "入门", "从邀请函到第一场战斗。", "zhushenspace:invitation_envelope", 0),
 ("stats", "属性与技能", "九项属性、九项技能与武器专业。", "minecraft:iron_sword", 1),
 ("combat", "战斗与伤势", "判定、伤害类型、伤势与肢体。", "minecraft:shield", 2),
 ("feats", "专长与能量池", "专长、九种能量池及其基础用法。", "minecraft:amethyst_shard", 3),
 ("shop", "商城与技艺", "货币、流派、技艺与施法规则。", "minecraft:emerald", 4),
 ("ref", "按键与指令", "速查表。", "minecraft:command_block", 5),
]

E_ = {
"basics": [
 ("envelope", "主神邀请函", "zhushenspace:invitation_envelope", [
   S("zhushenspace:invitation_envelope", "主神邀请函", "右键使用邀请函，接受主神的邀请。$(br2)使用后才能打开$(l)主神面板$()：在背包界面上方点击「主神面板」选项卡。"),
   T("面板共五页：$(li)$(bold)加点$()：属性与技能$(li)$(bold)预设$()：战斗技能栏 A / B$(li)$(bold)商城$()：流派与技艺$(li)$(bold)专长$()：已习得专长$(li)$(bold)技能$()：技能与武器专业", "主神面板")]),
 ("creation", "建卡 XP", "minecraft:experience_bottle", [
   T("新人物共有 $(bold)70 XP$()，需同时满足：$(li)属性投入 9~30$(li)技能投入 15~45$(li)专长投入至少 15$(br2)状态栏超出区间时会标红，全部满足后才能确认建卡。", "分配规则"),
   T("$(bold)属性$()：基础 1，上限 5，每升 1 级花费 = 当前等级。$(br)$(bold)技能$()：上限 15；0→1 花 1，此后每级 = 等级×2。$(br)$(bold)专长$()：普通专长 3×等级，轮回专长 6×等级。$(br)$(bold)特殊身份$() 1~3 级，$(bold)超凡身份$() 5 级 45 XP。", "花费")]),
 ("combat_mode", "战斗模式与预设", "minecraft:netherite_sword", [
   T("按 $(k:key.zhushenspace.toggle_combat) 切换战斗模式。战斗模式下：$(li)数字键 1~9 施放当前技能栏$(li)$(k:key.zhushenspace.switch_bar) 切换 A / B 栏$(li)肢体 HUD 显示在屏幕上$(br2)在面板「预设」页把技能芯片拖入技能栏。", "战斗模式"),
   T("按住 $(k:key.zhushenspace.art_wheel) 打开$(bold)动作轮盘$()，松开关闭。$(br2)不占技能栏的主动效果都在这里：意志加持、意志守御、能量加值、魔力感知、蛛行术、水面行走、灵感视觉、冥想、留手、增幅、八阵图元素。$(br)拥有对应能量池 / 技艺后才会出现。", "动作轮盘")]),
],
"stats": [
 ("attributes", "属性", "minecraft:golden_apple", [
   T("$(bold)生理$()：力量、敏捷、耐力$(br)$(bold)心智$()：智力、感知、决心$(br)$(bold)社交$()：风度、操作、沉着$(br2)属性满 5 即为$(bold)传奇$()，会解锁额外加成（见面板悬停说明）。", "九项属性"),
   T("$(li)$(bold)力量$()：近战判定$(li)$(bold)敏捷$()：护甲、投掷与弓箭判定$(li)$(bold)耐力$()：生命值$(li)$(bold)智力$()：魔法关键属性$(li)$(bold)感知$()：弱点光点、查克拉关键属性$(li)$(bold)决心$()：意志力池、心灵检定$(li)$(bold)风度$()：回声检定、道术 / 妖术$(li)$(bold)沉着$()：心灵检定、意志豁免", "作用")]),
 ("skills", "技能", "minecraft:book", [
   T("$(li)$(bold)运动$()：投掷与弓箭；自我保护 / 跳跃 / 攀爬$(li)$(bold)肉搏$()：徒手；格挡 / 摔绊 / 冲锋 / 擒抱$(li)$(bold)白刃$()：人造武器；白刃格挡$(li)$(bold)神秘学$()：施法判定$(li)$(bold)表达$()：回声检定$(li)$(bold)枪械$()：需 TACZ$(li)手艺、科学、动物沟通", "九项技能")]),
 ("weapons", "武器与专业", "minecraft:crossbow", [
   T("近战：$(bold)力量 + 技能 + 武器伤害 − 目标防御$()，再乘 20%~100% 浮动。$(br)弓箭 / 投掷：敏捷 + 运动 + 武器伤害 − 防御 − 距离减值（每超 1 个射程单位 −6）。$(br2)力量不足每差 1 点 −6，差 3 点以上无法使用。", "攻击公式"),
   T("白刃分类：长剑、重剑、突刺剑、扇子。$(br)枪械分类：手枪、冲锋枪、散弹枪、机枪、步枪、炮。$(br2)技能达到 3、4 级各免费获得一个专业，加点最多共 3 个。使用没有专业的分类 −9。", "专业")]),
],
"combat": [
 ("wounds", "伤势", "minecraft:red_dye", [
   T("伤害分三级：$(li)$(bold)B 冲击$()$(li)$(bold)L 严重$()$(li)$(bold)A 恶性$()$(br2)伤势填满生命后会逐级恶化。严重伤满载则$(bold)昏迷$()，治愈全部严重伤才会苏醒；恶性伤填满才会$(bold)死亡$()。", "B / L / A"),
   T("身体分为头、躯干、双臂、双腿，护甲按部位计算。$(li)头部血量清空：昏迷$(li)手臂断裂：该手无法交互物品$(li)双腿断裂：倒地，体积缩为一格$(br)断肢暂由管理员指令恢复。", "肢体")]),
 ("damage", "伤害类型", "minecraft:blaze_powder", [
   T("物理：钝击、挥砍、穿刺。$(br)能量：纯能量、火、寒、电、酸、神圣、邪恶、音波、光。$(br)另有心灵、力场、毒素。$(br2)结算顺序：免疫 → 忽略 → 硬度 / 抵消 → 抗力 / 减免 → 吸收 → 阈值 → 易伤。混合伤害取最弱的防御。", "类型与结算"),
   T("$(bold)【高速X】$()：目标防御 −X。$(br)$(bold)【破甲X】$()：防御再 −X。$(br)$(bold)【破魔X】$()：无视 X 点能量抗力。$(br)$(bold)【击飞】$()：距离 = 伤害 − max(体型+5, 耐力, 力量)；反射豁免低于距离则倒地。$(br)$(bold)措手不及$()：失去天生防御，不能做反射动作。$(br)坠落：超过 3 米每 2 米 1 点严重伤，上限 100。", "关键词")]),
 ("checks", "检定与意志力", "minecraft:ender_eye", [
   T("$(bold)心灵检定$() = 决心 + 沉着 ± 调整，上限同值。$(br)$(bold)回声检定$() = 风度 + 表达子技能；【回声】武器计入伤害。$(br)$(bold)意志豁免$() = 决心 + 沉着；$(bold)反射豁免$() = 敏捷 + 运动；豁免值从伤害中扣除。$(br2)1DP = 3 点；附加成功在浮动后直接加。", "检定"),
   T("意志力池 = 决心。$(li)$(bold)意志加持$()：下一次攻击 / 对抗 +9 固定伤害$(li)$(bold)意志守御$()：下一次受击 +9 护甲与韧性$(li)$(bold)强撑$()：昏迷时保持清醒（伤势照常计算）$(br)均在动作轮盘中使用。", "意志力")]),
 ("grapple", "擒抱", "minecraft:lead", [
   T("擒抱是肉搏的技能能力。对抗值 = 数值 × 20%~100% 浮动，1 轮 = 3 秒。$(br2)被擒抱时失去天生防御，也无法施放需要姿势的法术。", "擒抱")]),
],
"feats": [
 ("feats", "专长", "minecraft:nether_star", [
   T("专长在建卡时用 XP 购买，分为建卡专长、普通专长与轮回专长。$(br2)第六感、精神力天赋、妖怪血脉、转世活佛、魔法体质、先天道体、灵能体质、武学奇才、查克拉体质各自赋予一个$(bold)能量池$()。", "专长")]),
 ("pools_west", "能量池·西方", "minecraft:lapis_lazuli", [
   T("$(bold)池 = 智力 + 感知$()$(li)能量加值：心智系检定花 1 点 +1DP$(li)$(bold)魔力感知$()：花 1 点，1 分钟内半径 感知 米的魔法波动（魔力池生物、正在生效的法术）会发光，附近施法会提示$(br)长休回满；冥想：智力+感知 检定。", "魔力"),
   T("$(bold)池 = 决心 + 沉着$()$(li)能量加值：任何检定花 1 点 +1DP$(br)长休后进行一次心灵检定，恢复成功数。", "灵能"),
   T("$(bold)池 = 决心 + 沉着，每点传奇决心 / 沉着 +5$()$(li)能量加值：决心 / 沉着检定 +1DP$(br)长休回满；冥想：决心+沉着 检定。", "精神力")]),
 ("pools_east", "能量池·东方", "minecraft:blaze_rod", [
   T("$(bold)池 = 耐力 + 感知$()$(li)$(bold)蛛行术$()：花 1 点，1 分钟随意爬墙$(li)$(bold)水面行走$()：花 1 点，1 分钟$(br)持续期间每分钟自动续 1 点，再点一次关闭。长休回满；冥想：耐力+感知 检定。", "查克拉"),
   T("上限 = 查克拉基础上限，分开计算，不受任何上限增减；每有 1 点，查克拉上限 −1。$(br)静止每 60 秒掷 1D10，出 10 则 1 点查克拉化为仙术查克拉。$(br)仙人模式下施展忍术可消耗 1 点，威力 +X（D2 C4 B8 A16 S32）。", "仙术查克拉"),
   T("$(bold)池 = 感知 + 风度$()$(li)道术施法额外花 1 点：+3DP$(li)$(bold)灵感视觉$()：花 1 点，1 分钟可见并接触灵体$(br)长休回满；冥想：感知+风度 检定。", "道力"),
   T("$(bold)池 = 决心 + 风度$()$(li)抵抗心灵影响时花 1 点：+2DP$(li)池不为空时能看出不死生物 / 黑暗生物$(br)长休回满；冥想（诵经）：决心+风度 检定。", "佛力"),
   T("$(bold)池 = 耐力 + 风度$()$(li)能量加值：生理系与感知检定 +1DP$(br)子时 / 午时冥想 20 秒回满；其他时刻冥想进行 耐力+风度 检定。", "妖力")]),
 ("pools_special", "灵力与内力", "minecraft:soul_lantern", [
   T("$(bold)池 = 决心 + 沉着，每点传奇决心 / 沉着 +3$()$(br)使用灵力需进行$(bold)启动检定$()（心灵检定），失败则消耗不返还；无论成败累积 消耗/3 的$(bold)灵感疲劳$()，满载则无法再用。$(br)消耗 + 增幅不得超过 决心+沉着。$(br)长休清零；冥想降低 传奇决心+传奇沉着 点。", "灵力"),
   T("$(bold)池 = 耐力 + 感知$()$(li)内力吐息：近战每次花 1 点 +6$(li)打坐：5 秒回满$(br)由武学奇才专长或太极拳徽记提供。", "内力"),
   T("$(bold)长休$()：睡觉跳过夜晚。$(br)$(bold)冥想（短休）$()：动作轮盘中选择，原地 20 秒，冷却 5 分钟，按各池规则恢复。$(br2)$(bold)能量加值$()在动作轮盘中开关，开启后相关检定自动花 1 点；同类加值不叠加。", "恢复与能量加值")]),
],
"shop": [
 ("currency", "货币", "minecraft:gold_ingot", [
   T("$(bold)积分$()与五级$(bold)支线$()（S / A / B / C / D）。价格写作「D+500」= 1 个 D 级支线 + 500 积分。$(br)支线可在货币界面合成与拆分。$(br2)$(bold)XP$() 用于技艺研发。", "积分与支线")]),
 ("school", "流派·太极拳", "zhushenspace:tai_chi_emblem", [
   S("zhushenspace:tai_chi_emblem", "太极拳徽记", "购买流派后领取徽记，装备到流派饰品栏生效：获得内力池，徒手时天生武器攻击 +6、护甲 +6。"),
   T("八式（掤、捋、挤、按、采、挒、肘、靠）与揽雀尾、云手、海底针、引手、太极化劲等招式需在商城详情页单独购买；听劲随流派赠送。$(br)集齐八式后可购买被动「八劲合一」。", "招式")]),
 ("arts", "技艺", "minecraft:enchanted_book", [
   T("商城中打开「技艺 → 通用技艺」，按能量池分组：灵力、精神力、灵能、妖力、佛法、魔法、道术、内力、查克拉。$(br)拥有对应能量池才能购买与施放。买下后拖入预设使用，冷却 1 轮（3 秒）。$(br2)部分技艺有$(bold)选项$()（✦ 首个免费）与$(bold)研发$()（花 XP）。", "技艺"),
   T("$(bold)施法判定$() = 关键属性 + 神秘学。$(br)关键属性：魔法 智力（天生魔力用风度）、道术 风度、佛法 决心、忍术 感知。$(br)$(bold)伤害上限$()：持武器 = 武器×1.5 + 关键属性/3 + 神秘学；否则 = 威力×3 + 关键属性/3 + 神秘学。$(br)威力：D3 C6 B12 A24 S48。", "施法"),
   T("$(bold)姿势$()：需要一只空手，擒抱 / 措手不及时不可施放。$(br)$(bold)语言$()：施法时在聊天栏念出法术名。$(br2)$(bold)留手$()：在轮盘设定百分比，施放时 +1D51−25；为 0 则施放失败。$(br)$(bold)增幅$()：默认关，潜行施放 = 用满。", "成分、留手与增幅")]),
 ("art_list", "技艺一览", "minecraft:written_book", [
   T("$(bold)灵力$()：灵斩、灵力治疗$(br)$(bold)精神力$()：精神冲击、精神震荡$(br)$(bold)灵能$()：心灵系-无视我、生化系-生物闪电$(br)$(bold)妖力$()：黄泉活力、风斩$(br)$(bold)佛法$()：息法、夜叉空行", "一览（上）"),
   T("$(bold)魔法$()：魔能爆、初级防护$(br)$(bold)道术$()：五行道法、八阵图$(br)$(bold)内力$()：波动拳、基础掌法、起死回生$(br)$(bold)查克拉$()：火遁·凤仙火之术、火遁·豪火球之术$(br2)详细效果在商城中悬停查看。", "一览（下）")]),
],
"ref": [
 ("keys", "按键", "minecraft:tripwire_hook", [
   T("$(li)$(k:key.zhushenspace.toggle_combat)：战斗模式$(li)1~9：施放技能（战斗模式）$(li)$(k:key.zhushenspace.switch_bar)：切换 A / B 栏$(li)按住 $(k:key.zhushenspace.art_wheel)：动作轮盘$(br2)意志加持 / 意志守御默认不占键，可在「控制」中自行绑定。", "按键")]),
 ("commands", "管理员指令", "minecraft:command_block", [
   T("根指令 $(bold)/zhushen$()（权限 2）：$(li)currency give / show：发放、查看货币$(li)school unlock / grant：流派$(li)build reset / xp / info：建卡$(li)limb restore / sever：肢体$(li)energy give / set / clear：能量池$(li)resetall <玩家> [norefund]：重置加点与全部购买$(li)guide [玩家]：发放本手册", "指令")]),
],
}

for loc in ('zh_cn', 'en_us'):
    base = os.path.join(ROOT, 'assets', 'zhushenspace', 'patchouli_books', BOOK, loc)
    for cid, name, desc, icon, order in CATS:
        os.makedirs(os.path.join(base, 'categories'), exist_ok=True)
        json.dump({"name": name, "description": desc, "icon": icon, "sortnum": order},
                  open(os.path.join(base, 'categories', cid + '.json'), 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
        edir = os.path.join(base, 'entries', cid)
        os.makedirs(edir, exist_ok=True)
        for i, (eid, ename, eicon, pages) in enumerate(E_[cid]):
            json.dump({"name": ename, "icon": eicon, "category": "zhushenspace:" + cid, "sortnum": i, "pages": pages},
                      open(os.path.join(edir, eid + '.json'), 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
print('ok')
