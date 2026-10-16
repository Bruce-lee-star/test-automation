package com.hsbc.cmb.hk.dbb.automation.tests.verify;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.ApiCaptureQueryRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.CapturedApiCallView;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
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
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ApiCaptureContext 全字段 E2E 验证（真实浏览器 + 真实靶权 8899）。
 *
 * <p>验证链路：业务门面 {@link ApiCaptureContext#getCurrent()} → SPI → RouteEngine →
 * per-context sink → Playwright route。每一个字段都必须真实采到，不得为 null/空。
 *
 * <p>环境守卫：靶权 8899 不可达时 Assume 自动跳过。
 */
public class ApiCaptureContextE2ETest {

    private static final String TESTBED = "http://127.0.0.1:8899";

    private Playwright playwright;
    private Browser browser;
    private BrowserContext ctx;

    @Before
    public void setUp() {
        Assume.assumeTrue("testbed 8899 不可达，跳过 E2E", isTestbedUp());
        try {
            playwright = Playwright.create();
        } catch (Exception e) {
            Assume.assumeNoException("Playwright 浏览器不可用", e);
        }
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        ctx = browser.newContext();

        // 1. 注入真实 BrowserContext 到 PlaywrightManager（门面取 context 的唯一通道）
        PlaywrightManager.setProvider(new LiveProvider(ctx));

        // 2. 触发 ApiCaptureQueryImpl 类加载（静态块自注册 SPI）
        try {
            Class.forName("com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle.ApiCaptureQueryImpl");
        } catch (ClassNotFoundException e) {
            Assume.assumeNoException("pw-route 不在 classpath，跳过 E2E", e);
        }
    }

    @After
    public void tearDown() {
        PlaywrightManager.resetProvider();
        ApiCaptureQueryRegistry.clear();
        if (ctx != null) {
            try { RouteEngine.shutdown(ctx); } catch (Exception ignored) { }
            try { ctx.close(); } catch (Exception ignored) { }
        }
        if (browser != null) { try { browser.close(); } catch (Exception ignored) { } }
        if (playwright != null) { try { playwright.close(); } catch (Exception ignored) { } }
    }

    @Test
    public void monitorCapturesEveryFieldEndToEnd() {
        // 启动 route engine + 挂 monitor
        RouteEngine.runtimeOf(ctx);
        RouteDsl.on(ctx).api("/api/profile/list").monitor().done().start();

        // 打开页面并触发一次 XHR
        Page page = ctx.newPage();
        page.navigate(TESTBED + "/login", new Page.NavigateOptions().setTimeout(15000));
        page.evaluate("fetch('/api/profile/list', {headers:{'X-Test-Case':'e2e'}}).then(r => r.status)");

        // 用门面等待采集落库
        CapturedApiCallView view = ApiCaptureContext.getCurrent()
                .waitForApi(v -> v.url() != null && v.url().contains("/api/profile/list"), 10_000L);

        assertNotNull("门面必须能查到采集记录", view);

        // —— 逐字段断言 ——
        assertEquals("method 必须采到", "GET", view.method());
        assertNotNull("url 必须采到", view.url());
        assertTrue("url 必须是完整 URL", view.url().startsWith(TESTBED));
        assertNotNull("pattern 必须采到", view.pattern());
        assertTrue("requestHeaders 不得为空", !view.requestHeaders().isEmpty());
        assertEquals("requestHeaders 必须含自定义头",
                "e2e", view.requestHeaders().get("x-test-case"));
        assertNotNull("responseStatus 必须采到（用户反馈为 null 的字段）", view.responseStatus());
        assertEquals("responseStatus 应为 200", Integer.valueOf(200), view.responseStatus());
        assertNotNull("responseHeaders 必须采到", view.responseHeaders());
        assertTrue("responseHeaders 至少含 content-type",
                view.responseHeaders().keySet().stream().anyMatch(k -> k.toLowerCase().contains("content-type")));
        assertNotNull("responseBody 必须采到", view.responseBody());
        assertTrue("responseBody 应为真实靶权 JSON", view.responseBody().contains("enableAdminTools"));
        assertTrue("durationMs 必须 > 0", view.durationMs() > 0);
        assertFalse("timedOut 应为 false", view.timedOut());
        assertTrue("recordedAtMillis 应为当前时间附近",
                Math.abs(System.currentTimeMillis() - view.recordedAtMillis()) < 60_000L);
        assertNotNull("channels 不得为 null", view.channels());
        // 裸 .monitor() 规则的 channels 集合为空（primaryChannel=MONITOR 才是判定依据），
        // 故用 getCallsByChannel 验证（内部走 primaryChannel fallback）。
        List<CapturedApiCallView> byMonitor = ApiCaptureContext.getCurrent().getCallsByChannel("MONITOR");
        assertTrue("getCallsByChannel(MONITOR) 应能查到该记录",
                byMonitor.stream().anyMatch(v -> v.url().contains("/api/profile/list")));
        assertNotNull("contentType 必须采到", view.contentType());
        assertTrue("contentType 应为 JSON", view.contentType().toLowerCase().contains("json"));

        // 二次查询：getLastApiCall 应返回同一条
        CapturedApiCallView again = ApiCaptureContext.getCurrent().getLastApiCall("/api/profile/list");
        assertNotNull("getLastApiCall 应返回记录", again);
        assertEquals("两次查询应一致", view.url(), again.url());

        // getAllApiCalls 非空
        List<CapturedApiCallView> all = ApiCaptureContext.getCurrent().getAllApiCalls();
        assertTrue("getAllApiCalls 应至少含一条", !all.isEmpty());
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
        @Override public Playwright getPlaywright() { return null; }
        @Override public Browser getBrowser() { return null; }
        @Override public BrowserContext getContext() { return ctx; }
        @Override public Page getPage() { return null; }
        @Override public boolean isTestDouble() { return false; }
    }
}
