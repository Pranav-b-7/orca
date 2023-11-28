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

package com.netflix.spinnaker.orca.clouddriver.tasks.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.kork.core.RetrySupport;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerConversionException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerNetworkException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerServerException;
import com.netflix.spinnaker.orca.clouddriver.KatoRestService;
import com.netflix.spinnaker.orca.front50.Front50Service;
import com.netflix.spinnaker.orca.front50.model.Application;
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

public class JobUtilsTest {

  private RetrySupport retrySupport;
  private KatoRestService katoRestService;
  private Front50Service front50Service;

  private JobUtils jobUtils;

  @BeforeEach
  public void setup() {
    retrySupport = mock(RetrySupport.class);
    katoRestService = mock(KatoRestService.class);
    front50Service = mock(Front50Service.class);
    jobUtils = new JobUtils(retrySupport, katoRestService, Optional.ofNullable(front50Service));
  }

  @Test
  public void verifyApplicationExistsReturnsFalseWhenApplicationNameIsNull() {

    String app = null;
    var result = jobUtils.applicationExists(app);
    assertThat(result).isEqualTo(false);
  }

  @Test
  public void shouldReturnTrueWhenApplicationExists() {

    var user = "testuser";
    var app = "testapp";
    Application application = new Application(user);

    when(front50Service.get(app)).thenReturn(application);

    var result = jobUtils.applicationExists(app);

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void shouldThrowSpinnakerHttpExceptionWhenHttpErrorHasOccurred() {

    var app = "testapp";
    var url = "https://front50service.com/v2/applications/" + app;

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

    when(front50Service.get(app)).thenThrow(httpException);

    assertThatThrownBy(() -> jobUtils.applicationExists(app))
        .isExactlyInstanceOf(SpinnakerHttpException.class)
        .hasMessage(
            String.format(
                "Status: %s, URL: %s, Message: %s",
                HttpStatus.BAD_REQUEST.value(), url, HttpStatus.BAD_REQUEST.name()))
        .hasCause(httpException.getCause());
  }

  @Test
  public void shouldThrowSpinnakerConversionExceptionWhenConversionErrorHasOccurred() {

    var app = "testapp";
    var url = "https://front50service.com/v2/applications/" + app;

    Response mockResponse =
        new Response(
            url,
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.name(),
            Collections.emptyList(),
            new TypedByteArray("application/json", "{ \"error\": \"BAD REQUEST\"}".getBytes()));

    RetrofitError conversionError =
        RetrofitError.conversionError(
            url,
            mockResponse,
            new JacksonConverter(),
            null,
            new ConversionException("Failed to parse"));
    SpinnakerConversionException conversionException =
        new SpinnakerConversionException(conversionError);

    when(front50Service.get(app)).thenThrow(conversionException);

    assertThatThrownBy(() -> jobUtils.applicationExists(app))
        .isExactlyInstanceOf(SpinnakerConversionException.class)
        .hasMessage("Failed to parse")
        .hasCause(conversionException.getCause());
  }

  @Test
  public void shouldThrowSpinnakerNetworkExceptionWhenNetworkErrorHasOccurred() {

    var app = "testapp";
    var url = "https://front50service.com/v2/applications/" + app;

    RetrofitError networkError =
        RetrofitError.networkError(
            url, new IOException("Failed to connect to the host : front50service.com"));
    SpinnakerNetworkException networkException = new SpinnakerNetworkException(networkError);

    when(front50Service.get(app)).thenThrow(networkException);

    assertThatThrownBy(() -> jobUtils.applicationExists(app))
        .isExactlyInstanceOf(SpinnakerNetworkException.class)
        .hasMessage("Failed to connect to the host : front50service.com")
        .hasCause(networkException.getCause());
  }

  @Test
  public void shouldThrowSpinnakerServerExceptionWhenUnexpectedErrorHasOccurred() {

    var app = "testapp";
    var url = "https://front50service.com/v2/applications/" + app;

    RetrofitError unexpectedError =
        RetrofitError.unexpectedError(
            url, new IOException("Something went wrong, Please try again"));
    SpinnakerServerException serverException = new SpinnakerServerException(unexpectedError);

    when(front50Service.get(app)).thenThrow(serverException);

    assertThatThrownBy(() -> jobUtils.applicationExists(app))
        .isExactlyInstanceOf(SpinnakerServerException.class)
        .hasMessage("Something went wrong, Please try again")
        .hasCause(serverException.getCause());
  }
}
