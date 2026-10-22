@regression @route @route-testbed  @test
Feature: Route 能力 testbed 靶场 E2E（本地 127.0.0.1:8899，真浏览器）

  配套 docs/TEST-CASES.md（TC-* 矩阵）。模式与 route_capability_e2e（DBB SIT）一致：
  规则在 scenario 第一步武装（绑定 Context），随后登录 testbed —— 首页自动发起的 5 个
  DBB 式 API（notifications/streams SSE、profile/list、leftmenu、auth/assert、
  error/profile-error.jsp）+ 场景按钮即为真实流量来源。

  覆盖：MONITOR 正向/失败（2026-10-10 同步结算契约：失败在断言处就地结算、步骤 seam 立即可见）、
  MOCK 字段替换真实渲染、MODIFY 请求头到达服务端、DELAY 真延时、老 DSL 级联、SSE 采集不悬挂、
  路由状态无泄漏。每个 scenario 收尾做泄漏体检（未确证撤销 = 0、isClean）。

  # 运行：mvn clean verify -Dit.test=CucumberTestRunnerIT -Dtags="@route-testbed"
  # 前置：RouteTestBed 已启动（java -cp classes ...RouteTestBedServer 8899）

  @route-testbed-monitor
  Scenario: testbed - MONITOR 正向断言真实接口（status + jsonPath）
    Given I arm testbed route case "monitor-ok"
    And I logon to the route testbed
    Then testbed route case "monitor-ok" assertions pass

  @route-testbed-monitor-fail
  Scenario: testbed - MONITOR 断言失败同步结算立即可见（500 vs expect 200）
    Given I arm testbed route case "monitor-fail"
    And I logon to the route testbed
    And I trigger testbed failing endpoint and consume settled assertion
    Then testbed route case "monitor-fail" assertions pass

  @route-testbed-monitor-fail-post
  Scenario: testbed - MONITOR POST 断言失败采集请求体与请求头（报告含完整 API 信息）
    Given I arm testbed route case "monitor-fail-post"
    And I logon to the route testbed
    And I trigger testbed failing post endpoint and consume settled assertion
    Then testbed route case "monitor-fail-post" assertions pass

  @route-testbed-mock
  Scenario: testbed - MOCK 字段替换真实渲染（profile/list 两字段改 false）
    Given I arm testbed route case "mock-profile"
    And I logon to the route testbed
    Then testbed route case "mock-profile" assertions pass

  @route-testbed-modify
  Scenario: testbed - MODIFY 请求头到达服务端（/api/headers 回显 x-route-testbed）
    Given I arm testbed route case "modify-headers"
    And I logon to the route testbed
    And I trigger testbed endpoint "btn-headers"
    Then testbed route case "modify-headers" assertions pass

  @route-testbed-delay
  Scenario: testbed - DELAY 真延时（UI 实测 ≥2s）
    Given I arm testbed route case "delay-slow"
    And I logon to the route testbed
    And I trigger testbed endpoint "btn-slow"
    Then testbed route case "delay-slow" assertions pass

  @route-testbed-cascade
  Scenario: testbed - 老 DSL 级联全链路命中（TC-CASCADE-01）
    Given I arm testbed route case "cascade"
    And I logon to the route testbed
    And I trigger testbed endpoint "btn-302"
    Then testbed route case "cascade" assertions pass

  @route-testbed-capture-sse
  Scenario: testbed - SSE 流式采集不悬挂（notifications/streams 常开）
    Given I arm testbed route case "capture-sse"
    And I logon to the route testbed
    Then testbed route case "capture-sse" assertions pass

  @route-testbed-isolation
  Scenario: testbed - scenario 结束路由状态干净（无泄漏，可安全重建 context）
    Given I arm testbed route case "isolation"
    And I logon to the route testbed
    Then testbed route case "isolation" assertions pass

  # ── 2026-10-11 追加：route 不阻塞 UI（除 delay 外全部缺口）──
  # 主线验收：慢请求挂起 / route 高峰 / 超额度 / 超大 body / gzip / 非 JSON / popup /
  # fail-open / 页面异常 期间，UI 操作照常、收尾干净、无 35s 悬挂、无 driver 超时。

  @route-testbed-ui-slow-alive
  Scenario: testbed - UI 失败模拟+慢请求挂起期间 route 不阻塞 UI 且收尾收敛
    Given I arm testbed route case "ui-slow-alive"
    And I logon to the route testbed
    And I open testbed ui page "slow-element"
    And I click late element with 3 seconds timeout expecting failure
    And I click testbed button "btn-wait"
    Then testbed route case "ui-slow-alive" assertions pass

  @route-testbed-concurrent-ui
  Scenario: testbed - route 高峰（慢请求挂起+多规则并行）期间 UI 循环操作不阻塞
    Given I arm testbed route case "concurrent-ui"
    And I logon to the route testbed
    And I trigger testbed slow with 2000 ms
    And I click testbed button "btn-headers" 2 times
    And I click testbed button "btn-many" 2 times
    And I trigger testbed endpoint "btn-echo"
    Then testbed route case "concurrent-ui" assertions pass

  @route-testbed-pending-overflow
  Scenario: testbed - 20 并发慢请求超挂起额度 16 fail-open 放行不假死
    Given I arm testbed route case "pending-overflow"
    And I logon to the route testbed
    And I open testbed ui page "parallel"
    And I trigger testbed flood
    Then testbed route case "pending-overflow" assertions pass

  @route-testbed-huge-body
  Scenario: testbed - 3MB base64 超大 body 断言降级不误判 + UI 正常
    Given I arm testbed route case "huge-body"
    And I logon to the route testbed
    And I trigger testbed endpoint "btn-huge"
    Then testbed route case "huge-body" assertions pass

  @route-testbed-gzip-fidelity
  Scenario: testbed - gzip mock 字段替换保真渲染（content-encoding/length 重算）
    Given I arm testbed route case "gzip-fidelity"
    And I logon to the route testbed
    And I open testbed ui page "mock-target"
    Then testbed route case "gzip-fidelity" assertions pass

  @route-testbed-nonjson-mock
  Scenario: testbed - 非 JSON(xml) mock 后 UI 按原类型解析正常
    Given I arm testbed route case "non-json-mock"
    And I logon to the route testbed
    And I trigger testbed endpoint "btn-nonjson"
    Then testbed route case "non-json-mock" assertions pass

  @route-testbed-popup
  Scenario: testbed - popup 新 tab 立即关闭无残留（TC-POPUP-01）
    Given I arm testbed route case "popup"
    And I logon to the route testbed
    And I open testbed ui page "popup"
    And I trigger popup and verify closed
    Then testbed route case "popup" assertions pass

  @route-testbed-fail-open
  Scenario: testbed - 未注册请求走真实网络不被拦截（fail-open，TC-MATCH-06）
    Given I arm testbed route case "fail-open"
    And I logon to the route testbed
    And I trigger testbed endpoint "btn-302"
    Then testbed route case "fail-open" assertions pass

  @route-testbed-crash-cleanup
  Scenario: testbed - 页面致命异常后 UI 仍可操作 + 收尾干净（TC-FAIL-09 场景）
    Given I arm testbed route case "crash-cleanup"
    And I logon to the route testbed
    And I open testbed ui page "crash"
    And I click testbed button "btn-crash"
    Then testbed route case "crash-cleanup" assertions pass
