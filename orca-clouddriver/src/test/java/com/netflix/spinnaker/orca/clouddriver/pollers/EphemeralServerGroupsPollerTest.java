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

package com.netflix.spinnaker.orca.clouddriver.pollers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.kork.core.RetrySupport;
import com.netflix.spinnaker.kork.exceptions.SystemException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerConversionException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerNetworkException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerServerException;
import com.netflix.spinnaker.orca.clouddriver.CloudDriverService;
import com.netflix.spinnaker.orca.clouddriver.config.PollerConfigurationProperties;
import com.netflix.spinnaker.orca.front50.Front50Service;
import com.netflix.spinnaker.orca.front50.model.Application;
import com.netflix.spinnaker.orca.notifications.NotificationClusterLock;
import com.netflix.spinnaker.orca.pipeline.ExecutionLauncher;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import retrofit.RetrofitError;
import retrofit.client.Response;
import retrofit.converter.ConversionException;
import retrofit.converter.JacksonConverter;
import retrofit.mime.TypedByteArray;

public class EphemeralServerGroupsPollerTest {

  private Front50Service front50Service;

  private EphemeralServerGroupsPoller ephemeralServerGroupsPoller;

  private NotificationClusterLock notificationClusterLock;
  private ObjectMapper objectMapper;
  private CloudDriverService cloudDriverService;
  private RetrySupport retrySupport;
  private Registry registry;
  private ExecutionLauncher executionLauncher;
  private PollerConfigurationProperties pollerConfigurationProperties;

  @BeforeEach
  public void setup() {

    front50Service = mock(Front50Service.class);
    notificationClusterLock = mock(NotificationClusterLock.class);
    objectMapper = new ObjectMapper();
    cloudDriverService = mock(CloudDriverService.class);
    retrySupport = mock(RetrySupport.class);
    registry = mock(Registry.class);
    executionLauncher = mock(ExecutionLauncher.class);
    pollerConfigurationProperties = mock(PollerConfigurationProperties.class);
    ephemeralServerGroupsPoller =
        new EphemeralServerGroupsPoller(
            notificationClusterLock,
            objectMapper,
            cloudDriverService,
            retrySupport,
            registry,
            executionLauncher,
            front50Service,
            pollerConfigurationProperties);
  }

  @Test
  public void shouldReturnEmptyWhenApplicationNotFound() {
    var application = "testapp";

    var url = "https://front50service.com/v2/applications/" + application;
    Response mockResponse =
        new Response(
            url,
            HttpStatus.NOT_FOUND.value(),
            HttpStatus.NOT_FOUND.name(),
            Collections.emptyList(),
            new TypedByteArray(
                "application/json", "{ \"error\": \"application testapp not found\"}".getBytes()));

    RetrofitError httpError =
        RetrofitError.httpError(url, mockResponse, new JacksonConverter(), null);

    when(front50Service.get(application)).thenThrow(new SpinnakerHttpException(httpError));

    Optional<Application> app = ephemeralServerGroupsPoller.getApplication(application);
    assertThat(app).isEmpty();
  }

  @Test
  public void shouldThrowSystemExceptionWhenHttpErrorOtherThan404HasOccurred() {
    var application = "testapp";

    var url = "https://front50service.com/v2/applications/" + application;
    Response mockResponse =
        new Response(
            url,
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.name(),
            Collections.emptyList(),
            new TypedByteArray("application/json", "{ \"error\": \"BAD REQUEST\"}".getBytes()));

    RetrofitError httpError =
        RetrofitError.httpError(url, mockResponse, new JacksonConverter(), null);
    SpinnakerHttpException httpException = new SpinnakerHttpException(httpError);

    when(front50Service.get(application)).thenThrow(httpException);

    assertThatExceptionOfType(SystemException.class)
        .isThrownBy(() -> ephemeralServerGroupsPoller.getApplication(application))
        .withMessage(String.format("Failed to retrieve application '%s'", application));
  }

  @Test
  public void shouldThrowSystemExceptionWhenSpinnakerConversionExceptionHasOccurred() {
    var application = "testapp";

    var url = "https://front50service.com/v2/applications/" + application;
    Response mockResponse =
        new Response(
            url,
            HttpStatus.FORBIDDEN.value(),
            HttpStatus.FORBIDDEN.name(),
            Collections.emptyList(),
            new TypedByteArray("application/json", "{ \"error\": \"FORBIDDEN\"}".getBytes()));

    RetrofitError conversionError =
        RetrofitError.conversionError(
            url,
            mockResponse,
            new JacksonConverter(),
            null,
            new ConversionException("Failed to parse the error response body"));
    SpinnakerConversionException conversionException =
        new SpinnakerConversionException(conversionError);

    when(front50Service.get(application)).thenThrow(conversionException);

    assertThatExceptionOfType(SystemException.class)
        .isThrownBy(() -> ephemeralServerGroupsPoller.getApplication(application))
        .withMessage(String.format("Failed to retrieve application '%s'", application));
  }

  @Test
  public void shouldThrowSystemExceptionWhenSpinnakerNetworkExceptionHasOccurred() {
    var application = "testapp";
    var url = "https://front50service.com/v2/applications/" + application;

    RetrofitError networkError =
        RetrofitError.networkError(
            url, new IOException("Failed to connect to the host : front50service.com"));
    SpinnakerNetworkException networkException = new SpinnakerNetworkException(networkError);

    when(front50Service.get(application)).thenThrow(networkException);

    assertThatExceptionOfType(SystemException.class)
        .isThrownBy(() -> ephemeralServerGroupsPoller.getApplication(application))
        .withMessage(String.format("Failed to retrieve application '%s'", application));
  }

  @Test
  public void shouldThrowSystemExceptionWhenSpinnakerServerExceptionHasOccurred() {
    var application = "testapp";
    var url = "https://front50service.com/v2/applications/" + application;

    RetrofitError unexpectedError =
        RetrofitError.unexpectedError(
            url, new IOException("Something went wrong, Please try again later"));
    SpinnakerServerException serverException = new SpinnakerServerException(unexpectedError);

    when(front50Service.get(application)).thenThrow(serverException);

    assertThatExceptionOfType(SystemException.class)
        .isThrownBy(() -> ephemeralServerGroupsPoller.getApplication(application))
        .withMessage(String.format("Failed to retrieve application '%s'", application));
  }
}
