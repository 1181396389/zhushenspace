package com.zhushen.space.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端 UI 偏好（JSON 持久化到 config/zhushenspace_client.json）：
 * - 能量池 HUD 位置与缩放（多个能量池整组移动/缩放）
 * - 战斗模式伤势 HUD 位置与缩放
 * - 战斗模式伤害区间 HUD 位置与缩放
 * - 战斗模式当前生效的预设栏（A/B）
 */
public class ClientUiConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("zhushenspace_client.json");

    private static Data data;
    private static boolean dirty = false;

    public static class Data {
        /** 能量池 HUD 左上角（-1 表示使用默认位置：屏幕左侧垂直居中） */
        public float energyX = -1;
        public float energyY = -1;
        /** 能量池整体缩放（0.5 ~ 2.0） */
        public float energyScale = 1.0f;
        /** 战斗模式伤势 HUD 左上角（-1 表示默认位置：屏幕右上角） */
        public float woundX = -1;
        public float woundY = -1;
        /** 伤势 HUD 缩放（0.5 ~ 2.0） */
        public float woundScale = 1.0f;
        /** 战斗模式伤害区间 HUD 左上角（-1 表示默认位置：屏幕上方居中） */
        public float damageX = -1;
        public float damageY = -1;
        /** 伤害区间 HUD 缩放（0.5 ~ 2.0） */
        public float damageScale = 1.0f;
        /** 战斗模式当前生效的预设栏（0=A，1=B） */
        public int activeBar = 0;
        /** 拔剑演出时是否浮现宝具名（誓约胜利之剑 / 乖离剑） */
        public boolean showSwordNames = true;
        /** 专长页术式小游戏开关 */
        public boolean jjkGame = true;
        /** 战斗模式肢体 HUD：是否显示 */
        public boolean limbHudEnabled = true;
        /** 肢体 HUD 风格：0 = LV.999 像素终端，1 = Q 版大黑塔桌宠提醒 */
        public int limbHudStyle = 0;
        /** 大黑塔桌宠是否闲聊（事件提醒始终开启） */
        public boolean limbHudQuips = true;
        /** 肢体 HUD 左上角（-1 = 默认：伤势面板下方） */
        public float limbX = -1;
        public float limbY = -1;
        /** 肢体 HUD 缩放（0.5 ~ 2.0） */
        public float limbScale = 1.0f;
    }

    public static Data get() {
        if (data == null) load();
        return data;
    }

    private static void load() {
        try {
            if (Files.exists(PATH)) {
                data = GSON.fromJson(Files.readString(PATH), Data.class);
            }
        } catch (Exception ignored) {
        }
        if (data == null) data = new Data();
        // 越界防御
        if (data.energyScale < 0.5f || data.energyScale > 2.0f) data.energyScale = 1.0f;
        if (data.woundScale < 0.5f || data.woundScale > 2.0f) data.woundScale = 1.0f;
        if (data.damageScale < 0.5f || data.damageScale > 2.0f) data.damageScale = 1.0f;
        if (data.limbScale < 0.5f || data.limbScale > 2.0f) data.limbScale = 1.0f;
        if (data.limbHudStyle < 0 || data.limbHudStyle > 1) data.limbHudStyle = 0;
        if (data.activeBar < 0 || data.activeBar > 1) data.activeBar = 0;
    }

    /** 标记修改（本 tick 结束时统一落盘） */
    public static void save() {
        dirty = true;
    }

    /** 实际写盘；在事件循环末尾调用 */
    public static void saveNow() {
        if (!dirty) return;
        dirty = false;
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(data));
        } catch (Exception ignored) {
        }
    }
}
