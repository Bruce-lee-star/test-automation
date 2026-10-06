package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证语言状态的并发隔离（CORE-LANG 修复，2026-09-20）。
 *
 * <p><b>背景</b>：语言状态现已收口为「当前上下文（用例级优先、回退线程级）」，<b>不再有进程级全局值</b>。
 * 这是并行（多场景多线程）安全的必要条件——各场景/线程的语言状态必须互不串扰。</p>
 *
 * <p>历史说明：旧实现用进程级 {@code globalLang + 单调序号} 做跨线程桥，使「回调线程设置的语言对主线程可见」，
 * 但并行下会被<b>跨场景</b>读取导致串语言。经核查生产代码无异步回调线程设语言的路径（route 仅调 reset），
 * 故改为上下文作用域，跨线程共享可变语言状态不再被允许（若确需同场景异步可见，应经
 * {@code TestContextHolder.runWithContext} 显式传播）。本测试固化「隔离」语义。</p>
 */
public class NlsUtilsCrossThreadTest {

    @Before
    @After
    public void clear() {
        NLSUtils.reset();
    }

    /** 在独立线程执行（模拟并发场景/异步回调线程），并等待其完成。 */
    private static void runOnOtherThread(Runnable task) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        }, "simulated-worker");
        t.setDaemon(true);
        t.start();
        if (!done.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("simulated worker thread did not finish in time");
        }
    }

    /** 单一线程内的连续设置仍按最新值生效（回归保护）。 */
    @Test
    public void singleThreadSequentialWrites() {
        NLSUtils.setLanguage("en");
        assertEquals("en", NLSUtils.getLanguage());
        NLSUtils.setLanguage("zh");
        assertEquals("zh", NLSUtils.getLanguage());
        NLSUtils.setLanguage("ja");
        assertEquals("ja", NLSUtils.getLanguage());
    }

    /**
     * 并行隔离（CORE-LANG 核心验证）：两个并发线程/场景各自设置不同语言，
     * 各自读到自己的语言，互不串扰。
     */
    @Test
    public void parallelScenariosAreIsolated() throws Exception {
        AtomicReference<String> seenByThread1 = new AtomicReference<>();
        AtomicReference<String> seenByThread2 = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try {
                start.await();
                NLSUtils.setLanguage("zh");
                seenByThread1.set(NLSUtils.getLanguage());
            } catch (InterruptedException ignored) {
            } finally {
                end.countDown();
            }
        }, "scenario-zh");
        Thread t2 = new Thread(() -> {
            try {
                start.await();
                NLSUtils.setLanguage("en");
                seenByThread2.set(NLSUtils.getLanguage());
            } catch (InterruptedException ignored) {
            } finally {
                end.countDown();
            }
        }, "scenario-en");
        t1.start();
        t2.start();
        start.countDown();
        //  消费 await 返回值并断言：超时被静默吞掉会让下面的隔离性断言读到「尚未写入」的期望值，
        //  从而以假绿形式掩盖真实缺陷（SpotBugs RV_RETURN_VALUE_IGNORED）。
        assertTrue("两个场景线程应在 5s 内结束（否则隔离性断言可能读到未写入的期望值）", end.await(5, TimeUnit.SECONDS));

        assertEquals("场景1 应只读到自身设置的语言", "zh", seenByThread1.get());
        assertEquals("场景2 应只读到自身设置的语言", "en", seenByThread2.get());
    }

    /**
     * 工作线程写入的语言<b>不得</b>泄漏回主线程（隔离保留）。
     * 这正是修复前的缺陷方向：旧全局桥会让主线程读到工作线程的 "zh"。
     */
    @Test
    public void workerThreadWriteDoesNotLeakToMainThread() throws Exception {
        NLSUtils.setLanguage("en");                       // 主线程初值
        assertEquals("en", NLSUtils.getLanguage());

        runOnOtherThread(() -> NLSUtils.setLanguage("zh")); // 工作线程改为 zh

        assertEquals("主线程应只读到自身语言，不被其它线程的写入串扰", "en", NLSUtils.getLanguage());
    }

    /** 从未设置过的线程不继承任何语言态（无进程级残留）。 */
    @Test
    public void freshThreadHasNoResidualLanguage() throws Exception {
        runOnOtherThread(() -> NLSUtils.setLanguage("zh")); // 工作线程设置后结束
        NLSUtils.setLanguage("en");

        AtomicReference<String> seen = new AtomicReference<>("dirty");
        Thread reader = new Thread(() -> seen.set(NLSUtils.getLanguage()), "fresh-reader");
        reader.start();
        reader.join(5000);

        assertNull("从未设置语言的线程不应读到任何残留值", seen.get());
    }

    /** reset 清当前上下文，避免污染后续用例。 */
    @Test
    public void resetClearsCurrentContext() {
        NLSUtils.setLanguage("zh");
        NLSUtils.reset();
        assertNull("reset 后当前上下文不应再读到值", NLSUtils.getLanguage());
    }
}
