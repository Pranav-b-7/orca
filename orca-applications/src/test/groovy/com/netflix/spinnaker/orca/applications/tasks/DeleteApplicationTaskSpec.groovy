/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.applications.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerConversionException
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerNetworkException
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerServerException
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus
import com.netflix.spinnaker.orca.front50.Front50Service
import com.netflix.spinnaker.orca.front50.model.Application
import com.netflix.spinnaker.orca.KeelService
import org.springframework.http.HttpStatus
import retrofit.RetrofitError
import retrofit.client.Response
import retrofit.converter.ConversionException
import retrofit.converter.JacksonConverter
import spock.lang.Specification
import spock.lang.Subject

import static com.netflix.spinnaker.orca.test.model.ExecutionBuilder.pipeline
import static com.netflix.spinnaker.orca.test.model.ExecutionBuilder.stage

class DeleteApplicationTaskSpec extends Specification {
  @Subject
  def task = new DeleteApplicationTask(
      Mock(Front50Service),
      Mock(KeelService),
      new ObjectMapper(),
      Mock(DynamicConfigService))

  def config = [
    account    : "test",
    application: [
      "name": "application"
    ]
  ]
  def pipeline = pipeline {
    stage {
      type = "DeleteApplication"
      context = config
    }
  }

  void "should delete global application if it was only associated with a single account"() {
    given:
    task.front50Service = Mock(Front50Service) {
      1 * get(config.application.name) >> new Application()
      1 * delete(config.application.name)
      1 * deletePermission(config.application.name)
      0 * _._
    }
    task.keelService = Mock(KeelService) {
      1 * deleteDeliveryConfig(config.application.name)
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.SUCCEEDED
  }

  void "should keep track of previous state"() {
    given:
    Application application = new Application()
    task.front50Service = Mock(Front50Service) {
      1 * get(config.application.name) >> application
      1 * delete(config.application.name)
      1 * deletePermission(config.application.name)
      0 * _._
    }
    task.keelService = Mock(KeelService) {
      1 * deleteDeliveryConfig(config.application.name)
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.context.previousState == application
  }

  void "should ignore not found errors when deleting managed delivery data"() {
    given:
    task.front50Service = Mock(Front50Service) {
      get(config.application.name) >> new Application()
    }
    task.keelService = Mock(KeelService) {
      1 * deleteDeliveryConfig(config.application.name) >>
          new Response("http://keel", 404, "not found", [], null)
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.SUCCEEDED
  }

  void "should handle SpinnakerHttpException if the response code is 404 while fetching applications and return the task status as SUCCEEDED"() {
    given:
    task.front50Service = Mock(Front50Service) {
      get(config.application.name) >> {
        def url = "https://front50service.com/v2/applications/"+config.application.name
        Response response = new Response(url, HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.name(), [], null)
        RetrofitError httpError = RetrofitError.httpError(url, response, new JacksonConverter(), null)
        throw new SpinnakerHttpException(httpError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.SUCCEEDED

  }

  void "should catch SpinnakerHttpException if the response code is not 404 while fetching applications and return the task status as TERMINAL"() {
    given:
    task.front50Service = Mock(Front50Service) {
      get(config.application.name) >> {
        def url = "https://front50service.com/v2/applications/"+config.application.name
        Response response = new Response(url, HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.name(), [], null)
        RetrofitError httpError = RetrofitError.httpError(url, response, new JacksonConverter(), null)
        throw new SpinnakerHttpException(httpError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.TERMINAL

  }

  void "should catch SpinnakerNetworkException  and return the task status as TERMINAL"() {
    given:
    task.front50Service = Mock(Front50Service) {
      get(config.application.name) >> {
        def url = "https://front50service.com/v2/applications/"+config.application.name
        RetrofitError networkError = RetrofitError.networkError(url, new IOException("Failed to connect to the host : front50service.com"))
        throw new SpinnakerNetworkException(networkError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.TERMINAL

  }

  void "should catch SpinnakerNetworkException which occurs while deleting application  and return the task status as TERMINAL"() {
    given:
    def application = new Application()
    application.name = "testapp"
    application.email = "test@xxx.com"
    application.user = "testuser"
    task.front50Service = Mock(Front50Service) {
      1 * get(config.application.name) >> application
      1 * delete(config.application.name) >> {
        def url = "https://front50service.com/v2/applications/"+config.application.name
        RetrofitError networkError = RetrofitError.networkError(url, new IOException("Failed to connect to the host : front50service.com"))
        throw new SpinnakerNetworkException(networkError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.TERMINAL
    taskResult.context.get("notification.type") == "deleteapplication"
    taskResult.context.get("application.name") == "application"
    taskResult.context.previousState == application
  }

  void "should catch SpinnakerServerException which occurs while deleting application  and return the task status as TERMINAL"() {
    given:
    def application = new Application()
    application.name = "testapp"
    application.email = "test@xxx.com"
    application.user = "testuser"
    task.front50Service = Mock(Front50Service) {
      1 * get(config.application.name) >> application
      1 * delete(config.application.name) >> {
        def url = "https://front50service.com/v2/applications/"+config.application.name
        RetrofitError unexpectedError = RetrofitError.unexpectedError(url, new IOException("Something went wrong, please try again"))
        throw new SpinnakerServerException(unexpectedError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.TERMINAL
    taskResult.context.get("notification.type") == "deleteapplication"
    taskResult.context.get("application.name") == "application"
    taskResult.context.previousState == application
  }

  void "should catch SpinnakerHttpException which occurs while deleting application  and return the task status as TERMINAL"() {
    given:
    def application = new Application()
    application.name = "testapp"
    application.email = "test@xxx.com"
    application.user = "testuser"
    task.front50Service = Mock(Front50Service) {
      1 * get(config.application.name) >> application
      1 * delete(config.application.name) >> {
        def url = "https://front50service.com/v2/applications/"+config.application.name
        Response response = new Response(url, HttpStatus.UNAUTHORIZED.value(), HttpStatus.UNAUTHORIZED.name(), [], null)
        RetrofitError httpError = RetrofitError.httpError(url, response, new JacksonConverter(), null)
        throw new SpinnakerHttpException(httpError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.TERMINAL
    taskResult.context.get("notification.type") == "deleteapplication"
    taskResult.context.get("application.name") == "application"
    taskResult.context.previousState == application
  }

  void "should catch SpinnakerHttpException  with 404 response code which occurs while deleting application  and return the task status as SUCCEEDED"() {
    given:
    def application = new Application()
    application.name = "testapp"
    application.email = "test@xxx.com"
    application.user = "testuser"
    task.front50Service = Mock(Front50Service) {
      1 * get(config.application.name) >> application
      1 * delete(config.application.name) >> {
        def url = "https://front50service.com/v2/applications/"+config.application.name
        Response response = new Response(url, HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.name(), [], null)
        RetrofitError httpError = RetrofitError.httpError(url, response, new JacksonConverter(), null)
        throw new SpinnakerHttpException(httpError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.SUCCEEDED
    taskResult.context.get("notification.type") == "deleteapplication"
    taskResult.context.get("application.name") == "application"
  }

  void "should catch SpinnakerConversionException which occurs while deleting application  and return the task status as TERMINAL"() {
    given:
    def application = new Application()
    application.name = "testapp"
    application.email = "test@xxx.com"
    application.user = "testuser"
    task.front50Service = Mock(Front50Service) {
      1 * get(config.application.name) >> application
      1 * delete(config.application.name) >> {
        def url = "https://front50service.com/v2/applications/"+config.application.name
        Response response = new Response(url, HttpStatus.NOT_ACCEPTABLE.value(), HttpStatus.NOT_ACCEPTABLE.name(), [], null)
        RetrofitError conversionError = RetrofitError.conversionError(url, response, new JacksonConverter(), null, new ConversionException("Failed to parse the http error body"))
        throw new SpinnakerConversionException(conversionError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.TERMINAL
    taskResult.context.get("notification.type") == "deleteapplication"
    taskResult.context.get("application.name") == "application"
    taskResult.context.previousState == application
  }

  void "should catch SpinnakerHttpException  with 404 response code which occurs while deleting application permission  and return the task status as SUCCEEDED"() {
    given:
    task.front50Service = Mock(Front50Service) {
      def url = "https://front50service.com/v2/applications/"+config.application.name
      1 * get(config.application.name) >>  new Application()
      1 * delete(config.application.name) >> new Response(url, HttpStatus.NO_CONTENT.value(), HttpStatus.NO_CONTENT.name(), Collections.emptyList(), null)
      1 * deletePermission(config.application.name) >> {
        Response response = new Response(url, HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.name(), [], null)
        RetrofitError httpError = RetrofitError.httpError(url, response, new JacksonConverter(), null)
        throw new SpinnakerHttpException(httpError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.SUCCEEDED
    taskResult.context.get("notification.type") == "deleteapplication"
    taskResult.context.get("application.name") == "application"
  }

  void "should catch SpinnakerHttpException  with 400 response code which occurs while deleting application permission  and return the task status as TERMINAL"() {
    given:
    task.front50Service = Mock(Front50Service) {
      def url = "https://front50service.com/v2/applications/"+config.application.name
      1 * get(config.application.name) >>  new Application()
      1 * delete(config.application.name) >> new Response(url, HttpStatus.NO_CONTENT.value(), HttpStatus.NO_CONTENT.name(), Collections.emptyList(), null)
      1 * deletePermission(config.application.name) >> {
        Response response = new Response(url, HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.name(), [], null)
        RetrofitError httpError = RetrofitError.httpError(url, response, new JacksonConverter(), null)
        throw new SpinnakerHttpException(httpError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.TERMINAL
    taskResult.context.get("notification.type") == "deleteapplication"
    taskResult.context.get("application.name") == "application"
  }

  void "should catch SpinnakerConversionException which occurs while deleting application permission  and return the task status as TERMINAL"() {
    given:
    task.front50Service = Mock(Front50Service) {
      def url = "https://front50service.com/v2/applications/"+config.application.name
      1 * get(config.application.name) >>  new Application()
      1 * delete(config.application.name) >> new Response(url, HttpStatus.NO_CONTENT.value(), HttpStatus.NO_CONTENT.name(), Collections.emptyList(), null)
      1 * deletePermission(config.application.name) >> {
        Response response = new Response(url, HttpStatus.NOT_ACCEPTABLE.value(), HttpStatus.NOT_ACCEPTABLE.name(), [], null)
        RetrofitError conversionError = RetrofitError.conversionError(url, response, new JacksonConverter(), null, new ConversionException("Failed to parse"))
        throw new SpinnakerConversionException(conversionError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.TERMINAL
    taskResult.context.get("notification.type") == "deleteapplication"
    taskResult.context.get("application.name") == "application"
  }

  void "should catch SpinnakerNetworkException which occurs while deleting application permission  and return the task status as TERMINAL"() {
    given:
    task.front50Service = Mock(Front50Service) {
      def url = "https://front50service.com/v2/applications/"+config.application.name
      1 * get(config.application.name) >>  new Application()
      1 * delete(config.application.name) >> new Response(url, HttpStatus.NO_CONTENT.value(), HttpStatus.NO_CONTENT.name(), Collections.emptyList(), null)
      1 * deletePermission(config.application.name) >> {
        RetrofitError networkError = RetrofitError.networkError(url, new IOException("Failed to connect to the host : front50service.com"))
        throw new SpinnakerNetworkException(networkError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.TERMINAL
    taskResult.context.get("notification.type") == "deleteapplication"
    taskResult.context.get("application.name") == "application"
  }

  void "should catch SpinnakerServerException which occurs while deleting application permission  and return the task status as TERMINAL"() {
    given:
    task.front50Service = Mock(Front50Service) {
      def url = "https://front50service.com/v2/applications/"+config.application.name
      1 * get(config.application.name) >>  new Application()
      1 * delete(config.application.name) >> new Response(url, HttpStatus.NO_CONTENT.value(), HttpStatus.NO_CONTENT.name(), Collections.emptyList(), null)
      1 * deletePermission(config.application.name) >> {
        RetrofitError unexpectedError = RetrofitError.unexpectedError(url, new IOException("Something went wrong, Please try again"))
        throw new SpinnakerServerException(unexpectedError)
      }
    }

    when:
    def taskResult = task.execute(pipeline.stages.first())

    then:
    taskResult.status == ExecutionStatus.TERMINAL
    taskResult.context.get("notification.type") == "deleteapplication"
    taskResult.context.get("application.name") == "application"
  }
}
