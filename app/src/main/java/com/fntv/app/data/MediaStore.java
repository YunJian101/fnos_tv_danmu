package com.fntv.app.data;

import com.fntv.app.api.model.MediaDbItem;
import com.fntv.app.api.model.PlayListItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程级共享数据：首页与分类页共用同一份媒体库/列表数据。
 * 只存数据，绝不持有 View 或 Activity 引用。
 */
public class MediaStore {

    /** 媒体库列表（首页拿到后写入） */
    public static final List<MediaDbItem> libraries = new ArrayList<>();

    /** 每个媒体库的首屏内容：libGuid -> items */
    public static final Map<String, List<PlayListItem>> rails = new HashMap<>();

    /** 分类页按 (libGuid|sort) 缓存的完整列表 */
    public static final Map<String, CachedList> lists = new HashMap<>();

    private static final long FRESH_MS = 5 * 60 * 1000L;   // 5 分钟内视为新鲜

    public static class CachedList {
        public List<PlayListItem> items;
        public long at;

        public CachedList(List<PlayListItem> items) {
            this.items = items;
            this.at = System.currentTimeMillis();
        }

        public boolean fresh() {
            return items != null && System.currentTimeMillis() - at < FRESH_MS;
        }
    }

    public static void putList(String libGuid, int sort, List<PlayListItem> items) {
        if (libGuid == null || items == null) return;
        lists.put(libGuid + "|" + sort, new CachedList(items));
    }

    public static List<PlayListItem> getList(String libGuid, int sort) {
        CachedList c = lists.get(libGuid + "|" + sort);
        if (c == null || !c.fresh()) return null;
        return c.items;
    }

    /** 分类页记住的排序方式与滚动位置 */
    public static int lastSort = 0;
    public static int lastScrollY = 0;
    public static String lastListGuid = null;

    public static void clear() {
        libraries.clear();
        rails.clear();
        lists.clear();
    }
}
