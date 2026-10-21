package dev.voxnote;

/** 覆盖在主页上的一层（详情、选择器）；返回键按打开顺序关闭。 */
public interface Overlay {
    boolean isOpen();

    void dismiss();
}
