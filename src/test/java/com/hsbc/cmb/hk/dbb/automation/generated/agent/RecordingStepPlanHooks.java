package com.hsbc.cmb.hk.dbb.automation.generated.agent;

import com.hsbc.cmb.hk.dbb.automation.framework.agent.execution.StepPlanActionSink;
import com.hsbc.cmb.hk.dbb.automation.framework.agent.execution.StepPlanActionSinks;
import io.cucumber.java.After;
import io.cucumber.java.Before;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 路径 C 生成物的运行时装配与验证钩子（不启动浏览器）。
 *
 * <p>生成的 Steps 在<b>构造时</b>就调用 {@code StepPlanActionSinks.requireCurrent()}，
 * 因此必须在 glue 实例化前完成绑定。静态块覆盖「glue 先于 @Before 实例化」的时序，
 * {@code @Before} 再按场景线程重新绑定一次，{@code @After} 校验动作序列并清理 ThreadLocal，
 * 避免污染同 JVM 内的其它用例。</p>
 */
public final class RecordingStepPlanHooks {

    private static final List<String> ACTIONS = new CopyOnWriteArrayList<>();

    private static final StepPlanActionSink SINK = new StepPlanActionSink() {
        @Override public void navigateTo(String url) { ACTIONS.add("navigateTo:" + url); }
        @Override public void clickOn(String target) { ACTIONS.add("clickOn:" + target); }
        @Override public void fillWith(String target, String value) {
            ACTIONS.add("fillWith:" + target + "=" + value);
        }
        @Override public void selectIn(String target, String option) { ACTIONS.add("selectIn:" + target); }
        @Override public void check(String target) { ACTIONS.add("check:" + target); }
        @Override public void uncheck(String target) { ACTIONS.add("uncheck:" + target); }
        @Override public void waitFor(String target) { ACTIONS.add("waitFor:" + target); }
        @Override public void expectVisible(String target) { ACTIONS.add("expectVisible:" + target); }
        @Override public void expectText(String target) { ACTIONS.add("expectText:" + target); }
        @Override public void switchToPopup() { ACTIONS.add("switchToPopup"); }
    };

    static {
        StepPlanActionSinks.setCurrent(SINK);
    }

    /** Cucumber 的 ObjectFactory（含 PwReportObjectFactory）要求 public 无参构造器。 */
    public RecordingStepPlanHooks() {
    }

    @Before(order = Integer.MIN_VALUE)
    public void bindSink() {
        ACTIONS.clear();
        StepPlanActionSinks.setCurrent(SINK);
    }

    @After(order = Integer.MAX_VALUE)
    public void verifyAndUnbind() {
        try {
            List<String> expected = List.of(
                    "navigateTo:https://www.example.com/logon",
                    "fillWith:role=textbox;name=User=${user}",
                    "fillWith:role=textbox;name=Password=${password}",
                    "clickOn:role=button;name=Submit",
                    "expectVisible:role=heading;name=Home");
            if (!Objects.equals(expected, List.copyOf(ACTIONS))) {
                throw new AssertionError("生成 case 的动作序列不符，实际=" + ACTIONS + "，期望=" + expected);
            }
        } finally {
            StepPlanActionSinks.clear();
        }
    }
}
