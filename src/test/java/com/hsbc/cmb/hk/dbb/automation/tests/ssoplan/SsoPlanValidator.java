package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.fail;

/**
 * SSO 账号计划校验器（设计文档 §5.6.2 / §5.6.4）。
 *
 * <p>职责：
 * <ol>
 *   <li><b>fail-fast</b>：scenario 引用了 {@code (env,user)}，但 {@code sso.accounts.<env>} 已声明却不含该 user
 *        → 直接 fail，列出缺失账号与所属 scenario（构建期暴露配置缺口，而非跑到才报 401）；</li>
 *   <li><b>unused WARN</b>：{@code sso.accounts.<env>} 声明了但没有任何 scenario 引用的 user → WARN（提示清理），不 fail；</li>
 *   <li><b>cross-env 同名</b>：视为不同账号、独立 gate，不报错；</li>
 *   <li><b>dry-run 报告</b>：开跑前打印 {@code [SsoPlan]} 计划（文件/scenario 数、账号引用分布、fork 分配、缺失/未用告警）。</li>
 * </ol>
 * </p>
 *
 * <p>未声明 {@code sso.accounts.<env>} 的引用 env 仅 WARN（无法校验），不 fail；清单整体为空时由调用方跳过 fail-fast。</p>
 */
public final class SsoPlanValidator {

    private SsoPlanValidator() {
    }

    /**
     * 评估场景引用 vs 账号清单，产出 {@link SsoPlanResult}。
     *
     * @param used          所有场景抽取到的身份（可含占位，会被忽略）
     * @param manifest      从配置加载的账号清单（未配置的 env 不在其中）
     * @param featureFiles  扫描到的 feature 文件数（用于报告头）
     * @param scenarioCount 扫描到的 scenario 数（用于报告头）
     */
    public static SsoPlanResult evaluate(List<UsedAccount> used,
                                         SsoAccountManifest manifest,
                                         int featureFiles,
                                         int scenarioCount) {
        Map<String, Integer> refCount = new LinkedHashMap<>();
        for (UsedAccount u : used) {
            if (!u.isConcrete()) {
                continue;
            }
            refCount.merge(u.env() + "/" + u.user(), 1, Integer::sum);
        }

        Map<String, MissingAccount> missingMap = new LinkedHashMap<>();
        for (UsedAccount u : used) {
            if (!u.isConcrete()) {
                continue;
            }
            if (manifest.isConfigured(u.env()) && !manifest.contains(u.env(), u.user())) {
                String key = u.env() + "/" + u.user();
                MissingAccount m = missingMap.get(key);
                if (m == null) {
                    m = new MissingAccount(u.env(), u.user());
                    missingMap.put(key, m);
                }
                m.add(u.featureFile(), u.scenarioName());
            }
        }

        List<String> unused = new ArrayList<>();
        for (String env : manifest.configuredEnvs()) {
            for (String cu : manifest.usersOf(env)) {
                if (!refCount.containsKey(env + "/" + cu)) {
                    unused.add(env + "/" + cu);
                }
            }
        }

        return new SsoPlanResult(used, refCount,
                new ArrayList<>(missingMap.values()), unused, featureFiles, scenarioCount);
    }

    /** 缺失账号（场景引用但未在 {@code sso.accounts.<env>} 声明）。 */
    public static final class MissingAccount {
        private final String env;
        private final String user;
        private final Set<String> scenarios = new LinkedHashSet<>();
        private final Set<String> features = new LinkedHashSet<>();

        MissingAccount(String env, String user) {
            this.env = env;
            this.user = user;
        }

        void add(String feature, String scenario) {
            if (feature != null) {
                features.add(feature);
            }
            if (scenario != null) {
                scenarios.add(scenario);
            }
        }

        public String env() {
            return env;
        }

        public String user() {
            return user;
        }

        public Set<String> scenarios() {
            return scenarios;
        }

        public Set<String> features() {
            return features;
        }
    }

