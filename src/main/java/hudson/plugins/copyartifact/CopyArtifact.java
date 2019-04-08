/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Sun Microsystems, Inc., Alan Harder
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.copyartifact;

import com.thoughtworks.xstream.converters.UnmarshallingContext;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.console.HyperlinkNote;
import hudson.diagnosis.OldDataMonitor;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.*;
import hudson.model.listeners.ItemListener;
import hudson.remoting.VirtualChannel;
import hudson.security.ACL;
import hudson.security.SecurityRealm;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Fingerprinter;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.VariableResolver;
import hudson.util.XStream2;
import io.jenkins.plugins.httpclient.RobustHTTPClient;
import java.io.File;
import java.io.FileOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;

import jenkins.model.Jenkins;

import jenkins.tasks.SimpleBuildStep;
import org.acegisecurity.Authentication;
import org.acegisecurity.GrantedAuthority;
import org.acegisecurity.providers.UsernamePasswordAuthenticationToken;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jenkins.MasterToSlaveFileCallable;
import jenkins.util.VirtualFile;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.HttpGet;

/**
 * Build step to copy artifacts from another project.
 * @author Alan Harder
 */
public class CopyArtifact extends Builder implements SimpleBuildStep {

    // specifies upgradeCopyArtifact is needed to work.
    private static boolean upgradeNeeded = false;
    private static Logger LOGGER = Logger.getLogger(CopyArtifact.class.getName());
    private static final BuildSelector DEFAULT_BUILD_SELECTOR = new StatusBuildSelector(true);

    @Deprecated private String projectName;
    private String project;
    private String parameters;
    private String filter, target;
    private String excludes;
    private /*almost final*/ BuildSelector selector;
    @Deprecated private transient Boolean stable;
    private Boolean flatten, optional;
    private boolean doNotFingerprintArtifacts;
    private String resultVariableSuffix;

    @Deprecated
    public CopyArtifact(String projectName, String parameters, BuildSelector selector, String filter, String target,
            boolean flatten, boolean optional) {
        this(projectName, parameters, selector, filter, target, flatten, optional, true);
    }

    @Deprecated
    public CopyArtifact(String projectName, String parameters, BuildSelector selector, String filter, String target,
            boolean flatten, boolean optional, boolean fingerprintArtifacts) {
        this(projectName, parameters, selector, filter, null, target, flatten, optional, fingerprintArtifacts);
    }

    @Deprecated
    public CopyArtifact(String projectName, String parameters, BuildSelector selector, String filter, String excludes, String target,
                        boolean flatten, boolean optional, boolean fingerprintArtifacts) {
        this(projectName);
        setParameters(parameters);
        setFilter(filter);
        setTarget(target);
        setExcludes(excludes);
        if (selector == null) {
            selector = DEFAULT_BUILD_SELECTOR;
        }
        setSelector(selector);
        setFlatten(flatten);
        setOptional(optional);
        setFingerprintArtifacts(fingerprintArtifacts);
    }

    @DataBoundConstructor
    public CopyArtifact(String projectName) {
        // check the permissions only if we can
        StaplerRequest req = Stapler.getCurrentRequest();
        if (req!=null) {
            AbstractProject<?,?> p = req.findAncestorObject(AbstractProject.class);
            if (p != null) {
                ItemGroup<?> context = p.getParent();

                // Prevents both invalid values and access to artifacts of projects which this user cannot see.
                // If value is parameterized, it will be checked when build runs.
                Jenkins jenkins = Jenkins.getInstance();
                if (projectName.indexOf('$') < 0 && (jenkins == null || jenkins.getItem(projectName, context, Job.class) == null))
                    projectName = ""; // Ignore/clear bad value to avoid ugly 500 page
            }
        }

        this.project = projectName;

        // Apply defaults to all other properties.
        setParameters(null);
        setFilter(null);
        setTarget(null);
        setExcludes(null);
        setSelector(DEFAULT_BUILD_SELECTOR);
        setFlatten(false);
        setOptional(false);
        setFingerprintArtifacts(false);
        setResultVariableSuffix(null);
    }

    @DataBoundSetter
    public void setParameters(String parameters) {
        this.parameters = Util.fixEmptyAndTrim(parameters);
    }

