/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
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

package org.sonar.server.project.ws;

import com.google.common.io.Resources;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.core.util.stream.Collectors;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.server.user.UserSession;

import static java.util.Optional.ofNullable;
import static org.sonar.api.web.UserRole.USER;
import static org.sonar.core.util.stream.Collectors.uniqueIndex;
import static org.sonar.server.ws.KeyExamples.KEY_PROJECT_EXAMPLE_001;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.ACTION_INDEX;

/**
 * This web service is used by old version of SonarLint.
 */
public class IndexAction implements ProjectsWsAction {

  private static final String PARAM_KEY = "key";
  private static final String PARAM_SEARCH = "search";
  private static final String PARAM_SUB_PROJECTS = "subprojects";
  private static final String PARAM_FORMAT = "format";

  private final DbClient dbClient;
  private final UserSession userSession;

  public IndexAction(DbClient dbClient, UserSession userSession) {
    this.dbClient = dbClient;
    this.userSession = userSession;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction action = context.createAction(ACTION_INDEX)
      .setDescription("This web service is deprecated, please use api/components/search instead")
      .setSince("2.10")
      .setDeprecatedSince("6.3")
      .setHandler(this)
      .setResponseExample(Resources.getResource(this.getClass(), "index-example.json"));
    action.createParam(PARAM_KEY)
      .setDescription("key or id of the project")
      .setExampleValue(KEY_PROJECT_EXAMPLE_001);
    action.createParam(PARAM_SEARCH)
      .setDescription("Substring of project name, case insensitive. Ignored if the parameter key is set")
      .setExampleValue("Sonar");
    action.createParam(PARAM_SUB_PROJECTS)
      .setDescription("Load sub-projects. Ignored if the parameter key is set")
      .setDefaultValue("false")
      .setBooleanPossibleValues();
    action.createParam(PARAM_FORMAT)
      .setDescription("Only json response format is available")
      .setPossibleValues("json");
    addRemovedParameter("desc", action);
    addRemovedParameter("views", action);
    addRemovedParameter("libs", action);
    addRemovedParameter("versions", action);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    try (DbSession dbSession = dbClient.openSession(false)) {
      List<ComponentDto> projects = getAuthorizedComponents(dbSession, searchComponents(dbSession, request));
      JsonWriter json = response.newJsonWriter();
      json.beginArray();
      for (ComponentDto project : projects) {
        addProject(json, project);
      }
      json.endArray();
      json.close();
    }
  }

  private Optional<ComponentDto> getProjectByKeyOrId(DbSession dbSession, String component) {
    try {
      Long componentId = Long.parseLong(component);
      return ofNullable(dbClient.componentDao().selectById(dbSession, componentId).orNull());
    } catch (NumberFormatException e) {
      return ofNullable(dbClient.componentDao().selectByKey(dbSession, component).orNull());
    }
  }

  private List<ComponentDto> searchComponents(DbSession dbSession, Request request) {
    String projectKey = request.param(PARAM_KEY);
    List<ComponentDto> projects = new ArrayList<>();
    if (projectKey != null) {
      getProjectByKeyOrId(dbSession, projectKey).ifPresent(projects::add);
    } else {
      String nameQuery = request.param(PARAM_SEARCH);
      boolean includeModules = request.paramAsBoolean(PARAM_SUB_PROJECTS);
      projects.addAll(dbClient.componentDao().selectProjectsByNameQuery(dbSession, nameQuery, includeModules));
    }
    return projects;
  }

  private List<ComponentDto> getAuthorizedComponents(DbSession dbSession, List<ComponentDto> components) {
    if (components.isEmpty()) {
      return Collections.emptyList();
    }
    Set<String> projectUuids = components.stream().map(ComponentDto::projectUuid).collect(Collectors.toSet());
    List<ComponentDto> projects = dbClient.componentDao().selectByUuids(dbSession, projectUuids);
    Map<String, Long> projectIdsByUuids = projects.stream().collect(uniqueIndex(ComponentDto::uuid, ComponentDto::getId));
    Collection<Long> authorizedProjectIds = dbClient.authorizationDao().keepAuthorizedProjectIds(dbSession, projectIdsByUuids.values(), userSession.getUserId(), USER);
    return components.stream()
      .filter(component -> authorizedProjectIds.contains(projectIdsByUuids.get(component.projectUuid())))
      .collect(Collectors.toList());
  }

  private static void addProject(JsonWriter json, ComponentDto project) {
    json.beginObject()
      .prop("id", project.getId())
      .prop("k", project.getKey())
      .prop("nm", project.name())
      .prop("sc", project.scope())
      .prop("qu", project.qualifier())
      .endObject();
  }

  private static void addRemovedParameter(String key, WebService.NewAction action) {
    action.createParam(key)
      .setDescription("Since 6.3, this parameter has no effect")
      .setDeprecatedKey("6.3");
  }

}
