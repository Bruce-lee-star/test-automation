package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;

import java.util.List;
import java.util.ServiceLoader;

/**
 * §5.7 {@link RunScopeAccountProvider} 解析器（配置项 / ServiceLoader / 默认 三级回退 + 测试 seam）。
 *
 * <p>解析优先级：
 * <ol>
 *   <li><b>测试 seam</b> {@link #setInjected(RunScopeAccountProvider)}（仅测试注入，最高优先）；</li>
 *   <li><b>配置类</b> {@code sso.run.scope.provider=&lt;FQCN&gt;}（反射实例化，失败回落下一级，不致命）；</li>
 *   <li><b>ServiceLoader</b> {@code META-INF/services/...RunScopeAccountProvider}（取首个）；</li>
 *   <li><b>默认</b> {@link DefaultRunScopeProvider#INSTANCE}（全量）。</li>
 * </ol>
 * </p>
 *
 * <p>线程安全：{@code resolved} 以 {@code volatile} + 双重检查锁定缓存；{@code setInjected} 同时清缓存，供测试隔离。
 * 反射 / ServiceLoader 失败一律回落默认，绝不因注入错误中断构建。</p>
 */
public final class RunScopeAccountProviders {

    /** 配置项：业务实现类全限定名。 */
    public static final String PROVIDER_CLASS_KEY = "sso.run.scope.provider";

    private static volatile RunScopeAccountProvider injected; // 测试 seam（最高优先）
    private static volatile RunScopeAccountProvider resolved; // 运行期缓存
    private static final Object LOCK = new Object();

    private RunScopeAccountProviders() {
    }

    /**
     * 测试 seam：直接注入实现（覆盖配置 / ServiceLoader）。传 {@code null} 恢复默认解析（并清缓存）。
     *
     * @param provider 注入的实现；{@code null} = 解除注入
     */
    public static void setInjected(RunScopeAccountProvider provider) {
        injected = provider;
        resolved = null;
    }

    /**
     * 解析当前生效的 provider（三级回退）。结果缓存至下一次 {@link #setInjected(Object)} 或 JVM 退出。
     */
    public static RunScopeAccountProvider resolve() {
        RunScopeAccountProvider seam = injected;
        if (seam != null) {
            return seam;
        }
        if (resolved != null) {
            return resolved;
        }
        synchronized (LOCK) {
            if (resolved != null) {
                return resolved;
            }
            RunScopeAccountProvider p = fromConfigClass();
            if (p == null) {
                p = fromServiceLoader();
            }
            if (p == null) {
                p = DefaultRunScopeProvider.INSTANCE;
            }
            resolved = p;
            return p;
        }
    }

    /** 配置类注入：反射实例化 {@code sso.run.scope.provider}；未配置或实例化失败一律返回 {@code null}（回落下一级）。 */
    private static RunScopeAccountProvider fromConfigClass() {
        String fqcn = ConfigSource.resolve(PROVIDER_CLASS_KEY, "").trim();
        if (fqcn.isEmpty()) {
            return null;
        }
        try {
            Class<?> clazz = Class.forName(fqcn, true, RunScopeAccountProviders.class.getClassLoader());
            if (!RunScopeAccountProvider.class.isAssignableFrom(clazz)) {
                return null;
            }
            Object instance = clazz.getDeclaredConstructor().newInstance();
            return (RunScopeAccountProvider) instance;
        } catch (Throwable t) {
            // 反射失败不致命：回落 ServiceLoader / 默认，避免注入错误导致构建崩溃
            return null;
        }
    }

    /** ServiceLoader 注入：取 classpath 中 {@code META-INF/services} 注册的首个实现；无则 {@code null}。 */
    private static RunScopeAccountProvider fromServiceLoader() {
        try {
            ServiceLoader<RunScopeAccountProvider> loader =
                    ServiceLoader.load(RunScopeAccountProvider.class, RunScopeAccountProviders.class.getClassLoader());
            for (RunScopeAccountProvider p : loader) {
                return p;
            }
        } catch (Throwable t) {
            // ServiceLoader 异常（如模块层问题）不致命
        }
        return null;
    }
}
