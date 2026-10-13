package com.hsbc.cmb.hk.dbb.automation.tests.verify;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.DriverRaceErrors;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * §6.3 验证门禁（CI 通道压测，fail-closed）：真实浏览器 + 故意高并行 + 故意慢 handler（DELAY 能力启用）
 * 的 torture 下，断言<b>运行期协议异常计数 == 0</b>（{@link DriverRaceErrors.Monitor#raceCount()}）。
 *
 * <p>目标根因（§8.4）：把「调度线程 / 独立 IO 线程直接操作 {@code route}」彻底消除，让 {@code route} 的协议调用
 * （{@code fulfill}/{@code resume}/{@code fetch}）只落在<b>单一拥有线程</b>。若该治理回归（DELAY 交付落到调度线程），
 * 真实连接会抛出 {@code Object doesn't exist: …} 级联损坏 → {@code DriverRaceErrors.Monitor} 累加 → 计数非零 → 构建失败。</p>
 *
 * <p>本用例是 {@code pw-route} 的 {@code DelayGuardTortureTest}（Mockito 机理级：断言 resume 只在 main 线程）在
 * <b>真实连接</b>上的对等落点：机理级验证保证「resume 不落非 main 线程」，本用例保证「真实连接上因此产生的协议异常计数 == 0」。</p>
 *
 * <p><b>为何不「故意中断导航」</b>：{@link DriverRaceErrors.Monitor#record} 在<b>每次</b>观测到驱动竞态
 * （含 {@code INTERRUPTED_BY_ANOTHER_NAVIGATION}，即便已自愈）时 +1。门禁语义是「零协议异常」，故 torture 只压
 * §8.4 的 DELAY / 跨线程路径（不人为制造中断导航），避免把已自愈的良性中断也计入计数、使门禁失真。</p>
 *
 * <p>规则拆分：DELAY 规则（单位秒，{@code .delay(1)}）负责压 §8.4 交付路径；MOCK 规则（status 200）提供确定性 200
 * 响应，保证 torture 非真空（{@code delivered > 0}）。两者在同 origin 不同 pattern 上并行触发。</p>
 *
 * <p>环境守卫：靶权 8899 不可达或 Playwright 浏览器不可用时 Assume 自动跳过（与 {@code ApiCaptureContextE2ETest} 一致）。</p>
 */
public class ProtocolRaceGateTest {

    private static final String TESTBED = "http://127.0.0.1:8899";

    private Playwright playwright;
    private Browser browser;
    private BrowserContext ctx;
    private final AtomicInteger delivered = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();

    @Before
    public void setUp() {
        Assume.assumeTrue("testbed 8899 不可达，跳过 §6.3 验证门禁", isTestbedUp());
        try {
            playwright = Playwright.create();
        } catch (Exception e) {
            Assume.assumeNoException("Playwright 浏览器不可用", e);
        }
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        ctx = browser.newContext();
        PlaywrightManager.setProvider(new LiveProvider(ctx));
        RouteEngine.runtimeOf(ctx);
        // 门禁隔离：清零运行期协议异常计数
        DriverRaceErrors.Monitor.reset();
    }

    @After
    public void tearDown() {
        PlaywrightManager.resetProvider();
        if (ctx != null) {
            try { RouteEngine.shutdown(ctx); } catch (Exception ignored) { }
            try { ctx.close(); } catch (Exception ignored) { }
        }
        if (browser != null) { try { browser.close(); } catch (Exception ignored) { } }
        if (playwright != null) { try { playwright.close(); } catch (Exception ignored) { } }
    }

    @Test
    public void delayTortureKeepsProtocolRaceCountZero() throws Exception {
        // MOCK 规则：确定性 200，保证 torture 非真空
        String[] mockPatterns = {"/api/mock/a", "/api/mock/b", "/api/mock/c", "/api/mock/d"};
        for (String p : mockPatterns) {
            RouteDsl.on(ctx).api(p)
                    .mock().status(200).body("{\"ok\":true}").contentType("application/json")
                    .done().start();
        }
        // DELAY 规则（.delay 单位为秒）：压 §8.4「DELAY 交付重入拥有线程」路径
        String[] delayPatterns = {"/api/delay/a", "/api/delay/b", "/api/delay/c", "/api/delay/d"};
        for (String p : delayPatterns) {
            RouteDsl.on(ctx).api(p).delay(1).done().start();
        }

        int workers = 6;
        int iterations = 10;
        Thread[] ts = new Thread[workers];
        for (int w = 0; w < workers; w++) {
            final int seed = w;
            ts[w] = new Thread(() -> {
                for (int k = 0; k < iterations; k++) {
                    String mockUrl = TESTBED + mockPatterns[(k + seed) % mockPatterns.length];
                    String delayUrl = TESTBED + delayPatterns[(k + seed) % delayPatterns.length];
                    Page page = null;
                    try {
                        page = ctx.newPage();
                        page.navigate(TESTBED + "/login", new Page.NavigateOptions().setTimeout(15000));
                        // MOCK 路径：确定性 200，计入 delivered
                        Object mockResult = page.evaluate(
                                "u => fetch(u).then(r => r.status).catch(() => -1)", mockUrl);
                        int mockStatus = (mockResult instanceof Number) ? ((Number) mockResult).intValue() : -1;
                        statuses.add(mockStatus);
                        if (mockStatus == 200) {
                            delivered.incrementAndGet();
                        }
                        // DELAY 路径：触发 DELAY 交付（resume/fulfill 必须落在 main 拥有线程）
                        page.evaluate(
                                "u => fetch(u).then(r => r.status).catch(() => 0)", delayUrl);
                        // UI 条件等待：内部循环 drain 入队 DELAY 交付，使 fulfill 在 main 拥有线程执行
                        RouteDsl.waitForUiFunction(page, "() => true", null, 5000L);
                    } catch (Exception ignored) {
                        // 单页瞬时失败不影响门禁整体；门禁判定依据是 Monitor 计数而非单页成功数
                    } finally {
                        if (page != null) {
                            try { page.close(); } catch (Exception ignored) { }
                        }
                    }
                }
            }, "protocol-gate-worker-" + w);
            ts[w].start();
        }

        // main 拥有线程侧「慢 drain」：周期 drain 制造队列压力（贴近业务 step 间隙），
        // 但绝不自行调用 route 协议方法——DELAY 交付必须只由拥有线程的 drain 触发。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            RouteEngine.drainMainThreadActions(ctx);
            try {
                Thread.sleep(3);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Thread t : ts) {
            t.join(15_000);
        }
        RouteEngine.drainMainThreadActions(ctx);

        // 非真空断言：torture 必须真正产生了 MOCK 交付，否则 raceCount==0 是伪通过
        assertTrue("§6.3 torture 未产生任何 MOCK 交付（断言失真）：delivered=" + delivered.get(),
                delivered.get() > 0);

        long raceCount = DriverRaceErrors.Monitor.raceCount();
        if (raceCount != 0) {
            DriverRaceErrors.Record last = DriverRaceErrors.Monitor.last();
            String diag = (last == null) ? "<no record>"
                    : "kind=" + last.kind + ", thread=" + last.threadName
                    + ", objectGuid=" + last.objectGuid + ", msg=" + last.message;
            assertEquals("§6.3 验证门禁失败：运行期协议异常计数应为 0（fail-closed）。诊断: " + diag
                    + " | delivered=" + delivered.get(), 0L, raceCount);
        }
    }

    private static boolean isTestbedUp() {
        try (Socket s = new Socket("127.0.0.1", 8899)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class LiveProvider implements RuntimeProvider {
        private final BrowserContext ctx;
        private LiveProvider(BrowserContext ctx) { this.ctx = ctx; }
        @Override public com.microsoft.playwright.Playwright getPlaywright() { return null; }
        @Override public Browser getBrowser() { return null; }
        @Override public BrowserContext getContext() { return ctx; }
        @Override public Page getPage() { return null; }
        @Override public boolean isTestDouble() { return false; }
    }
}
