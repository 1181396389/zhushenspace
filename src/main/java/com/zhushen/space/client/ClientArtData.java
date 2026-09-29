package com.zhushen.space.client;

import com.zhushen.space.data.ArtSkill;

/** 客户端技艺镜像 */
public class ClientArtData {
    private static long owned;
    private static int[] opt = new int[ArtSkill.COUNT], cur = new int[ArtSkill.COUNT], res = new int[ArtSkill.COUNT];
    private static int holdback = -1, xp;
    private static boolean amp;

    private static int flags;
    public static boolean flag(int f) { return (flags & f) != 0; }

    public static void update(long o, int[] op, int[] c, int[] r, int hb, boolean a, int x, int fl) {
        flags = fl;
        owned = o; opt = op; cur = c; res = r; holdback = hb; amp = a; xp = x;
    }

    public static boolean owns(ArtSkill s) { return (owned & (1L << s.ordinal())) != 0; }
    public static boolean hasOption(ArtSkill s, int i) { return s.ordinal() < opt.length && (opt[s.ordinal()] & (1 << i)) != 0; }
    public static int current(ArtSkill s) { return s.ordinal() < cur.length ? cur[s.ordinal()] : 0; }
    public static boolean hasResearch(ArtSkill s, int i) { return s.ordinal() < res.length && (res[s.ordinal()] & (1 << i)) != 0; }
    public static int holdback() { return holdback; }
    public static boolean amplify() { return amp; }
    public static int xp() { return xp; }
}