    @DataBoundSetter
    public void setFilter(String filter) {
        this.filter = Util.fixNull(filter).trim();
    }

    @DataBoundSetter
    public void setTarget(String target) {
        this.target = Util.fixNull(target).trim();
    }

    @DataBoundSetter
    public void setExcludes(String excludes) {
        this.excludes = Util.fixNull(excludes).trim();
    }

    @DataBoundSetter
    public void setSelector(@Nonnull BuildSelector selector) {
        this.selector = selector;
    }

    @DataBoundSetter
    public void setFlatten(boolean flatten) {
        this.flatten = flatten ? Boolean.TRUE : null;
    }

    @DataBoundSetter
    public void setOptional(boolean optional) {
        this.optional = optional ? Boolean.TRUE : null;
    }

    @DataBoundSetter
    public void setFingerprintArtifacts(boolean fingerprintArtifacts) {
        this.doNotFingerprintArtifacts = !fingerprintArtifacts;
    }

    /**
     * Set the suffix for variables to store copying results.
     * 
     * @param resultVariableSuffix Variable suffix to use.
     */
    @DataBoundSetter
    public void setResultVariableSuffix(String resultVariableSuffix) {
        this.resultVariableSuffix = Util.fixEmptyAndTrim(resultVariableSuffix);
    }

    // Upgrade data from old format
    public static class ConverterImpl extends XStream2.PassthruConverter<CopyArtifact> {
        public ConverterImpl(XStream2 xstream) { super(xstream); }
        @Override protected void callback(CopyArtifact obj, UnmarshallingContext context) {
            if (obj.selector == null) {
                obj.selector = new StatusBuildSelector(obj.stable != null && obj.stable);
                OldDataMonitor.report(context, "1.355"); // Core version# when CopyArtifact 1.2 released
            }
            if (obj.isUpgradeNeeded()) {
                // A Copy Artifact to be upgraded.
                // For information of the containing project is needed, 
                // The upgrade will be performed by upgradeCopyArtifact.
                setUpgradeNeeded();
            }
        }
    }

    private static synchronized void setUpgradeNeeded() {
        if (!upgradeNeeded) {
            LOGGER.info("Upgrade for Copy Artifact is scheduled.");
            upgradeNeeded = true;
        }
    }

    // get all CopyArtifacts configured to AbstractProject. This works both for Project and MatrixProject.
    private static List<CopyArtifact> getCopyArtifactsInProject(AbstractProject<?,?> project) throws IOException {
        DescribableList<Builder,Descriptor<Builder>> list =
                project instanceof Project ? ((Project<?,?>)project).getBuildersList()
                  : (project instanceof MatrixProject ?
                      ((MatrixProject)project).getBuildersList() : null);
        if (list == null) return Collections.emptyList();
        return list.getAll(CopyArtifact.class);
    }

    @Initializer(after=InitMilestone.JOB_LOADED)
    public static void upgradeCopyArtifact() {
        if (!upgradeNeeded) {
            return;
        }
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            LOGGER.log(Level.SEVERE, "Called for initializing, but Jenkins instance is unavailable.");
            return;
        }
        upgradeNeeded = false;
        
        boolean isUpgraded = false;
        for (AbstractProject<?,?> project: jenkins.getAllItems(AbstractProject.class)) {
            try {
                for (CopyArtifact target: getCopyArtifactsInProject(project)) {
                    try {
                        if (target.upgradeIfNecessary(project)) {
                            isUpgraded = true;
                        }
                    } catch(IOException e) {
                        LOGGER.log(Level.SEVERE, String.format("Failed to upgrade CopyArtifact in %s", project.getFullName()), e);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, String.format("Failed to upgrade CopyArtifact in %s", project.getFullName()), e);
            }
        }
        
        if (!isUpgraded) {
            // No CopyArtifact is upgraded.
            LOGGER.warning("Update of CopyArtifact is scheduled, but no CopyArtifact to upgrade was found!");
        }
    }

    public String getProjectName() {
        return project;
    }
    
    public String getParameters() {
        return parameters;
    }

