package dev.voxnote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据层与 UI 之间的中间层：
 *   - 扫描（含插件导入）全部放在单线程后台，绝不在主线程读 sdcard / 解析日志；
 *   - 扫描完把 Item + State 合成 {@link RecordVm}，UI 只认 VM；
 *   - 用户态写入（改名/分类/标记）也走同一后台线程，避免写文件卡住滑动。
 *
 * 旧实现是 JS 线程同步调 refresh()，每次回前台卡 ~300ms；这里换成了异步 + 只更新列表数据。
 */
public class DataCenter {

    public interface LoadCallback {
        void onLoaded(Result r);
    }

    public interface DoneCallback {
        void onDone();
    }

    public static class Result {
        public ArrayList<RecordVm> items = new ArrayList<RecordVm>();
        public int total, done, raw, failed, empty, sentences, withAudio;
        public String error = "";
        public long scannedAt;
        public JSONArray sources = new JSONArray();
        public final Map<String, String> sourceLabels = new HashMap<String, String>();
    }

    private final State state;
    private final Library library;
    private final Storage storage;
    private final List<ContentSource> sources;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public DataCenter(Context c, State state, Storage storage) {
        this.state = state;
        this.storage = storage;
        this.library = new Library(c, state, storage);
        this.sources = Plugins.create();
    }

    public Storage storage() {
        return storage;
    }

    public JSONArray sourceInfoBlocking() {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < sources.size(); i++) {
            try {
                arr.put(sources.get(i).info());
            } catch (Throwable ignore) { }
        }
        return arr;
    }

    /** Activity 销毁时停掉后台线程；未完成的任务随进程一起丢弃。 */
    public void shutdown() {
        try {
            io.shutdownNow();
        } catch (Throwable ignore) { }
        ui.removeCallbacksAndMessages(null);
    }

    /** forceAll=true 时让插件忽略缓存重扫；onlySourceId 限定只强刷某个来源。 */
    public void load(final boolean forceAll, final String onlySourceId, final LoadCallback cb) {
        io.execute(new Runnable() {
            public void run() {
                final Result r = new Result();
                List<Item> imported = new ArrayList<Item>();
                JSONArray sinfo = new JSONArray();
                for (int i = 0; i < sources.size(); i++) {
                    ContentSource s = sources.get(i);
                    try {
                        boolean force = forceAll && (onlySourceId == null || onlySourceId.equals(s.id()));
                        List<Item> its = s.scan(force);
                        if (its != null) imported.addAll(its);
                        JSONObject info = s.info();
                        sinfo.put(info);
                        String label = info.optString("label", s.label());
                        r.sourceLabels.put(s.id(), label);
                    } catch (Throwable t) {
                        try {
                            JSONObject e = new JSONObject();
                            e.put("id", s.id());
                            e.put("label", s.label());
                            e.put("available", false);
                            e.put("error", String.valueOf(t));
                            sinfo.put(e);
                        } catch (Throwable ignore) { }
                    }
                }
                r.sources = sinfo;

                List<Item> items;
                try {
                    items = library.scan(imported);
                } catch (Throwable t) {
                    items = new ArrayList<Item>();
                    r.error = String.valueOf(t);
                }
                r.error = library.error();
                r.scannedAt = System.currentTimeMillis();
                ArrayList<RecordVm> vms = new ArrayList<RecordVm>(items.size());
                for (int i = 0; i < items.size(); i++) {
                    Item it = items.get(i);
                    RecordVm vm = new RecordVm(it, state.of(it.id));
                    vms.add(vm);
                    String st = vm.status();
                    if ("done".equals(st)) r.done++;
                    else if ("raw".equals(st)) r.raw++;
                    else if ("failed".equals(st)) r.failed++;
                    else r.empty++;
                    if (vm.hasAudio()) r.withAudio++;
                    r.sentences += vm.segs.size();
                }
                r.total = vms.size();
                Collections.sort(vms, BY_START);
                r.items = vms;

                ui.post(new Runnable() {
                    public void run() {
                        cb.onLoaded(r);
                    }
                });
            }
        });
    }

    /** 用户态更新（改名 / 分类 / 标记）。p 里的 cat 用 JSONObject.NULL 表示清空。 */
    public void patch(final String id, final JSONObject p, final DoneCallback done) {
        io.execute(new Runnable() {
            public void run() {
                try {
                    state.patch(id, p);
                } catch (Throwable ignore) { }
                if (done != null) {
                    ui.post(new Runnable() {
                        public void run() {
                            done.onDone();
                        }
                    });
                }
            }
        });
    }

    /** 删掉一个分类后，把所有挂在该分类下的记录清成未分类（一次落盘）。 */
    public void clearCat(final String catId, final DoneCallback done) {
        io.execute(new Runnable() {
            public void run() {
                try {
                    state.clearCat(catId);
                } catch (Throwable ignore) { }
                if (done != null) {
                    ui.post(new Runnable() {
                        public void run() {
                            done.onDone();
                        }
                    });
                }
            }
        });
    }

    private static final Comparator<RecordVm> BY_START = new Comparator<RecordVm>() {
        public int compare(RecordVm a, RecordVm b) {
            if (a.startMs != b.startMs) return a.startMs > b.startMs ? -1 : 1;
            return a.id.compareTo(b.id);
        }
    };
}
