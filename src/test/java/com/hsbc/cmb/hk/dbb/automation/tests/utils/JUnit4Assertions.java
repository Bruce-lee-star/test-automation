package com.hsbc.cmb.hk.dbb.automation.tests.utils;

import org.junit.function.ThrowingRunnable;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.fail;

/**
 * JUnit 4 缺失断言的补齐（<b>测试侧工具</b>）。
 *
 * <p><b>为什么需要它</b>：2026-10 全仓从 JUnit 5 统一回 JUnit 4，而这两个 API 是 JUnit 5 独有、
 * JUnit 4.13 没有对应物：</p>
 * <ul>
 *   <li>{@code assertTimeoutPreemptively(Duration, Executable)} —— 本类以「独立线程 + {@code Future.get(timeout)}」
 *       复刻其语义：<b>超时即在另一线程提前判定失败</b>，不会把死锁留到测试框架超时；</li>
 *   <li>{@code assertDoesNotThrow(Executable[, String])} —— 动作正常返回即通过，抛任何异常/错误即失败。</li>
 * </ul>
 *
 * <p>放在 {@code tests.utils}（与 {@code AsyncWaits} 同域）：仅测试代码使用，不涉生产链路。</p>
 */
public final class JUnit4Assertions {

    private JUnit4Assertions() {
    }

    /** 断言动作不抛异常（失败信息取默认文案）。 */
    public static void assertDoesNotThrow(ThrowingRunnable action) {
        assertDoesNotThrow("预期不抛异常", action);
    }

    /** 断言动作不抛异常；失败时输出给定信息 + 原始异常（便于定位）。 */
    public static void assertDoesNotThrow(String message, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            fail(message + " —— 但抛出了 " + e);
        }
    }

    /**
     * 断言动作在给定时限内完成（超时即在独立线程提前判失败，等价 JUnit 5 的
     * {@code assertTimeoutPreemptively}）。
     *
     * <p><b>为什么必须抢占式</b>：本类的使用者是死锁/并发用例 —— 若只是「等超时再报错」，被测代码一旦死锁，
     * 判定线程自己也被拖住，最终以框架级超时收场（丢失「谁死锁」的信息）。抢占式在另一个线程执行动作，
     * 主线程到点即判失败，并中断执行线程。</p>
     */
    public static void assertTimeoutPreemptively(Duration timeout, ThrowingRunnable action) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "junit4-timeout-assert");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    // Callable 只允许抛 Exception，而 ThrowingRunnable 可抛 Throwable（含 AssertionError）
                    // → 统一包一层，由下方 get() 处解包还原原始异常语义。
                    throw new IllegalStateException(t);
                }
                return null;
            });
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail("动作未在 " + timeout.toMillis() + "ms 内完成（疑似死锁/挂起）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("等待动作完成时被中断: " + e);
        } catch (Exception e) {
            // 动作自身抛出的异常（可能是 AssertionError 被包装）→ 解包后原样抛出，保留原始失败语义
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof AssertionError assertionError) {
                throw assertionError;
            }
            throw new AssertionError("动作执行失败: " + cause, cause);
        } finally {
            executor.shutdownNow();
        }
    }
}
