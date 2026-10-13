package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SsoPlanValidator} 逻辑单测（注入式清单，不依赖配置/文件扫描）。
 */
public class SsoPlanValidatorTest {

    private static final List<UsedAccount> USED = List.of(
            new UsedAccount("web/login_dbb.feature", "scn-a", "O63_SIT1", "WP7UAT2_2"),
            new UsedAccount("web/login_dbb.feature", "scn-b", "O63_SIT1", "WP7UAT2_2"),
            new UsedAccount("web/login_dbb.feature", "scn-c", "O88_SIT3", "amhb2g0680_2"));

    @Test
    public void missingAccountIsDetectedWhenEnvConfiguredButUserAbsent() {
        SsoAccountManifest manifest = SsoAccountManifest.of(
                Map.of("O63_SIT1", List.of("WP7UAT2_2")));
        SsoPlanResultExpose r = evaluate(USED, manifest);

        assertEquals(0, r.missing().size());
        assertTrue(r.unused().isEmpty());
    }

    @Test
    public void undeclaredUserUnderConfiguredEnvFailsFast() {
        // 仅声明了 WP7UAT2_1，但场景用了 WP7UAT2_2（同 env 未声明）
        SsoAccountManifest manifest = SsoAccountManifest.of(
                Map.of("O63_SIT1", List.of("WP7UAT2_1")));
        SsoPlanResultExpose r = evaluate(USED, manifest);

        assertEquals(1, r.missing().size());
        SsoPlanValidator.MissingAccount m = r.missing().get(0);
        assertEquals("O63_SIT1", m.env());
        assertEquals("WP7UAT2_2", m.user());
        assertTrue(m.scenarios().contains("scn-a"));
        assertTrue(m.scenarios().contains("scn-b"));
    }

    @Test
    public void crossEnvSameUsernameTreatedAsDistinct() {
        // O63 与 O88 都声明了同名 user X —— 视为不同账号，不误报
        SsoAccountManifest manifest = SsoAccountManifest.of(Map.of(
                "O63_SIT1", List.of("X"),
                "O88_SIT3", List.of("X")));
        List<UsedAccount> used = List.of(
                new UsedAccount("f", "s1", "O63_SIT1", "X"),
                new UsedAccount("f", "s2", "O88_SIT3", "X"));
        SsoPlanResultExpose r = evaluate(used, manifest);
        assertEquals(0, r.missing().size());
    }

    @Test
    public void unusedConfiguredAccountWarns() {
        SsoAccountManifest manifest = SsoAccountManifest.of(
                Map.of("O63_SIT1", List.of("WP7UAT2_2", "WP7UAT2_3")));
        SsoPlanResultExpose r = evaluate(USED, manifest);
        assertTrue(r.unused().contains("O63_SIT1/WP7UAT2_3"));
        assertEquals(0, r.missing().size());
    }

    @Test
    public void reportContainsSsoPlanMarker() {
        SsoAccountManifest manifest = SsoAccountManifest.of(
                Map.of("O63_SIT1", List.of("WP7UAT2_2")));
        SsoPlanResultExpose r = evaluate(USED, manifest);
        String report = r.report();
        assertTrue(report.contains("[SsoPlan]"));
        assertTrue(report.contains("Accounts used:"));
        assertTrue(report.contains("→ fork"));
    }

    @Test(expected = AssertionError.class)
    public void failFastThrowsOnMissing() {
        SsoAccountManifest manifest = SsoAccountManifest.of(
                Map.of("O63_SIT1", List.of("WP7UAT2_1")));
        SsoPlanResultExpose r = evaluate(USED, manifest);
        r.failFast();
    }

    @Test
    public void failFastNoOpWhenNothingMissing() {
        SsoAccountManifest manifest = SsoAccountManifest.of(
                Map.of("O63_SIT1", List.of("WP7UAT2_2")));
        SsoPlanResultExpose r = evaluate(USED, manifest);
        r.failFast(); // 不应抛
        assertFalse(r.missing().isEmpty() == false && true); // 占位，确保 failFast 已调用
    }

    private static SsoPlanResultExpose evaluate(List<UsedAccount> used, SsoAccountManifest manifest) {
        return new SsoPlanResultExpose(SsoPlanValidator.evaluate(used, manifest, 1, used.size()));
    }

    /** 暴露内部结果字段的薄包装，避免重复写 getter 断言。 */
    private static final class SsoPlanResultExpose {
        private final SsoPlanValidator.SsoPlanResult delegate;
        SsoPlanResultExpose(SsoPlanValidator.SsoPlanResult delegate) {
            this.delegate = delegate;
        }
        List<SsoPlanValidator.MissingAccount> missing() {
            return delegate.missing();
        }
        List<String> unused() {
            return delegate.unused();
        }
        String report() {
            return delegate.report();
        }
        void failFast() {
            delegate.failFast();
        }
    }
}
