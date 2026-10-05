@regression @route @route-e2e
Feature: Route V2 能力真实 E2E（DBB SIT，真浏览器）

  规则在 scenario 的**第一步**武装（早于 `logon DBB`），因此覆盖的是"登录期应用自己触发的"真实接口流量：
  auth/assert、j_spring_security-check_v2、leftmenu/permissionLeftMenuConfig、profile/list、
  notifications/streams、insight/retrieve、pinned-account —— 而不是用脚本去"重打"接口。

  本轮覆盖矩阵：S1 采集（capture/captureBody/captureBodyLimitBytes/record）
  · S2 MONITOR 正向断言（expectStatus/expectJsonPath/expectBodyContains/monitorTimeoutMs）
  · S4 MOCK 静态体（mockStatus/mockBody/mockContentType/mockHeaders/mockReplacements）。
  每个 scenario 收尾都做路由状态泄漏体检（未确证撤销 = 0、isClean）。

  # 运行：
  #   mvn verify -Dtest=CucumberRouteE2ERunnerIT -Dtags="@route-e2e"

  Scenario: route v2 e2e - 登录期真实接口采集（CAPTURE）
    Given I arm route v2 e2e case "login-capture"
    And logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then route v2 e2e case "login-capture" assertions pass


  Scenario: route v2 e2e - 登录期 MONITOR 断言真实接口
    Given I arm route v2 e2e case "login-monitor"
    And logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then route v2 e2e case "login-monitor" assertions pass


  Scenario: route v2 e2e - 登录期 MOCK 静态体（真实夹具 + 字段替换 + 自定义头）
    Given I arm route v2 e2e case "login-mock"
    And logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then route v2 e2e case "login-mock" assertions pass


  Scenario: route v2 e2e - 登录期 MOCK 条件替换 when/then（命中 + 不命中两分支）
    Given I arm route v2 e2e case "login-mock-when"
    And logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then route v2 e2e case "login-mock-when" assertions pass


  Scenario: route v2 e2e - 登录期 MODIFY 改写请求（自定义请求头到达服务端）
    Given I arm route v2 e2e case "login-modify"
    And logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then route v2 e2e case "login-modify" assertions pass


  Scenario: route v2 e2e - 登录期 DELAY 延时（链路完整 + 规则命中）
    Given I arm route v2 e2e case "login-delay"
    And logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then route v2 e2e case "login-delay" assertions pass


  Scenario: route v2 e2e - 登录期能力并存与执行顺序（MODIFY + MOCK + MONITOR 同 pattern）
    Given I arm route v2 e2e case "login-priority"
    And logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
    Then route v2 e2e case "login-priority" assertions pass
