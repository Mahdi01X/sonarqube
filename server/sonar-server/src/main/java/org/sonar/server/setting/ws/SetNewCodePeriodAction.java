/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
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
package org.sonar.server.setting.ws;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.BranchDto;
import org.sonar.db.component.BranchType;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.newcodeperiod.NewCodePeriodDao;
import org.sonar.db.newcodeperiod.NewCodePeriodDto;
import org.sonar.db.newcodeperiod.NewCodePeriodParser;
import org.sonar.db.newcodeperiod.NewCodePeriodType;
import org.sonar.server.component.ComponentFinder;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.user.UserSession;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static org.sonar.db.newcodeperiod.NewCodePeriodType.DATE;
import static org.sonar.db.newcodeperiod.NewCodePeriodType.NUMBER_OF_DAYS;
import static org.sonar.db.newcodeperiod.NewCodePeriodType.PREVIOUS_VERSION;
import static org.sonar.db.newcodeperiod.NewCodePeriodType.SPECIFIC_ANALYSIS;
import static org.sonar.server.component.ComponentFinder.ParamNames.PROJECT_ID_AND_KEY;

public class SetNewCodePeriodAction implements SettingsWsAction {
  private static final String PARAM_BRANCH = "branchKey";
  private static final String PARAM_PROJECT = "projectKey";
  private static final String PARAM_TYPE = "type";
  private static final String PARAM_VALUE = "value";
  private static final Set<NewCodePeriodType> OVERALL_TYPES = ImmutableSet.of(PREVIOUS_VERSION, NUMBER_OF_DAYS);
  private static final Set<NewCodePeriodType> PROJECT_TYPES = ImmutableSet.of(DATE, PREVIOUS_VERSION, NUMBER_OF_DAYS);
  private static final Set<NewCodePeriodType> BRANCH_TYPES = ImmutableSet.of(DATE, PREVIOUS_VERSION, NUMBER_OF_DAYS, SPECIFIC_ANALYSIS);

  private final DbClient dbClient;
  private final UserSession userSession;
  private final ComponentFinder componentFinder;
  private final NewCodePeriodDao newCodePeriodDao;

  public SetNewCodePeriodAction(DbClient dbClient, UserSession userSession, ComponentFinder componentFinder, NewCodePeriodDao newCodePeriodDao) {
    this.dbClient = dbClient;
    this.userSession = userSession;
    this.componentFinder = componentFinder;
    this.newCodePeriodDao = newCodePeriodDao;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction action = context.createAction("set_new_code_period")
      .setDescription("Updates the setting for the New Code Period.<br>" +
        "Requires one of the following permissions: " +
        "<ul>" +
        "<li>'Administer System'</li>" +
        "<li>'Administer' rights on the specified component</li>" +
        "</ul>")
      .setSince("8.0")
      .setResponseExample(getClass().getResource("generate_secret_key-example.json"))
      .setHandler(this);

    action.createParam(PARAM_PROJECT)
      .setDescription("Project key");
    action.createParam(PARAM_BRANCH)
      .setDescription("Branch key");
    action.createParam(PARAM_TYPE)
      .setRequired(true)
      .setDescription("Type");
    action.createParam(PARAM_VALUE)
      .setDescription("Value");
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    String projectStr = request.getParam(PARAM_PROJECT).emptyAsNull().or(() -> null);
    String branchStr = request.getParam(PARAM_BRANCH).emptyAsNull().or(() -> null);

    if (projectStr == null && branchStr != null) {
      throw new IllegalArgumentException("If branch key is specified, project key needs to be specified too");
    }

    try (DbSession dbSession = dbClient.openSession(false)) {
      String typeStr = request.mandatoryParam(PARAM_TYPE);
      String valueStr = request.getParam(PARAM_VALUE).emptyAsNull().or(() -> null);
      NewCodePeriodType type = validateType(typeStr, projectStr == null, branchStr != null);

      NewCodePeriodDto dto = new NewCodePeriodDto();
      dto.setType(type);

      ComponentDto projectBranch = null;
      if (projectStr != null) {
        projectBranch = getProject(dbSession, projectStr, branchStr);
        userSession.checkComponentPermission(UserRole.ADMIN, projectBranch);
        dto.setProjectUuid(projectBranch.projectUuid());
      } else {
        userSession.checkIsSystemAdministrator();
      }

      setValue(dbSession, dto, type, projectBranch, branchStr, valueStr);

      // TODO upsert?
      newCodePeriodDao.insert(dbSession, dto);
    }
  }

