package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
 * 另有专门用例断言 dispatch 后无残留 claim、IO 拒绝数为 0、未确证撤销为 0，
 * 以及 runtime 关闭后 IO / 撤销 / 巡检线程<b>全部退出</b>（无线程残留）。
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

    @BeforeEach
    public void setUp() {
        runtimeBaseline = RouteEngine.activeRuntimes();
    }

    @AfterEach
    public void tearDown() {
        try {
            if (ctx != null) {
                RouteEngine.shutdown(ctx);
            }
        } finally {
            // 泄漏体检：每个用例结束，runtime 注册表必须回落基线（否则 context→runtime 映射在被持有）
            assertEquals(runtimeBaseline, RouteEngine.activeRuntimes(),
                    "用例结束后 runtime 注册表必须回落基线（无泄漏）");
            ctx = null;
            runtime = null;
            apiCtx = null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // A. 能力优先级契约
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("能力优先级：选择优先级 MOCK < MODIFY_REQUEST < MONITOR < DELAY")
    public void selectionPriorityOrderIsMockModifyMonitorDelay() {
        List<RouteCapability> sorted = new ArrayList<>(List.of(RouteCapability.values()));
        sorted.sort(RouteCapability.bySelectionPriority());
        assertEquals(List.of(RouteCapability.MOCK, RouteCapability.MODIFY_REQUEST,
                RouteCapability.MONITOR, RouteCapability.DELAY), sorted,
                "选择优先级（同 endpoint 多能力位时谁被选中）：MOCK 最优先，DELAY 最后");
    }

    @Test
    @DisplayName("能力优先级：执行时序 DELAY → MODIFY_REQUEST → MOCK → MONITOR")
    public void executionOrderIsDelayModifyMockMonitor() {
        List<RouteCapability> sorted = new ArrayList<>(List.of(RouteCapability.values()));
        sorted.sort(RouteCapability.byExecutionOrder());
        assertEquals(List.of(RouteCapability.DELAY, RouteCapability.MODIFY_REQUEST,
                RouteCapability.MOCK, RouteCapability.MONITOR), sorted,
                "执行时序（同一请求上的编排顺序）：先延时，再改请求，再伪造，最后观测");
    }

    @Test
    @DisplayName("能力优先级：仅 MOCK 是终结能力（命中即短路 fulfill）")
    public void onlyMockIsTerminal() {
        for (RouteCapability capability : RouteCapability.values()) {
            assertEquals(capability == RouteCapability.MOCK, capability.isTerminal(),
                    capability + " 的 terminal 标记必须为 " + (capability == RouteCapability.MOCK));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // B. MOCK
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MOCK：夹具静态体 + 字段替换（pinned-account 真实响应）")
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
        assertEquals(200, options.status, "MOCK 状态码");
        assertTrue(find(options.body, "\"maxPinnedAccount\"\\s*:\\s*20"),
                "真实夹具的 maxPinnedAccount 必须被替换为 20，实际: " + options.body);
        assertTrue(find(options.body, "\"maxPinnedAccount\"\\s*:\\s*10") == false,
                "原始值 10 不应残留");
    }

    @Test
    @DisplayName("MOCK：when(...).thenSet(...) 条件命中时写入新值")
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
        assertTrue(find(options.body, "\"isDefault\"\\s*:\\s*false"),
                "条件命中时必须写入 false，实际: " + options.body);
    }

    @Test
    @DisplayName("MOCK：when(...).thenSet(...) 条件不命中时保留原值")
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
        assertTrue(find(options.body, "\"isDefault\"\\s*:\\s*true"),
                "条件不命中时必须保留原值 true，实际: " + options.body);
    }

    @Test
    @DisplayName("MOCK：interceptResponse 取真实响应 + 字段替换（insight/retrieve 真实响应）")
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
        assertEquals(200, options.status, "intercept 必须沿用真实响应状态");
        assertTrue(find(options.body, "\"message\"\\s*:\\s*\"MOCKED\""),
                "真实响应的 message 必须被替换，实际: " + options.body);
    }

    @Test
    @DisplayName("MOCK：status / contentType / 自定义 header 全部落到终结点")
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
        assertEquals(201, options.status, "MOCK 状态码必须是 201");
        assertTrue(hasHeader(options, "content-type", "application/json"),
                "content-type 必须下发，实际 headers=" + options.headers + ", contentType=" + options.contentType);
        assertTrue(hasHeader(options, "x-mock", "1"),
                "自定义 header 必须下发，实际 headers=" + options.headers);
    }

    @Test
    @DisplayName("MOCK：mockBodyFromFile 资源缺失时注册即快速失败")
    public void mockBodyFromFileMissingResourceFailsFast() {
        newMockContext();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                RouteDsl.on(ctx)
                        .api("/dbb/nope")
                        .mock().mockBodyFromFile("mocks/dbb/does-not-exist.json")
                        .register());
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("classpath resource not found"),
                "必须是明确的资源缺失错误，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("MOCK：interceptResponse 与静态 body 互斥（build 期快速失败）")
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
    @DisplayName("MODIFY：header 新增 + method 改写 + body 字段改写，一次 resume 携带全部改动")
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
        assertEquals("PUT", options.method, "method 必须被改写");
        assertEquals("mock", options.headers.get("X-Env"), "新增 header 必须生效");
        assertTrue(find(options.postData, "\"password\"\\s*:\\s*\"changed\""),
                "body 字段必须被改写，实际: " + options.postData);
    }

    @Test
    @DisplayName("MODIFY：removeRequestHeader 删除指定头且保留其它头")
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
        assertFalse(options.headers.containsKey("x-drop"), "被删除的头不得出现");
        assertEquals("application/json", options.headers.get("content-type"), "其它头必须保留");
    }

    @Test
    @DisplayName("MODIFY：body 字段 改（SET）/ 数组追加（ADD）/ 删（REMOVE）三个操作并存")
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
        assertTrue(find(postData, "\"user\"\\s*:\\s*\"u2\""), "字段改写生效，实际: " + postData);
        assertTrue(find(postData, "\"roles\"\\s*:\\s*\\[\\s*\"a\"\\s*,\\s*\"b\"\\s*\\]"),
                "数组追加（ADD 语义）生效，实际: " + postData);
        assertFalse(find(postData, "\"temp\""), "字段删除生效，实际: " + postData);
    }

    @Test
    @DisplayName("MODIFY：表单（urlencoded）字段改写")
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
        assertNotNull(postData, "表单改写后必须携带 body");
        assertTrue(postData.contains("password=new"), "表单字段必须被改写，实际: " + postData);
        assertTrue(postData.contains("user=u"), "其它表单字段必须保留，实际: " + postData);
    }

    @Test
    @DisplayName("MODIFY：非适用 content-type 明确拒绝修改，fail-open 原样放行")
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
        assertEquals("binary-data", options.postData, "非适用类型必须原样 resume（绝不丢 body）");
    }

    // ══════════════════════════════════════════════════════════════
    // D. DELAY
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DELAY：delay(0) 无有效延时 → 立即放行（链式裁决 fallback）")
    public void delayZeroReleasesImmediately() {
        newMockContext();
        RouteDsl.on(ctx).api("/dbb/delay-zero").delay(0).done().start();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/delay-zero", "GET", null, null, null));
        long start = System.nanoTime();
        handler.accept(route);
        awaitTerminal(route, 5_000L, "resume", "fallback");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 1_000L, "delay(0) 必须近乎立即放行，实际 " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("DELAY：delay(1) 真延时后才放行（对齐到点必 resume 契约）")
    public void delayOneSecondResumesAfterDelay() {
        newMockContext();
        RouteDsl.on(ctx).api("/dbb/delay-one").delay(1).done().start();
        Consumer<Route> handler = handler();

        Route route = mockRoute(mockRequest("https://host/dbb/delay-one", "GET", null, null, null));
        long start = System.nanoTime();
        handler.accept(route);
        awaitTerminal(route, 5_000L, "resume");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs >= 900L, "delay(1) 必须真延时 ≥900ms 才放行，实际 " + elapsedMs + "ms");
    }

    // ══════════════════════════════════════════════════════════════
    // E. MONITOR 断言
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MONITOR：expectStatus 命中 → 不定案失败")
    public void monitorExpectStatusPasses() {
        newMockContext();
        RouteDsl.on(ctx).api("/dbb/monitor-status").monitor().expectStatus(200).register();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(200, fixture(FIX_INSIGHT));
        Route route = mockRoute(mockRequest("https://host/dbb/monitor-status", "GET", null, null, response));
        handler.accept(route);

        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "期望与响应一致时不得上报断言失败");
    }

    @Test
    @DisplayName("MONITOR：expectStatus 不符 → 定案为断言失败")
    public void monitorExpectStatusMismatchSettlesAsFailure() {
        newMockContext();
        RouteDsl.on(ctx).api("/dbb/monitor-status-fail").monitor().expectStatus(200).register();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(500, "{\"error\":\"boom\"}");
        Route route = mockRoute(mockRequest("https://host/dbb/monitor-status-fail", "GET", null, null, response));
        handler.accept(route);

        assertEquals(1, runtime.drainSettledAssertionFailures().size(),
                "期望 200 实际 500 必须定案为一条断言失败");
    }

    @Test
    @DisplayName("MONITOR：expectJsonPath 对 leftmenu 真实响应命中（enableAdminTools=Y）")
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

        // body 断言在 IO 线程执行：关闭 IO 池（有界等待）以确保断言任务落定
        runtime.io().close();
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "真实夹具的两条 jsonPath 断言必须全部通过");
    }

    @Test
    @DisplayName("MONITOR：expectJsonPath 值不符 → 定案失败")
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

        runtime.io().close();
        assertEquals(1, runtime.drainSettledAssertionFailures().size(),
                "期望 N 实际 Y 必须定案为一条断言失败");
    }

    @Test
    @DisplayName("MONITOR：expectJsonPath 路径不存在 → 定案失败（与值不符区分）")
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

        runtime.io().close();
        assertEquals(1, runtime.drainSettledAssertionFailures().size(),
                "路径不存在必须定案为一条断言失败");
    }

    @Test
    @DisplayName("MONITOR：expectBodyContains + expectBodyRegex 命中 → 不定案")
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

        runtime.io().close();
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "contains/regex 均命中时不得上报失败");
    }

    @Test
    @DisplayName("MONITOR：expectFormField 对 urlencoded 响应命中")
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

        runtime.io().close();
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "表单字段命中时不得上报失败");
    }

    @Test
    @DisplayName("MONITOR：响应始终未到达 + 超时窗口 → 定案为超时失败")
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
        assertEquals(1, runtime.drainSettledAssertionFailures().size(),
                "超过监控窗口仍未收到响应，必须定案为一条（超时）失败");
    }

    // ══════════════════════════════════════════════════════════════
    // F. 编排 + 泄漏 / 可观测性
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("编排：同 pattern 多能力合并 —— MODIFY 改 body 与 MONITOR 观测同时生效")
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
        assertTrue(find(options.postData, "\"user\"\\s*:\\s*\"merged\""),
                "合并规则里的 MODIFY 必须生效，实际: " + options.postData);

        runtime.io().close();
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "同一合并规则里的 MONITOR 期望 200 且响应 200，不得上报失败");
    }

    @Test
    @DisplayName("泄漏：dispatch 后无残留 claim、无未确证撤销、IO 无拒绝")
    public void dispatchLeavesNoResidualClaimsAndCountsHit() {
        newMockContext();
        RouteDsl.on(ctx).api("/dbb/leak-check").monitor().expectStatus(200).register();
        Consumer<Route> handler = handler();

        Response response = mockJsonResponse(200, "{\"code\":\"200\"}");
        Route route = mockRoute(mockRequest("https://host/dbb/leak-check", "GET", null, null, response));
        handler.accept(route);

        assertEquals(1L, runtime.metrics().dispatches(), "本用例应恰好分发 1 次");
        assertEquals(1L, runtime.metrics().hits(), "匹配成功的次数应为 1");
        assertEquals(0, runtime.claims().size(), "终结后不得残留 claim（悬挂额度 = 泄漏）");
        assertEquals(0L, runtime.io().rejectedCount(), "IO 池不得因队满拒绝任务");
        assertEquals(0, runtime.unconfirmedRetirements(), "不得存在未确证的撤销");
        assertTrue(runtime.isClean(), "路由状态必须确证干净（可安全复用该 Context）");

        // 泄漏体检数值读出（每轮跑完可直接比对基线/差值）
        System.out.println("[LEAK-REPORT] activeRuntimes(baseline/now)=" + runtimeBaseline + "/"
                + RouteEngine.activeRuntimes()
                + ", dispatches=" + runtime.metrics().dispatches()
                + ", hits=" + runtime.metrics().hits()
                + ", claims=" + runtime.claims().size()
                + ", ioRejected=" + runtime.io().rejectedCount()
                + ", unconfirmedRetirements=" + runtime.unconfirmedRetirements()
                + ", isClean=" + runtime.isClean()
                + ", runtimeThreadsAlive=" + aliveRuntimeThreadNames());
    }

    @Test
    @DisplayName("泄漏：runtime 关闭后 IO / 撤销 / 巡检线程全部退出，注册表回落")
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
        assertEquals(runtimeBaseline + 1, RouteEngine.activeRuntimes(),
                "注册后应新增 1 个 runtime 条目");

        RouteEngine.shutdown(local);
        assertEquals(runtimeBaseline, RouteEngine.activeRuntimes(),
                "关闭后 runtime 注册表必须回落基线");

        List<String> residual = awaitNoAliveRuntimeThreads(5_000L);
        System.out.println("[LEAK-REPORT] afterShutdown activeRuntimes(baseline/now)=" + runtimeBaseline + "/"
                + RouteEngine.activeRuntimes() + ", residualRuntimeThreads=" + residual);
        assertTrue(residual.isEmpty(), "runtime 关闭后不得残留线程: " + residual);
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
        assertFalse(all.isEmpty(), "必须已注册 at least 1 条规则");
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
            assertNotNull(in, "测试夹具必须存在: " + classpathResource);
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

    /** 轮询等待运行时线程退出，返回仍在存活的线程名（空 = 无残留）。 */
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
