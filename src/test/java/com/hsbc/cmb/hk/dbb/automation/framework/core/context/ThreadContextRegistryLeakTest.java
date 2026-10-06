package com.hsbc.cmb.hk.dbb.automation.framework.core.context;

import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertTrue;

/**
 * CORE-P0-2 验证：上下文容器可观测 + 防泄漏。
 * 用 {@link TestContextHolder#activeContextCount()} 观测活跃线程上下文数，验证
 * get 登记、resetForCurrentThread 回落、线程池线程登记等行为。
 */
public class ThreadContextRegistryLeakTest {

    @Test
    public void contextCountTracksCurrentThreadAndResets() {
        // 测试隔离：先清空当前线程上下文，保证基线不含主线程既有登记（避免跨用例污染）。
        TestContextHolder.resetForCurrentThread();
        int base = TestContextHolder.activeContextCount();
        ContextKey<String> k = ContextKey.of("leak-track", String.class);
        TestContextHolder.get().set(k, "main");

        assertTrue("主线程登记后活跃计数应 +1", TestContextHolder.activeContextCount() == base + 1);

        TestContextHolder.resetForCurrentThread();
        assertTrue("resetForCurrentThread 后计数应回落至基线", TestContextHolder.activeContextCount() == base);
    }

    @Test
    public void threadPoolThreadsAreCounted() throws Exception {
        // 基线：先清空跨用例残留，避免主线程既有登记污染计数。
        TestContextHolder.resetForCurrentThread();
        int base = TestContextHolder.activeContextCount();
        int threads = 4;
        // 用 latch 让工作线程在"已登记上下文"状态下挂起，确保计数观测时线程仍强可达：
        // ThreadContextRegistry 为 WeakHashMap<Thread,?>，若直接 shutdown 后线程被 GC，弱键条目会消失 → 误判失败。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch registered = new CountDownLatch(threads);
        CountDownLatch hold = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                TestContextHolder.get().set(
                        ContextKey.of("t" + Thread.currentThread().threadId(), String.class), "v");
                registered.countDown();
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        }
        assertTrue("线程池线程应全部完成上下文登记", registered.await(5, TimeUnit.SECONDS));

        int afterPool = TestContextHolder.activeContextCount();
        assertTrue("线程池线程应已登记上下文（base=" + base + ", after=" + afterPool + "）", afterPool >= base + threads);

        hold.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
