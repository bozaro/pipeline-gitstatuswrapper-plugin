/*
MIT License

Copyright (c) 2019 Zachary Sherwin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */
package org.jenkinsci.plugins.gitstatuswrapper.pipeline;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.AbstractIdCredentialsListBoxModel;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.YesNoMaybe;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.displayurlapi.DisplayURLProvider;
import org.jenkinsci.plugins.github.GitHubHelper;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision;
import org.jenkinsci.plugins.jenkins.JenkinsHelpers;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;


public final class GitStatusWrapperStep extends Step {

  private EnvVars env;

  /**
   * A string label to differentiate the send status from the status of other systems.
   */
  private String gitHubContext = "";
  /**
   * The optional GitHub enterprise instance api url endpoint.
   *
   * Used when you are using your own GitHub enterprise instance instead of the default GitHub SaaS
   * (http://github.com)
   */
  private String gitApiUrl = "";
  /**
   * The id of the jenkins stored credentials to use to connect to GitHub, must identify a
   * UsernamePassword credential
   */
  private String credentialsId = "";
  /**
   * The GitHub's account that owns the repo to notify
   */
  private String account = "";
  /**
   * The repository that owns the commit to notify
   */
  private String repo = "";
  /**
   * The commit to notify unique sha1, used as commit identifier
   */
  private String sha = "";
  /**
   * A short description of the status to send
   */
  private String description = "";
  /**
   * The target URL to associate with the sendstatus.
   *
   * This URL will be linked from the GitHub UI to allow users to easily see the 'source' of the
   * Status.
   */
  private String targetUrl = "";


  public String getGitHubContext() {
    if (StringUtils.isEmpty(gitHubContext)) {
      this.gitHubContext = "gitStatusWrapper";
    }
    return gitHubContext;
  }

  @DataBoundSetter
  public void setGitHubContext(String gitHubContext) {
    this.gitHubContext = gitHubContext;
  }

  public String getGitApiUrl() {
    if (StringUtils.isEmpty(gitApiUrl)) {
      this.gitApiUrl = GitHubHelper.DEFAULT_GITHUB_API_URL;
    }
    return gitApiUrl;
  }

  @DataBoundSetter
  public void setGitApiUrl(String gitApiUrl) {
    this.gitApiUrl = gitApiUrl;
  }

  public String getCredentialsId() {
    return credentialsId;
  }

  @DataBoundSetter
  public void setCredentialsId(String credentialsId) {
    this.credentialsId = credentialsId;
  }

  public String getRepo() {
    return repo;
  }

  @DataBoundSetter
  public void setRepo(String repo) {
    this.repo = repo;
  }

  public String getSha() {
    return sha;
  }

  @DataBoundSetter
  public void setSha(String sha) {
    this.sha = sha;
  }

  public String getDescription() {
    return description;
  }

  @DataBoundSetter
  public void setDescription(String description) {
    this.description = description;
  }

  public String getTargetUrl() {
    return targetUrl;
  }

  @DataBoundSetter
  public void setTargetUrl(String targetUrl) {
    this.targetUrl = targetUrl;
  }

  public String getAccount() {
    return this.account;
  }

  @DataBoundSetter
  public void setAccount(String account) {
    this.account = account;
  }


  @DataBoundConstructor
  public GitStatusWrapperStep() {
  }

  @Override
  public StepExecution start(StepContext context) throws Exception {
    env = context.get(EnvVars.class);
    return new ExecutionImpl(context, this);
  }

  @Extension(dynamicLoadable = YesNoMaybe.YES, optional = true)
  public static class DescriptorImpl extends StepDescriptor {

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Collections.singleton(TaskListener.class);
    }

    @Override
    public String getFunctionName() {
      return "gitStatusWrapper";
    }

    @Override
    public String getDisplayName() {
      return "gitStatusWrapper";
    }

