package com.infi.agent.react.model;

/** 同步任务的模型选择，退出请求时恢复上下文，禁止跨用户共享。 */
public final class ModelSelection implements AutoCloseable {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private final String previous;
    private ModelSelection(String id) { previous = CURRENT.get(); CURRENT.set(id); }
    public static ModelSelection select(String id) { return new ModelSelection(id); }
    public static String current() { return CURRENT.get(); }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
