package com.hsbc.cmb.hk.dbb.automation.tests.utils;

import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B-4：{@link AsyncWaits} 有界轮询原语的行为契约（成功即返回 / 超时有界返回 / 取值语义）。
 */
public class AsyncWaitsTest {

    @Test
    public void awaitTrue_returnsTrueWhenConditionEventuallyHolds() {
        AtomicInteger calls = new AtomicInteger();

        boolean ok = AsyncWaits.awaitTrue(Duration.ofMillis(500), Duration.ofMillis(5),
                () -> calls.incrementAndGet() >= 3);

        assertTrue("条件最终成立应返回 true", ok);
        assertTrue("应至少轮询至条件成立", calls.get() >= 3);
    }

    @Test
    public void awaitTrue_returnsFalseOnTimeout_andRespectsTimeoutBound() {
        long start = System.nanoTime();

        boolean ok = AsyncWaits.awaitTrue(AsyncWaits.ms(80), AsyncWaits.ms(10), () -> false);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertFalse("条件始终不成立应返回 false", ok);
        assertTrue("应等待至超时上界，实际 " + elapsedMs + "ms", elapsedMs >= 80);
    }

    @Test
    public void awaitResult_returnsFirstNonNullValue() {
        AtomicInteger calls = new AtomicInteger();

        String value = AsyncWaits.awaitResult(AsyncWaits.ms(500), AsyncWaits.ms(5),
                () -> calls.incrementAndGet() >= 2 ? "done" : null);

        assertEquals("done", value);
    }

    @Test
    public void awaitResult_returnsNullOnTimeout() {
        String value = AsyncWaits.awaitResult(AsyncWaits.ms(60), AsyncWaits.ms(10), () -> null);

        assertNull("超时仍无值应返回 null", value);
    }
}
