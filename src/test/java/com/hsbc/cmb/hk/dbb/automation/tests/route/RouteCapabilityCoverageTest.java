package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dispatch.RouteDispatcher;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Route V2 能力全量覆盖 + 资源泄漏体检（mock 驱动，无需浏览器 / 不依赖 DBB 环境）。
 *
 * <p>范围对齐登录场景（{@code Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"}）里
 * 实际用到的四种能力及其"边角"，全部走 {@link RouteDsl} 公共 API：
 * <ul>
 *   <li><b>MOCK</b>：静态体 / 字段替换 / {@code when(...).thenSet(...)} 条件替换（命中与不命中两条分支）/
 *       {@code interceptResponse()} 取真实响应 / status+headers+contentType / {@code mockBodyFromFile}
 *       （含失败快速失败）/ intercept 与静态体互斥；</li>
 *   <li><b>MONITOR</b>：{@code expectStatus} / {@code expectJsonPath} / {@code expectBodyContains} /
 *       {@code expectBodyRegex} / {@code expectFormField} 的通过-失败两侧，以及 <b>timeout 定案</b>；</li>
 *   <li><b>MODIFY_REQUEST</b>：header 增删、method、body 字段增删改、表单字段，非适用 content-type 的 fail-open；</li>
 *   <li><b>DELAY</b>：{@code delay(0)} 立即放行 与 {@code delay(1)} 真延时后放行；</li>
 *   <li><b>能力优先级</b>：{@link RouteCapability} 的"选择优先级 / 执行时序 / terminal"契约，
 *       以及同 pattern 多能力合并后 DELAY→MODIFY→MONITOR 的编排顺序。</li>
 * </ul>
 *
 * <p><b>资源泄漏体检（硬断言）</b>：每个用例收尾断言 runtime 注册表回落到基线；
 * 另有专门用例断言 dispatch 后无残留 claim、未确证撤销为 0，
 * 以及 runtime 关闭后 撤销 / 巡检线程<b>全部退出</b>（无线程残留）。
 *
 * <p>响应夹具取真实 DBB 返回：{@code mocks/dbb/leftmenu-permissionLeftMenuConfig.json}、
 * {@code mocks/dbb/pinned-account.json}、{@code mocks/dbb/insight-retrieve.json}。
 */
public class RouteCapabilityCoverageTest {

    private static final String FIX_LEFTMENU = "mocks/dbb/leftmenu-permissionLeftMenuConfig.json";
    private static final String FIX_PINNED = "mocks/dbb/pinned-account.json";
    private static final String FIX_INSIGHT = "mocks/dbb/insight-retrieve.json";

    /** 运行时线程名前缀（runtime 生命周期内存在，close() 后必须全部退出）。 */
    private static final String[] RUNTIME_THREAD_PREFIXES = {"route-v2-io-", "route-v2-retire-", "route-v2-sweep-"};

    private BrowserContext ctx;
    private RouteRuntime runtime;
    private APIRequestContext apiCtx;
    private int runtimeBaseline;

    @Before
    public void setUp() {
        runtimeBaseline = RouteEngine.activeRuntimes();
    }

    @After
    public void tearDown() {
        try {
            if (ctx != null) {
                RouteEngine.shutdown(ctx);
            }
        } finally {
            // 泄漏体检：每个用例结束，runtime 注册表必须回落基线（否则 context→runtime 映射在被持有）
            assertEquals("用例结束后 runtime 注册表必须回落基线（无泄漏）", runtimeBaseline, RouteEngine.activeRuntimes());
            ctx = null;
            runtime = null;
            apiCtx = null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // A. 能力优先级契约
    // ══════════════════════════════════════════════════════════════

    @Test
    // @DisplayName: "能力优先级：选择优先级 MOCK < MODIFY_REQUEST < MONITOR < DELAY"
    public void selectionPriorityOrderIsMockModifyMonitorDelay() {
        List<RouteCapability> sorted = new ArrayList<>(List.of(RouteCapability.values()));
        sorted.sort(RouteCapability.bySelectionPriority());
        assertEquals("选择优先级（同 endpoint 多能力位时谁被选中）：MOCK 最优先，DELAY 最后", List.of(RouteCapability.MOCK, RouteCapability.MODIFY_REQUEST,
                RouteCapability.MONITOR, RouteCapability.DELAY), sorted);
    }

    @Test
    // @DisplayName: "能力优先级：执行时序 DELAY → MODIFY_REQUEST → MOCK → MONITOR"
    public void executionOrderIsDelayModifyMockMonitor() {
        List<RouteCapability> sorted = new ArrayList<>(List.of(RouteCapability.values()));
        sorted.sort(RouteCapability.byExecutionOrder());
        assertEquals("执行时序（同一请求上的编排顺序）：先延时，再改请求，再伪造，最后观测", List.of(RouteCapability.DELAY, RouteCapability.MODIFY_REQUEST,
                RouteCapability.MOCK, RouteCapability.MONITOR), sorted);
    }

    @Test
    // @DisplayName: "能力优先级：仅 MOCK 是终结能力（命中即短路 fulfill）"
    public void onlyMockIsTerminal() {
        for (RouteCapability capability : RouteCapability.values()) {
            assertEquals(capability + " 的 terminal 标记必须为 " + (capability == RouteCapability.MOCK), capability == RouteCapability.MOCK, capability.isTerminal());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // B. MOCK
    // ══════════════════════════════════════════════════════════════

    @Test
    // @DisplayName: "MOCK：夹具静态体 + 字段替换（pinned-account 真实响应）"
    public void mockStaticBodyFromFixtureReplacesField() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/pinned-accounts")
                .mock().status(200).contentType("application/json")
                .mockBodyFromFile(FIX_PINNED)
                .replaceField("$.data.currentAccounts.maxPinnedAccount", 20)
                .register();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/pinned-accounts", "GET", null, null, null));
        handler.accept(route);

        Route.FulfillOptions options = captureFulfill(route);
        assertEquals("MOCK 状态码", 200L, (long) options.status);
        assertTrue("真实夹具的 maxPinnedAccount 必须被替换为 20，实际: " + options.body, find(options.body, "\"maxPinnedAccount\"\\s*:\\s*20"));
        assertTrue("原始值 10 不应残留", find(options.body, "\"maxPinnedAccount\"\\s*:\\s*10") == false);
    }

    @Test
    // @DisplayName: "MOCK：when(...).thenSet(...) 条件命中时写入新值"
    public void conditionalWhenThenSetAppliesWhenMatched() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/pinned-accounts")
                .mock().status(200).contentType("application/json")
                .mockBodyFromFile(FIX_PINNED)
                .when("$.data.currentAccounts.obsStatus", "EQUALS", "Y")
                .thenSet("$.data.currentAccounts.isDefault", false)
                .register();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/pinned-accounts", "GET", null, null, null));
        handler.accept(route);

        Route.FulfillOptions options = captureFulfill(route);
        assertTrue("条件命中时必须写入 false，实际: " + options.body, find(options.body, "\"isDefault\"\\s*:\\s*false"));
    }

    @Test
    // @DisplayName: "MOCK：when(...).thenSet(...) 条件不命中时保留原值"
    public void conditionalWhenThenSetKeepsValueWhenNotMatched() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/pinned-accounts")
                .mock().status(200).contentType("application/json")
                .mockBodyFromFile(FIX_PINNED)
                .when("$.data.currentAccounts.obsStatus", "EQUALS", "N")
                .thenSet("$.data.currentAccounts.isDefault", false)
                .register();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/pinned-accounts", "GET", null, null, null));
        handler.accept(route);

        Route.FulfillOptions options = captureFulfill(route);
        assertTrue("条件不命中时必须保留原值 true，实际: " + options.body, find(options.body, "\"isDefault\"\\s*:\\s*true"));
    }

    @Test
    // @DisplayName: "MOCK：interceptResponse 取真实响应 + 字段替换（insight/retrieve 真实响应）"
    public void mockInterceptReturnsRealResponseWithReplacement() {
        newMockContext();
        APIResponse apiResponse = Mockito.mock(APIResponse.class);
        Mockito.when(apiResponse.status()).thenReturn(200);
        Mockito.when(apiResponse.headers()).thenReturn(Map.of("content-type", "application/json"));
        Mockito.when(apiResponse.body()).thenReturn(fixture(FIX_INSIGHT).getBytes(StandardCharsets.UTF_8));
        Mockito.when(apiCtx.fetch(Mockito.anyString(), Mockito.any())).thenReturn(apiResponse);

        RouteDsl.on(ctx)
                .api("/dbb/insight/retrieve")
                .mock().interceptResponse()
                .mockReplaceField("$.message", "MOCKED")
                .register();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/insight/retrieve", "POST",
                "{\"location\":\"/home?href=InsightBox\",\"languageType\":\"en-US\",\"recordNum\":\"6\"}",
                "application/json", null));
        handler.accept(route);

        Route.FulfillOptions options = captureFulfill(route);
        assertEquals("intercept 必须沿用真实响应状态", 200L, (long) options.status);
        assertTrue("真实响应的 message 必须被替换，实际: " + options.body, find(options.body, "\"message\"\\s*:\\s*\"MOCKED\""));
    }

    @Test
    // @DisplayName: "MOCK：status / contentType / 自定义 header 全部落到终结点"
    public void mockStatusHeadersContentTypeApplied() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/mocked-status")
                .mock().status(201)
                .contentType("application/json")
                .header("x-mock", "1")
                .body("{\"code\":\"200\"}")
                .register();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/mocked-status", "GET", null, null, null));
        handler.accept(route);

        Route.FulfillOptions options = captureFulfill(route);
        assertEquals("MOCK 状态码必须是 201", 201L, (long) options.status);
        assertTrue("content-type 必须下发，实际 headers=" + options.headers + ", contentType=" + options.contentType, hasHeader(options, "content-type", "application/json"));
        assertTrue("自定义 header 必须下发，实际 headers=" + options.headers, hasHeader(options, "x-mock", "1"));
    }

    @Test
    // @DisplayName: "MOCK：mockBodyFromFile 资源缺失时注册即快速失败"
    public void mockBodyFromFileMissingResourceFailsFast() {
        newMockContext();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                RouteDsl.on(ctx)
                        .api("/dbb/nope")
                        .mock().mockBodyFromFile("mocks/dbb/does-not-exist.json")
                        .register());
        assertTrue("必须是明确的资源缺失错误，实际: " + ex.getMessage(), ex.getMessage() != null && ex.getMessage().contains("classpath resource not found"));
    }

    @Test
    // @DisplayName: "MOCK：interceptResponse 与静态 body 互斥（build 期快速失败）"
    public void mockInterceptConflictsWithStaticBody() {
        newMockContext();
        assertThrows(IllegalArgumentException.class, () ->
                RouteDsl.on(ctx)
                        .api("/dbb/conflict")
                        .mock().interceptResponse().body("{}")
                        .register());
    }

    // ══════════════════════════════════════════════════════════════
    // C. MODIFY_REQUEST
    // ══════════════════════════════════════════════════════════════

    @Test
    // @DisplayName: "MODIFY：header 新增 + method 改写 + body 字段改写，一次 resume 携带全部改动"
    public void modifyRequestHeaderMethodAndBody() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/modify-all")
                .modifyRequest()
                .setRequestHeader("X-Env", "mock")
                .modifyMethod("PUT")
                .modifyRequestBody("$.password", "changed")
                .register();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/modify-all", "POST",
                "{\"user\":\"u\",\"password\":\"old\"}", "application/json", null));
        handler.accept(route);

        Route.ResumeOptions options = captureResume(route);
        assertEquals("method 必须被改写", "PUT", options.method);
        assertEquals("新增 header 必须生效", "mock", options.headers.get("X-Env"));
        assertTrue("body 字段必须被改写，实际: " + options.postData, find(options.postData, "\"password\"\\s*:\\s*\"changed\""));
    }

    @Test
    // @DisplayName: "MODIFY：removeRequestHeader 删除指定头且保留其它头"
    public void modifyRemoveRequestHeaderDropsIt() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/modify-remove")
                .modifyRequest()
                .removeRequestHeader("x-drop")
                .register();
        Consumer<Route> handler = handler();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("x-drop", "secret");
        Route route = mockRoute(mockRequestWithHeaders("https://host/dbb/modify-remove", "GET", null, headers, null));
        handler.accept(route);

        Route.ResumeOptions options = captureResume(route);
        assertFalse("被删除的头不得出现", options.headers.containsKey("x-drop"));
        assertEquals("其它头必须保留", "application/json", options.headers.get("content-type"));
    }

    @Test
    // @DisplayName: "MODIFY：body 字段 改（SET）/ 数组追加（ADD）/ 删（REMOVE）三个操作并存"
    public void modifyRequestBodyAddSetRemoveField() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/modify-body")
                .modifyRequest()
                .modifyRequestBody("$.user", "u2")
                .addRequestBodyField("$.roles", "b")
                .removeRequestBodyField("$.temp")
                .register();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/modify-body", "POST",
                "{\"user\":\"u\",\"roles\":[\"a\"],\"temp\":\"x\"}", "application/json", null));
        handler.accept(route);

        Route.ResumeOptions options = captureResume(route);
        String postData = options.postData == null ? null : String.valueOf(options.postData);
        assertTrue("字段改写生效，实际: " + postData, find(postData, "\"user\"\\s*:\\s*\"u2\""));
        assertTrue("数组追加（ADD 语义）生效，实际: " + postData, find(postData, "\"roles\"\\s*:\\s*\\[\\s*\"a\"\\s*,\\s*\"b\"\\s*\\]"));
        assertFalse("字段删除生效，实际: " + postData, find(postData, "\"temp\""));
    }

    @Test
    // @DisplayName: "MODIFY：表单（urlencoded）字段改写"
    public void modifyFormFieldOnUrlEncodedBody() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/modify-form")
                .modifyRequest()
                .modifyFormField("password", "new")
                .register();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/modify-form", "POST",
                "user=u&password=old", "application/x-www-form-urlencoded", null));
        handler.accept(route);

        Route.ResumeOptions options = captureResume(route);
        String postData = options.postData == null ? null : String.valueOf(options.postData);
        assertNotNull("表单改写后必须携带 body", postData);
        assertTrue("表单字段必须被改写，实际: " + postData, postData.contains("password=new"));
        assertTrue("其它表单字段必须保留，实际: " + postData, postData.contains("user=u"));
    }

    @Test
    // @DisplayName: "MODIFY：非适用 content-type 明确拒绝修改，fail-open 原样放行"
    public void modifyNonApplicableContentTypeFailsOpen() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/modify-binary")
                .modifyRequest()
                .modifyRequestBody("$.a", "1")
                .register();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/modify-binary", "POST",
                "binary-data", "application/octet-stream", null));
        handler.accept(route);

        Route.ResumeOptions options = captureResume(route);
        assertEquals("非适用类型必须原样 resume（绝不丢 body）", "binary-data", options.postData);
    }

    // ══════════════════════════════════════════════════════════════
    // D. DELAY
    // ══════════════════════════════════════════════════════════════

    @Test
    // @DisplayName: "DELAY：delay(0) 无有效延时 → 立即放行（链式裁决 fallback）"
    public void delayZeroReleasesImmediately() {
        newMockContext();
        RouteDsl.on(ctx).api("/dbb/delay-zero").delay(0).done().start();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/delay-zero", "GET", null, null, null));
        long start = System.nanoTime();
        handler.accept(route);
        awaitTerminal(route, 5_000L, "resume", "fallback");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("delay(0) 必须近乎立即放行，实际 " + elapsedMs + "ms", elapsedMs < 1_000L);
    }

    @Test
    // @DisplayName: "DELAY：delay(1) 真延时后才放行（对齐到点必 resume 契约）"
    public void delayOneSecondResumesAfterDelay() {
        // 关闭 main 队列中转：delay 到点后直接在 SCHED 线程交付 resume，消除单测里 main 队列偶发晚到的时序竞态（确定性）。
        boolean prevDelivery = RouteDispatcher.isDelayDeliveryMainQueue();
        RouteDispatcher.setDelayDeliveryMainQueue(false);
        try {
            newMockContext();
            RouteDsl.on(ctx).api("/dbb/delay-one").delay(1).done().start();
            Consumer<Route> handler = handler();

            Route route = mockRoute(mockRequest("https://host/dbb/delay-one", "GET", null, null, null));
            long start = System.nanoTime();
            handler.accept(route);
            awaitTerminal(route, 5_000L, "resume");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertTrue("delay(1) 必须真延时 ≥900ms 才放行，实际 " + elapsedMs + "ms", elapsedMs >= 900L);
        } finally {
            RouteDispatcher.setDelayDeliveryMainQueue(prevDelivery);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // E. MONITOR 断言
    // ══════════════════════════════════════════════════════════════

    @Test
    // @DisplayName: "MONITOR：expectStatus 命中 → 不定案失败"
    public void monitorExpectStatusPasses() {
        newMockContext();
        RouteDsl.on(ctx).api("/dbb/monitor-status").monitor().expectStatus(200).register();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(200, fixture(FIX_INSIGHT));
        Route route = mockRoute(mockRequest("https://host/dbb/monitor-status", "GET", null, null, response));
        handler.accept(route);

        awaitAssertionSettlement();
        assertTrue("期望与响应一致时不得上报断言失败", runtime.drainSettledAssertionFailures().isEmpty());
    }

    @Test
    // @DisplayName: "MONITOR：expectStatus 不符 → 定案为断言失败"
    public void monitorExpectStatusMismatchSettlesAsFailure() {
        newMockContext();
        RouteDsl.on(ctx).api("/dbb/monitor-status-fail").monitor().expectStatus(200).register();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(500, "{\"error\":\"boom\"}");
        Route route = mockRoute(mockRequest("https://host/dbb/monitor-status-fail", "GET", null, null, response));
        handler.accept(route);

        awaitAssertionSettlement();
        assertEquals("期望 200 实际 500 必须定案为一条断言失败", 1, runtime.drainSettledAssertionFailures().size());
    }

    @Test
    // @DisplayName: "MONITOR：expectJsonPath 对 leftmenu 真实响应命中（enableAdminTools=Y）"
    public void monitorExpectJsonPathAgainstLeftmenuFixturePasses() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/leftmenu/permissionLeftMenuConfig")
                .monitor().expectStatus(200)
                .expectJsonPath("$.enableAdminTools", "Y")
                .expectJsonPath("$.enablePaymentPP", "P")
                .register();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(200, fixture(FIX_LEFTMENU));
        Route route = mockRoute(mockRequest("https://host/dbb/leftmenu/permissionLeftMenuConfig",
                "GET", null, null, response));
        handler.accept(route);

        // 2026-10-10 同步化：body 断言在调度线程（route-v2-delay）轮询到响应时【同步】就地结算，
        // 不再投 IO 线程 —— 无需（也不应）关闭 IO 池等待。这里等待的是调度轮询线程完成结算。
        awaitAssertionSettlement();
        assertTrue("真实夹具的两条 jsonPath 断言必须全部通过", runtime.drainSettledAssertionFailures().isEmpty());
    }

    @Test
    // @DisplayName: "MONITOR：expectJsonPath 值不符 → 定案失败"
    public void monitorExpectJsonPathMismatchSettlesAsFailure() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/leftmenu/permissionLeftMenuConfig")
                .monitor().expectStatus(200)
                .expectJsonPath("$.enableAdminTools", "N")
                .register();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(200, fixture(FIX_LEFTMENU));
        Route route = mockRoute(mockRequest("https://host/dbb/leftmenu/permissionLeftMenuConfig",
                "GET", null, null, response));
        handler.accept(route);

        awaitAssertionSettlement();
        assertEquals("期望 N 实际 Y 必须定案为一条断言失败", 1, runtime.drainSettledAssertionFailures().size());
    }

    @Test
    // @DisplayName: "MONITOR：expectJsonPath 路径不存在 → 定案失败（与值不符区分）"
    public void monitorExpectJsonPathMissingPathSettlesAsFailure() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/leftmenu/permissionLeftMenuConfig")
                .monitor()
                .expectJsonPath("$.noSuchFlag", "Y")
                .register();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(200, fixture(FIX_LEFTMENU));
        Route route = mockRoute(mockRequest("https://host/dbb/leftmenu/permissionLeftMenuConfig",
                "GET", null, null, response));
        handler.accept(route);

        awaitAssertionSettlement();
        assertEquals("路径不存在必须定案为一条断言失败", 1, runtime.drainSettledAssertionFailures().size());
    }

    @Test
    // @DisplayName: "MONITOR：expectBodyContains + expectBodyRegex 命中 → 不定案"
    public void monitorExpectBodyContainsAndRegexPass() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/monitor-body-text")
                .monitor().expectStatus(200)
                .expectBodyContains("SUCCESS")
                .expectBodyRegex("\"code\"\\s*:\\s*\"\\d+\"")
                .register();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(200, fixture(FIX_INSIGHT));
        Route route = mockRoute(mockRequest("https://host/dbb/monitor-body-text", "GET", null, null, response));
        handler.accept(route);

        awaitAssertionSettlement();
        assertTrue("contains/regex 均命中时不得上报失败", runtime.drainSettledAssertionFailures().isEmpty());
    }

    @Test
    // @DisplayName: "MONITOR：expectFormField 对 urlencoded 响应命中"
    public void monitorExpectFormFieldPasses() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/monitor-form")
                .monitor().expectStatus(200)
                .expectFormField("result", "OK")
                .register();
        Consumer<Route> handler = handler();

        Response response = Mockito.mock(Response.class);
        Mockito.when(response.status()).thenReturn(200);
        Mockito.when(response.headers()).thenReturn(Map.of("content-type", "application/x-www-form-urlencoded"));
        Mockito.when(response.body()).thenReturn("result=OK&code=0".getBytes(StandardCharsets.UTF_8));
        Mockito.when(response.url()).thenReturn("https://host/dbb/monitor-form");

        Route route = mockRoute(mockRequest("https://host/dbb/monitor-form", "POST", "a=1", 
                "application/x-www-form-urlencoded", response));
        handler.accept(route);

        awaitAssertionSettlement();
        assertTrue("表单字段命中时不得上报失败", runtime.drainSettledAssertionFailures().isEmpty());
    }

    @Test
    // @DisplayName: "MONITOR：响应始终未到达 + 超时窗口 → 定案为超时失败"
    public void monitorTimeoutWithoutResponseSettlesAsFailure() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/monitor-timeout")
                .monitor().expectStatus(200).timeoutMs(200L)
                .register();
        Consumer<Route> handler = handler();

        // existingResponse() 恒为 null（mock 默认值）→ 轮询窗口内拿不到响应
        Route route = mockRoute(mockRequest("https://host/dbb/monitor-timeout", "GET", null, null, null));
        handler.accept(route);

        sleep(400L);
        assertEquals("超过监控窗口仍未收到响应，必须定案为一条（超时）失败", 1, runtime.drainSettledAssertionFailures().size());
    }

    // ══════════════════════════════════════════════════════════════
    // F. 编排 + 泄漏 / 可观测性
    // ══════════════════════════════════════════════════════════════

    @Test
    // @DisplayName: "编排：同 pattern 多能力合并 —— MODIFY 改 body 与 MONITOR 观测同时生效"
    public void mergedCapabilitiesOnSamePatternBothApply() {
        newMockContext();
        RouteDsl.on(ctx)
                .api("/dbb/merged/**").modifyRequest().modifyRequestBody("$.user", "merged").done()
                .api("/dbb/merged/**").monitor().expectStatus(200).done()
                .start();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(200, "{\"code\":\"200\"}");
        Route route = mockRoute(mockRequest("https://host/dbb/merged/list", "POST",
                "{\"user\":\"u\"}", "application/json", response));
        handler.accept(route);

        Route.ResumeOptions options = captureResume(route);
        assertTrue("合并规则里的 MODIFY 必须生效，实际: " + options.postData, find(options.postData, "\"user\"\\s*:\\s*\"merged\""));

        awaitAssertionSettlement();
        assertTrue("同一合并规则里的 MONITOR 期望 200 且响应 200，不得上报失败", runtime.drainSettledAssertionFailures().isEmpty());
    }

    @Test
    // @DisplayName: "泄漏：dispatch 后无残留 claim、无未确证撤销、IO 无拒绝"
    public void dispatchLeavesNoResidualClaimsAndCountsHit() {
        newMockContext();
        RouteDsl.on(ctx).api("/dbb/leak-check").monitor().expectStatus(200).register();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(200, "{\"code\":\"200\"}");
        Route route = mockRoute(mockRequest("https://host/dbb/leak-check", "GET", null, null, response));
        handler.accept(route);

        assertEquals("本用例应恰好分发 1 次", 1L, runtime.metrics().dispatches());
        assertEquals("匹配成功的次数应为 1", 1L, runtime.metrics().hits());
        assertEquals("终结后不得残留 claim（悬挂额度 = 泄漏）", 0, runtime.claims().size());
        assertEquals("不得存在未确证的撤销", 0, runtime.unconfirmedRetirements());
        assertTrue("路由状态必须确证干净（可安全复用该 Context）", runtime.isClean());

        // 泄漏体检数值读出（每轮跑完可直接比对基线/差值）
        System.out.println("[LEAK-REPORT] activeRuntimes(baseline/now)=" + runtimeBaseline + "/"
                + RouteEngine.activeRuntimes()
                + ", dispatches=" + runtime.metrics().dispatches()
                + ", hits=" + runtime.metrics().hits()
                + ", claims=" + runtime.claims().size()
                + ", unconfirmedRetirements=" + runtime.unconfirmedRetirements()
                + ", isClean=" + runtime.isClean()
                + ", runtimeThreadsAlive=" + aliveRuntimeThreadNames());
    }

    @Test
    // @DisplayName: "泄漏：runtime 关闭后 IO / 撤销 / 巡检线程全部退出，注册表回落"
    public void shutdownReleasesRuntimeThreadsAndRegistryEntry() {
        BrowserContext local = newMockContext();
        RouteDsl.on(local)
                .api("/dbb/thread-leak")
                .monitor().expectStatus(200).expectJsonPath("$.code", "200")
                .register();
        Consumer<Route> handler = handler();

        // 先真正跑一次带 body 断言的 dispatch：确保 IO 池线程被创建（否则"关闭后无线程残留"等于没检）
        Response response = mockJsonResponse(200, "{\"code\":\"200\"}");
        Route route = mockRoute(mockRequest("https://host/dbb/thread-leak", "GET", null, null, response));
        handler.accept(route);
        sleep(200L);
        assertEquals("注册后应新增 1 个 runtime 条目", runtimeBaseline + 1, RouteEngine.activeRuntimes());

        RouteEngine.shutdown(local);
        assertEquals("关闭后 runtime 注册表必须回落基线", runtimeBaseline, RouteEngine.activeRuntimes());

        List<String> residual = awaitNoAliveRuntimeThreads(5_000L);
        System.out.println("[LEAK-REPORT] afterShutdown activeRuntimes(baseline/now)=" + runtimeBaseline + "/"
                + RouteEngine.activeRuntimes() + ", residualRuntimeThreads=" + residual);
        assertTrue("runtime 关闭后不得残留线程: " + residual, residual.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════

    private BrowserContext newMockContext() {
        ctx = Mockito.mock(BrowserContext.class);
        Mockito.when(ctx.route(Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(Mockito.mock(AutoCloseable.class));
        Mockito.doAnswer(invocation -> null).when(ctx).onClose(Mockito.any());
        apiCtx = Mockito.mock(APIRequestContext.class);
        Mockito.when(ctx.request()).thenReturn(apiCtx);
        Mockito.when(ctx.pages()).thenReturn(List.of());
        runtime = RouteEngine.runtimeOf(ctx);
        return ctx;
    }

    private Request mockRequest(String url, String method, String postData, String contentType,
                                Response existingResponse) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentType != null) {
            headers.put("content-type", contentType);
        }
        return mockRequestWithHeaders(url, method, postData, headers, existingResponse);
    }

    private Request mockRequestWithHeaders(String url, String method, String postData,
                                           Map<String, String> headers, Response existingResponse) {
        Request request = Mockito.mock(Request.class);
        Mockito.when(request.url()).thenReturn(url);
        Mockito.when(request.method()).thenReturn(method);
        Mockito.when(request.headers()).thenReturn(new LinkedHashMap<>(headers));
        Mockito.when(request.postData()).thenReturn(postData);
        Mockito.when(request.resourceType()).thenReturn("xhr");
        Mockito.when(request.isNavigationRequest()).thenReturn(false);
        Frame frame = Mockito.mock(Frame.class);
        Mockito.when(frame.parentFrame()).thenReturn(null);
        Mockito.when(request.frame()).thenReturn(frame);
        if (existingResponse != null) {
            Mockito.when(request.existingResponse()).thenReturn(existingResponse);
        }
        return request;
    }

    private static Route mockRoute(Request request) {
        Route route = Mockito.mock(Route.class);
        Mockito.when(route.request()).thenReturn(request);
        return route;
    }

    /** 取回最近一次注册在 context 上的驱动 handler。 */
    @SuppressWarnings("unchecked")
    private Consumer<Route> handler() {
        ArgumentCaptor<Consumer<Route>> captor = ArgumentCaptor.forClass((Class) Consumer.class);
        Mockito.verify(ctx, Mockito.atLeastOnce())
                .route(Mockito.anyString(), captor.capture(), Mockito.any());
        List<Consumer<Route>> all = captor.getAllValues();
        assertFalse("必须已注册 at least 1 条规则", all.isEmpty());
        return all.get(all.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static Route.FulfillOptions captureFulfill(Route route) {
        ArgumentCaptor<Route.FulfillOptions> captor =
                ArgumentCaptor.forClass((Class) Route.FulfillOptions.class);
        Mockito.verify(route, Mockito.timeout(5_000L)).fulfill(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Route.ResumeOptions captureResume(Route route) {
        ArgumentCaptor<Route.ResumeOptions> captor =
                ArgumentCaptor.forClass((Class) Route.ResumeOptions.class);
        Mockito.verify(route, Mockito.timeout(5_000L)).resume(captor.capture());
        return captor.getValue();
    }

    /**
     * 轮询等待 route 上出现任一"放行类"终结调用。
     *
     * <p>{@code resume} 有 {@code resume()} / {@code resume(options)} 两条重载，且 {@code delay(0)}
     * 因"无有效能力位"走 {@code fallback} —— 故按调用名匹配、不绑定具体重载。
     */
    private static void awaitTerminal(Route route, long timeoutMs, String... methodNames) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            boolean hit = Mockito.mockingDetails(route).getInvocations().stream()
                    .anyMatch(invocation -> {
                        for (String name : methodNames) {
                            if (name.equals(invocation.getMethod().getName())) {
                                return true;
                            }
                        }
                        return false;
                    });
            if (hit) {
                return;
            }
            sleep(20L);
        }
        fail("等待放行调用超时，期望之一: " + String.join("/", methodNames));
    }

    private static Response mockJsonResponse(int status, String body) {
        Response response = Mockito.mock(Response.class);
        Mockito.when(response.status()).thenReturn(status);
        Mockito.when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        Mockito.when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        Mockito.when(response.url()).thenReturn("https://host/mock-response");
        return response;
    }

    private static boolean hasHeader(Route.FulfillOptions options, String name, String value) {
        if (value.equals(options.contentType) && "content-type".equalsIgnoreCase(name)) {
            return true;
        }
        if (options.headers == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : options.headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && value.equals(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** 正则包含匹配。入参放宽为 {@code Object}：{@code ResumeOptions.postData} 声明为 Object（兼容 byte[]）。 */
    private static boolean find(Object text, String regex) {
        return text != null && Pattern.compile(regex).matcher(String.valueOf(text)).find();
    }

    private static String fixture(String classpathResource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(classpathResource)) {
            assertNotNull("测试夹具必须存在: " + classpathResource, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("等待被中断");
        }
    }

    /**
     * 等待调度线程完成响应观测与断言结算（2026-10-10 同步化语义）。
     *
     * <p><b>为什么需要等待</b>：{@code handler.accept(route)} 只在主线程完成 dispatch（claim 终结、
     * 请求入观测队列）；响应轮询走 {@code RouteDelayScheduler}（首轮 25ms、指数退避上限 100ms），
     * 轮询到 {@code existingResponse()} 后<b>就地同步</b>完成 body 断言并结算（不再投 IO 线程）。
     * 断言结果<b>不可直接感知"已完成"</b>（无完成信号暴露），故用固定等待窗口覆盖
     * 首轮轮询 + 结算时延；窗口大于最大轮询间隔，稳定无竞态。
     */
    private void awaitAssertionSettlement() {
        sleep(300L);
    }

    /**
     * 轮询等待 runtime 关闭后 IO / 撤销 / 巡检线程全部退出，返回仍在存活的线程名（空 = 无残留）。
     */
    private static List<String> awaitNoAliveRuntimeThreads(long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        List<String> residual = aliveRuntimeThreadNames();
        while (!residual.isEmpty() && System.nanoTime() < deadline) {
            sleep(50L);
            residual = aliveRuntimeThreadNames();
        }
        return residual;
    }

    private static List<String> aliveRuntimeThreadNames() {
        List<String> names = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.isAlive()) {
                continue;
            }
            String name = thread.getName();
            for (String prefix : RUNTIME_THREAD_PREFIXES) {
                if (name.startsWith(prefix)) {
                    names.add(name);
                    break;
                }
            }
        }
        return names;
    }
}
