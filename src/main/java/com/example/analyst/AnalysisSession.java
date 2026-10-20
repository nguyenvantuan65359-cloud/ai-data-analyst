package com.example.analyst;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 持有"当前正在分析的数据表"。
 *
 * 多人同时用时，**按 sessionId 隔离**：A 上传的文件不会覆盖 B 的当前表。
 * 不传 sessionId 的旧调用走 "default" 这一格（向后兼容，单机自用时零负担）。
 *
 * ────── 为什么要分成 pinned / scratch 两层 ──────
 * 早期版本只有一个 map，JdbcQueryTool 每执行一次就把结果写进来。
 * 副作用是：AI 在对话里做**探索性查询**（比如先去看一眼本地示例表 sales），
 * 用户的上传文件当场被顶掉，后续所有统计工具都在算那张探索用的表——
 * 表现为"我明明传了文件，分析的却是本地库"。这是用户肉眼可见的错误结论，必须堵。
 *
 * 规则：
 *   - pinned ：**用户显式动作**产生的分析对象（上传文件 / 手写 SQL）。只能被下一个用户动作替换。
 *   - scratch：AI 探索性查询的结果，仅供它自己看结构，不影响分析主体。
 *   - 取数顺序：pinned 优先，没有 pinned 才退回 scratch。
 */
@Component
public class AnalysisSession {

    private static final String DEFAULT_SID = "default";
    private final ConcurrentMap<String, DataTable> pinned = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DataTable> scratch = new ConcurrentHashMap<>();
    /** pinned 的来源描述，用于前端展示与提示词里告诉 AI「你现在分析的是哪张表」 */
    private final ConcurrentMap<String, String> source = new ConcurrentHashMap<>();

    private final DataIngestion ingestion;
    /** 已经从落盘库找过一遍的会话：找不到就不再反复查（用户真上传时会 pin 进来） */
    private final java.util.Set<String> restoreTried = ConcurrentHashMap.newKeySet();

    public AnalysisSession(DataIngestion ingestion) {
        this.ingestion = ingestion;
    }

    /** AI 探索性查询的结果：不覆盖用户钉住的分析对象 */
    public void set(String sid, DataTable t) {
        scratch.put(norm(sid), t);
    }

    /** 取当前分析对象：用户钉住的优先，否则退回 AI 查询的临时结果 */
    public DataTable resolve(String sid) {
        String k = norm(sid);
        DataTable t = pinned.get(k);
        if (t != null) return t;
        t = scratch.get(k);
        if (t != null) return t;
        // 内存里没有：服务可能重启过（内存表全丢）。本地库现在是落盘的，
        // 表名又能由 sid 反推，所以直接把本会话的上传表接回来——
        // 否则用户会看到"上传明明成功过，重启后一问又说没有数据"。
        if (restoreTried.add(k)) {
            DataTable restored = ingestion.restoreSessionTable(k);
            if (restored != null && restored.rowCount() > 0) {
                pinned.put(k, restored);
                scratch.put(k, restored);
                source.put(k, ("uploaded_data_" + DataIngestion.safeSid(k)).toLowerCase());
                return restored;
            }
        }
        return null;
    }

    /** 用户显式动作：上传文件 / 手写 SQL，确立（或替换）本次分析的对象 */
    public void pin(String sid, DataTable t, String sourceName) {
        String k = norm(sid);
        pinned.put(k, t);
        scratch.put(k, t);
        if (sourceName != null) source.put(k, sourceName);
    }

    public String sourceName(String sid) {
        return source.get(norm(sid));
    }

    public boolean hasPinned(String sid) {
        String k = norm(sid);
        // 先 resolve 一次：内存里没有时会尝试从落盘库把本会话的表接回来，
        // 这样重启后 /state 与 dataReady 判断才准。
        if (!pinned.containsKey(k)) resolve(k);
        return pinned.containsKey(k);
    }

    /** 清掉某个会话的当前表（断开会话时调用） */
    public void clear(String sid) {
        String k = norm(sid);
        pinned.remove(k);
        scratch.remove(k);
        source.remove(k);
    }

    // 兼容旧调用：不传 sid 等价于 default
    public void set(DataTable t) { set(DEFAULT_SID, t); }
    public DataTable get(String sid) { return resolve(sid); }
    public DataTable get() { return resolve(DEFAULT_SID); }
    public void clear() { clear(DEFAULT_SID); }

    /**
     * 会话 key 统一走 safeSid。
     *
     * 之前这里是原样返回，于是埋了个雷：Controller 用**原始** sid 调 pin()，
     * 而工具层经 ToolContext 拿到的是 safeSid(sessionId)，两边 key 不同——
     * 浏览器生成的会话 id 都是 `s-xxxxxxxx` 这种带连字符的，safeSid 会把它变成 `sxxxxxxxx`，
     * 工具层永远查不到用户刚钉住的那张表，只能靠"从落盘库恢复"兜底。
     * 兜底在 upload 之后碰巧能对上（表名同样由 safeSid 生成），但**手写 SQL 的结果集不入库**，
     * 那种情况下工具会报"当前会话还没有数据表"。
     */
    private static String norm(String sid) {
        return DataIngestion.safeSid(sid);
    }
}
