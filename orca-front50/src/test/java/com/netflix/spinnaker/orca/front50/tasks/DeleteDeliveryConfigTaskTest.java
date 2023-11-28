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

package com.netflix.spinnaker.orca.front50.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerConversionException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerNetworkException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerServerException;
import com.netflix.spinnaker.orca.front50.Front50Service;
import java.io.IOException;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import retrofit.RetrofitError;
import retrofit.client.Response;
import retrofit.converter.ConversionException;
import retrofit.converter.JacksonConverter;

public class DeleteDeliveryConfigTaskTest {

  private DeleteDeliveryConfigTask deleteDeliveryConfigTask;

  private Front50Service front50Service = mock(Front50Service.class);

  @BeforeEach
  public void setup() {
    deleteDeliveryConfigTask = new DeleteDeliveryConfigTask(front50Service, new ObjectMapper());
  }

  @Test
  public void returnEmptyWhenDeliveryConfigNotFoundWithGivenId() {

    var deliveryConfigId = "abcd1234";
    var url = "https://front50service.com/deliveries/" + deliveryConfigId;
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
                new JacksonConverter(),
                null));

    when(front50Service.getDeliveryConfig(deliveryConfigId)).thenThrow(httpException);

    var result = deleteDeliveryConfigTask.getDeliveryConfig(deliveryConfigId);

    assertThat(result).isEmpty();
  }

  @Test
  public void returnEmptyWhenDeliveryConfigThrowsAccessFrobiddenForGivenId() {

    var deliveryConfigId = "abcd1234";
    var url = "https://front50service.com/deliveries/" + deliveryConfigId;
    SpinnakerHttpException httpException =
        new SpinnakerHttpException(
            RetrofitError.httpError(
                url,
                new Response(
                    url,
                    HttpStatus.FORBIDDEN.value(),
                    HttpStatus.FORBIDDEN.name(),
                    Collections.emptyList(),
                    null),
                new JacksonConverter(),
                null));

    when(front50Service.getDeliveryConfig(deliveryConfigId)).thenThrow(httpException);

    var result = deleteDeliveryConfigTask.getDeliveryConfig(deliveryConfigId);

    assertThat(result).isEmpty();
  }

  @Test
  public void returnEmptyWhenDeliveryConfigThrowsUnauthorizedForGivenId() {

    var deliveryConfigId = "abcd1234";
    var url = "https://front50service.com/deliveries/" + deliveryConfigId;
    SpinnakerHttpException httpException =
        new SpinnakerHttpException(
            RetrofitError.httpError(
                url,
                new Response(
                    url,
                    HttpStatus.UNAUTHORIZED.value(),
                    HttpStatus.UNAUTHORIZED.name(),
                    Collections.emptyList(),
                    null),
                new JacksonConverter(),
                null));

    when(front50Service.getDeliveryConfig(deliveryConfigId)).thenThrow(httpException);

    var result = deleteDeliveryConfigTask.getDeliveryConfig(deliveryConfigId);

    assertThat(result).isEmpty();
  }

  @Test
  public void throwSpinnakerHttpExceptionWhenDeliveryConfigAPIFailsWithHttpError() {

    var deliveryConfigId = "abcd1234";
    var url = "https://front50service.com/deliveries/" + deliveryConfigId;
    SpinnakerHttpException httpException =
        new SpinnakerHttpException(
            RetrofitError.httpError(
                url,
                new Response(
                    url,
                    HttpStatus.CONFLICT.value(),
                    HttpStatus.CONFLICT.name(),
                    Collections.emptyList(),
                    null),
                new JacksonConverter(),
                null));

    when(front50Service.getDeliveryConfig(deliveryConfigId)).thenThrow(httpException);

    assertThatThrownBy(() -> deleteDeliveryConfigTask.getDeliveryConfig(deliveryConfigId))
        .isExactlyInstanceOf(SpinnakerHttpException.class)
        .hasMessage(
            "Status: "
                + HttpStatus.CONFLICT.value()
                + ", URL: "
                + url
                + ", Message: "
                + HttpStatus.CONFLICT.name())
        .hasCause(httpException.getCause());
  }

  @Test
  public void throwSpinnakerConversionExceptionWhenDeliveryConfigAPIFailsWithConversionError() {

    var deliveryConfigId = "abcd1234";
    var url = "https://front50service.com/deliveries/" + deliveryConfigId;
    SpinnakerConversionException conversionException =
        new SpinnakerConversionException(
            RetrofitError.conversionError(
                url,
                new Response(
                    url,
                    HttpStatus.NOT_ACCEPTABLE.value(),
                    HttpStatus.NOT_ACCEPTABLE.name(),
                    Collections.emptyList(),
                    null),
                new JacksonConverter(),
                null,
                new ConversionException("Failed to parse the response body")));

    when(front50Service.getDeliveryConfig(deliveryConfigId)).thenThrow(conversionException);

    assertThatThrownBy(() -> deleteDeliveryConfigTask.getDeliveryConfig(deliveryConfigId))
        .isExactlyInstanceOf(SpinnakerConversionException.class)
        .hasMessage("Failed to parse the response body")
        .hasCause(conversionException.getCause());
  }

  @Test
  public void throwSpinnakerNetworkExceptionWhenDeliveryConfigAPIFailsWithNetworkError() {

    var deliveryConfigId = "abcd1234";
    var url = "https://front50service.com/deliveries/" + deliveryConfigId;
    SpinnakerNetworkException networkException =
        new SpinnakerNetworkException(
            RetrofitError.networkError(
                url, new IOException("Failed to connect to the host : front50service.com")));

    when(front50Service.getDeliveryConfig(deliveryConfigId)).thenThrow(networkException);

    assertThatThrownBy(() -> deleteDeliveryConfigTask.getDeliveryConfig(deliveryConfigId))
        .isExactlyInstanceOf(SpinnakerNetworkException.class)
        .hasMessage("Failed to connect to the host : front50service.com")
        .hasCause(networkException.getCause());
  }

  @Test
  public void throwSpinnakerServerExceptionWhenDeliveryConfigAPIFailsWithUnexpectedError() {

    var deliveryConfigId = "abcd1234";
    var url = "https://front50service.com/deliveries/" + deliveryConfigId;
    SpinnakerServerException serverException =
        new SpinnakerServerException(
            RetrofitError.unexpectedError(
                url, new IOException("Something went wrong, please try again")));

    when(front50Service.getDeliveryConfig(deliveryConfigId)).thenThrow(serverException);

    assertThatThrownBy(() -> deleteDeliveryConfigTask.getDeliveryConfig(deliveryConfigId))
        .isExactlyInstanceOf(SpinnakerServerException.class)
        .hasMessage("Something went wrong, please try again")
        .hasCause(serverException.getCause());
  }
}
