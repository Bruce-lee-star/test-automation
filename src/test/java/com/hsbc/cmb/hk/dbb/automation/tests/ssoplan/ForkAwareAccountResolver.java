package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨 Surefire fork 的账号静态分区解析器（设计文档 §5.1~§5.5）。
 *
 * <p><b>职责</b>：在「多 JVM fork 并行」下，按固定步长 {@code sso.fork.user.stride} 把每个 env 的账号池
 * 静态切成连续片段，每个 fork（由 {@code surefire.forkNumber} 或显式 {@code -Dfork.index} 识别）独占其中一段。
 * 与 §3 {@code ConcurrencyGate}（JVM 之内同 user 串行）形成 §5.4 两层防线：</p>
 * <ul>
 *   <li><b>stride 分区（本类）</b>：JVM <b>之间</b>不撞 user（框架自动识别 forkNumber）；</li>
 *   <li><b>ConcurrencyGate</b>：JVM <b>之内</b>同 user 串行。</li>
 * </ul>
 * <p>两者不可互相替代。</p>
 *
 * <p>本实现为「<b>静态 stride</b>」（§5.5）：不引入跨进程账号池 / 文件锁 —— scenario 钉死具体 user，
 * 账号权限/角色/租户各异、不可互换，故可预测、零跨进程协调、无 stale lock 恢复问题，故障面最小。</p>
 *
 * <p><b>fail-fast</b>：{@link #assertAccountBelongsToCurrentFork(String, String)} 在登录解析入口
 * （{@code BDDUtils.getLogonDBBInfo}）调用；若 {@code sso.accounts.<env>} 已配置但 user 不属于当前 fork 切片，
 * 立即抛 {@link IllegalStateException} 并指明该 user 归属的 fork（fix：在正确 fork 运行，或重平衡 stride/账号池）。
 * 未配置 {@code sso.accounts.<env>} 时 no-op（无法分区，由 §5.6 dry-run 报告 WARN），零回归。</p>
 *
 * <p>线程安全：全部为无状态静态方法，配置读取经 {@link ConfigSource} 实时解析（set 即时生效）。</p>
 */
public final class ForkAwareAccountResolver {

    public static final String FORK_INDEX_OVERRIDE = "fork.index";
    public static final String SUREFIRE_FORK_NUMBER = "surefire.forkNumber";
    public static final String STRIDE_KEY = "sso.fork.user.stride";
    public static final int DEFAULT_STRIDE = 4;

    private static final Logger LOG = LoggerFactory.getLogger(ForkAwareAccountResolver.class);

    /** §5.7 过分区一次性告警去重（key = env#forkIndex，每 JVM 内只告警一次）。 */
    private static final Set<String> WARNED_OVER_PARTITION = ConcurrentHashMap.newKeySet();

    private ForkAwareAccountResolver() {
    }

    /**
     * 当前 fork 的 0-based 索引。
     * <ol>
     *   <li>{@code -Dfork.index} 显式覆盖优先；</li>
     *   <li>否则取 Surefire 注入的 {@code surefire.forkNumber}（1-based → 转 0-based）；</li>
     *   <li>均缺省（IDE 单跑 / {@code -Pparallel} 单 JVM 进程内并行）→ 0。</li>
     * </ol>
     * 负值 / 非数字一律按 0 处理（防御性，避免索引越界）。
     */
    public static int currentForkIndex() {
        String override = ConfigSource.resolve(FORK_INDEX_OVERRIDE, "").trim();
        if (!override.isEmpty()) {
            return nonNegative(parseIntOrZero(override));
        }
        String raw = ConfigSource.resolve(SUREFIRE_FORK_NUMBER, "").trim();
        if (!raw.isEmpty()) {
            return nonNegative(parseIntOrZero(raw) - 1);
        }
        return 0;
    }

    /** 步长：每个 fork 分得的账号数；缺省 / 非正 / 非数字回退 {@link #DEFAULT_STRIDE}。 */
    public static int stride() {
        String raw = ConfigSource.resolve(STRIDE_KEY, "").trim();
        if (raw.isEmpty()) {
            return DEFAULT_STRIDE;
        }
        int s = parseIntOrZero(raw);
        return s < 1 ? DEFAULT_STRIDE : s;
    }

    /** 当前 fork 在给定 env 下分得的账号切片（连续 {@link #stride()} 段，大小写敏感、已去重）。 */
    public static List<String> myUsers(String env) {
        return myUsers(load(env), env);
    }

    /**
     * §5.7 有效账号池：在 {@code sso.accounts.&lt;env&gt;} 全量之上，再经 {@link RunScopeAccountProvider}
     * 裁剪出本次运行活跃子集。provider 返回空 / null = 不裁剪（全量）。
     * 保持全量中的相对顺序，仅剔除非活跃账号。
     */
    private static List<String> activePool(SsoAccountManifest manifest, String env) {
        List<String> all = manifest.usersOf(env);
        if (all.isEmpty()) {
            return all;
        }
        RunScopeAccountProvider provider = RunScopeAccountProviders.resolve();
        List<String> active = provider.activeUsers(env);
        if (active == null || active.isEmpty()) {
            return all; // 不裁剪
        }
        Set<String> activeSet = new LinkedHashSet<>(active);
        List<String> trimmed = new ArrayList<>(Math.min(all.size(), active.size()));
        for (String u : all) {
            if (activeSet.contains(u)) {
                trimmed.add(u);
            }
        }
        return List.copyOf(trimmed);
    }

    /**
     * 该 (env,user) 是否属于当前 fork 切片。
     * 未配置 {@code sso.accounts.<env>} 时返回 {@code true}（无法分区，不拦截）。
     */
    public static boolean isMine(String env, String user) {
        if (user == null) {
            return false;
        }
        SsoAccountManifest manifest = load(env);
        if (!manifest.isConfigured(env)) {
            return true;
        }
        return myUsers(manifest, env).contains(user);
    }

    /**
     * fail-fast：若 {@code sso.accounts.<env>} 已配置但 user 不属于当前 fork 切片 → 抛 {@link IllegalStateException}。
     * 未配置 env 时 no-op（无法分区，零回归）。
     *
     * @param env     登录环境前缀，如 {@code O63_SIT1}
     * @param user    用户名，如 {@code WP7UAT2_2}
     */
    public static void assertAccountBelongsToCurrentFork(String env, String user) {
        if (env == null || user == null) {
            return;
        }
        SsoAccountManifest manifest = load(env);
        if (!manifest.isConfigured(env)) {
            return;
        }
        if (myUsers(manifest, env).contains(user)) {
            return;
        }
        int idx = currentForkIndex();
        int owner = ownerForkOf(manifest, env, user);
        throw new IllegalStateException(String.format(
                "Fork partition violation (design §5.4): account %s/%s is assigned to fork %d, "
                        + "but this JVM is fork %d (fork.index=%d). Cross-fork reuse of the same SSO account "
                        + "would cause mutual-kick (random 401 / redirect to logon). "
                        + "Fix: run this scenario in fork %d (pass -Dfork.index=%d), or rebalance "
                        + "sso.fork.user.stride / sso.accounts.%s so this account lands in fork %d.",
                env, user, owner, idx, idx, owner, owner, env, idx));
    }

    private static SsoAccountManifest load(String env) {
        return SsoAccountManifest.loadFor(Set.of(env));
    }

    private static List<String> myUsers(SsoAccountManifest manifest, String env) {
        List<String> all = activePool(manifest, env);
        if (all.isEmpty()) {
            return List.of();
        }
        int stride = stride();
        if (stride < 1) {
            return List.of();
        }
        int idx = currentForkIndex();
        int start = idx * stride;
        if (start >= all.size()) {
            warnOverPartition(env, idx, all.size());
            return List.of();
        }
        int end = Math.min(all.size(), start + stride);
        return List.copyOf(all.subList(start, end));
    }

    /**
     * §5.7 过分区告警：fork 索引超出有效账号池 → 该 fork 无账号可用，并发上限应降（多余 fork 排队 / 跳过）。
     * 每 (env, fork) 仅告警一次，避免日志刷屏。仅信息性 —— fail-fast 仍是真实跨 fork 防线的兜底。
     */
    private static void warnOverPartition(String env, int idx, int poolSize) {
        if (poolSize <= 0) {
            return;
        }
        String key = env + "#" + idx;
        if (WARNED_OVER_PARTITION.add(key)) {
            LOG.warn("SSO fork over-partition (design §5.7): fork {} has no accounts in env {} "
                    + "(active pool size={}, stride={}). Effective concurrency auto-reduced: extra forks without "
                    + "accounts either queue or are skipped (scenarios still fail-fast if they attempt a cross-fork "
                    + "account). Fix: lower forkCount or raise sso.fork.user.stride so accounts cover all forks, "
                    + "or trim active accounts via RunScopeAccountProvider.", idx, env, poolSize, stride());
        }
    }

    private static int ownerForkOf(SsoAccountManifest manifest, String env, String user) {
        List<String> all = activePool(manifest, env);
        int i = all.indexOf(user);
        if (i < 0) {
            return -1;
        }
        return i / Math.max(1, stride());
    }

    private static int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int nonNegative(int n) {
        return n < 0 ? 0 : n;
    }
}