    @Deprecated
    public BuildSelector getBuildSelector() {
        return selector;
    }

    public BuildSelector getSelector() {
        return selector;
    }

    public String getFilter() {
        return filter;
    }

    public String getExcludes() {
        return excludes;
    }

    public String getTarget() {
        return target;
    }

    public boolean isFlatten() {
        return flatten != null && flatten;
    }

    public boolean isOptional() {
        return optional != null && optional;
    }

    /**
     * @return the suffix for variables to store copying results.
     */
    public String getResultVariableSuffix() {
        return resultVariableSuffix;
    }

    private boolean upgradeIfNecessary(AbstractProject<?,?> job) throws IOException {
        if (isUpgradeNeeded()) {
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
                LOGGER.log(Level.SEVERE, "upgrading copyartifact is required for {0} but Jenkins instance is unavailable", job.getDisplayName());
                return false;
            }
            int i = projectName.lastIndexOf('/');
            if (i != -1 && projectName.indexOf('=', i) != -1 && /* not matrix */jenkins.getItem(projectName, job.getParent(), Job.class) == null) {
                project = projectName.substring(0, i);
                parameters = projectName.substring(i + 1);
            } else {
                project = projectName;
                parameters = null;
            }
            LOGGER.log(Level.INFO, "Split {0} into {1} with parameters {2}", new Object[] {projectName, project, parameters});
            projectName = null;
            job.save();
            return true;
        } else {
            return false;
        }
    }

    private boolean isUpgradeNeeded() {
        return (projectName != null);
    }

    public boolean isFingerprintArtifacts() {
        return !doNotFingerprintArtifacts;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new AbortException("Jenkins instance is unavailable.");
        }
        if (build instanceof AbstractBuild) {
            upgradeIfNecessary(((AbstractBuild)build).getProject());
        }

        EnvVars env = build.getEnvironment(listener);
        if (build instanceof AbstractBuild) {
            env.putAll(((AbstractBuild)build).getBuildVariables()); // Add in matrix axes..
        } else {
            // Abstract#getEnvironment(TaskListener) put build parameters to
            // environments, but Run#getEnvironment(TaskListener) doesn't.
            // That means we can't retrieve build parameters from WorkflowRun
            // as it is a subclass of Run, not of AbstractBuild.
            // We need expand build parameters manually.
            // See JENKINS-26694, JENKINS-30357 for details.
            for(ParametersAction pa: build.getActions(ParametersAction.class)) {
                // We have to extract parameters manally as ParametersAction#buildEnvVars
                // (overrides EnvironmentContributingAction#buildEnvVars)
                // is applicable only for AbstractBuild.
                for(ParameterValue pv: pa.getParameters()) {
                    pv.buildEnvironment(build, env);
                }
            }
        }

        PrintStream console = listener.getLogger();
        String expandedProject = project, expandedFilter = filter;
        String expandedExcludes = getExcludes();

        expandedProject = env.expand(project);
        Job<?, ?> job = jenkins.getItem(expandedProject, getItemGroup(build), Job.class);
        if (job != null && !expandedProject.equals(project)
            // If projectName is parameterized, need to do permission check on source project.
            && !canReadFrom(job, build)) {
            job = null; // Disallow access
        }
        if (job == null) {
            throw new AbortException(Messages.CopyArtifact_MissingProject(expandedProject));
        }
        Run src = selector.getBuild(job, env, parameters != null ? new ParametersBuildFilter(env.expand(parameters)) : new BuildFilter(), build);
        if (src == null) {
            String message = Messages.CopyArtifact_MissingBuild(expandedProject);
            if (isOptional()) {
                // just return without an error
                console.println(message);
                return;
            } else {
                // Fail build if copy is not optional
                throw new AbortException(message);
            }
        }
        FilePath targetDir = workspace;
        targetDir.mkdirs(); // being a SimpleBuildStep guarantees it will have a workspace, but the physical dir might not yet exist.
        // Add info about the selected build into the environment
        EnvAction envData = build.getAction(EnvAction.class);
        if (envData == null) {
            envData = new EnvAction();
            build.addAction(envData);
        }
        envData.add(build, src, expandedProject, getResultVariableSuffix());
        if (target.length() > 0) targetDir = new FilePath(targetDir, env.expand(target));
        expandedFilter = env.expand(filter);
        if (expandedFilter.trim().length() == 0) expandedFilter = "**";
        expandedExcludes = env.expand(expandedExcludes);
        if (StringUtils.isBlank(expandedExcludes)) {
            expandedExcludes = null;
        }

        if (jenkins.getPlugin("maven-plugin") != null && (src instanceof MavenModuleSetBuild) ) {
        // use classes in the "maven-plugin" plugin as might not be installed
            // Copy artifacts from the build (ArchiveArtifacts build step)
            boolean ok = perform(src, build, expandedFilter, expandedExcludes, targetDir, listener);
            // Copy artifacts from all modules of this Maven build (automatic archiving)
            for (Iterator<MavenBuild> it = ((MavenModuleSetBuild)src).getModuleLastBuilds().values().iterator(); it.hasNext(); ) {
                // for(Run r: ....values()) causes upcasting and loading MavenBuild compiled with jdk 1.6.
                // SEE https://wiki.jenkins-ci.org/display/JENKINS/Tips+for+optional+dependencies for details.
                Run<?,?> r = it.next();
                ok |= perform(r, build, expandedFilter, expandedExcludes, targetDir, listener);
            }
            if (!ok) {
                throw new AbortException(Messages.CopyArtifact_FailedToCopy(expandedProject, expandedFilter));
            }
        } else if (src instanceof MatrixBuild) {
            boolean ok = false;
            // Copy artifacts from all configurations of this matrix build
            // Use MatrixBuild.getExactRuns if available
            for (Run r : ((MatrixBuild) src).getExactRuns())
                // Use subdir of targetDir with configuration name (like "jdk=java6u20")
                ok |= perform(r, build, expandedFilter, expandedExcludes, targetDir.child(r.getParent().getName()), listener);

            if (!ok) {
                throw new AbortException(Messages.CopyArtifact_FailedToCopy(expandedProject, expandedFilter));
            }
        } else {
            if (!perform(src, build, expandedFilter, expandedExcludes, targetDir, listener)) {
                throw new AbortException(Messages.CopyArtifact_FailedToCopy(expandedProject, expandedFilter));
            }
        }
    }

    private boolean canReadFrom(Job<?, ?> job, Run<?, ?> build) {
        Job<?, ?> fromJob = job;
        Job<?, ?> toJob = build.getParent();

        if (CopyArtifactPermissionProperty.canCopyArtifact(getRootProject(toJob), getRootProject(fromJob))) {
            return true;
        }

        Authentication a = Jenkins.getAuthentication();
        if (!ACL.SYSTEM.equals(a)) {
            // if the build does not run on SYSTEM authorization,
            // Jenkins is configured to use QueueItemAuthenticator.
            // In this case, builds are configured to run with a proper authorization
            // (for example, builds run with the authorization of the user who triggered the build),
            // and we should check access permission with that authorization.
            // QueueItemAuthenticator is available from Jenkins 1.520.
            // See also JENKINS-14999, JENKINS-16956, JENKINS-18285.
            boolean b = job.getACL().hasPermission(Item.READ);
            if (!b)
                LOGGER.fine(String.format("Refusing to copy artifact from %s to %s because %s lacks Item.READ access",job,build, a));
            return b;
        }
        
        // for the backward compatibility, 
        // test the permission as an anonymous authenticated user.
        boolean b = job.getACL().hasPermission(
                new UsernamePasswordAuthenticationToken("authenticated", "",
                        new GrantedAuthority[]{ SecurityRealm.AUTHENTICATED_AUTHORITY }),
                Item.READ);
        if (!b)
            LOGGER.fine(String.format("Refusing to copy artifact from %s to %s because 'authenticated' lacks Item.READ access",job,build));
        return b;
    }

    private static Job<?, ?> getRootProject(Job<?, ?> job) {
        if (job instanceof AbstractProject) {
            return ((AbstractProject<?,?>)job).getRootProject();
        } else {
            return job;
        }
    }

    // retrieve the "folder" (jenkins root if no folder used) for this build
    private static ItemGroup getItemGroup(Run<?, ?> build) {
        return getRootProject(build.getParent()).getParent();
    }


    private boolean perform(Run src, Run<?,?> dst, String expandedFilter, @CheckForNull String expandedExcludes, FilePath targetDir, TaskListener listener) throws IOException, InterruptedException {
        PrintStream console = listener.getLogger();
        VirtualFile srcDir = selector.getArtifacts(src, console);
        if (srcDir == null) {
            return isOptional();  // Fail build unless copy is optional
        }
        Map<String, String> fingerprints = null; // entry → MD5
        try {
            fingerprints = copy(targetDir, srcDir, expandedFilter, expandedExcludes, isFingerprintArtifacts(), listener, isFlatten());
            int cnt = fingerprints.size();
            console.println(Messages.CopyArtifact_Copied(cnt, HyperlinkNote.encodeTo('/'+ src.getParent().getUrl(), src.getParent().getFullDisplayName()),
                    HyperlinkNote.encodeTo('/'+src.getUrl(), Integer.toString(src.getNumber()))));
            // Fail build if 0 files copied unless copy is optional
            return cnt > 0 || isOptional();
        } finally {
            if (fingerprints != null) {
                Map<String, String> fingerprintsShallow = new HashMap<>();
                FingerprintMap map = Jenkins.get().getFingerprintMap();
                for (Map.Entry<String, String> entry : fingerprints.entrySet()) {
                    String name = entry.getKey().replaceFirst(".+/", "");
                    String digest = entry.getValue();
                    if (digest == null) {
                        continue;
                    }
                    fingerprintsShallow.put(name, digest);
                    Fingerprint f = map.getOrCreate(src, name, digest);
                    f.addFor(src);
                    f.addFor(dst);
                }
                if (!fingerprintsShallow.isEmpty()) {
                    for (Run<?, ?> r : new Run<?, ?>[] {src, dst}) {
                        Fingerprinter.FingerprintAction fa = r.getAction(Fingerprinter.FingerprintAction.class);
                        if (fa != null) {
                            fa.add(fingerprintsShallow);
                        } else {
                            r.addAction(new Fingerprinter.FingerprintAction(r, fingerprintsShallow));
                        }
                    }
                }
            }
        }
    }

    private static Map<String, String> copy(FilePath targetDir, VirtualFile srcDir, String expandedFilter, String expandedExcludes, boolean fingerprint, TaskListener listener, boolean flatten) throws IOException, InterruptedException {
        targetDir.mkdirs();  // Create target if needed
        Collection<String> list = srcDir.list(expandedFilter.replace('\\', '/'), expandedExcludes != null ? expandedExcludes.replace('\\', '/') : null, false);
        Map<String, String> fingerprints = new HashMap<>();
        for (String entry : list) {
            String digest = copyOne(srcDir.child(entry), new FilePath(targetDir, flatten ? entry.replaceFirst(".+/", "") : entry), fingerprint, listener);
            fingerprints.put(entry, digest);
        }
        return fingerprints;
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException x) {
            throw new AssertionError(x);
        }
    }

    private static String copyOne(VirtualFile s, FilePath d, boolean fingerprint, TaskListener listener) throws IOException, InterruptedException {
        String link = s.readLink();
        if (link != null) {
            d.getParent().mkdirs();
            d.symlinkTo(link, listener);
            return null;
        }
        try {
            URL u = s.toExternalURL();
            byte[] digest;
            if (u != null) {
                if (fingerprint) {
                    digest = d.act(new CopyURLWithFingerprinting(u, listener));
                } else {
                    new RobustHTTPClient().copyFromRemotely(d, u, listener);
                    digest = null;
                }
            } else {
                if (fingerprint) {
                    MessageDigest md5 = md5();
                    try (InputStream is = s.open(); OutputStream os = d.write()) {
                        IOUtils.copy(is, new DigestOutputStream(os, md5));
                    }
                    digest = md5.digest();
                } else {
                    try (InputStream is = s.open()) {
                        d.copyFrom(is);
                    }
                    digest = null;
                }
            }
            // FilePath.setLastModifiedIfPossible private; copyToWithPermission OK but would have to calc digest separately:
            try {
                d.touch(s.lastModified());
            } catch (IOException x) {
                LOGGER.warning(x.getMessage());
            }
            int mode = s.mode();
            if (mode != -1) {
                d.chmod(mode);
            }
            return digest != null ? Util.toHexString(digest) : null;
        } catch (IOException e) {
            throw new IOException("Failed to copy " + s + " to " + d, e);
        }
    }

    private static class CopyURLWithFingerprinting extends MasterToSlaveFileCallable<byte[]> {
        private static final long serialVersionUID = 1;
        private final URL u;
        private final TaskListener listener;
        private final RobustHTTPClient client = new RobustHTTPClient();
        CopyURLWithFingerprinting(URL u, TaskListener listener) {
            this.u = u;
            this.listener = listener;
        }
        @Override
        public byte[] invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            hudson.util.IOUtils.mkdirs(f.getParentFile());
            MessageDigest md5 = md5();
            client.connect("download", "download " + RobustHTTPClient.sanitize(u) + " to " + f, c -> c.execute(new HttpGet(u.toString())), response -> {
                try (InputStream is = response.getEntity().getContent(); OutputStream os = new FileOutputStream(f)) {
                    IOUtils.copy(is, new DigestOutputStream(os, md5));
                }
            }, listener);
            return md5.digest();
        }
    }

    /**
     * Tests whether specified variable name is valid.
     * Package scope for testing purpose.
     * 
     * @param variableName
     * @return true if <code>variableName</code> is valid as a variable name.
     */
    static boolean isValidVariableName(final String variableName) {
        if(StringUtils.isBlank(variableName)) {
            return false;
        }
        
        // The pattern for variables are defined in hudson.Util.VARIABLE.
        // It's not exposed unfortunately and tests the variable
        // by actually expanding that.
        final String expected = "GOOD";
        String expanded = Util.replaceMacro(
            String.format("${%s}", variableName),
            new VariableResolver<String>() {
                @Override
                public String resolve(String name) {
                    if(variableName.equals(name)) {
                        return expected;
                    }
                    return null;
                }
            }
        );
        
        return expected.equals(expanded);
    }
    
    @Extension @Symbol("copyArtifacts")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckProjectName(
                @AncestorInPath Job<?,?> anc, @QueryParameter String value) {
            // JENKINS-32526: Check that it behaves gracefully for an unknown context
            if (anc == null) return FormValidation.ok(Messages.CopyArtifact_AncestorIsNull());
            // Require CONFIGURE permission on this project
            if (!anc.hasPermission(Item.CONFIGURE)) return FormValidation.ok();
            
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
                // validation is useless if Jenkins is no longer available.
                return FormValidation.ok();
            }
            FormValidation result;
            Item item = jenkins.getItem(value, anc.getParent());
            if (item != null)
                if (jenkins.getPlugin("maven-plugin") != null && item instanceof MavenModuleSet) {
                    result = FormValidation.warning(Messages.CopyArtifact_MavenProject());
                } else {
                    result = (item instanceof MatrixProject)
                          ? FormValidation.warning(Messages.CopyArtifact_MatrixProject())
                          : FormValidation.ok();
                }
            else if (value.indexOf('$') >= 0)
                result = FormValidation.warning(Messages.CopyArtifact_ParameterizedName());
            else {
                Job<?,?> nearest = Items.findNearest(Job.class, value, anc.getParent());
                if (nearest != null) {
                result = FormValidation.error(
                    Messages.BuildTrigger_NoSuchProject(
                        value, nearest.getName()));
                } else {
                    result = FormValidation.error(Messages.BuildTrigger_NoProjectSpecified());
                }
            }
            return result;
        }

        public FormValidation doCheckResultVariableSuffix(@QueryParameter String value) {
            value = Util.fixEmptyAndTrim(value);
            if (value == null) {
                // optional field.
                return FormValidation.ok();
            }
            
            if (!isValidVariableName(value)) {
                return FormValidation.error(Messages.CopyArtifact_InvalidVariableName());
            }
            
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> clazz) {
            return true;
        }

        public String getDisplayName() {
            return Messages.CopyArtifact_DisplayName();
        }

    }

    // Listen for project renames and update property here if needed.
    @Extension
    public static final class ListenerImpl extends ItemListener {
        @Override
        public void onRenamed(Item item, String oldName, String newName) {
            String oldFullName = Items.getCanonicalName(item.getParent(), oldName);
            String newFullName = Items.getCanonicalName(item.getParent(), newName);
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
                LOGGER.log(Level.SEVERE, "Jenkins instance is no longer available.");
                return;
            }
            for (AbstractProject<?,?> project
                    : jenkins.getAllItems(AbstractProject.class)) {
                try {
                for (CopyArtifact ca : getCopiers(project)) {
                    String projectName = ca.getProjectName();
                    if (projectName == null) {
                        // JENKINS-27475 (not sure why this happens).
                        continue;
                    }

                    String suffix = "";
                    // Support rename for "MatrixJobName/AxisName=value" type of name
                    int i = projectName.indexOf('=');
                    if (i > 0) {
                        int end = projectName.substring(0,i).lastIndexOf('/');
                        suffix = projectName.substring(end);
                        projectName = projectName.substring(0, end);
                    }

                    ItemGroup context = project.getParent();
                    String newProjectName = Items.computeRelativeNamesAfterRenaming(oldFullName, newFullName, projectName, context);
                    if (!projectName.equals(newProjectName)) {
                        ca.project = newProjectName + suffix;
                        project.save();
                    }
                }
                } catch (IOException ex) {
                    Logger.getLogger(ListenerImpl.class.getName()).log(Level.WARNING,
                            "Failed to resave project " + project.getName()
                            + " for project rename in CopyArtifact build step ("
                            + oldName + " =>" + newName + ")", ex);
                }
            }
        }

        private static List<CopyArtifact> getCopiers(AbstractProject<?,?> project) throws IOException {
            List<CopyArtifact> copiers = getCopyArtifactsInProject(project);
            for (CopyArtifact copier : copiers) {
                copier.upgradeIfNecessary(project);
            }
            return copiers;
        }
    }

    private static class EnvAction implements EnvironmentContributingAction {
        // Decided not to record this data in build.xml, so marked transient:
        private transient Map<String,String> data = new HashMap<String,String>();

        @Nullable
        private String calculateDefaultSuffix(@Nonnull Run<?,?> build, @Nonnull Run<?,?> src, @Nonnull String projectName) {
            ItemGroup<?> ctx = getItemGroup(build);
            Job<?,?> item = src.getParent();
            // Use full name if configured with absolute path
            // and relative otherwise
            projectName = projectName.startsWith("/") ? item.getFullName() : item.getRelativeNameFrom(ctx);
            if (projectName == null) {
                // this is a case when the copying project doesn't belong to Jenkins item tree.
                // (e.g. promotion for Promoted Builds plugin)
                LOGGER.log(
                        Level.WARNING,
                        "Failed to calculate a relative path of {0} from {2}",
                        new Object[] {
                                item.getFullName(),
                                ctx.getFullName(),
                        }
                );
                return null;
            }
            
            return  projectName.toUpperCase().replaceAll("[^A-Z]+", "_"); // Only use letters and _
        }
        
        private void add(
                @Nonnull Run<?,?> build,
                @Nonnull Run<?,?> src,
                @Nonnull String projectName,
                @Nullable String resultVariableSuffix
        ) {
            if (data==null) return;
            
            if (!isValidVariableName(resultVariableSuffix)) {
                resultVariableSuffix = calculateDefaultSuffix(build, src, projectName);
                if (resultVariableSuffix == null) {
                    return;
                }
            }
            data.put(
                String.format("COPYARTIFACT_BUILD_NUMBER_%s", resultVariableSuffix),
                Integer.toString(src.getNumber())
            );
        }

        public void buildEnvVars(AbstractBuild<?,?> build, EnvVars env) {
            if (data!=null) env.putAll(data);
        }
        public void buildEnvironment(Run<?,?> run, EnvVars env) {
            if (data!=null) env.putAll(data);
        }

        public String getIconFileName() { return null; }
        public String getDisplayName() { return null; }
        public String getUrlName() { return null; }
    }
}
