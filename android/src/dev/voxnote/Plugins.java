package dev.voxnote;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件注册表。
 *
 * 故意用"类名字符串 + 反射"而不是编译期引用：把 android/src/dev/voxnote/plugin/ 整个目录删掉，
 * 核心不报错、App 照常启动，只是列表里没有"外部来源"。
 * 想彻底移除，连下面这行类名一起删。
 */
public final class Plugins {

    private static final String[] BUILTIN = {
            "dev.voxnote.plugin.sogou.SogouLogSource",
    };

    private Plugins() {
    }

    public static List<ContentSource> create() {
        List<ContentSource> out = new ArrayList<ContentSource>();
        for (int i = 0; i < BUILTIN.length; i++) {
            try {
                Object o = Class.forName(BUILTIN[i]).newInstance();
                out.add((ContentSource) o);
            } catch (Throwable ignore) {
                // 插件不存在 / 初始化失败：当作这台设备没有这个来源
            }
        }
        return out;
    }
}