    @Override
    public boolean takesImplicitBlockArgument() {
      return true;
    }

    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item project) {
      AbstractIdCredentialsListBoxModel result = new StandardListBoxModel();
      List<UsernamePasswordCredentials> credentialsList = CredentialsProvider
          .lookupCredentials(UsernamePasswordCredentials.class, project, ACL.SYSTEM);
      for (UsernamePasswordCredentials credential : credentialsList) {
        result = result.with((IdCredentials) credential);
      }
      return result;
    }

    public FormValidation doTestConnection(
        @QueryParameter("credentialsId") final String credentialsId,
        @QueryParameter("gitApiUrl") String gitApiUrl, @AncestorInPath Item context) {
      try {
        gitApiUrl = (StringUtils.isEmpty(gitApiUrl)) ? GitHubHelper.DEFAULT_GITHUB_API_URL
            : gitApiUrl;
        GitHubHelper.getGitHubIfValid(credentialsId, gitApiUrl, JenkinsHelpers.getProxy(gitApiUrl),
            context);
        return FormValidation.ok("Success");
      } catch (Exception e) {
        return FormValidation.error(e, e.getMessage());
      }
    }
  }

  public static final class ExecutionImpl extends StepExecution {

    private static final Logger LOGGER = Logger.getLogger(ExecutionImpl.class.getName());
    public static final String UNABLE_TO_INFER_DATA = "Unable to infer git data, please specify repo, credentialsId, account and sha values";
    public static final String UNABLE_TO_INFER_COMMIT = "Could not infer exact commit to use, please specify one";
    public static final String UNABLE_TO_INFER_CREDENTIALS_ID = "Can not infer exact credentialsId to use, please specify one";

    private final transient GitStatusWrapperStep step;
    private transient BodyExecution body;
    private transient Run run;

    public transient GHRepository repository;
    public transient GHCommit commit;

    public transient TaskListener listener;

    protected ExecutionImpl(@Nonnull StepContext context, GitStatusWrapperStep step) {
      super(context);
      this.step = step;

    }

    @Override
    public boolean start() throws Exception {
      run = getContext().get(Run.class);
      listener = getContext().get(TaskListener.class);

      this.step.setSha(this.getSha());
      this.step.setRepo(this.getRepo());
      this.step.setCredentialsId(this.getCredentialsId());
      this.step.setAccount(this.getAccount());
      this.step.setTargetUrl(this.getTargetUrl());

      this.repository = GitHubHelper
          .getRepoIfValid(this.step.getCredentialsId(), this.step.getGitApiUrl(),
              JenkinsHelpers.getProxy(step.getGitApiUrl()), this.step.getAccount(),
              this.step.getRepo(),
              run.getParent());

      this.commit = GitHubHelper
          .getCommitIfValid(this.step.getCredentialsId(), this.step.getGitApiUrl(),
              JenkinsHelpers.getProxy(this.step.getGitApiUrl()), this.step.getAccount(),
              this.step.getRepo(),
              this.step.getSha(), run.getParent());
      this.setStatus(GHCommitState.PENDING);

      EnvVars envOverride = new EnvVars();
      EnvironmentExpander envEx = EnvironmentExpander
          .merge(getContext().get(EnvironmentExpander.class),
              new ExpanderImpl(envOverride));
      body = getContext().newBodyInvoker().withContext(envEx).withCallback(new Callback(this))
          .start();

      return false;
    }

    public void setStatus(GHCommitState state) throws IOException {
      listener.getLogger().println(
          String.format("[GitStatusWrapper] - Setting %s status for %s on commit %s",
              state.toString(),
              this.step.getGitHubContext(), commit.getSHA1())
      );
      this.repository.createCommitStatus(commit.getSHA1(),
          state, this.step.getTargetUrl(), this.step.getDescription(),
          this.step.getGitHubContext());
    }


    public String getCredentialsId() {
      if (StringUtils.isEmpty(step.getCredentialsId())) {
        return GitHubHelper.inferBuildCredentialsId(run);
      } else {
        return step.getCredentialsId();
      }
    }

    public String getRepo() throws IOException {
      if (StringUtils.isEmpty(step.getRepo())) {
        return GitHubHelper.inferBuildRepo(run);
      } else {
        return step.getRepo();
      }
    }

    public String getSha() {
      if (StringUtils.isEmpty(step.getSha())) {
        try {
          return GitHubHelper.inferBuildCommitSHA1(run);
        } catch (Exception e) {
          if (!StringUtils.isEmpty(step.env.get("GIT_COMMIT"))) {
            return step.env.get("GIT_COMMIT");
          } else {
            throw new IllegalArgumentException(UNABLE_TO_INFER_COMMIT);
          }
        }
      } else {
        return step.getSha();
      }
    }

    public String getTargetUrl() {
      if (StringUtils.isEmpty(step.getTargetUrl())) {
        return DisplayURLProvider.get().getRunURL(run);
      } else {
        return step.getTargetUrl();
      }
    }

    public String getAccount() throws IOException {
      if (StringUtils.isEmpty(step.getAccount())) {
        return GitHubHelper.inferBuildAccount(run);
      } else {
        return step.getAccount();
      }
    }

    /**
     * Callback to cleanup tmp script after finishing the job
     */
    private static class Callback extends BodyExecutionCallback {

      ExecutionImpl execution;

      public Callback(ExecutionImpl execution) {
        this.execution = execution;
      }

      @Override
      public final void onSuccess(StepContext context, Object result) {
        try {
          execution.setStatus(GHCommitState.SUCCESS);
        } catch (Exception x) {
          context.onFailure(x);
          return;
        }
        context.onSuccess(result);
      }

      @Override
      public void onFailure(StepContext context, Throwable t) {
        try {
          execution.setStatus(GHCommitState.FAILURE);
        } catch (Exception x) {
          t.addSuppressed(x);
        }
        context.onFailure(t);
      }

      private static final long serialVersionUID = 1L;
    }

    @Override
    public void stop(Throwable cause) throws Exception {
      if (body != null) {
        body.cancel(cause);
      }
    }

    /**
     * Takes care of overriding the environment with our defined overrides
     */
    private static final class ExpanderImpl extends EnvironmentExpander {

      private static final long serialVersionUID = 1;
      private final Map<String, String> overrides;

      private ExpanderImpl(EnvVars overrides) {
        LOGGER.log(Level.FINE, "Overrides: " + overrides.toString());
        this.overrides = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
          this.overrides.put(entry.getKey(), entry.getValue());
        }
      }

      @Override
      public void expand(EnvVars env) throws IOException, InterruptedException {
        env.overrideAll(overrides);
      }
    }
  }

}