package top.mores.bFBackpack.backpack;


import java.util.Locale;
import java.util.Optional;

/**
 * 四类背包，对应战地系列的四个兵种。
 * <p>
 * 枚举只负责"身份"（存库用的 id、默认显示名、默认图标、建议槽位、默认禁带物品），
 * 实际槽位数 / 图标 / 禁带列表都可以被 config.yml 覆盖，方便服务器按自己的玩法调。
 */
public enum BackpackType {

    ASSAULT("assault", "突击兵", "IRON_SWORD", 27),
    MEDIC("medic", "医疗兵", "GOLDEN_APPLE", 27),
    SUPPORT("support", "支援兵", "CHEST", 36),
    RECON("recon", "侦察兵", "SPYGLASS", 18);

    private final String id;
    private final String defaultDisplay;
    private final String defaultIcon;
    private final int defaultSlots;

    BackpackType(String id, String defaultDisplay, String defaultIcon, int defaultSlots) {
        this.id = id;
        this.defaultDisplay = defaultDisplay;
        this.defaultIcon = defaultIcon;
        this.defaultSlots = defaultSlots;
    }

    /** 落库使用的小写标识，枚举改名也不会影响已有数据。 */
    public String id() {
        return id;
    }

    public String defaultDisplay() {
        return defaultDisplay;
    }

    public String defaultIcon() {
        return defaultIcon;
    }

    public int defaultSlots() {
        return defaultSlots;
    }

    /** 兼容中文名、英文 id 与枚举名三种写法。 */
    public static Optional<BackpackType> fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (BackpackType type : values()) {
            if (type.id.equals(key) || type.name().toLowerCase(Locale.ROOT).equals(key)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public BackpackType next() {
        BackpackType[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
