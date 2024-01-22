/*
 * Copyright 2024 OpsMx, Inc.
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

package com.netflix.spinnaker.orca.keel.task;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType.PIPELINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerRetrofitErrorHandler;
import com.netflix.spinnaker.okhttp.OkHttpClientConfigurationProperties;
import com.netflix.spinnaker.okhttp.SpinnakerRequestInterceptor;
import com.netflix.spinnaker.orca.KeelService;
import com.netflix.spinnaker.orca.api.pipeline.TaskResult;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.config.KeelConfiguration;
import com.netflix.spinnaker.orca.igor.ScmService;
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl;
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpStatus;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.OkClient;
import retrofit.converter.JacksonConverter;

/*
 *  @see com.netflix.spinnaker.orca.keel.ImportDeliveryConfigTaskTests.kt already covers up few tests related to @see ImportDeliveryConfigTask.
 * This new java class is Introduced to improvise the API testing with the help of wiremock.
 * Test using wiremock would help in smooth migration to retrofit2.x along with the addition of {@link SpinnakerRetrofitErrorHandler}.
 * */
public class ImportDeliveryConfigTaskTest {

  private static KeelService keelService;
  private static ScmService scmService;

  private static ObjectMapper objectMapper = new KeelConfiguration().keelObjectMapper();

  private StageExecution stage;

  private ImportDeliveryConfigTask importDeliveryConfigTask;

  private Map<String, Object> contextMap = new LinkedHashMap<>();

  private static final int keelPort = 8087;

  @BeforeAll
  static void setupOnce(WireMockRuntimeInfo wmRuntimeInfo) {
    OkClient okClient = new OkClient();
    RestAdapter.LogLevel retrofitLogLevel = RestAdapter.LogLevel.NONE;

    keelService =
        new RestAdapter.Builder()
            .setRequestInterceptor(
                new SpinnakerRequestInterceptor(new OkHttpClientConfigurationProperties()))
            .setEndpoint(wmRuntimeInfo.getHttpBaseUrl())
            .setClient(okClient)
            .setErrorHandler(SpinnakerRetrofitErrorHandler.getInstance())
            .setLogLevel(retrofitLogLevel)
            .setConverter(new JacksonConverter(objectMapper))
            .build()
            .create(KeelService.class);

    scmService = mock(ScmService.class);
  }

  @BeforeEach
  public void setup() {
    importDeliveryConfigTask = new ImportDeliveryConfigTask(keelService, scmService, objectMapper);

    PipelineExecutionImpl pipeline = new PipelineExecutionImpl(PIPELINE, "keeldemo");
    contextMap.put("repoType", "stash");
    contextMap.put("projectKey", "SPKR");
    contextMap.put("repositorySlug", "keeldemo");
    contextMap.put("directory", ".");
    contextMap.put("manifest", "spinnaker.yml");
    contextMap.put("ref", "refs/heads/master");
    contextMap.put("attempt", 1);
    contextMap.put("maxRetries", 5);

    stage = new StageExecutionImpl(pipeline, ExecutionType.PIPELINE.toString(), contextMap);
  }

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance().options(wireMockConfig().port(keelPort)).build();

  private static void simulateFault(String url, String body, HttpStatus httpStatus) {
    wireMock.givenThat(
        WireMock.post(urlPathEqualTo(url))
            .willReturn(aResponse().withStatus(httpStatus.value()).withBody(body)));
  }

  private static void simulateFault(String url, Fault fault) {
    wireMock.givenThat(WireMock.post(urlPathEqualTo(url)).willReturn(aResponse().withFault(fault)));
  }

