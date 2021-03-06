/**
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.maven.plugin.mojo.develop;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Set;

import org.eclipse.jkube.generator.api.GeneratorContext;
import org.eclipse.jkube.generator.api.GeneratorMode;
import org.eclipse.jkube.kit.build.service.docker.BuildService;
import org.eclipse.jkube.kit.build.service.docker.ImageConfiguration;
import org.eclipse.jkube.kit.build.service.docker.ServiceHub;
import org.eclipse.jkube.kit.build.service.docker.WatchService;
import org.eclipse.jkube.kit.common.KitLogger;
import org.eclipse.jkube.kit.common.util.AnsiLogger;
import org.eclipse.jkube.kit.common.util.MavenUtil;
import org.eclipse.jkube.kit.common.util.OpenshiftHelper;
import org.eclipse.jkube.kit.common.util.ResourceUtil;
import org.eclipse.jkube.kit.config.access.ClusterAccess;
import org.eclipse.jkube.kit.config.access.ClusterConfiguration;
import org.eclipse.jkube.kit.config.resource.ProcessorConfig;
import org.eclipse.jkube.kit.config.resource.RuntimeMode;
import org.eclipse.jkube.kit.config.service.JKubeServiceHub;
import org.eclipse.jkube.kit.profile.ProfileUtil;
import org.eclipse.jkube.maven.enricher.api.util.KubernetesResourceUtil;
import org.eclipse.jkube.maven.plugin.generator.GeneratorManager;
import org.eclipse.jkube.maven.plugin.mojo.build.AbstractDockerMojo;
import org.eclipse.jkube.maven.plugin.watcher.WatcherManager;
import org.eclipse.jkube.watcher.api.WatcherContext;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import static org.eclipse.jkube.maven.plugin.mojo.build.ApplyMojo.DEFAULT_KUBERNETES_MANIFEST;
import static org.eclipse.jkube.maven.plugin.mojo.build.ApplyMojo.DEFAULT_OPENSHIFT_MANIFEST;


// TODO: Similar to the DebugMojo the WatchMojo should scale down any deployment to 1 replica (or ensure that its running only with one replica)
// The WatchEnricher has been removed since the enrichment shouldn't know anything about the mode running and should
// always create the same resources

/**
 * Used to automatically rebuild Docker images and restart containers in case of updates.
 */
@Mojo(name = "watch", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE)
@Execute(goal = "deploy")
public class WatchMojo extends AbstractDockerMojo {

    /**
     * The generated kubernetes YAML file
     */
    @Parameter(property = "jkube.kubernetesManifest", defaultValue = DEFAULT_KUBERNETES_MANIFEST)
    private File kubernetesManifest;
    /**
     * The generated openshift YAML file
     */
    @Parameter(property = "jkube.openshiftManifest", defaultValue = DEFAULT_OPENSHIFT_MANIFEST)
    private File openshiftManifest;

    /**
     * Watcher specific options. This is a generic prefix where the keys have the form
     * <code>&lt;watcher-prefix&gt;-&lt;option&gt;</code>.
     */
    @Parameter
    private ProcessorConfig watcher;

