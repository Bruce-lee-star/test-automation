package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.tests.steps.HomeSteps;
import io.cucumber.java.en.When;
import com.hsbc.cmb.hk.dbb.automation.framework.report.annotation.Steps;

public class HomeGlue {

    @Steps
    private HomeSteps homeSteps;

    @When("switch profile to {string} and close reminder")
    public void switchProfileToAndCloseReminder(String profile) {
        homeSteps.switchProfileToAndCloseReminder(profile);
    }
}