  /**
   * This test is a positive case which verifies if the task returns {@link
   * ImportDeliveryConfigTask.SpringHttpError} on 4xx http error. Here the error body is mocked with
   * timestamp in {@link ChronoUnit#MILLIS}, which will be parsed to exact same timestamp in the
   * method @see {@link ImportDeliveryConfigTask#handleRetryableFailures(RetrofitError,
   * ImportDeliveryConfigTask.ImportDeliveryConfigContext)} and results in successful assertions of
   * all the fields.
   *
   * <p>Other positive scenarios when the timestamp results in accurate value, are when the units in
   * : {@link ChronoUnit#SECONDS} {@link ChronoUnit#DAYS} {@link ChronoUnit#HOURS} {@link
   * ChronoUnit#HALF_DAYS} {@link ChronoUnit#MINUTES}
   */
  @Test
  public void verifyTaskReturnsSpringHttpError() throws JsonProcessingException {

    var mockResponseBody =
        Map.of(
            "name",
            "keeldemo-manifest",
            "application",
            "keeldemo",
            "artifacts",
            Collections.emptySet(),
            "environments",
            Collections.emptySet());

    var httpStatus = HttpStatus.BAD_REQUEST;

    // Initialize SpringHttpError with timestamp in milliseconds and HttpStatus 400 bad request.
    var httpError = mockSpringHttpError(httpStatus, Instant.now().truncatedTo(ChronoUnit.MILLIS));

    var context = Map.of("error", httpError);

    TaskResult terminal = TaskResult.builder(ExecutionStatus.TERMINAL).context(context).build();

    // simulate 400 bad request http error with SpringHttpError body
    simulateFault("/delivery-configs/", objectMapper.writeValueAsString(httpError), httpStatus);

    when(scmService.getDeliveryConfigManifest(
            (String) contextMap.get("repoType"),
            (String) contextMap.get("projectKey"),
            (String) contextMap.get("repositorySlug"),
            (String) contextMap.get("directory"),
            (String) contextMap.get("manifest"),
            (String) contextMap.get("ref")))
        .thenReturn(mockResponseBody);

    var result = importDeliveryConfigTask.execute(stage);

    assertThat(result).isEqualTo(terminal);
  }

  /**
   * This test is a negative case which verifies if the task returns {@link
   * ImportDeliveryConfigTask.SpringHttpError} on 4xx http error. Here the error body is mocked with
   * timestamp in {@link ChronoUnit#NANOS}, which WILL NOT be parsed to exact timestamp in the
   * method @see {@link ImportDeliveryConfigTask#handleRetryableFailures(RetrofitError,
   * ImportDeliveryConfigTask.ImportDeliveryConfigContext)} and results in will contain incorrect
   * timestamp.
   *
   * <p>Other cases where the timestamp will result in incorrect value, are when the units in :
   * {@link ChronoUnit#MICROS}
   */
  @Test
  public void verifyTaskResultReturnsSpringHttpErrorWhenTimestampIsInNanos()
      throws JsonProcessingException {

    var mockResponseBody =
        Map.of(
            "name",
            "keeldemo-manifest",
            "application",
            "keeldemo",
            "artifacts",
            Collections.emptySet(),
            "environments",
            Collections.emptySet());

    var httpStatus = HttpStatus.BAD_REQUEST;
    var timestamp = Instant.now().truncatedTo(ChronoUnit.NANOS);

    // Initialize SpringHttpError with timestamp in nanos and HttpStatus 400 bad request.
    var httpError = mockSpringHttpError(httpStatus, timestamp);

    var context = Map.of("error", httpError);

    TaskResult terminal = TaskResult.builder(ExecutionStatus.TERMINAL).context(context).build();

    // simulate 400 bad request http error with SpringHttpError body
    simulateFault("/delivery-configs/", objectMapper.writeValueAsString(httpError), httpStatus);

    when(scmService.getDeliveryConfigManifest(
            (String) contextMap.get("repoType"),
            (String) contextMap.get("projectKey"),
            (String) contextMap.get("repositorySlug"),
            (String) contextMap.get("directory"),
            (String) contextMap.get("manifest"),
            (String) contextMap.get("ref")))
        .thenReturn(mockResponseBody);

    var result = importDeliveryConfigTask.execute(stage);

    var httpErrorBody = (ImportDeliveryConfigTask.SpringHttpError) result.getContext().get("error");

    // assert all the values in the http error body are true except the timestamp
    assertThat(httpErrorBody.getStatus()).isEqualTo(httpStatus.value());
    assertThat(httpErrorBody.getError()).isEqualTo(httpStatus.getReasonPhrase());
    assertThat(httpErrorBody.getMessage()).isEqualTo(httpStatus.name());
    assertThat(httpErrorBody.getDetails()).isEqualTo(Map.of("exception", "Http Error occured"));
    assertThat(httpErrorBody.getTimestamp()).isNotEqualTo(timestamp);
  }

