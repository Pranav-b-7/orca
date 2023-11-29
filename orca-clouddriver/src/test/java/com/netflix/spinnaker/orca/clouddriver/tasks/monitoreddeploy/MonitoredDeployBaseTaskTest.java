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

package com.netflix.spinnaker.orca.clouddriver.tasks.monitoreddeploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spectator.api.NoopRegistry;
import com.netflix.spinnaker.config.DeploymentMonitorDefinition;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerServerException;
import com.netflix.spinnaker.orca.deploymentmonitor.DeploymentMonitorServiceProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit.RequestInterceptor;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.Header;
import retrofit.client.OkClient;
import retrofit.client.Response;
import retrofit.converter.Converter;
import retrofit.converter.JacksonConverter;
import retrofit.mime.TypedInput;

public class MonitoredDeployBaseTaskTest {

  private MonitoredDeployBaseTask monitoredDeployBaseTask;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setup() {
    OkClient okClient = new OkClient();
    RestAdapter.LogLevel retrofitLogLevel = RestAdapter.LogLevel.NONE;

    RequestInterceptor requestInterceptor = request -> {};
    DeploymentMonitorDefinition deploymentMonitorDefinition = new DeploymentMonitorDefinition();
    deploymentMonitorDefinition.setId("LogMonitorId");
    deploymentMonitorDefinition.setName("LogMonitor");
    deploymentMonitorDefinition.setFailOnError(true);
    var deploymentMonitorDefinitions = new ArrayList<DeploymentMonitorDefinition>();
    deploymentMonitorDefinitions.add(deploymentMonitorDefinition);

    DeploymentMonitorServiceProvider deploymentMonitorServiceProvider =
        new DeploymentMonitorServiceProvider(
            okClient, retrofitLogLevel, requestInterceptor, deploymentMonitorDefinitions);
    monitoredDeployBaseTask =
        new MonitoredDeployBaseTask(deploymentMonitorServiceProvider, new NoopRegistry());
  }

  @Test
  @DisplayName(
      "should read and return log message with response body and headers when SpinnakerHttpException is thrown")
  void test1() throws Exception {

    var converter = new JacksonConverter(new ObjectMapper());
    var responseBody = new HashMap<String, String>();
    var headers = new ArrayList<Header>();
    var header = new Header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

    headers.add(header);
    responseBody.put("error", "account");

    Response response =
        new Response(
            "/deployment/evaluateHealth",
            HttpStatus.BAD_REQUEST.value(),
            "bad-request",
            headers,
            new MockTypedInput(converter, responseBody));

    RetrofitError retrofitError =
        RetrofitError.httpError(
            "https://foo.com/deployment/evaluateHealth", response, new JacksonConverter(), null);
    SpinnakerHttpException spinnakerHttpException = new SpinnakerHttpException(retrofitError);

    String logMessage =
        ReflectionTestUtils.invokeMethod(
            monitoredDeployBaseTask, "getErrorMessage", spinnakerHttpException);

    assertEquals(
        "headers: "
            + spinnakerHttpException.getHeaders()
            + "\n"
            + "response body: "
            + objectMapper.writeValueAsString(responseBody),
        logMessage);
    assertEquals(
        "Status: "
            + HttpStatus.BAD_REQUEST.value()
            + ", URL: https://foo.com/deployment/evaluateHealth"
            + ", Message: bad-request",
        spinnakerHttpException.getMessage());
  }

  @Test
  @DisplayName(
      "should return empty headers and response body when parsing/reading of error details fails while handling SpinnakerHttpException")
  void test2() {

    SpinnakerHttpException exception = mock(SpinnakerHttpException.class);

    when(exception.getResponseBody()).thenThrow(IllegalArgumentException.class);

    String logMessage =
        ReflectionTestUtils.invokeMethod(monitoredDeployBaseTask, "getErrorMessage", exception);

    assertEquals("headers: \nresponse body: ", logMessage);
  }

  @Test
  @DisplayName("should return default log message when SpinnakerServerException is thrown")
  void test3() {

    RetrofitError retrofitError =
        RetrofitError.networkError(
            "https://foo.com/deployment/evaluateHealth",
            new IOException("Failed to connect to the host : foo.com"));
    SpinnakerServerException spinnakerServerException = new SpinnakerServerException(retrofitError);

    String logMessage =
        ReflectionTestUtils.invokeMethod(
            monitoredDeployBaseTask, "getErrorMessage", spinnakerServerException);

    assertEquals("<NO RESPONSE>", logMessage);
  }

  static class MockTypedInput implements TypedInput {
    private final Converter converter;
    private final Object body;

    private byte[] bytes;

    MockTypedInput(Converter converter, Object body) {
      this.converter = converter;
      this.body = body;
    }

    @Override
    public String mimeType() {
      return "application/unknown";
    }

    @Override
    public long length() {
      try {
        initBytes();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return bytes.length;
    }

    @Override
    public InputStream in() throws IOException {
      initBytes();
      return new ByteArrayInputStream(bytes);
    }

    private synchronized void initBytes() throws IOException {
      if (bytes == null) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        converter.toBody(body).writeTo(out);
        bytes = out.toByteArray();
      }
    }
  }
}
