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

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigList;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import okhttp3.*;
import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.arquillian.smart.testing.rules.git.GitCloner;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.jboss.shrinkwrap.resolver.api.maven.embedded.EmbeddedMaven;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Core {

    protected final String fabric8PluginGroupId = "io.fabric8";

    protected final String fabric8MavenPluginKey = "io.fabric8:fabric8-maven-plugin";

    protected final String fabric8PluginArtifactId = "fabric8-maven-plugin";

    protected String testsuiteNamespace;

    protected String testsuiteRepositoryArtifactId;

    protected final String fmpConfigurationFile = "/fmp-plugin-config.xml";

    protected OpenShiftClient openShiftClient;

    protected final static Logger logger = LoggerFactory.getLogger(Core.class);

    private GitCloner gitCloner;

    protected enum HttpRequestType {
        GET("GET"), POST("POST"), PUT("PUT"), DELETE("DELETE");

        private String httpRequestType;

        HttpRequestType(String aHttpRequestType) {
            this.httpRequestType = aHttpRequestType;
        }

        protected String getValue() {
            return httpRequestType;
        }
    }

    private Repository cloneRepositoryUsingHttp(String repositoryUrl) throws IOException, GitAPIException {
        gitCloner = new GitCloner(repositoryUrl);
        return gitCloner.cloneRepositoryToTempFolder();
    }

    private void modifyPomFileToProjectVersion(Repository aRepository, String relativePomPath) throws IOException, XmlPullParserException {
        /**
         * Read Maven model from the project pom file(Here the pom file is not the test repository is cloned
         * for the test suite. It refers to the rt/ project and fetches the current version of fabric8-maven-plugin
         * (any SNAPSHOT version) and updates that accordingly in the sample cloned project's pom.
         */
        File clonedRepositoryPomFile = new File(aRepository.getWorkTree().getAbsolutePath(), relativePomPath);
        String fmpCurrentVersion = readPomModelFromFile(new File("pom.xml")).getVersion();
        Model model = readPomModelFromFile(clonedRepositoryPomFile);
        testsuiteRepositoryArtifactId = model.getArtifactId();

        Map<String, Plugin> aStringToPluginMap = model.getBuild().getPluginsAsMap();
        List<Profile> profiles = model.getProfiles();
        if (aStringToPluginMap.get(fabric8PluginGroupId + ":" + fabric8PluginArtifactId) != null) {
            aStringToPluginMap.get(fabric8PluginGroupId + ":" + fabric8PluginArtifactId).setVersion(fmpCurrentVersion);
        } else {
            for (Profile profile : profiles) {
                if (profile.getBuild() != null && profile.getBuild().getPluginsAsMap().get(fabric8PluginGroupId + ":" + fabric8PluginArtifactId) != null) {
                    profile.getBuild().getPluginsAsMap()
                            .get(fabric8PluginGroupId + ":" + fabric8PluginArtifactId)
                            .setVersion(fmpCurrentVersion);
                }
            }
        }

        // Write back the updated model to the pom file
        writePomModelToFile(clonedRepositoryPomFile, model);
    }

    protected static Model readPomModelFromFile(File aFileObj) throws XmlPullParserException, IOException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        return reader.read(new FileInputStream(aFileObj));
    }

    protected void writePomModelToFile(File aFileObj, Model model) throws IOException {
        MavenXpp3Writer writer = new MavenXpp3Writer();
        writer.write(new FileOutputStream(aFileObj), model);
    }

    protected void updateSourceCode(Repository repository, String relativePomPath) throws Exception {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        String baseDir = repository.getWorkTree().getAbsolutePath();
        Model model = reader.read(new FileInputStream(new File(baseDir, relativePomPath)));

        Dependency dependency = new Dependency();
        dependency.setGroupId("org.apache.commons");
        dependency.setArtifactId("commons-lang3");
        dependency.setVersion("3.5");
        model.getDependencies().add(dependency);

        MavenXpp3Writer writer = new MavenXpp3Writer();
        writer.write(new FileOutputStream(new File(baseDir, relativePomPath)), model);
        model.getArtifactId();
    }

    protected Repository setupSampleTestRepository(String repositoryUrl, String relativePomPath) throws IOException, GitAPIException, XmlPullParserException {
        openShiftClient = new DefaultOpenShiftClient(new ConfigBuilder().build());
        testsuiteNamespace = openShiftClient.getNamespace();
        Repository repository = cloneRepositoryUsingHttp(repositoryUrl);
        modifyPomFileToProjectVersion(repository, relativePomPath);
        return repository;
    }

    protected void runEmbeddedMavenBuild(Repository sampleRepository, String goals, String profiles) {
        String baseDir = sampleRepository.getWorkTree().getAbsolutePath();
        EmbeddedMaven.forProject(baseDir + "/pom.xml")
                .setGoals(goals)
                .setProfiles(profiles)
                .build();
    }

    @AfterClass
    protected void cleanSampleTestRepository() throws IOException {
        gitCloner.removeClone();
        openShiftClient.close();
    }

    protected Route getApplicationRouteWithName(String name) {
        RouteList aRouteList = openShiftClient.routes().inNamespace(testsuiteNamespace).list();
        for (Route aRoute : aRouteList.getItems()) {
            if (aRoute.getMetadata().getName().equals(name)) {
                return aRoute;
            }
        }
        return null;
    }

    /**
     * Just makes a basic GET request to the url provided as parameter and returns Response.
     *
     * @param hostUrl
     * @return
     * @throws Exception
     */
    protected Response makeHttpRequest(HttpRequestType requestType, String hostUrl, String params) throws IOException {
        OkHttpClient okHttpClient = new OkHttpClient();
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        params = (params == null ? new JSONObject().toString() : params);
        Request request = null;
        RequestBody requestBody = RequestBody.create(JSON, params);

        switch (requestType.getValue()) {
            case "GET":
                request = new Request.Builder().url(hostUrl).get().build();
                break;
            case "POST":
                request = new Request.Builder().url(hostUrl).post(requestBody).build();
                break;
            case "PUT":
                request = new Request.Builder().url(hostUrl).put(requestBody).build();
                break;
            case "DELETE":
                request = new Request.Builder().url(hostUrl).delete(requestBody).build();
                break;
            default:
                logger.error("No valid Http request type specified, using GET instread.");
                request = new Request.Builder().url(hostUrl).get().build();
        }
        Response response = okHttpClient.newCall(request).execute();
        logger.info("[" + requestType.getValue() + "] " + hostUrl + " " + HttpStatus.getCode(response.code()));

        return response;
    }

    protected int exec(String command) throws IOException, InterruptedException {
        Process child = Runtime.getRuntime().exec(command);
        child.waitFor();

        BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream()));
        String line = null;
        while ((line = reader.readLine()) != null) {
            logger.info(line);
        }
        return child.exitValue();
    }

    /**
     * Appends some annotation properties to the fmp's configuration in test repository's pom
     * just to distinguish whether the application is re-deployed or not.
     *
     * @param testRepository
     * @throws Exception
     */
    protected void addRedeploymentAnnotations(Repository testRepository, String relativePomPath, String annotationKey, String annotationValue, String fmpConfigFragmentFile) throws IOException, XmlPullParserException {
        File pomFile = new File(testRepository.getWorkTree().getAbsolutePath(), relativePomPath);
        Model model = readPomModelFromFile(pomFile);

        File pomFragment = new File(getClass().getResource(fmpConfigFragmentFile).getFile());
        String pomFragmentStr = String.format(FileUtils.readFileToString(pomFragment), annotationKey, annotationValue, annotationKey, annotationValue);

        Xpp3Dom configurationDom = Xpp3DomBuilder.build(
                new ByteArrayInputStream(pomFragmentStr.getBytes()),
                "UTF-8");

        int nOpenShiftProfile = getOpenshiftProfileIndex(model);
        model.getProfiles().get(nOpenShiftProfile).getBuild().getPluginsAsMap().get(fabric8MavenPluginKey).setConfiguration(configurationDom);
        writePomModelToFile(pomFile, model);
    }

    protected int getOpenshiftProfileIndex(Model aPomModel) {
        List<Profile> profiles = aPomModel.getProfiles();
        for (int nIndex = 0; nIndex < profiles.size(); nIndex++) {
            if (profiles.get(nIndex).getId().equals("openshift"))
                return nIndex;
        }
        throw new AssertionError("No openshift profile found in project's pom.xml");
    }

    /**
     * A method to check Re-deployment scenario. We append some annotations in all the resources and
     * check that we have those after deployment for 2nd time. This is basically to distinguish
     * deployment's versions.
     *
     * @param key
     * @return
     */
    protected boolean checkDeploymentsForAnnotation(String key) {
        DeploymentConfigList deploymentConfigs = openShiftClient.deploymentConfigs().inNamespace(testsuiteNamespace).list();
        for (DeploymentConfig aDeploymentConfig : deploymentConfigs.getItems()) {
            if (aDeploymentConfig.getMetadata() != null && aDeploymentConfig.getMetadata().getAnnotations() != null &&
                    aDeploymentConfig.getMetadata().getAnnotations().containsKey(key))
                return true;
        }
        return false;
    }

    /**
     * It watches over application pod until it becomes ready to serve.
     *
     * @throws Exception
     */
    protected void waitTillApplicationPodStarts() throws InterruptedException {
        logger.info("Waiting to application pod .... ");

        int nPolls = 0;
        // Keep polling till 5 minutes
        while (nPolls < 60) {
            PodList podList = openShiftClient.pods().withLabel("app", testsuiteRepositoryArtifactId).list();
            for (Pod pod : podList.getItems()) {
                logger.info("waitTillApplicationPodStarts() -> Pod : " + pod.getMetadata().getName() + ", isReady : " + KubernetesHelper.isPodReady(pod));
                if (KubernetesHelper.isPodReady(pod)) {
                    logger.info("OK ✓ ... Pod wait over.");
                    return;
                }
            }
            TimeUnit.SECONDS.sleep(5);
            nPolls++;
        }
        throw new AssertionError("Pod wait timeout! Could not find application pod for " + testsuiteRepositoryArtifactId);
    }

    /**
     * This variation is used in order to check for the redeployment scenario, since some
     * annotations are added while making changes in source code, and those are checked so
     * that we are able to differentiate between the redeployed pod and the previously
     * existing pod instance from previous deployment.
     *
     * @param key
     * @param value
     * @throws Exception
     */
    protected void waitTillApplicationPodStarts(String key, String value) throws InterruptedException {
        logger.info("Waiting for application pod .... ");

        int nPolls = 0;
        // Keep polling till 5 minutes
        while (nPolls < 60) {
            PodList podList = openShiftClient.pods().withLabel("app", testsuiteRepositoryArtifactId).list();
            for (Pod pod : podList.getItems()) {
                logger.info("waitTillApplicationPodStarts(" + key + ", " + value + ") -> Pod : "
                        + pod.getMetadata().getName() + ", STATUS : " + KubernetesHelper.getPodStatus(pod) + ", isPodReady : " + KubernetesHelper.isPodReady(pod));

                if (pod.getMetadata().getAnnotations().containsKey(key)) {
                    logger.info(pod.getMetadata().getName() + " is redeployed pod.");
                }
                if (pod.getMetadata().getAnnotations().containsKey(key)
                        && pod.getMetadata().getAnnotations().get(key).equalsIgnoreCase(value)
                        && KubernetesHelper.isPodReady(pod)) {
                    logger.info("OK ✓ ... Pod wait over.");
                    return;
                }
            }
            nPolls++;
            TimeUnit.SECONDS.sleep(5);
        }
        throw new AssertionError("Pod wait timeout! Could not find application pod for " + testsuiteRepositoryArtifactId);
    }

    protected void createViewRoleToServiceAccount() throws IOException, InterruptedException {
        exec("oc policy add-role-to-user view -z default");
    }

    protected void createOrReplaceConfigMap(String name, Map<String, String> data) {
        openShiftClient.configMaps()
                .inNamespace(testsuiteNamespace)
                .withName(name)
                .edit()
                .withData(data)
                .done();
    }

    protected void createConfigMapResource(String name, Map<String, String> data) {
        if (openShiftClient.configMaps().inNamespace(testsuiteNamespace).withName(name).get() == null) {
            openShiftClient.configMaps()
                    .inNamespace(testsuiteNamespace)
                    .createNew()
                    .withNewMetadata()
                    .withName(name)
                    .endMetadata()
                    .withData(data)
                    .done();
        } else
            createOrReplaceConfigMap(name, data);
    }
}
