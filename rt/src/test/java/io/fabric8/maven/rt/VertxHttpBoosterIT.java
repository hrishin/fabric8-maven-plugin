/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.rt;

import io.fabric8.openshift.api.model.Route;
import okhttp3.Response;
import org.eclipse.jgit.lib.Repository;
import org.json.JSONObject;
import org.testng.annotations.Test;

import java.io.File;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;

public class VertxHttpBoosterIT extends Core {
    public static final String SPRING_BOOT_HTTP_BOOSTER_GIT = "https://github.com/openshiftio-vertx-boosters/vertx-http-booster.git";

    public final String EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL = "fabric8:deploy", EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE = "openshift";

    private final String RELATIVE_POM_PATH = "/pom.xml";

    private final String testEndpoint = "/api/greeting";

    public static final String ANNOTATION_KEY = "vertx-testKey", ANNOTATION_VALUE = "vertx-testValue";

    public final String FMP_CONFIGURATION_FILE = "/fmp-plugin-config.xml";

    @Test
    public void deploy_springboot_app_once() throws Exception {
        Repository testRepository = setupSampleTestRepository(SPRING_BOOT_HTTP_BOOSTER_GIT, RELATIVE_POM_PATH);

        deploy(testRepository, EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
        waitTillApplicationPodStarts();

        assertDeployment(TESTSUITE_REPOSITORY_ARTIFACT_ID);
    }

    @Test
    public void redeploy_springboot_app() throws Exception {
        Repository testRepository = setupSampleTestRepository(SPRING_BOOT_HTTP_BOOSTER_GIT, RELATIVE_POM_PATH);

        // change the source code
        updateSourceCode(testRepository, RELATIVE_POM_PATH);
        addRedeploymentAnnotations(testRepository, RELATIVE_POM_PATH, ANNOTATION_KEY, ANNOTATION_VALUE, FMP_CONFIGURATION_FILE);
        deploy(testRepository, EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
        waitTillApplicationPodStarts(ANNOTATION_KEY, ANNOTATION_VALUE);

        assertDeployment(TESTSUITE_REPOSITORY_ARTIFACT_ID);
        assert checkDeploymentsForAnnotation(ANNOTATION_KEY);
    }

    public void deploy(Repository testRepository, String buildGoal, String buildProfile) throws Exception {
        String sampleProjectArtifactId = readPomModelFromFile(new File(testRepository.getWorkTree().getAbsolutePath(), RELATIVE_POM_PATH)).getArtifactId();
        runEmbeddedMavenBuild(testRepository, buildGoal, buildProfile);
    }

    private void assertDeployment(String sampleProjectArtifactId) throws Exception {
        assertThat(openShiftClient).deployment(sampleProjectArtifactId);
        assertThat(openShiftClient).service(sampleProjectArtifactId);

        RouteAssert.assertRoute(openShiftClient, sampleProjectArtifactId);
        assertThatWeServeAsExpected(getApplicationRouteWithName(TESTSUITE_REPOSITORY_ARTIFACT_ID));
    }

    private void assertThatWeServeAsExpected(Route applicationRoute) throws Exception {
        String hostUrl = "http://" + applicationRoute.getSpec().getHost() + testEndpoint;

        Response readResponse = makeHttpRequest(HttpRequestType.GET, hostUrl, null);
        assert new JSONObject(readResponse.body().string()).getString("content").equals("Hello, World!");

        // let's change default greeting message
        hostUrl += "?name=vertx";

        readResponse = makeHttpRequest(HttpRequestType.GET, hostUrl, null);
        assert new JSONObject(readResponse.body().string()).getString("content").equals("Hello, vertx!");
    }
}
