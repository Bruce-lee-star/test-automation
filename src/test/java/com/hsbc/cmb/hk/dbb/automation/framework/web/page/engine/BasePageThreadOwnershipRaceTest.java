package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * N-12（doc 21，跨仓覆盖错位）：<b>竞态敏感</b>的线程归属用例 —— 镜像框架仓的
 * {@code PageContextStateOwnershipRaceTest}。
 *
 * <p><b>为什么必须补这条</b>：本仓既有 {@code BasePageThreadOwnershipConcurrencyTest} 覆盖的是
 * 「归属<b>已确立之后</b>的跨线程访问」—— 那正是守卫<b>能</b>正确拒绝的路径（另两条是单线程正常、
 * 不同实例互不干扰）。而归属守卫唯一的竞态窗口是「实例<b>首次</b>被使用」：两线程可同时读到
 * "未归属"并双双通过，写入后者覆盖前者 → 根锚点失效 → 同一 Page 被并发使用（pipe closed / 静默操作错对象）。
 * 该路径<b>在本仓此前无任何用例覆盖</b>，测试的存在反而制造了虚假信心（doc 21 N-12：两仓覆盖错位）。</p>
 *
 * <p><b>做法（与框架仓镜像一致）</b>：每轮新建一个 {@code BasePage}，两线程经 {@link CyclicBarrier}
 * 对齐起点后同时冲刺 {@code getPage()}，重复 {@value #ROUNDS} 轮以放大"双双通过"的复现概率。</p>
 *
 * <p><b>断言口径（刻意不依赖异常文案）</b>：每轮必须<b>恰有一成功、恰有一失败</b>，且失败必须是
 * {@code IllegalStateException}。这样既是竞态敏感的（双双通过则 成功=2 直接报红），又不会因为框架改写
 * 提示文案而误报 —— 镜像版比"匹配消息子串"的写法更稳。</p>
 *
 * <p>无浏览器依赖：经 WEB-P0-2 DI seam（{@link PlaywrightManager#setProvider}）注入桩，
 * 仅在 JVM 内验证线程归属语义（正是消费者视角对框架 jar 的核验）。</p>
 */
class BasePageThreadOwnershipRaceTest {

    private static final int ROUNDS = 200;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface) {
        return (T) Proxy.newProxyInstance(
                BasePageThreadOwnershipRaceTest.class.getClassLoader(),
                new Class<?>[]{iface},
                (p, method, args) -> {
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    private static RuntimeProvider provider(Page page, BrowserContext ctx) {
        return new RuntimeProvider() {
            @Override
            public Playwright getPlaywright() {
                return null;
            }

            @Override
            public Browser getBrowser() {
                return null;
            }

            @Override
            public BrowserContext getContext() {
                return ctx;
            }

            @Override
            public Page getPage() {
                return page;
            }
        };
    }

    @AfterEach
    void tearDown() {
        PlaywrightManager.resetProvider();
    }

    @Test
    @Timeout(180)
    @DisplayName("N-12：两线程【同时首次】访问同一 BasePage → 每轮恰一成功一被拒（竞态敏感）")
    void simultaneousFirstAccessHasExactlyOneOwner() throws Exception {
        Page page = proxy(Page.class);
        BrowserContext ctx = proxy(BrowserContext.class);
        PlaywrightManager.setProvider(provider(page, ctx));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                final BasePage bp = new BasePage() {
                };
                final CyclicBarrier barrier = new CyclicBarrier(2);
                Callable<Throwable> racer = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        bp.getPage();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                };
                Future<Throwable> f1 = pool.submit(racer);
                Future<Throwable> f2 = pool.submit(racer);
                Throwable t1 = f1.get(20, TimeUnit.SECONDS);
                Throwable t2 = f2.get(20, TimeUnit.SECONDS);

                int succeeded = (t1 == null ? 1 : 0) + (t2 == null ? 1 : 0);
                int rejected = (t1 instanceof IllegalStateException ? 1 : 0)
                        + (t2 instanceof IllegalStateException ? 1 : 0);

                assertEquals(1, succeeded,
                        "第 " + round + " 轮：必须恰有一线程取得归属（若为 2，说明两线程双双通过了"
                                + "「读-判-写」窗口 = CT2-03 根锚点竞态回归）。t1=" + t1 + ", t2=" + t2);
                assertEquals(1, rejected,
                        "第 " + round + " 轮：另一线程必须被线程归属守卫以 IllegalStateException 拒绝。"
                                + "t1=" + t1 + ", t2=" + t2);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
