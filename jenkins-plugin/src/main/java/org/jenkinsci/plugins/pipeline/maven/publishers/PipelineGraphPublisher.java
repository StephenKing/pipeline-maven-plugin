package org.jenkinsci.plugins.pipeline.maven.publishers;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.pipeline.maven.GlobalPipelineMavenConfig;
import org.jenkinsci.plugins.pipeline.maven.MavenPublisher;
import org.jenkinsci.plugins.pipeline.maven.MavenSpyLogProcessor;
import org.jenkinsci.plugins.pipeline.maven.dao.PipelineMavenPluginDao;
import org.jenkinsci.plugins.pipeline.maven.util.XmlUtils;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

/**
 * Fingerprint the dependencies of the maven project.
 *
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
public class PipelineGraphPublisher extends MavenPublisher {
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(PipelineGraphPublisher.class.getName());

    private boolean includeSnapshotVersions = true;

    private boolean includeReleaseVersions;

    private boolean includeScopeCompile = true;

    private boolean includeScopeRuntime = true;

    private boolean includeScopeTest;

    private boolean includeScopeProvided = true;

    private boolean skipDownstreamTriggers;

    private boolean ignoreUpstreamTriggers;

    @DataBoundConstructor
    public PipelineGraphPublisher() {
        super();
    }

    protected Set<String> getIncludedScopes() {
        Set<String> includedScopes = new TreeSet<>();
        if (includeScopeCompile)
            includedScopes.add("compile");
        if (includeScopeRuntime)
            includedScopes.add("runtime");
        if (includeScopeProvided)
            includedScopes.add("provided");
        if (includeScopeTest)
            includedScopes.add("test");
        return includedScopes;
    }

    @Override
    public void process(@Nonnull StepContext context, @Nonnull Element mavenSpyLogsElt) throws IOException, InterruptedException {
        Run run = context.get(Run.class);
        TaskListener listener = context.get(TaskListener.class);

        PipelineMavenPluginDao dao = GlobalPipelineMavenConfig.get().getDao();

        recordDependencies(mavenSpyLogsElt, run, listener, dao);
        recordGeneratedArtifacts(mavenSpyLogsElt,run,listener, dao);
    }

    protected void recordDependencies(@Nonnull Element mavenSpyLogsElt, @Nonnull Run run, @Nonnull TaskListener listener, @Nonnull PipelineMavenPluginDao dao) {
        List<MavenSpyLogProcessor.MavenDependency> dependencies = listDependencies(mavenSpyLogsElt);

        if (LOGGER.isLoggable(Level.FINE)) {
            listener.getLogger().println("[withMaven] pipelineGraphPublisher - recordDependencies - filter: " +
                    "versions[snapshot: " + isIncludeSnapshotVersions() + ", release: " + isIncludeReleaseVersions() + "], " +
                    "scopes:" + getIncludedScopes());
        }

        for (MavenSpyLogProcessor.MavenDependency dependency : dependencies) {
            if (dependency.snapshot) {
                if (!includeSnapshotVersions) {
                    if (LOGGER.isLoggable(Level.FINER)) {
                        listener.getLogger().println("[withMaven] pipelineGraphPublisher - Skip recording snapshot dependency: " + dependency.getId());
                    }
                    continue;
                }
            } else {
                if (!includeReleaseVersions) {
                    if (LOGGER.isLoggable(Level.FINER)) {
                        listener.getLogger().println("[withMaven] pipelineGraphPublisher - Skip recording release dependency: " + dependency.getId());
                    }
                    continue;
                }
            }
            if (!getIncludedScopes().contains(dependency.getScope())) {
                if (LOGGER.isLoggable(Level.FINER)) {
                    listener.getLogger().println("[withMaven] pipelineGraphPublisher - Skip recording dependency with ignored scope: " + dependency.getId());
                }
                continue;
            }

            try {
                if (LOGGER.isLoggable(Level.FINE)) {
                    listener.getLogger().println("[withMaven] pipelineGraphPublisher - Record dependency: " + dependency.getId() + ", ignoreUpstreamTriggers: " + ignoreUpstreamTriggers);
                }

                dao.recordDependency(run.getParent().getFullName(), run.getNumber(),
                        dependency.groupId, dependency.artifactId, dependency.baseVersion, dependency.type, dependency.getScope(),
                        this.ignoreUpstreamTriggers);

            } catch (RuntimeException e) {
                listener.error("[withMaven] pipelineGraphPublisher - WARNING: Exception recording " + dependency.getId() + " on build, skip");
                e.printStackTrace(listener.getLogger());
                listener.getLogger().flush();
            }
        }
    }

    protected void recordGeneratedArtifacts(@Nonnull Element mavenSpyLogsElt, @Nonnull Run run, @Nonnull TaskListener listener, @Nonnull PipelineMavenPluginDao dao) {
        if (LOGGER.isLoggable(Level.FINE)) {
            listener.getLogger().println("[withMaven] pipelineGraphPublisher - recordGeneratedArtifacts...");
        }
        List<MavenSpyLogProcessor.MavenArtifact> generatedArtifacts = listArtifacts(mavenSpyLogsElt);
        for(MavenSpyLogProcessor.MavenArtifact artifact: generatedArtifacts) {

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Build {0}#{1} - record generated {2}:{3}, version:{4}, skipDownstreamTriggers:{5}",
                        new Object[]{run.getParent().getFullName(), run.getNumber(), artifact.getId(), artifact.type, artifact.version, skipDownstreamTriggers});
                listener.getLogger().println("[withMaven] pipelineGraphPublisher - Record generated artifact: " + artifact.getId() + ", version: " + artifact.version + ", skipDownstreamTriggers: " + skipDownstreamTriggers +
                        ", file: " + artifact.file);
            }
            dao.recordGeneratedArtifact(run.getParent().getFullName(), run.getNumber(),
                    artifact.groupId, artifact.artifactId, artifact.version, artifact.type, artifact.baseVersion,
                    this.skipDownstreamTriggers);
        }
    }

    /**
     * @param mavenSpyLogs Root XML element
     * @return list of {@link MavenSpyLogProcessor.MavenArtifact}
     */
    /*
    <artifact artifactId="demo-pom" groupId="com.example" id="com.example:demo-pom:pom:0.0.1-SNAPSHOT" type="pom" version="0.0.1-SNAPSHOT">
      <file/>
    </artifact>
     */
    @Nonnull
    public List<MavenSpyLogProcessor.MavenArtifact> listArtifacts(Element mavenSpyLogs) {

        List<MavenSpyLogProcessor.MavenArtifact> result = new ArrayList<>();

        for (Element projectSucceededElt : XmlUtils.getExecutionEvents(mavenSpyLogs, "ProjectSucceeded")) {

            Element projectElt = XmlUtils.getUniqueChildElement(projectSucceededElt, "project");
            MavenSpyLogProcessor.MavenArtifact projectArtifact = XmlUtils.newMavenArtifact(projectElt);

            MavenSpyLogProcessor.MavenArtifact pomArtifact = new MavenSpyLogProcessor.MavenArtifact();
            pomArtifact.groupId = projectArtifact.groupId;
            pomArtifact.artifactId = projectArtifact.artifactId;
            pomArtifact.baseVersion = projectArtifact.baseVersion;
            pomArtifact.version = projectArtifact.version;
            pomArtifact.snapshot = projectArtifact.snapshot;
            pomArtifact.type = "pom";
            pomArtifact.extension = "pom";
            pomArtifact.file = projectElt.getAttribute("file");

            result.add(pomArtifact);

            Element artifactElt = XmlUtils.getUniqueChildElement(projectSucceededElt, "artifact");
            MavenSpyLogProcessor.MavenArtifact mavenArtifact = XmlUtils.newMavenArtifact(artifactElt);
            if ("pom".equals(mavenArtifact.type)) {
                // NO file is generated by Maven for pom projects, skip
                continue;
            }

            Element fileElt = XmlUtils.getUniqueChildElementOrNull(artifactElt, "file");
            if (fileElt == null || fileElt.getTextContent() == null || fileElt.getTextContent().isEmpty()) {
                if (LOGGER.isLoggable(Level.FINER)) {
                    LOGGER.log(Level.FINE, "listArtifacts: Project " + projectArtifact + ":  no associated file found for " +
                            mavenArtifact + " in " + XmlUtils.toString(artifactElt));
                }
            } else {
                mavenArtifact.file = StringUtils.trim(fileElt.getTextContent());
            }
            result.add(mavenArtifact);
        }

        return result;
    }


    @Override
    public String toString() {
        return getClass().getName() + "[" +
                "disabled=" + isDisabled() + ", " +
                "scopes=" + getIncludedScopes() + ", " +
                "versions={snapshot:" + isIncludeSnapshotVersions() + ", release:" + isIncludeReleaseVersions() + "}" +
                ']';
    }
    /**
     * @param mavenSpyLogs Root XML element
     * @return list of {@link MavenSpyLogProcessor.MavenArtifact}
     */
    @Nonnull
    public List<MavenSpyLogProcessor.MavenDependency> listDependencies(Element mavenSpyLogs) {

        List<MavenSpyLogProcessor.MavenDependency> result = new ArrayList<>();

        for (Element dependencyResolutionResult : XmlUtils.getChildrenElements(mavenSpyLogs, "DependencyResolutionResult")) {
            Element resolvedDependenciesElt = XmlUtils.getUniqueChildElementOrNull(dependencyResolutionResult, "resolvedDependencies");

            if (resolvedDependenciesElt == null) {
                continue;
            }

            for (Element dependencyElt : XmlUtils.getChildrenElements(resolvedDependenciesElt, "dependency")) {
                MavenSpyLogProcessor.MavenDependency dependencyArtifact = XmlUtils.newMavenDependency(dependencyElt);

                Element fileElt = XmlUtils.getUniqueChildElementOrNull(dependencyElt, "file");
                if (fileElt == null || fileElt.getTextContent() == null || fileElt.getTextContent().isEmpty()) {
                    LOGGER.log(Level.WARNING, "listDependencies: no associated file found for " + dependencyArtifact + " in " + XmlUtils.toString(dependencyElt));
                } else {
                    dependencyArtifact.file = StringUtils.trim(fileElt.getTextContent());
                }

                result.add(dependencyArtifact);
            }
        }

        return result;
    }

    public boolean isIncludeSnapshotVersions() {
        return includeSnapshotVersions;
    }

    @DataBoundSetter
    public void setIncludeSnapshotVersions(boolean includeSnapshotVersions) {
        this.includeSnapshotVersions = includeSnapshotVersions;
    }

    public boolean isIncludeReleaseVersions() {
        return includeReleaseVersions;
    }

    @DataBoundSetter
    public void setIncludeReleaseVersions(boolean includeReleaseVersions) {
        this.includeReleaseVersions = includeReleaseVersions;
    }

    public boolean isIncludeScopeCompile() {
        return includeScopeCompile;
    }

    @DataBoundSetter
    public void setIncludeScopeCompile(boolean includeScopeCompile) {
        this.includeScopeCompile = includeScopeCompile;
    }

    public boolean isIncludeScopeRuntime() {
        return includeScopeRuntime;
    }

    @DataBoundSetter
    public void setIncludeScopeRuntime(boolean includeScopeRuntime) {
        this.includeScopeRuntime = includeScopeRuntime;
    }

    public boolean isIncludeScopeTest() {
        return includeScopeTest;
    }

    @DataBoundSetter
    public void setIncludeScopeTest(boolean includeScopeTest) {
        this.includeScopeTest = includeScopeTest;
    }

    public boolean isIncludeScopeProvided() {
        return includeScopeProvided;
    }

    @DataBoundSetter
    public void setIncludeScopeProvided(boolean includeScopeProvided) {
        this.includeScopeProvided = includeScopeProvided;
    }

    public boolean isSkipDownstreamTriggers() {
        return skipDownstreamTriggers;
    }

    @DataBoundSetter
    public void setSkipDownstreamTriggers(boolean skipDownstreamTriggers) {
        this.skipDownstreamTriggers = skipDownstreamTriggers;
    }

    public boolean isIgnoreUpstreamTriggers() {
        return ignoreUpstreamTriggers;
    }

    @DataBoundSetter
    public void setIgnoreUpstreamTriggers(boolean ignoreUpstreamTriggers) {
        this.ignoreUpstreamTriggers = ignoreUpstreamTriggers;
    }

    @Symbol("pipelineGraphPublisher")
    @Extension
    public static class DescriptorImpl extends MavenPublisher.DescriptorImpl {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Pipeline Graph Publisher";
        }

        @Override
        public int ordinal() {
            return 20;
        }

        @Nonnull
        @Override
        public String getSkipFileName() {
            return ".skip-pipeline-graph";
        }
    }
}
