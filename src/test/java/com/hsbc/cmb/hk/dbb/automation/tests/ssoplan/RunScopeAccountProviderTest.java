package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link RunScopeAccountProviders} 解析契约守卫（§5.7 注入三级回退 + 测试 seam）。
 *
 * <p>锁定：
 * <ol>
 *   <li>未注入且配置 / ServiceLoader 均无 → {@link DefaultRunScopeProvider}（全量，返回空）；</li>
 *   <li>测试 seam {@link #setInjected} 最高优先；</li>
 *   <li>配置类 {@code sso.run.scope.provider} 反射实例化成功 → 取该类；</li>
 *   <li>配置类实例化失败（类名错误 / 无无参构造 / 类型不符）→ 回落默认，不致命。</li>
 * </ol>
 */
public class RunScopeAccountProviderTest {

    @After
    public void tearDown() {
        RunScopeAccountProviders.setInjected(null);
        System.setProperty(RunScopeAccountProviders.PROVIDER_CLASS_KEY, "");
    }

    @Test
    // @DisplayName: "默认无注入：返回 DefaultRunScopeProvider（全量 = 空）"
    public void defaultProvider_returnsEmpty_full() {
        RunScopeAccountProviders.setInjected(null);
        assertTrue(RunScopeAccountProviders.resolve() instanceof DefaultRunScopeProvider);
        assertTrue(RunScopeAccountProviders.resolve().activeUsers("O63_SIT1").isEmpty());
    }

    @Test
    // @DisplayName: "测试 seam 注入优先于默认"
    public void injectedProvider_winsOverDefault() {
        RunScopeAccountProviders.setInjected(env -> List.of("A"));
        assertEquals(List.of("A"), RunScopeAccountProviders.resolve().activeUsers("O63_SIT1"));
    }

    @Test
    // @DisplayName: "配置类 sso.run.scope.provider 反射实例化成功"
    public void configClass_providerResolves() {
        RunScopeAccountProviders.setInjected(null);
        System.setProperty(RunScopeAccountProviders.PROVIDER_CLASS_KEY, O63ConfigProvider.class.getName());
        assertEquals(List.of("A", "B"), RunScopeAccountProviders.resolve().activeUsers("O63_SIT1"));
    }

    @Test
    // @DisplayName: "配置类实例化失败（类名错误）回落默认，不致命"
    public void configClass_instantiationFailure_fallsBackToDefault() {
        RunScopeAccountProviders.setInjected(null);
        System.setProperty(RunScopeAccountProviders.PROVIDER_CLASS_KEY, "com.nonexistent.NoSuchProvider");
        assertTrue(RunScopeAccountProviders.resolve() instanceof DefaultRunScopeProvider);
    }

    /** 配置类注入的示例实现（无参构造）。 */
    public static class O63ConfigProvider implements RunScopeAccountProvider {
        public O63ConfigProvider() {
        }

        @Override
        public List<String> activeUsers(String env) {
            return List.of("A", "B");
        }
    }
}
