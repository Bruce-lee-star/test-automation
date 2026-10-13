package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

/**
 * 一个 feature scenario 引用的 SSO 登录身份。
 *
 * <p>身份键 = {@code env + "/" + user}（与设计文档 §3.1 一致：无 entity、无"厂"维度）。
 * {@link #isConcrete()} 用于排除 {@code Scenario Outline} 的 {@code <env>}/<user>} 占位参数——
 * 这类占位在 {@code Examples} 展开前不是真实账号，不应参与声明式校验。</p>
 */
public record UsedAccount(String featureFile, String scenarioName, String env, String user) {

    /** 是否为可校验的真实身份（排除 {@code <...>} 占位）。 */
    public boolean isConcrete() {
        return env != null && user != null
                && !env.contains("<") && !user.contains("<")
                && !env.contains(">") && !user.contains(">");
    }
}
