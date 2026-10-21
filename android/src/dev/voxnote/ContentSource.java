package dev.voxnote;

import org.json.JSONObject;

import java.util.List;

/**
 * 外部数据来源 = 可拆卸的插件。
 *
 * 核心对它只有一个约定：给我一批 {@link Item}（已经是卡片形态），外加一个名字和一份状态。
 * "厂商日志"这种只对某一台设备有意义的导入，全部落在插件包里；
 * 删掉插件包、核心照常编译运行（见 {@link Plugins}）。
 */
public interface ContentSource {

    /** 稳定 id，例如 "sogou-log"。 */
    String id();

    /** 设置页展示名。 */
    String label();

    /** 这台设备上有没有可读的源（没有就不在 UI 出现）。 */
    boolean available();

    /** 扫一遍。force=true 表示忽略内部缓存。 */
    List<Item> scan(boolean force);

    /** 给 UI 的统计：sessions / sentences / lastRunAt / error / dir ... */
    JSONObject info();
}
