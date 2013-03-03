/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mendhak.teamcity.github;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.WebLinks;
import jetbrains.buildServer.serverSide.executors.ExecutorServices;
import jetbrains.buildServer.util.ExceptionUtil;
import mendhak.teamcity.github.api.GitHubApi;
import mendhak.teamcity.github.api.GitHubApiFactory;
import mendhak.teamcity.github.api.GitHubChangeState;
import mendhak.teamcity.github.ui.UpdateChangeStatusFeature;
import mendhak.teamcity.github.ui.UpdateChangesConstants;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * Created by Eugene Petrenko (eugene.petrenko@gmail.com)
 * Date: 06.09.12 3:29
 */
public class ChangeStatusUpdater {
  private static final Logger LOG = Logger.getInstance(ChangeStatusUpdater.class.getName());

  private final ExecutorService myExecutor;
  @NotNull
  private final GitHubApiFactory myFactory;
  private final WebLinks myWeb;

  public ChangeStatusUpdater(@NotNull final ExecutorServices services,
                             @NotNull final GitHubApiFactory factory,
                             @NotNull final WebLinks web) {
    myFactory = factory;
    myWeb = web;
    myExecutor = services.getLowPriorityExecutorService();
  }

  public static interface Handler {
    void scheduleChangeStarted(@NotNull final String hash, @NotNull final SRunningBuild build);
    void scheduleChangeCompeted(@NotNull final String hash, @NotNull final SRunningBuild build);
  }

  @NotNull
  public Handler getUpdateHandler(@NotNull final SBuildFeatureDescriptor feature) {
    if (!feature.getType().equals(UpdateChangeStatusFeature.FEATURE_TYPE)) {
      throw new IllegalArgumentException("Unexpected feature type " + feature.getType());
    }

    final UpdateChangesConstants c = new UpdateChangesConstants();
    final GitHubApi api = myFactory.openGitHub(
            feature.getParameters().get(c.getServerKey()),
            feature.getParameters().get(c.getUserNameKey()),
            feature.getParameters().get(c.getPasswordKey()));
//    final String repositoryOwner = feature.getParameters().get(c.getRepositoryOwnerKey());
//    final String repositoryName = feature.getParameters().get(c.getRepositoryNameKey());

    return new Handler() {

      public void scheduleChangeStarted(@NotNull String hash, @NotNull SRunningBuild build) {
        scheduleChangeUpdate(hash, build, "TeamCity Build " + build.getFullName() + " started", GitHubChangeState.Pending);
      }

      public void scheduleChangeCompeted(@NotNull String hash, @NotNull SRunningBuild build) {
        GitHubChangeState status = build.getStatusDescriptor().isSuccessful() ? GitHubChangeState.Success : GitHubChangeState.Error;
        String text = build.getStatusDescriptor().getText();
        if (text != null) {
          text = ": " + text;
        } else {
          text = "";
        }
        scheduleChangeUpdate(hash, build, "TeamCity Build " + build.getFullName() + " finished" + text, status);
      }

      private void scheduleChangeUpdate(@NotNull final String hash,
                                        @NotNull final SRunningBuild build,
                                        @NotNull final String message,
                                        @NotNull final GitHubChangeState status) {
        System.err.println("Scheduling Stash status update for hash: " + hash + ", buildId: " + build.getBuildId() + ", status: " + status);

        myExecutor.submit(ExceptionUtil.catchAll("set change status on github", new Runnable() {
          public void run() {
            try {
              api.setChangeStatus(
                      "",
                      "",
                      hash,
                      status,
                      myWeb.getViewResultsUrl(build),
                      message
              );
              LOG.info("Updated GitHub status for hash: " + hash + ", buildId: " + build.getBuildId() + ", status: " + status);
            } catch (IOException e) {
              LOG.warn("Failed to update GitHub status for hash: " + hash + ", buildId: " + build.getBuildId() + ", status: " + status + ". " + e.getMessage(), e);
            }
          }
        }));
      }
    };
  }
}