  @Test
  public void testTaskResultWhenErrorBodyIsEmpty() {

    var mockResponseBody =
        Map.of(
            "name",
            "keeldemo-manifest",
            "application",
            "keeldemo",
            "artifacts",
            Collections.emptySet(),
            "environments",
            Collections.emptySet());

    String expectedMessage =
        String.format(
            "Non-retryable HTTP response %s received from downstream service: %s",
            HttpStatus.BAD_REQUEST.value(),
            "HTTP 400 "
                + wireMock.baseUrl()
                + "/delivery-configs/: Status: 400, URL: "
                + wireMock.baseUrl()
                + "/delivery-configs/, Message: Bad Request");

    var errorMap = new HashMap<>();
    errorMap.put("message", expectedMessage);

    TaskResult terminal =
        TaskResult.builder(ExecutionStatus.TERMINAL).context(Map.of("error", errorMap)).build();

    // Simulate any 4xx http error with empty error response body
    String emptyBody = "";
    simulateFault("/delivery-configs/", emptyBody, HttpStatus.BAD_REQUEST);

    when(scmService.getDeliveryConfigManifest(
            (String) contextMap.get("repoType"),
            (String) contextMap.get("projectKey"),
            (String) contextMap.get("repositorySlug"),
            (String) contextMap.get("directory"),
            (String) contextMap.get("manifest"),
            (String) contextMap.get("ref")))
        .thenReturn(mockResponseBody);

    var result = importDeliveryConfigTask.execute(stage);

    assertThat(result).isEqualTo(terminal);
  }

  @Test
  public void testTaskResultWhenHttp5xxErrorIsThrown() {

    var mockResponseBody =
        Map.of(
            "name",
            "keeldemo-manifest",
            "application",
            "keeldemo",
            "artifacts",
            Collections.emptySet(),
            "environments",
            Collections.emptySet());

    contextMap.put("attempt", (Integer) contextMap.get("attempt") + 1);
    contextMap.put(
        "errorFromLastAttempt",
        "Retryable HTTP response 500 received from downstream service: HTTP 500 "
            + wireMock.baseUrl()
            + "/delivery-configs/: Status: 500, URL: "
            + wireMock.baseUrl()
            + "/delivery-configs/, Message: Server Error");

    TaskResult running = TaskResult.builder(ExecutionStatus.RUNNING).context(contextMap).build();

    // Simulate any 5xx http error with empty error response body
    String emptyBody = "";
    simulateFault("/delivery-configs/", emptyBody, HttpStatus.INTERNAL_SERVER_ERROR);

    when(scmService.getDeliveryConfigManifest(
            (String) contextMap.get("repoType"),
            (String) contextMap.get("projectKey"),
            (String) contextMap.get("repositorySlug"),
            (String) contextMap.get("directory"),
            (String) contextMap.get("manifest"),
            (String) contextMap.get("ref")))
        .thenReturn(mockResponseBody);

    var result = importDeliveryConfigTask.execute(stage);

    assertThat(result).isEqualTo(running);
  }

  @Test
  public void testTaskResultWhenAPIFailsWithNetworkError() {

    var mockResponseBody =
        Map.of(
            "name",
            "keeldemo-manifest",
            "application",
            "keeldemo",
            "artifacts",
            Collections.emptySet(),
            "environments",
            Collections.emptySet());

    contextMap.put("attempt", (Integer) contextMap.get("attempt") + 1);
    contextMap.put(
        "errorFromLastAttempt",
        String.format(
            "Network error talking to downstream service, attempt 1 of %s: Connection reset: Connection reset",
            contextMap.get("maxRetries")));

    TaskResult running = TaskResult.builder(ExecutionStatus.RUNNING).context(contextMap).build();

    // Simulate network failure
    simulateFault("/delivery-configs/", Fault.CONNECTION_RESET_BY_PEER);

    when(scmService.getDeliveryConfigManifest(
            (String) contextMap.get("repoType"),
            (String) contextMap.get("projectKey"),
            (String) contextMap.get("repositorySlug"),
            (String) contextMap.get("directory"),
            (String) contextMap.get("manifest"),
            (String) contextMap.get("ref")))
        .thenReturn(mockResponseBody);

    var result = importDeliveryConfigTask.execute(stage);

    assertThat(result).isEqualTo(running);
  }

  private ImportDeliveryConfigTask.SpringHttpError mockSpringHttpError(
      HttpStatus httpStatus, Instant timestamp) {

    return new ImportDeliveryConfigTask.SpringHttpError(
        httpStatus.getReasonPhrase(),
        httpStatus.value(),
        httpStatus.name(),
        timestamp,
        Map.of("exception", "Http Error occured"));
  }
}
