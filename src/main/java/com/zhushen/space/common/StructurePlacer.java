package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 结构加载器：摆放场景文件，供大厅与后续副本复用。
 * <p>三级来源（大厅按此优先级取 hall_cafe）：</p>
 * <ol>
 *   <li>外部目录 {@code config/zhushenspace/structures/<name>.nbt} —— 结构方块格式，
 *       完整支持方块实体（箱子/告示牌文字）</li>
 *   <li>外部目录 {@code config/zhushenspace/structures/<name>.schem} —— Sponge 原理图 v2/v3
 *       （WorldEdit/Litematica 通用分享格式），逐方块摆放（方块实体暂不支持）</li>
 *   <li>mod 内置 {@code data/zhushenspace/structure/<name>.nbt}</li>
 * </ol>
 * <p>外部目录的好处：替换建筑文件无需重新打包 mod，放好文件执行
 * {@code /zhushen hall rebuild} 即可生效。</p>
 */
public final class StructurePlacer {

    private StructurePlacer() {
    }

    /** 外部场景目录 */
    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve("zhushenspace").resolve("structures");
    }

    // ===== mod 内置结构 =====

    /** 内置结构尺寸 [w,h,l]，不存在返回 null */
    public static int[] builtinSize(ServerLevel level, ResourceLocation id) {
        Optional<StructureTemplate> t = template(level, id);
        if (t.isEmpty()) return null;
        var s = t.get().getSize();
        return new int[]{s.getX(), s.getY(), s.getZ()};
    }

    /** 摆放 mod 内置结构（anchor = 结构最小角），返回是否成功 */
    public static boolean placeBuiltin(ServerLevel level, ResourceLocation id, BlockPos anchor) {
        Optional<StructureTemplate> t = template(level, id);
        if (t.isEmpty()) return false;
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(false);
        return t.get().placeInWorld(level, anchor, anchor, settings, level.getRandom(), 3);
    }

    private static Optional<StructureTemplate> template(ServerLevel level, ResourceLocation id) {
        StructureTemplateManager mgr = level.getServer().getStructureManager();
        return mgr.get(id);
    }

    // ===== config 目录场景 =====

    /** config 场景尺寸 [w,h,l]，无可用文件返回 null */
    public static int[] configSize(String name) {
        Path nbt = resolve(name, ".nbt");
        if (nbt != null) {
            try {
                CompoundTag tag = readNbtAny(nbt);
                StructureTemplate t = new StructureTemplate();
                t.load(BuiltInRegistries.BLOCK.asLookup(), tag);
                var s = t.getSize();
                return new int[]{s.getX(), s.getY(), s.getZ()};
            } catch (Exception e) {
                ZhuShenSpace.LOGGER.warn("读取结构文件失败: {}", nbt, e);
            }
            return null;
        }
        Path schem = resolve(name, ".schem");
        if (schem != null) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(schem))) {
                CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                return new int[]{root.getShort("Width") & 0xFFFF,
                        root.getShort("Height") & 0xFFFF,
                        root.getShort("Length") & 0xFFFF};
            } catch (Exception e) {
                ZhuShenSpace.LOGGER.warn("读取原理图文件失败: {}", schem, e);
            }
        }
        return null;
    }

    /** 摆放 config 场景（anchor = 场景最小角），返回是否成功 */
    public static boolean placeFromConfig(ServerLevel level, String name, BlockPos anchor) {
        Path nbt = resolve(name, ".nbt");
        if (nbt != null) {
            try {
                CompoundTag tag = readNbtAny(nbt);
                StructureTemplate t = new StructureTemplate();
                t.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
                StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(false);
                return t.placeInWorld(level, anchor, anchor, settings, level.getRandom(), 3);
            } catch (Exception e) {
                ZhuShenSpace.LOGGER.warn("摆放结构文件失败: {}", nbt, e);
                return false;
            }
        }
        Path schem = resolve(name, ".schem");
        if (schem != null) {
            return placeSchem(level, schem, anchor);
        }
        return false;
    }

    private static Path resolve(String name, String ext) {
        Path p = configDir().resolve(name + ext);
        return Files.exists(p) ? p : null;
    }

    /** 读取 NBT：先按 gzip 压缩格式尝试，失败再按未压缩格式 */
    private static CompoundTag readNbtAny(Path p) throws Exception {
        try (InputStream in = Files.newInputStream(p)) {
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (Exception ignored) {
            // fall through
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p))) {
            return NbtIo.read(new DataInputStream(in), NbtAccounter.unlimitedHeap());
        }
    }

    // ===== Sponge .schem 解析 =====

    private static boolean placeSchem(ServerLevel level, Path file, BlockPos anchor) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            int w = root.getShort("Width") & 0xFFFF;
            int h = root.getShort("Height") & 0xFFFF;
            int l = root.getShort("Length") & 0xFFFF;
            if (w <= 0 || h <= 0 || l <= 0) return false;
            CompoundTag paletteTag = root.getCompound("Palette");
            byte[] data = root.getByteArray("BlockData");
            BlockState[] palette = parsePalette(paletteTag);
            if (palette == null) return false;

            int placed = 0;
            int pos = 0;
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < l; z++) {
                    for (int x = 0; x < w; x++) {
                        // Sponge varint：LSB first，最高位为继续标志
                        int value = 0;
                        int shift = 0;
                        while (true) {
                            if (pos >= data.length) {
                                ZhuShenSpace.LOGGER.warn("原理图 BlockData 不完整: {}", file.getFileName());
                                return placed > 0;
                            }
                            int b = data[pos++] & 0xFF;
                            value |= (b & 0x7F) << shift;
                            if ((b & 0x80) == 0) break;
                            shift += 7;
                        }
                        if (value < 0 || value >= palette.length) continue;
                        BlockState st = palette[value];
                        if (st == null) continue;
                        Block blk = st.getBlock();
                        if (blk == Blocks.AIR || blk == Blocks.STRUCTURE_VOID) continue;
                        level.setBlock(anchor.offset(x, y, z), st, 3);
                        placed++;
                    }
                }
            }
            ZhuShenSpace.LOGGER.info("原理图 {} 摆放完成：{} 个方块", file.getFileName(), placed);
            return placed > 0;
        } catch (Exception e) {
            ZhuShenSpace.LOGGER.warn("摆放原理图失败: {}", file, e);
            return false;
        }
    }

    /** 解析调色板：值为 IntTag（v2，状态含在键名中）或 CompoundTag（v3，含 Index/Name/Properties） */
    private static BlockState[] parsePalette(CompoundTag paletteTag) {
        BlockState[] palette = new BlockState[paletteTag.size() + 1];
        for (String key : paletteTag.getAllKeys()) {
            Tag v = paletteTag.get(key);
            int idx;
            BlockState st;
            if (v instanceof IntTag intTag) {
                idx = intTag.getAsInt();
                st = parseStateKey(key);
            } else if (v instanceof CompoundTag ct) {
                idx = ct.getInt("Index");
                st = parseStateKey(ct.getString("Name"));
                CompoundTag props = ct.getCompound("Properties");
                for (String pn : props.getAllKeys()) {
                    st = withProp(st, pn, props.getString(pn));
                }
            } else {
                continue;
            }
            if (idx >= 0 && idx < palette.length) {
                palette[idx] = st;
            }
        }
        return palette;
    }

    /** 解析方块状态键：{@code minecraft:oak_stairs[facing=east,half=bottom]} */
    private static BlockState parseStateKey(String key) {
        String idPart = key;
        String propsPart = null;
        int lb = key.indexOf('[');
        if (lb >= 0) {
            idPart = key.substring(0, lb);
            int rb = key.lastIndexOf(']');
            propsPart = rb > lb ? key.substring(lb + 1, rb) : "";
        }
        ResourceLocation rl;
        try {
            rl = ResourceLocation.parse(idPart);
        } catch (Exception e) {
            return null;
        }
        if (!BuiltInRegistries.BLOCK.containsKey(rl)) {
            return null; // 方块不存在（mod 未安装）→ 跳过该方块
        }
        Block block = BuiltInRegistries.BLOCK.get(rl);
        if (block == Blocks.AIR) return Blocks.AIR.defaultBlockState();
        BlockState state = block.defaultBlockState();
        if (propsPart != null && !propsPart.isEmpty()) {
            for (String kv : propsPart.split(",")) {
                String[] e = kv.split("=", 2);
                if (e.length == 2) state = withProp(state, e[0].trim(), e[1].trim());
            }
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withProp(BlockState state, String name, String value) {
        if (state == null) return null;
        for (Property<?> p : state.getProperties()) {
            if (p.getName().equals(name)) {
                Optional<?> v = p.getValue(value);
                if (v.isPresent()) return state.setValue((Property) p, (Comparable) v.get());
            }
        }
        return state;
    }
}
