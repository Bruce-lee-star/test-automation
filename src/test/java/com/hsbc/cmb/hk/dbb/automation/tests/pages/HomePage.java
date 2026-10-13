package com.hsbc.cmb.hk.dbb.automation.tests.pages;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.Element;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.element.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.AbstractManagedPage;

/**
 * 首页
 *
 * 链式调用示例：
 * homePage.quickLink.click();
 * homePage.loadingIndicator.waitForNotVisible(30);
 *
 * 如果需要获取选择器字符串：
 * homePage.quickLink.getSelector()
 */
public class HomePage extends AbstractManagedPage {

    @Element("h3[aria-label=\"Recent activity\"]")
    public PageElement quickLink;

    @Element("[id='topnav.profileswitcher.dropdown'],.current-profile")
    public PageElement profileSwitcher;
}