    private KubernetesClient kubernetes;
    private ServiceHub hub;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            return;
        }

        log = new AnsiLogger(getLog(), useColor, verbose, !settings.getInteractiveMode(), getLogPrefix());
        clusterAccess = new ClusterAccess(getClusterConfiguration());
        kubernetes = clusterAccess.createDefaultClient(log);

        if(clusterAccess.resolveRuntimeMode(mode, log).equals(RuntimeMode.kubernetes)) {
            super.execute();
        } else {
            executeInternal(null);
        }
    }

    @Override
    protected ClusterConfiguration getClusterConfiguration() {
        if(access == null) {
            access = new ClusterConfiguration.Builder().build();
        }
        final ClusterConfiguration.Builder clusterConfigurationBuilder = new ClusterConfiguration.Builder(access);

        return clusterConfigurationBuilder.from(System.getProperties())
            .from(project.getProperties()).build();
    }

    @Override
    protected synchronized void executeInternal(ServiceHub hub) throws MojoExecutionException {
        if(hub != null) {
            this.hub = hub;
        }

        URL masterUrl = kubernetes.getMasterUrl();
        KubernetesResourceUtil.validateKubernetesMasterUrl(masterUrl);

        File manifest;
        boolean isOpenshift = OpenshiftHelper.isOpenShift(kubernetes);
        if (isOpenshift) {
            manifest = openshiftManifest;
        } else {
            manifest = kubernetesManifest;
        }

        try {
            Set<HasMetadata> resources = KubernetesResourceUtil.loadResources(manifest);
            WatcherContext context = getWatcherContext();

            WatcherManager.watch(getResolvedImages(), resources, context);

        } catch (KubernetesClientException ex) {
            KubernetesResourceUtil.handleKubernetesClientException(ex, this.log);
        } catch (Exception ex) {
            throw new MojoExecutionException("An error has occurred while while trying to watch the resources", ex);
        }

    }

    public WatcherContext getWatcherContext() throws MojoExecutionException {
        try {
            BuildService.BuildContext buildContext = getBuildContext();
            WatchService.WatchContext watchContext = hub != null ? getWatchContext(hub) : null;

            return new WatcherContext.Builder()
                    .serviceHub(hub)
                    .buildContext(buildContext)
                    .watchContext(watchContext)
                    .config(extractWatcherConfig())
                    .logger(log)
                    .newPodLogger(createLogger("[[C]][NEW][[C]] "))
                    .oldPodLogger(createLogger("[[R]][OLD][[R]] "))
                    .mode(mode)
                    .project(MavenUtil.convertMavenProjectToJKubeProject(project, session))
                    .useProjectClasspath(useProjectClasspath)
                    .clusterConfiguration(getClusterConfiguration())
                    .kubernetesClient(kubernetes)
                    .fabric8ServiceHub(getJKubeServiceHub())
                    .build();
        } catch (IOException exception) {
            throw new MojoExecutionException(exception.getMessage());
        } catch (DependencyResolutionRequiredException dependencyException) {
            throw new MojoExecutionException("Instructed to use project classpath, but cannot. Continuing build if we can: " + dependencyException.getMessage());
        }
    }

    @Override
    protected JKubeServiceHub getJKubeServiceHub() throws DependencyResolutionRequiredException {
        return new JKubeServiceHub.Builder()
                .log(log)
                .clusterAccess(clusterAccess)
                .dockerServiceHub(hub)
                .platformMode(mode)
                .jkubeProject(MavenUtil.convertMavenProjectToJKubeProject(project, session))
                .build();
    }

    @Override
    public List<ImageConfiguration> customizeConfig(List<ImageConfiguration> configs) {
        try {
            JKubeServiceHub serviceHub = getJKubeServiceHub();
            GeneratorContext ctx = new GeneratorContext.Builder()
                    .config(extractGeneratorConfig())
                    .project(MavenUtil.convertMavenProjectToJKubeProject(project, session))
                    .logger(log)
                    .runtimeMode(mode)
                    .strategy(buildStrategy)
                    .useProjectClasspath(useProjectClasspath)
                    .artifactResolver(serviceHub.getArtifactResolverService())
                    .generatorMode(GeneratorMode.WATCH)
                    .build();
            return GeneratorManager.generate(configs, ctx, false);
        } catch (DependencyResolutionRequiredException de) {
            throw new IllegalArgumentException("Instructed to use project classpath, but cannot. Continuing build if we can: ", de);
        }
    }

    // Get watcher config
    private ProcessorConfig extractWatcherConfig() {
        try {
            return ProfileUtil.blendProfileWithConfiguration(ProfileUtil.WATCHER_CONFIG, profile, ResourceUtil.getFinalResourceDir(resourceDir, environment), watcher);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot extract watcher config: " + e, e);
        }
    }

    protected KitLogger createLogger(String prefix) {
        return new AnsiLogger(getLog(), useColor, verbose, !settings.getInteractiveMode(), "k8s:" + prefix);
    }

    @Override
    protected String getLogPrefix() {
        return "k8s: ";
    }

    protected WatchService.WatchContext getWatchContext(ServiceHub hub) throws IOException, DependencyResolutionRequiredException {
        return new WatchService.WatchContext.Builder()
                .watchInterval(watchInterval)
                .watchMode(watchMode)
                .watchPostGoal(watchPostGoal)
                .watchPostExec(watchPostExec)
                .autoCreateCustomNetworks(autoCreateCustomNetworks)
                .keepContainer(keepContainer)
                .keepRunning(keepRunning)
                .removeVolumes(removeVolumes)
                .containerNamePattern(containerNamePattern)
                .buildTimestamp(getBuildTimestamp())
                .pomLabel(getGavLabel())
                .mojoParameters(createMojoParameters())
                .follow(follow())
                .showLogs(showLogs())
                .serviceHubFactory(serviceHubFactory)
                .hub(hub)
                .dispatcher(getLogDispatcher(hub))
                .build();
    }

}
