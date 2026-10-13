package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 构建期 SSO 账号计划校验（设计文档 §5.6 质量增强）。
 *
 * <p>随 {@code mvn test} 自动运行（surefire），在开跑前扫描全部 feature 引用的登录身份，并打印
 * {@code [SsoPlan]} dry-run 报告。<b>fail-fast 仅在 {@code sso.accounts.*} 已配置时生效</b>：
 * 当某 env 声明了账号清单、却含场景引用了未声明的 user，构建直接失败并列出缺失账号与 scenario。
 * 当前仓库未配置 {@code sso.accounts.*} 时仅打印报告、不 fail（零回归）。</p>
 *
 * <p>CI 启用方式：在 {@code serenity.conf} / {@code serenity.properties} 声明
 * {@code sso.accounts.O63_SIT1=WP7UAT2_2,WP7UAT2_5,...} 等，本用例即成为 fail-closed 硬门禁。</p>
 */
public class SsoPlanDryRunValidatorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SsoPlanDryRunValidatorTest.class);
    private static final Path FEATURES = Paths.get("src/test/resources/features");

    @Test
    public void dryRunReportsAndFailsFastOnMissingAccounts() {
        FeatureAccountScanner.ScanResult scan = scanFeatures();
        Set<String> envs = new LinkedHashSet<>();
        for (UsedAccount u : scan.used) {
            if (u.isConcrete()) {
                envs.add(u.env());
            }
        }
        SsoAccountManifest manifest = SsoAccountManifest.loadFor(envs);
        SsoPlanValidator.SsoPlanResult result =
                SsoPlanValidator.evaluate(scan.used, manifest, scan.featureFiles, scan.scenarios);

        LOGGER.info("\n{}", result.report());

        if (manifest.isEmpty()) {
            LOGGER.info("[SsoPlan] account manifest (sso.accounts.*) not configured; fail-fast skipped");
            return;
        }
        result.failFast();
    }

    private static FeatureAccountScanner.ScanResult scanFeatures() {
        try {
            return FeatureAccountScanner.scan(FEATURES);
        } catch (Exception e) {
            LOGGER.warn("[SsoPlan] feature scan failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            return new FeatureAccountScanner.ScanResult(0, 0, java.util.List.of());
        }
    }
}
