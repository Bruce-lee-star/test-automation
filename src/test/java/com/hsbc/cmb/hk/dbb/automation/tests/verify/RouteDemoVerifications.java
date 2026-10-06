package com.hsbc.cmb.hk.dbb.automation.tests.verify;

import org.junit.Assert;

/**
 * B-5：步骤层断言收口（Step-layer assertion facade）。
 *
 * <p>步骤类（{@code *Steps}）不直接依赖任何测试框架断言 API——所有步骤层断言统一经本类转发，从而：
 * ① 步骤类与测试框架断言 API 解耦（ArchUnit
 * {@code LayeringArchTest#stepsMustNotUseJunitAssertionsDirectly} 固化）；② 断言集中为单一收口点，
 * 后续可平滑替换为业务语义 Verifier（如 {@code verifyCapturedStatus(call, 200)}）而不触及任何步骤。</p>
 *
 * <p><b>参数顺序</b>：与 {@link org.junit.Assert} 保持<b>同一口径——message 在前</b>
 * （{@code assertTrue(String message, boolean condition)}）。全仓 2026-10 统一 JUnit 4 后，
 * 这里不再保留 JUnit 5 的「message 在后」签名：门面与底层断言库顺序一致，调用点才不会出现
 * 「同一调用在两个世界里顺序相反」的隐性错误。</p>
 *
 * <p>线程安全：无状态静态方法。
 */
public final class RouteDemoVerifications {

    private RouteDemoVerifications() {
    }

    public static void assertTrue(boolean condition) {
        Assert.assertTrue(condition);
    }

    public static void assertTrue(String message, boolean condition) {
        Assert.assertTrue(message, condition);
    }

    public static void assertFalse(boolean condition) {
        Assert.assertFalse(condition);
    }

    public static void assertFalse(String message, boolean condition) {
        Assert.assertFalse(message, condition);
    }

    public static void assertNotNull(Object actual) {
        Assert.assertNotNull(actual);
    }

    public static void assertNotNull(String message, Object actual) {
        Assert.assertNotNull(message, actual);
    }

    public static void assertNull(Object actual) {
        Assert.assertNull(actual);
    }

    public static void assertNull(String message, Object actual) {
        Assert.assertNull(message, actual);
    }

    public static void assertEquals(String message, Object expected, Object actual) {
        Assert.assertEquals(message, expected, actual);
    }

    public static void fail(String message) {
        Assert.fail(message);
    }
}
