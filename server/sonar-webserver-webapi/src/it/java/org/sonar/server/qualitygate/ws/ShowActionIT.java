/*
 * SonarQube
 * Copyright (C) 2009-2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.qualitygate.ws;

import java.util.Collection;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.Param;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.qualitygate.QualityGateConditionDto;
import org.sonar.db.qualitygate.QualityGateDto;
import org.sonar.db.user.UserDto;
import org.sonar.server.ai.code.assurance.AiCodeAssuranceEntitlement;
import org.sonar.server.component.TestComponentFinder;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.qualitygate.QualityGateCaycChecker;
import org.sonar.server.qualitygate.QualityGateFinder;
import org.sonar.server.qualitygate.QualityGateModeChecker;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.WsActionTester;
import org.sonarqube.ws.Qualitygates.ShowWsResponse;
import org.sonarqube.ws.Qualitygates.ShowWsResponse.Condition;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.tuple;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonar.db.permission.GlobalPermission.ADMINISTER_QUALITY_GATES;
import static org.sonar.db.permission.GlobalPermission.ADMINISTER_QUALITY_PROFILES;
import static org.sonar.server.qualitygate.QualityGateCaycStatus.COMPLIANT;
import static org.sonar.server.qualitygate.QualityGateCaycStatus.NON_COMPLIANT;
import static org.sonar.test.JsonAssert.assertJson;
import static org.sonarqube.ws.Qualitygates.Actions;

class ShowActionIT {

  @RegisterExtension
  UserSessionRule userSession = UserSessionRule.standalone();
  @RegisterExtension
  DbTester db = DbTester.create();
  private final QualityGateCaycChecker qualityGateCaycChecker = mock(QualityGateCaycChecker.class);
  private final QualityGateModeChecker qualityGateModeChecker = mock(QualityGateModeChecker.class);
  private final AiCodeAssuranceEntitlement aiCodeAssuranceEntitlement = mock(AiCodeAssuranceEntitlement.class);
  private final QualityGatesWsSupport wsSupport = new QualityGatesWsSupport(db.getDbClient(), userSession, TestComponentFinder.from(db));

  private final WsActionTester ws = new WsActionTester(
    new ShowAction(db.getDbClient(), new QualityGateFinder(db.getDbClient()), wsSupport, qualityGateCaycChecker, qualityGateModeChecker,
      new QualityGateActionsSupport(wsSupport, aiCodeAssuranceEntitlement)));

  @BeforeEach
  void setUp() {
    when(qualityGateCaycChecker.checkCaycCompliant(any(), any(String.class))).thenReturn(COMPLIANT);
    when(qualityGateModeChecker.getUsageOfModeMetrics(any()))
      .thenReturn(new QualityGateModeChecker.QualityModeResult(false, false));
  }

  @Test
  void show() {
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    db.qualityGates().setDefaultQualityGate(qualityGate);
    MetricDto metric1 = db.measures().insertMetric();
    MetricDto metric2 = db.measures().insertMetric();
    QualityGateConditionDto condition1 = db.qualityGates().addCondition(qualityGate, metric1, c -> c.setOperator("GT"));
    QualityGateConditionDto condition2 = db.qualityGates().addCondition(qualityGate, metric2, c -> c.setOperator("LT"));

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertThat(response.getName()).isEqualTo(qualityGate.getName());
    assertThat(response.getIsBuiltIn()).isFalse();
    assertThat(response.getConditionsList()).hasSize(2);
    assertThat(response.getConditionsList())
      .extracting(Condition::getId, Condition::getMetric, Condition::getOp, Condition::getError)
      .containsExactlyInAnyOrder(
        tuple(condition1.getUuid(), metric1.getKey(), "GT", condition1.getErrorThreshold()),
        tuple(condition2.getUuid(), metric2.getKey(), "LT", condition2.getErrorThreshold()));
  }

  @Test
  void show_built_in() {
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate(qg -> qg.setBuiltIn(true));
    db.qualityGates().setDefaultQualityGate(qualityGate);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertThat(response.getIsBuiltIn()).isTrue();
  }

  @Test
  void show_ai_code_supported() {
    when(aiCodeAssuranceEntitlement.isEnabled()).thenReturn(true);
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    db.qualityGates().setDefaultQualityGate(qualityGate);

    QualityGateDto qualityGateWithAiCodeSupported = db.qualityGates().insertQualityGate(qg -> qg.setAiCodeSupported(true));
    QualityGateDto qualityGateWithoutAiCodeSupported = db.qualityGates().insertQualityGate(qg -> qg.setAiCodeSupported(false));

    ShowWsResponse responseWithAiCodeAssurance = ws.newRequest()
      .setParam("name", qualityGateWithAiCodeSupported.getName())
      .executeProtobuf(ShowWsResponse.class);

    ShowWsResponse responseWithoutAiCodeAssurance = ws.newRequest()
      .setParam("name", qualityGateWithoutAiCodeSupported.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertThat(responseWithAiCodeAssurance.getIsAiCodeSupported()).isTrue();
    assertThat(responseWithoutAiCodeAssurance.getIsAiCodeSupported()).isFalse();
  }

  @Test
  void show_isCaycCompliant() {
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    when(qualityGateCaycChecker.checkCaycCompliant(any(DbSession.class), eq(qualityGate.getUuid()))).thenReturn(COMPLIANT);
    db.qualityGates().setDefaultQualityGate(qualityGate);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertEquals(COMPLIANT.toString(), response.getCaycStatus());
  }

  @Test
  void execute_shouldShowModeFlags() {
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    when(qualityGateModeChecker.getUsageOfModeMetrics(any(Collection.class)))
      .thenReturn(new QualityGateModeChecker.QualityModeResult(true, true));
    db.qualityGates().setDefaultQualityGate(qualityGate);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    Assertions.assertThat(response.getHasStandardConditions()).isTrue();
    Assertions.assertThat(response.getHasMQRConditions()).isTrue();
  }

  @Test
  void show_by_name() {
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    db.qualityGates().setDefaultQualityGate(qualityGate);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertThat(response.getName()).isEqualTo(qualityGate.getName());
  }

  @Test
  void no_condition() {
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    db.qualityGates().setDefaultQualityGate(qualityGate);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertThat(response.getName()).isEqualTo(qualityGate.getName());
    assertThat(response.getConditionsList()).isEmpty();
  }

  @Test
  void actions() {
    when(aiCodeAssuranceEntitlement.isEnabled()).thenReturn(true);
    userSession.logIn("john").addPermission(ADMINISTER_QUALITY_GATES);
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    QualityGateDto qualityGate2 = db.qualityGates().insertQualityGate();
    db.qualityGates().setDefaultQualityGate(qualityGate2);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    Actions actions = response.getActions();
    assertThat(actions.getRename()).isTrue();
    assertThat(actions.getManageConditions()).isTrue();
    assertThat(actions.getDelete()).isTrue();
    assertThat(actions.getCopy()).isTrue();
    assertThat(actions.getSetAsDefault()).isTrue();
    assertThat(actions.getAssociateProjects()).isTrue();
    assertThat(actions.getManageAiCodeAssurance()).isTrue();
  }

  @Test
  void getManageAiCodeAssurance_action_not_available_when_feature_disabled() {
    when(aiCodeAssuranceEntitlement.isEnabled()).thenReturn(false);
    userSession.logIn("john").addPermission(ADMINISTER_QUALITY_GATES);
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    QualityGateDto qualityGate2 = db.qualityGates().insertQualityGate();
    db.qualityGates().setDefaultQualityGate(qualityGate2);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertThat(response.getIsAiCodeSupported()).isFalse();

    Actions actions = response.getActions();
    assertThat(actions.getManageAiCodeAssurance()).isFalse();
  }

  @Test
  void actions_on_default() {
    when(aiCodeAssuranceEntitlement.isEnabled()).thenReturn(true);
    userSession.logIn("john").addPermission(ADMINISTER_QUALITY_GATES);
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    db.qualityGates().setDefaultQualityGate(qualityGate);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    Actions actions = response.getActions();
    assertThat(actions.getRename()).isTrue();
    assertThat(actions.getManageConditions()).isTrue();
    assertThat(actions.getDelete()).isFalse();
    assertThat(actions.getCopy()).isTrue();
    assertThat(actions.getSetAsDefault()).isFalse();
    assertThat(actions.getAssociateProjects()).isFalse();
    assertThat(actions.getManageAiCodeAssurance()).isTrue();
  }

  @Test
  void actions_on_built_in() {
    when(aiCodeAssuranceEntitlement.isEnabled()).thenReturn(true);
    userSession.logIn("john").addPermission(ADMINISTER_QUALITY_GATES);
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate(qg -> qg.setBuiltIn(true));
    QualityGateDto qualityGate2 = db.qualityGates().insertQualityGate(qg -> qg.setBuiltIn(false));
    db.qualityGates().setDefaultQualityGate(qualityGate2);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    Actions actions = response.getActions();
    assertThat(actions.getRename()).isFalse();
    assertThat(actions.getManageConditions()).isFalse();
    assertThat(actions.getDelete()).isFalse();
    assertThat(actions.getCopy()).isTrue();
    assertThat(actions.getSetAsDefault()).isTrue();
    assertThat(actions.getAssociateProjects()).isTrue();
    assertThat(actions.getManageAiCodeAssurance()).isFalse();
  }

  @Test
  void actions_when_not_quality_gate_administer() {
    when(aiCodeAssuranceEntitlement.isEnabled()).thenReturn(true);
    userSession.logIn("john").addPermission(ADMINISTER_QUALITY_PROFILES);
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate(qg -> qg.setBuiltIn(true));
    db.qualityGates().setDefaultQualityGate(qualityGate);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    Actions actions = response.getActions();
    assertThat(actions.getRename()).isFalse();
    assertThat(actions.getManageConditions()).isFalse();
    assertThat(actions.getDelete()).isFalse();
    assertThat(actions.getCopy()).isFalse();
    assertThat(actions.getSetAsDefault()).isFalse();
    assertThat(actions.getAssociateProjects()).isFalse();
    assertThat(actions.getManageAiCodeAssurance()).isFalse();
  }

  @Test
  void actions_when_delegate_quality_gate_administer() {
    when(aiCodeAssuranceEntitlement.isEnabled()).thenReturn(true);
    QualityGateDto defaultQualityGate = db.qualityGates().insertQualityGate(qg -> qg.setName("Sonar way"));
    QualityGateDto otherQualityGate = db.qualityGates().insertQualityGate(qg -> qg.setName("Sonar way - Without Coverage"));
    UserDto user = db.users().insertUser();
    db.qualityGates().addUserPermission(defaultQualityGate, user);
    db.qualityGates().addUserPermission(otherQualityGate, user);
    userSession.logIn(user);
    db.qualityGates().setDefaultQualityGate(defaultQualityGate);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", defaultQualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertThat(response.getActions())
      .extracting(
        Actions::getRename, Actions::getDelete, Actions::getManageConditions,
        Actions::getCopy, Actions::getSetAsDefault, Actions::getAssociateProjects, Actions::getDelegate, Actions::getManageAiCodeAssurance)
      .containsExactlyInAnyOrder(
        false, false, true, false, false, false, true, false);

    response = ws.newRequest()
      .setParam("name", otherQualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertThat(response.getActions())
      .extracting(
        Actions::getRename, Actions::getDelete, Actions::getManageConditions,
        Actions::getCopy, Actions::getSetAsDefault, Actions::getAssociateProjects, Actions::getDelegate, Actions::getManageAiCodeAssurance)
      .containsExactlyInAnyOrder(
        false, false, true, false, false, false, true, false);

  }

  @Test
  void reponse_should_show_isDefault() {
    QualityGateDto defaultQualityGate = db.qualityGates().insertQualityGate();
    db.qualityGates().setDefaultQualityGate(defaultQualityGate);

    ShowWsResponse response = ws.newRequest()
      .setParam("name", defaultQualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertTrue(response.getIsDefault());

    QualityGateDto nonDefaultQualityGate = db.qualityGates().insertQualityGate();

    response = ws.newRequest()
      .setParam("name", nonDefaultQualityGate.getName())
      .executeProtobuf(ShowWsResponse.class);

    assertFalse(response.getIsDefault());
  }

  @Test
  void fail_when_no_name() {
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();

    assertThatThrownBy(() -> ws.newRequest().execute())
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("The 'name' parameter is missing");
  }

  @Test
  void fail_when_condition_is_on_disabled_metric() {
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    db.qualityGates().setDefaultQualityGate(qualityGate);
    MetricDto metric = db.measures().insertMetric();
    db.qualityGates().addCondition(qualityGate, metric);
    db.getDbClient().metricDao().disableByKey(db.getSession(), metric.getKey());
    db.commit();

    assertThatThrownBy(() -> ws.newRequest()
      .setParam("name", qualityGate.getName())
      .execute())
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining(format("Could not find metric with id %s", metric.getUuid()));
  }

  @Test
  void fail_when_quality_name_does_not_exist() {
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();

    assertThatThrownBy(() -> ws.newRequest()
      .setParam("name", "UNKNOWN")
      .execute())
      .isInstanceOf(NotFoundException.class)
      .hasMessageContaining("No quality gate has been found for name UNKNOWN");
  }

  @Test
  void json_example() {
    when(aiCodeAssuranceEntitlement.isEnabled()).thenReturn(true);
    userSession.logIn("admin").addPermission(ADMINISTER_QUALITY_GATES);
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate(qg -> qg.setName("My Quality Gate"));
    QualityGateDto qualityGate2 = db.qualityGates().insertQualityGate(qg -> qg.setName("My Quality Gate 2"));
    db.qualityGates().setDefaultQualityGate(qualityGate2);
    MetricDto blockerViolationsMetric = db.measures().insertMetric(m -> m.setKey("blocker_violations"));
    MetricDto criticalViolationsMetric = db.measures().insertMetric(m -> m.setKey("tests"));
    db.qualityGates().addCondition(qualityGate, blockerViolationsMetric, c -> c.setOperator("GT").setErrorThreshold("0"));
    db.qualityGates().addCondition(qualityGate, criticalViolationsMetric, c -> c.setOperator("LT").setErrorThreshold("10"));
    when(qualityGateCaycChecker.checkCaycCompliant(any(), any(String.class))).thenReturn(NON_COMPLIANT);

    String response = ws.newRequest()
      .setParam("name", qualityGate.getName())
      .execute()
      .getInput();

    assertJson(response).ignoreFields("id")
      .isSimilarTo(getClass().getResource("show-example.json"));
  }

  @Test
  void verify_definition() {
    WebService.Action action = ws.getDef();
    assertThat(action.since()).isEqualTo("4.3");
    assertThat(action.params())
      .extracting(Param::key, Param::isRequired)
      .containsExactlyInAnyOrder(
        tuple("name", true));
  }

}
