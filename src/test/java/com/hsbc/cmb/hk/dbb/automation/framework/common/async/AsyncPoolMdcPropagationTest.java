package com.hsbc.cmb.hk.dbb.automation.framework.common.async;

import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * C-5 回归：异步任务必须传播提交线程的 MDC（日志诊断上下文）。
 *
 * <p>修复前 {@link AsyncPool#run(Runnable)} 仅传播 {@code TestContextHolder}（场景上下文），
 * 不传播 SLF4J MDC，导致异步落盘 / 监控回调里的日志丢失 scenarioId / traceId，无法关联链路。
 * 本测试在测试线程写入 MDC，提交异步任务，断言 worker 线程能读到同一 MDC 值。</p>
 */
public class AsyncPoolMdcPropagationTest {

    @After
    public void tearDown() {
        MDC.clear();
    }

    @Test
    public void runPropagatesMdcToWorkerThread() throws Exception {
        MDC.put("scenarioId", "S-C5-1");
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        AsyncPool.run(() -> {
            try {
                seen.set(MDC.get("scenarioId"));
            } finally {
                done.countDown();
            }
        });

        assertTrue("异步任务应在 5s 内完成", done.await(5, TimeUnit.SECONDS));
        assertEquals("MDC scenarioId 必须传播到异步 worker 线程", "S-C5-1", seen.get());
    }
}
