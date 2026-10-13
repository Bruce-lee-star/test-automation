package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import java.util.List;

/**
 * §5.7 默认实现：返回空（= 全量，不裁剪）。
 *
 * <p>当未注入任何 {@link RunScopeAccountProvider}（配置项 / ServiceLoader 均无）时生效，
 * 行为与 §5.1~§5.5 落地前完全一致 —— 零回归。</p>
 */
public final class DefaultRunScopeProvider implements RunScopeAccountProvider {

    /** 单例（无状态）。 */
    public static final DefaultRunScopeProvider INSTANCE = new DefaultRunScopeProvider();

    private DefaultRunScopeProvider() {
    }

    @Override
    public List<String> activeUsers(String env) {
        return List.of(); // 空 = 全量，不裁剪
    }
}
