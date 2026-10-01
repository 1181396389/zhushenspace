package com.zhushen.space.client;

import com.zhushen.space.data.DamageKind;
import com.zhushen.space.network.DamageKeywordsPayload;
import com.zhushen.space.network.DamageKeywordsPayload.Entry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** 客户端：把同步来的减伤关键字排成悬停提示（属性面板「减伤关键字」标签 / 防御 HUD 悬停） */
public final class DamageKeywordText {

    private DamageKeywordText() {}

    private static final String K = "dmgkw.zhushenspace.";
    private static final String[] WEAK = {"magic", "divine", "pierce", "blunt", "slash"};
    private static final String[] SCOPE = {"physical", "energy", "all"};
    private static final String[] LAYER = {"shield", "armor", "self"};
    private static final String[] SEV = {"b", "l", "a"};

    private static MutableComponent kinds(int mask) {
        MutableComponent c = Component.empty();
        boolean first = true;
        for (DamageKind k : DamageKind.values()) {
            if ((mask & (1 << k.ordinal())) == 0) continue;
            if (!first) c.append(Component.translatable(K + "sep"));
            c.append(Component.translatable(k.nameKey()));
            first = false;
        }
        return c;
    }

    private static MutableComponent list(int mask, String prefix, String[] keys) {
        MutableComponent c = Component.empty();
        boolean first = true;
        for (int i = 0; i < keys.length; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (!first) c.append(Component.translatable(K + "sep"));
            c.append(Component.translatable(K + prefix + keys[i]));
            first = false;
        }
        return c;
    }

    /** 范围后缀：（火焰、寒冰）；默认范围为空 */
    private static Component scope(int mask) {
        return mask == 0 ? Component.empty() : Component.translatable(K + "scope", kinds(mask));
    }

    private static Component line(Entry e) {
        int x = e.extra() & ~DamageKeywordsPayload.ITEM_BIT;
        return switch (e.type()) {
            case DamageKeywordsPayload.IMMUNE -> Component.translatable(K + "immune", kinds(e.kinds()));
            case DamageKeywordsPayload.IMMUNE_SEV -> Component.translatable(K + "immune_sev", list(x, "sev.", SEV));
            case DamageKeywordsPayload.IGNORE -> Component.translatable(K + "ignore", e.value(), scope(e.kinds()));
            case DamageKeywordsPayload.IGNORE_IF -> Component.translatable(K + "ignore_if", Component.translatable(e.source()));
            case DamageKeywordsPayload.HARDNESS -> Component.translatable(K + "hardness", e.value());
            case DamageKeywordsPayload.OFFSET -> Component.translatable(K + "offset", e.value(), scope(e.kinds()));
            case DamageKeywordsPayload.RESIST -> e.kinds() == 0
                    ? Component.translatable(K + "resist_all", e.value())
                    : Component.translatable(K + "resist", kinds(e.kinds()), e.value());
            case DamageKeywordsPayload.DR -> Component.translatable(K + "dr", e.value(),
                    x == 0 ? Component.literal("-") : list(x, "weak.", WEAK));
            case DamageKeywordsPayload.ABSORB -> Component.translatable(K + "absorb",
                    Component.translatable(K + "absorb." + SCOPE[Math.max(0, Math.min(2, x))]), e.value());
            case DamageKeywordsPayload.THRESHOLD -> Component.translatable(K + "threshold", e.value());
            case DamageKeywordsPayload.CONVERT -> e.value() < 0
                    ? Component.translatable(K + "convert_all", Component.translatable(e.source()),
                    Component.translatable(K + "layer." + LAYER[Math.max(0, Math.min(2, x))]))
                    : Component.translatable(K + "convert", Component.translatable(e.source()), e.value(),
                    Component.translatable(K + "layer." + LAYER[Math.max(0, Math.min(2, x))]));
            case DamageKeywordsPayload.VULN_DOUBLE -> Component.translatable(K + "vuln_double", kinds(e.kinds()));
            case DamageKeywordsPayload.VULN -> Component.translatable(K + "vuln", e.value(), kinds(e.kinds()));
            case DamageKeywordsPayload.LIGHT -> Component.translatable(K + "light");
            case DamageKeywordsPayload.DARK -> Component.translatable(K + "dark");
            default -> Component.literal("?");
        };
    }

    private static ChatFormatting color(int type) {
        return switch (type) {
            case DamageKeywordsPayload.VULN, DamageKeywordsPayload.VULN_DOUBLE -> ChatFormatting.RED;
            case DamageKeywordsPayload.IMMUNE, DamageKeywordsPayload.IMMUNE_SEV -> ChatFormatting.GOLD;
            case DamageKeywordsPayload.CONVERT -> ChatFormatting.LIGHT_PURPLE;
            case DamageKeywordsPayload.LIGHT, DamageKeywordsPayload.DARK -> ChatFormatting.GRAY;
            default -> ChatFormatting.AQUA;
        };
    }

    public static boolean any() { return !ClientDefenseData.keywords().isEmpty(); }

    /** 悬停提示的全部行 */
    public static List<FormattedCharSequence> tooltip(Font font, int width) {
        List<FormattedCharSequence> out = new ArrayList<>();
        out.add(Component.translatable(K + "title").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD).getVisualOrderText());
        List<Entry> es = ClientDefenseData.keywords();
        if (es.isEmpty()) {
            out.addAll(font.split(Component.translatable(K + "none").withStyle(ChatFormatting.GRAY), width));
        }
        for (Entry e : es) {
            MutableComponent c = Component.literal("◆ ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(line(e).copy().withStyle(color(e.type())));
            if ((e.extra() & DamageKeywordsPayload.ITEM_BIT) != 0)
                c.append(Component.translatable(K + "item").withStyle(ChatFormatting.DARK_AQUA));
            boolean named = e.type() != DamageKeywordsPayload.IGNORE_IF && e.type() != DamageKeywordsPayload.CONVERT;
            if (named && e.source() != null && !e.source().isEmpty())
                c.append(Component.translatable(K + "from", Component.translatable(e.source())).withStyle(ChatFormatting.GRAY));
            out.addAll(font.split(c, width));
        }
        out.addAll(font.split(Component.translatable(K + "hint").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), width));
        return out;
    }
}
