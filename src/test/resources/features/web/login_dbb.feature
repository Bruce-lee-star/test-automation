@regression @web @login @test
Feature: testing logon - 1

  Scenario: testing logon to dbb-1
    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"


  Scenario: testing logon to dbb-2
    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"

  Scenario: testing logon to dbb-3
    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"


#  @route-edge
#  Scenario: route edge - capture API info (request+response payload)
#    Given I register route edge case "capture"
#    When I refresh the page to trigger routed APIs
#    Then route edge case "capture" assertions pass
#
#  @route-edge
#  Scenario: route edge - capture non-JSON (HTML) responses
#    Given I register route edge case "nonjson-capture"
#    When I refresh the page to trigger routed APIs
#    Then route edge case "nonjson-capture" assertions pass
#
#  @route-edge
#  Scenario: route edge - mock a non-JSON (HTML) endpoint fails open
#    Given I register route edge case "nonjson-mock"
#    When I refresh the page to trigger routed APIs
#    Then route edge case "nonjson-mock" assertions pass
#
#  @route-edge
#  Scenario: route edge - monitor settle on real 200 endpoint
#    Given I register route edge case "monitor"
#    When I refresh the page to trigger routed APIs
#    Then route edge case "monitor" assertions pass
#
#  @route-edge
#  Scenario: route edge - legacy cascade syntax (monitor x4 + mock x1)
#    Given I register route edge case "cascade"
#    When I refresh the page to trigger routed APIs
#    Then route edge case "cascade" assertions pass
#
#  @route-edge
#  Scenario: route edge - unmatched pattern falls through
#    Given I register route edge case "unmatched"
#    When I refresh the page to trigger routed APIs
#    Then route edge case "unmatched" assertions pass

#  Scenario: testing logon to dbb-4 (edge browser override)
#    Given logon DBB "O63_SIT1" environment as user "WP7UAT2_2"
