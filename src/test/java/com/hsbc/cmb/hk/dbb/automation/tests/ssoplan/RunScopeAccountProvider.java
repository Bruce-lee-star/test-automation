package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import java.util.List;

/**
 * §5.7 运行期账号池裁剪 SPI：框架不读业务参数（如 {@code -Dsso.run.factory}），
 * 而是让业务项目自行决定「本次运行涉及哪些账号」。
 *
 * <p>契约：{@link #activeUsers(String)} 返回该 env 的<b>活跃账号子集</b>；
 * 返回 {@code null} 或空列表 = 不裁剪（全量，由 {@code sso.accounts.&lt;env&gt;} 决定）。</p>
 *
 * <p>注入方式（见 {@link RunScopeAccountProviders}）：
 * ① 配置项 {@code sso.run.scope.provider=&lt;FQCN&gt;}（实现类须有无参构造）；
 * ② 或 {@code META-INF/services/...RunScopeAccountProvider} 的 ServiceLoader；
 * ③ 均未提供 → {@link DefaultRunScopeProvider}（全量）。</p>
 *
 * <p>典型业务实现：只跑 O63 时返回 O63 的 user 列表；O38/O88 返回空（不参与分区）。
 * 与 §5.1~§5.5 静态 stride 分区结合：fork 只在「活跃子集」里切，O38/O88 账号不占 fork、不触发跨 fork fail-fast。</p>
 *
 * @apiNote 业务侧实现；框架 / test-automation 仅消费，不依赖任何具体实现。
 */
public interface RunScopeAccountProvider {

    /**
     * 本次运行、该 env 下参与的账号。
     *
     * @param env 登录环境前缀（如 {@code O63_SIT1}）
     * @return 活跃账号子集（可为空 / null = 全量，不裁剪）；元素大小写敏感、与 feature 步骤文本逐字匹配
     */
    List<String> activeUsers(String env);
}