    /** 评估结果：含报告文本与 fail-fast 断言。 */
    public static final class SsoPlanResult {
        private final List<UsedAccount> used;
        private final Map<String, Integer> refCount;
        private final List<MissingAccount> missing;
        private final List<String> unused;
        private final int featureFiles;
        private final int scenarioCount;

        SsoPlanResult(List<UsedAccount> used,
                      Map<String, Integer> refCount,
                      List<MissingAccount> missing,
                      List<String> unused,
                      int featureFiles,
                      int scenarioCount) {
            this.used = used;
            this.refCount = refCount;
            this.missing = missing;
            this.unused = unused;
            this.featureFiles = featureFiles;
            this.scenarioCount = scenarioCount;
        }

        public List<MissingAccount> missing() {
            return missing;
        }

        public List<String> unused() {
            return unused;
        }

        /** 构建期 dry-run 报告（{@code [SsoPlan]} 前缀，与设计文档 §5.6.4 一致）。 */
        public String report() {
            StringBuilder sb = new StringBuilder();
            sb.append("[SsoPlan] Scanned ").append(featureFiles).append(" feature files, ")
                    .append(scenarioCount).append(" scenarios\n");

            Map<String, Integer> order = new LinkedHashMap<>();
            Map<String, Integer> envCursor = new LinkedHashMap<>();
            for (UsedAccount u : used) {
                if (!u.isConcrete()) {
                    continue;
                }
                order.putIfAbsent(u.env() + "/" + u.user(), 0);
            }

            sb.append("[SsoPlan] Accounts used: ").append(order.size()).append("\n");
            int stride = stride();
            for (String key : order.keySet()) {
                int slash = key.indexOf('/');
                String env = key.substring(0, slash);
                String user = key.substring(slash + 1);
                int ref = refCount.getOrDefault(key, 0);
                int idx = envCursor.merge(env, 1, Integer::sum) - 1; // 0-based
                int fork = idx / stride + 1;
                sb.append("[SsoPlan]   ").append(env).append('/').append(user)
                        .append(" (").append(ref).append(" scenarios) → fork ").append(fork).append('\n');
            }

            for (String u : unused) {
                sb.append("[SsoPlan] WARNING: configured account ").append(u).append(" is unused\n");
            }

            if (missing.isEmpty()) {
                sb.append("[SsoPlan] Ready.");
            } else {
                sb.append("[SsoPlan] FAIL: ").append(missing.size())
                        .append(" account(s) referenced but not declared in sso.accounts.*\n");
                for (MissingAccount m : missing) {
                    sb.append("[SsoPlan]   missing ").append(m.env()).append('/').append(m.user())
                            .append(" used by scenario(s): ").append(String.join(", ", m.scenarios()))
                            .append(" [feature: ").append(String.join(", ", m.features())).append("]\n");
                }
            }
            return sb.toString();
        }

        /** fail-fast：存在缺失账号时使构建失败（列出缺失账号与所属 scenario）。 */
        public void failFast() {
            if (missing.isEmpty()) {
                return;
            }
            StringBuilder msg = new StringBuilder("SsoPlan fail-fast: ")
                    .append(missing.size())
                    .append(" account(s) used by scenarios are NOT declared in sso.accounts.*")
                    .append(" (design §5.6.2, fail-closed):\n");
            for (MissingAccount m : missing) {
                msg.append("  - ").append(m.env()).append('/').append(m.user())
                        .append(" (scenario: ").append(String.join(", ", m.scenarios()))
                        .append(", feature: ").append(String.join(", ", m.features())).append(")\n");
            }
            fail(msg.toString());
        }

        private static int stride() {
            try {
                String raw = ConfigSource.resolve("sso.fork.user.stride", "4").trim();
                if (!raw.isEmpty()) {
                    return Integer.parseInt(raw);
                }
            } catch (RuntimeException ignored) {
                // 解析失败回退默认
            }
            return 4;
        }
    }
}