  private void setValue(DbSession dbSession, NewCodePeriodDto dto, NewCodePeriodType type, @Nullable ComponentDto projectBranch,
    @Nullable String branch, @Nullable String value) {
    switch (type) {
      case PREVIOUS_VERSION:
        Preconditions.checkArgument(value == null, "Unexpected value for type '%s'", type);
        break;
      case NUMBER_OF_DAYS:
        requireValue(type, value);
        try {
          dto.setValue(Integer.toString(NewCodePeriodParser.parseDays(value)));
        } catch (Exception e) {
          throw new IllegalArgumentException("Failed to parse number of days: " + value);
        }
        break;
      case DATE:
        requireValue(type, value);
        try {
          dto.setValue(NewCodePeriodParser.parseDate(value).toString());
        } catch (Exception e) {
          throw new IllegalArgumentException("Failed to parse date: " + value);
        }
        break;
      case SPECIFIC_ANALYSIS:
        requireValue(type, value);
        SnapshotDto analysis = getAnalysis(dbSession, value, projectBranch, branch);
        dto.setValue(analysis.getUuid());
        break;
      default:
        throw new IllegalStateException("Unexpected type: " + type);
    }
  }

  private void requireValue(NewCodePeriodType type, @Nullable String value) {
    Preconditions.checkArgument(value != null, "New Code Period type '%s' requires a value", type);
  }

  private ComponentDto getProject(DbSession dbSession, String projectKey, @Nullable String branchKey) {
    if (branchKey == null) {
      return componentFinder.getByUuidOrKey(dbSession, null, projectKey, PROJECT_ID_AND_KEY);
    }
    ComponentDto project = componentFinder.getByKeyAndBranch(dbSession, projectKey, branchKey);

    BranchDto branchDto = dbClient.branchDao().selectByUuid(dbSession, project.uuid())
      .orElseThrow(() -> new NotFoundException(format("Branch '%s' is not found", branchKey)));

    checkArgument(branchDto.getBranchType() == BranchType.LONG,
      "Not a long-living branch: '%s'", branchKey);

    return project;
  }

  private NewCodePeriodType validateType(String typeStr, boolean isOverall, boolean isBranch) {
    NewCodePeriodType type;
    try {
      type = NewCodePeriodType.valueOf(typeStr.toUpperCase(Locale.US));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid type: " + typeStr);
    }

    if (isOverall) {
      checkType("Overall setting", OVERALL_TYPES, type);
    } else if (isBranch) {
      checkType("Branches", BRANCH_TYPES, type);
    } else {
      checkType("Projects", PROJECT_TYPES, type);
    }
    return type;
  }

  private SnapshotDto getAnalysis(DbSession dbSession, String analysisUuid, ComponentDto projectBranch, @Nullable String branchKey) {
    SnapshotDto snapshotDto = dbClient.snapshotDao().selectByUuid(dbSession, analysisUuid)
      .orElseThrow(() -> new NotFoundException(format("Analysis '%s' is not found", analysisUuid)));
    checkAnalysis(dbSession, projectBranch, branchKey, snapshotDto);
    return snapshotDto;
  }

  private void checkAnalysis(DbSession dbSession, ComponentDto projectBranch, @Nullable String branchKey, SnapshotDto analysis) {
    ComponentDto project = dbClient.componentDao().selectByUuid(dbSession, analysis.getComponentUuid()).orElse(null);

    boolean analysisMatchesProjectBranch = project != null && projectBranch.uuid().equals(project.uuid());
    if (branchKey != null) {
      checkArgument(analysisMatchesProjectBranch,
        "Analysis '%s' does not belong to branch '%s' of project '%s'",
        analysis.getUuid(), branchKey, projectBranch.getKey());
    } else {
      checkArgument(analysisMatchesProjectBranch,
        "Analysis '%s' does not belong to project '%s'",
        analysis.getUuid(), projectBranch.getKey());
    }
  }

  private static void checkType(String name, Set<NewCodePeriodType> validTypes, NewCodePeriodType type) {
    if (!validTypes.contains(type)) {
      throw new IllegalArgumentException(String.format("Invalid type '%s'. %s can only be set with types: %s", type, name, validTypes));
    }
  }
}
