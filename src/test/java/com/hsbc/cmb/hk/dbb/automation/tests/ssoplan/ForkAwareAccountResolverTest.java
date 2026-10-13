package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link ForkAwareAccountResolver} 单测（设计文档 §5.1~§5.5）。
 *
 * <p>配置经 {@link ForkAwareAccountResolver} 内部 {@code ConfigSource.resolve} 实时读取系统属性，
 * 故每个用例用 {@code System.setProperty} 注入，{@link #tearDown()} 复位为空串（置空而非 clearProperty，
 * 避免 ConfigSource 快照回落歧义，见工程记忆 Serenity 配置快照陈旧性陷阱）。</p>
 */
public class ForkAwareAccountResolverTest {

    private static final String ENV = "O63_SIT1";
    private static final String ACCOUNTS_KEY = "sso.accounts." + ENV;

    @After
    public void tearDown() {
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "");
        set(ForkAwareAccountResolver.SUREFIRE_FORK_NUMBER, "");
        set(ForkAwareAccountResolver.STRIDE_KEY, "");
        set(ACCOUNTS_KEY, "");
        RunScopeAccountProviders.setInjected(null); // 解除 §5.7 测试 seam，防跨测试泄漏
    }

    private static void set(String k, String v) {
        System.setProperty(k, v);
    }

    // ---- §5.2 currentForkIndex ----

    @Test
    public void currentForkIndex_forkIndexOverrideWins() {
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "2");
        set(ForkAwareAccountResolver.SUREFIRE_FORK_NUMBER, "9");
        assertEquals(2, ForkAwareAccountResolver.currentForkIndex());
    }

    @Test
    public void currentForkIndex_fromSurefireForkNumberOneBased() {
        set(ForkAwareAccountResolver.SUREFIRE_FORK_NUMBER, "3");
        assertEquals(2, ForkAwareAccountResolver.currentForkIndex());
    }

    @Test
    public void currentForkIndex_defaultZeroWhenAbsent() {
        assertEquals(0, ForkAwareAccountResolver.currentForkIndex());
    }

    @Test
    public void currentForkIndex_negativeOrGarbageTreatedAsZero() {
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "-1");
        assertEquals(0, ForkAwareAccountResolver.currentForkIndex());
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "not-a-number");
        assertEquals(0, ForkAwareAccountResolver.currentForkIndex());
    }

    // ---- §5.1 stride ----

    @Test
    public void stride_customValue() {
        set(ForkAwareAccountResolver.STRIDE_KEY, "2");
        assertEquals(2, ForkAwareAccountResolver.stride());
    }

    @Test
    public void stride_defaultWhenAbsentOrInvalid() {
        assertEquals(4, ForkAwareAccountResolver.stride());
        set(ForkAwareAccountResolver.STRIDE_KEY, "0");
        assertEquals(4, ForkAwareAccountResolver.stride());
        set(ForkAwareAccountResolver.STRIDE_KEY, "x");
        assertEquals(4, ForkAwareAccountResolver.stride());
    }

    // ---- §5.2 myUsers 静态切片 ----

    @Test
    public void myUsers_partitionsByStride() {
        set(ACCOUNTS_KEY, "A,B,C,D,E,F,G,H");
        set(ForkAwareAccountResolver.STRIDE_KEY, "4");
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "0");
        assertEquals(List.of("A", "B", "C", "D"), ForkAwareAccountResolver.myUsers(ENV));
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "1");
        assertEquals(List.of("E", "F", "G", "H"), ForkAwareAccountResolver.myUsers(ENV));
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "2");
        assertEquals(List.of(), ForkAwareAccountResolver.myUsers(ENV));
    }

    @Test
    public void myUsers_partialLastSlice() {
        set(ACCOUNTS_KEY, "A,B,C");
        set(ForkAwareAccountResolver.STRIDE_KEY, "4");
        assertEquals(List.of("A", "B", "C"), ForkAwareAccountResolver.myUsers(ENV));
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "1");
        assertEquals(List.of(), ForkAwareAccountResolver.myUsers(ENV));
    }

    // ---- §5.4/§5.5 isMine + fail-fast ----

    @Test
    public void isMine_trueForInSliceUserFalseForOtherFork() {
        set(ACCOUNTS_KEY, "A,B,C,D,E,F,G,H");
        set(ForkAwareAccountResolver.STRIDE_KEY, "4");
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "0");
        assertTrue(ForkAwareAccountResolver.isMine(ENV, "C"));
        assertFalse(ForkAwareAccountResolver.isMine(ENV, "F")); // 属 fork 1
    }

    @Test
    public void assertAccountBelongsToCurrentFork_okWhenInSlice() {
        set(ACCOUNTS_KEY, "A,B,C,D,E,F,G,H");
        set(ForkAwareAccountResolver.STRIDE_KEY, "4");
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "0");
        ForkAwareAccountResolver.assertAccountBelongsToCurrentFork(ENV, "A"); // 不抛
    }

    @Test
    public void assertAccountBelongsToCurrentFork_throwsWhenOutOfSlice() {
        set(ACCOUNTS_KEY, "A,B,C,D,E,F,G,H");
        set(ForkAwareAccountResolver.STRIDE_KEY, "4");
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "0"); // 本 fork 只拥有 A~D
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ForkAwareAccountResolver.assertAccountBelongsToCurrentFork(ENV, "F"));
        assertTrue(ex.getMessage().contains("fork 1"));
        assertTrue(ex.getMessage().contains("Fork partition violation"));
    }

    @Test
    public void assertAccountBelongsToCurrentFork_noOpWhenEnvUnconfigured() {
        // 未配置 sso.accounts.<env>：不分区、不拦截（零回归）
        ForkAwareAccountResolver.assertAccountBelongsToCurrentFork("UNCONFIGURED_ENV", "anyUser");
    }

    @Test
    public void isMine_trueWhenEnvUnconfigured() {
        assertTrue(ForkAwareAccountResolver.isMine("UNCONFIGURED_ENV", "anyUser"));
    }

    // ---- §5.7 RunScopeAccountProvider 动态裁剪 ----
    // 仅跑 O63（provider 返回活跃子集 [A,C]）：fork 只在子集里切，其余账号不参与分区。

    private static final RunScopeAccountProvider O63_ACTIVE_ONLY = env ->
            "O63_SIT1".equals(env) ? List.of("A", "C") : List.of();

    @Test
    public void myUsers_trimmedByActiveProvider() {
        RunScopeAccountProviders.setInjected(O63_ACTIVE_ONLY);
        set(ACCOUNTS_KEY, "A,B,C,D,E,F,G,H"); // 全量 8 个
        set(ForkAwareAccountResolver.STRIDE_KEY, "4");
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "0");
        // 活跃子集 [A,C] → fork0 整段切得 [A,C]
        assertEquals(List.of("A", "C"), ForkAwareAccountResolver.myUsers(ENV));
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "1");
        // 子集已空 → 无账号（O38/O88 不占 fork）
        assertEquals(List.of(), ForkAwareAccountResolver.myUsers(ENV));
    }

    @Test
    public void isMine_trimmedProviderExcludesOutOfScopeUser() {
        RunScopeAccountProviders.setInjected(O63_ACTIVE_ONLY);
        set(ACCOUNTS_KEY, "A,B,C,D,E,F,G,H");
        set(ForkAwareAccountResolver.STRIDE_KEY, "4");
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "0");
        // A/C 在活跃子集内 → 属本 fork
        assertTrue(ForkAwareAccountResolver.isMine(ENV, "A"));
        assertTrue(ForkAwareAccountResolver.isMine(ENV, "C"));
        // F 不在活跃子集 → 不属本 fork（不会误占 O38/O88 账号）
        assertFalse(ForkAwareAccountResolver.isMine(ENV, "F"));
    }

    @Test
    public void myUsers_fullWhenProviderReturnsEmpty() {
        RunScopeAccountProviders.setInjected(env -> List.of()); // 空 = 不裁剪
        set(ACCOUNTS_KEY, "A,B,C,D,E,F,G,H");
        set(ForkAwareAccountResolver.STRIDE_KEY, "4");
        set(ForkAwareAccountResolver.FORK_INDEX_OVERRIDE, "0");
        assertEquals(List.of("A", "B", "C", "D"), ForkAwareAccountResolver.myUsers(ENV));
    }
}
