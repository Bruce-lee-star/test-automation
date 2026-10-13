package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SSO 账号清单（设计文档 §5.6.1）：框架启动时从 {@code sso.accounts.<env>} 读取，
 * 构建内存中的 {@code env -> 用户列表} 映射。
 *
 * <p>不可变、线程安全（加载后即只读）。两种构造：
 * <ul>
 *   <li>{@link #loadFor(Set)}：从配置源按被 feature 实际引用的 env 加载（构建期校验主路径）；</li>
 *   <li>{@link #of(Map)}：从显式 map 构造（单测注入，无配置依赖）。</li>
 * </ul>
 * </p>
 *
 * <p>键语义：{@code env} 为登录环境前缀（O63/O38/O88），{@code user} 大小写敏感、与 feature 步骤文本逐字匹配。</p>
 */
public final class SsoAccountManifest {

    private final Map<String, List<String>> accounts; // env -> 有序去重用户列表

    private SsoAccountManifest(Map<String, List<String>> accounts) {
        this.accounts = accounts;
    }

    /**
     * 仅对被 feature 实际引用的 env 加载账号清单（避免枚举全部动态配置键）。
     * 某 env 未配置 {@code sso.accounts.<env>} 时，该 env 不进入清单 —— 后续校验对其仅 WARN 不 fail。
     */
    public static SsoAccountManifest loadFor(Set<String> referencedEnvs) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String env : referencedEnvs) {
            if (env == null || env.contains("<")) {
                continue; // 跳过占位 env
            }
            String raw = ConfigSource.resolve("sso.accounts." + env, "").trim();
            if (raw.isEmpty()) {
                continue;
            }
            Set<String> seen = new LinkedHashSet<>();
            List<String> users = new ArrayList<>();
            for (String u : raw.split(",")) {
                String tu = u.trim();
                if (!tu.isEmpty() && seen.add(tu)) {
                    users.add(tu);
                }
            }
            map.put(env, users);
        }
        return new SsoAccountManifest(map);
    }

    /** 从显式 map 构造（单测友好）。 */
    public static SsoAccountManifest of(Map<String, List<String>> accounts) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        accounts.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return new SsoAccountManifest(copy);
    }

    /** 该 env 是否在清单中声明（即配置了 {@code sso.accounts.<env>}）。 */
    public boolean isConfigured(String env) {
        return accounts.containsKey(env);
    }

    /** 清单是否为空（完全未配置任何 {@code sso.accounts.*}）。 */
    public boolean isEmpty() {
        return accounts.isEmpty();
    }

    /** 清单是否含该 env 下的指定 user。 */
    public boolean contains(String env, String user) {
        List<String> users = accounts.get(env);
        return users != null && users.contains(user);
    }

    /** 该 env 声明的用户列表（未配置则返回空列表）。 */
    public List<String> usersOf(String env) {
        return accounts.getOrDefault(env, List.of());
    }

    /** 已配置的 env 集合（插入序）。 */
    public Set<String> configuredEnvs() {
        return accounts.keySet();
    }
}
