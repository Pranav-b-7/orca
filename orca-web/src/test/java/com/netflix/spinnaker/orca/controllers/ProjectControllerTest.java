/*
 * Copyright 2023 OpsMx, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;

import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerConversionException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerNetworkException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerServerException;
import com.netflix.spinnaker.orca.front50.Front50Service;
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository;
import java.io.IOException;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import retrofit.RetrofitError;
import retrofit.client.Response;
import retrofit.converter.ConversionException;

@ExtendWith(SpringExtension.class)
@SpringBootTest(
    classes = {ProjectController.class},
    webEnvironment = MOCK)
@AutoConfigureMockMvc
@EnableWebMvc
@WithMockUser("test user")
public class ProjectControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private Front50Service front50Service;

  @MockBean private ExecutionRepository executionRepository;

  @InjectMocks private ProjectController projectController;

  @BeforeEach
  public void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @Test
  public void shouldReturnEmptyWhenProjectIdNotFound() throws Exception {

    var projectId = "pro123";
    var url = "https://front50service.com/v2/projects/" + projectId + "/pipelines";

    SpinnakerHttpException httpException =
        new SpinnakerHttpException(
            RetrofitError.httpError(
                url,
                new Response(
                    url,
                    HttpStatus.NOT_FOUND.value(),
                    HttpStatus.NOT_FOUND.name(),
                    Collections.emptyList(),
                    null),
                null,
                null));

    when(front50Service.getProject(projectId)).thenThrow(httpException);

    MvcResult result =
        mockMvc
            .perform(
                MockMvcRequestBuilders.get("/projects/" + projectId + "/pipelines")
                    .queryParam("limit", "5")
                    .queryParam("statuses", "RUNNING")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();

    var response = result.getResponse().getContentAsString();

    assertThat(response).isEqualTo("[]");
  }

  @Test
  public void shouldThrowSpinnakerConversionExceptionWhenGetProjectAPIFailsToParseResponseBody() {

    var projectId = "pro123";
    var url = "https://front50service.com/v2/projects/" + projectId + "/pipelines";

    SpinnakerConversionException conversionException =
        new SpinnakerConversionException(
            RetrofitError.conversionError(
                url,
                new Response(
                    url,
                    HttpStatus.BAD_REQUEST.value(),
                    HttpStatus.BAD_REQUEST.name(),
                    Collections.emptyList(),
                    null),
                null,
                null,
                new ConversionException("Failed to parse the response body")));

    when(front50Service.getProject(projectId)).thenThrow(conversionException);

    assertThatThrownBy(() -> projectController.list(projectId, 5, "RUNNING"))
        .isExactlyInstanceOf(SpinnakerConversionException.class)
        .hasMessage("Failed to parse the response body")
        .hasCause(conversionException.getCause());
  }

  @Test
  public void shouldThrowSpinnakerHttpExceptionWhenGetProjectAPIReturnsSomeHttpError() {

    var projectId = "pro123";
    var url = "https://front50service.com/v2/projects/" + projectId + "/pipelines";

    SpinnakerHttpException httpException =
        new SpinnakerHttpException(
            RetrofitError.httpError(
                url,
                new Response(
                    url,
                    HttpStatus.BAD_REQUEST.value(),
                    HttpStatus.BAD_REQUEST.name(),
                    Collections.emptyList(),
                    null),
                null,
                null));

    when(front50Service.getProject(projectId)).thenThrow(httpException);

    assertThatThrownBy(() -> projectController.list(projectId, 5, "RUNNING"))
        .isExactlyInstanceOf(SpinnakerHttpException.class)
        .hasMessage(
            "Status: "
                + HttpStatus.BAD_REQUEST.value()
                + ", URL: "
                + url
                + ", Message: "
                + HttpStatus.BAD_REQUEST.name())
        .hasCause(httpException.getCause());
  }

  @Test
  public void shouldThrowSpinnakerNetworkExceptionWhenGetProjectAPIFailsToConnectToFront50() {

    var projectId = "pro123";
    var url = "https://front50service.com/v2/projects/" + projectId + "/pipelines";

    SpinnakerNetworkException networkException =
        new SpinnakerNetworkException(
            RetrofitError.networkError(
                url, new IOException("Failed to connect to the host : front50service.com")));

    when(front50Service.getProject(projectId)).thenThrow(networkException);

    assertThatThrownBy(() -> projectController.list(projectId, 5, "RUNNING"))
        .isExactlyInstanceOf(SpinnakerNetworkException.class)
        .hasMessage("Failed to connect to the host : front50service.com")
        .hasCause(networkException.getCause());
  }

  @Test
  public void shouldThrowSpinnakerServerExceptionWhenGetProjectAPIFailsDueToSomeUnexpectedError() {

    var projectId = "pro123";
    var url = "https://front50service.com/v2/projects/" + projectId + "/pipelines";

    SpinnakerServerException serverException =
        new SpinnakerServerException(
            RetrofitError.unexpectedError(
                url, new IOException("Something went wrong, please try again")));

    when(front50Service.getProject(projectId)).thenThrow(serverException);

    assertThatThrownBy(() -> projectController.list(projectId, 5, "RUNNING"))
        .isExactlyInstanceOf(SpinnakerServerException.class)
        .hasMessage("Something went wrong, please try again")
        .hasCause(serverException.getCause());
  }
}
