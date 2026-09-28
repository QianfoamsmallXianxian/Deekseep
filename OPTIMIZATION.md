# Agent 与 MCP 集成稳定性优化

面向 `AgentMcpManager.java`、`AgentToolConfig.java`、`HeartbeatToolProtocol.java`。

## P0-1 线程池并发探测

原实现：

```java
private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "Deekseep-MCP");
    thread.setDaemon(true);
    return thread;
});
```

替换为：

```java
private static final int IO_THREADS =
        Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(IO_THREADS, r -> {
    Thread t = new Thread(r, "Deekseep-MCP");
    t.setDaemon(true);
    return t;
});
```

`probeAllAsync` 由串行改为按服务器并发，使用 `CountDownLatch` 聚合。

## P0-2 SSE 读超时

原实现 `stream.setReadTimeout(0)` 永久阻塞。改为 45s 读超时，并增加 90s 空闲看门狗线程，超时后 `close()` 唤醒所有等待。

## P0-3 会话状态按服务器隔离

原实现使用全局 `sessionId` 与 `legacySse`，连接第二台服务器会清空第一台的会话。改为：

```java
private static final ConcurrentHashMap<String, SessionState> SESSIONS =
        new ConcurrentHashMap<>();

static final class SessionState {
    final AtomicReference<String> sessionId = new AtomicReference<>("");
    final AtomicReference<LegacySseSession> sse = new AtomicReference<>();
    volatile boolean connected;
    volatile long lastActivityAt = System.currentTimeMillis();
}

private static SessionState sessionFor(Config c) {
    return SESSIONS.computeIfAbsent(connectionKey(c), k -> new SessionState());
}
```

`post`、`connectAndSync`、`executeAsync` 全部改用 `sessionFor(config)`。

## P0-4 路径注入

原实现硬编码 `/data/data/com.deepseek.chat/files/deekseep_agent`。改为可注入：

```java
private static volatile File directory;

static void init(Context host) {
    if (host != null) directory = new File(host.getFilesDir(), "deekseep_agent");
}

private static File configDirectory() {
    File dir = directory;
    return dir != null ? dir : new File("/data/data/com.deepseek.chat/files/deekseep_agent");
}
```

兼容应用分身、多用户、工作资料。

## P0-5 原子写入

原实现 `FileWriter` + `renameTo`。改为 `FileOutputStream` + `getFD().sync()` + `Files.move(ATOMIC_MOVE)`，失败回退 `renameTo`。

## P0-6 危险工具默认关闭

原 `defaults()` 返回全工具 + `PERMISSION_ALL`。改为 `SAFE_DEFAULTS` 集合 + `PERMISSION_EXECUTE`。

```java
static Snapshot defaults() {
    return new Snapshot(true, BACKEND_IN_APP, PERMISSION_EXECUTE,
            new LinkedHashSet<>(SAFE_DEFAULTS));
}
```

## P0-7 解析失败最小权限兜底

原 `load()` 的 catch 分支回退 `defaults()`（全开），属静默权限提升。改为 `minimal()`，仅保留 `get_current_time`。

## P1-1 超时分级

```java
private static final int TIMEOUT_CONNECT = 15_000;
private static final int TIMEOUT_INIT    = 20_000;
private static final int TIMEOUT_LIST    = 30_000;
private static final int TIMEOUT_CALL    = 120_000;
```

`post` 增加 `readTimeout` 参数，`rpc` 按方法传入；`finally` 中 `connection.disconnect()` 防泄漏。

## P1-2 文件戳

```java
private static long fileStamp(File f) {
    if (!f.isFile()) return Long.MIN_VALUE;
    return (f.lastModified() * 31L) ^ f.length();
}
```

规避部分 ROM 上 `lastModified` 秒级精度导致缓存不失效的问题。

## P1-3 异常日志环形缓冲

```java
private static final int ERR_CAP = 32;
private static final ArrayDeque<String> ERR_RING = new ArrayDeque<>(ERR_CAP);
private static void logQuiet(String tag, Throwable t) {
    synchronized (ERR_RING) {
        if (ERR_RING.size() >= ERR_CAP) ERR_RING.removeFirst();
        ERR_RING.addLast(tag + ": " + safeError(t));
    }
}
static List<String> recentErrors() {
    synchronized (ERR_RING) { return new ArrayList<>(ERR_RING); }
}
```

替换原先遍布各处的 `catch (Throwable ignored) {}`。

## P1-4 TOOL_NAMES 补 MCP

`HeartbeatToolProtocol.TOOL_NAMES` 数组缺少 `TOOL_MCP`。改用不可变 `Set` 并在构建时断言与 `AgentToolConfig.TOOLS` 同步。

```java
private static final Set<String> TOOL_NAME_SET;
static {
    Set<String> names = new HashSet<>();
    Collections.addAll(names, TOOL_SCHEDULE_ONCE, /* ... */, TOOL_RENDER_RICH_PANEL, TOOL_MCP);
    TOOL_NAME_SET = Collections.unmodifiableSet(names);
}
```

## P1-5 isToolEnabled 连接检查

```java
static boolean isToolEnabled(String fullName) {
    if (!isDynamicTool(fullName)) return false;
    if (!AgentToolConfig.load().enabledTools.contains(
            HeartbeatToolProtocol.TOOL_MCP)) return false;
    for (Config config : loadAll()) {
        Tool tool = resolveTool(config, fullName);
        if (config.enabled && tool != null && tool.enabled
                && sessionFor(config).connected) return true;
    }
    return false;
}
```

避免未连接服务器仍报告工具可用。

## 落地顺序

1. 线程池 + 并发探测 + 会话隔离
2. 路径注入 + 原子写 + 读超时 + disconnect
3. SSE 看门狗
4. 超时分级 + 文件戳 + 日志环形缓冲
5. 危险工具默认关闭 + 最小权限兜底
6. TOOL_NAMES 补 MCP
