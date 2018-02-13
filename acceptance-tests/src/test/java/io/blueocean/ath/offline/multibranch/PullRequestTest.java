package io.blueocean.ath.offline.multibranch;


import com.google.common.io.Files;
import com.google.common.io.Resources;
import io.blueocean.ath.ATHJUnitRunner;
import io.blueocean.ath.GitRepositoryRule;
import io.blueocean.ath.Retry;
import io.blueocean.ath.WaitUtil;
import io.blueocean.ath.api.classic.ClassicJobApi;
import io.blueocean.ath.factory.MultiBranchPipelineFactory;
import io.blueocean.ath.model.MultiBranchPipeline;
import org.apache.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openqa.selenium.By;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.URL;

@RunWith(ATHJUnitRunner.class)

public class PullRequestTest {

    private Logger logger = Logger.getLogger(CommitMessagesTest.class);

    @Rule
    @Inject
    public GitRepositoryRule git;

    @Inject
    ClassicJobApi jobApi;

    @Inject
    WaitUtil wait;

    @Inject
    MultiBranchPipelineFactory mbpFactory;

    // Names of the pipelines we'll create
    String pullRequestFlowTest          = "pullRequestFlowTest";

    // Initialize our MultiBranchPipeline objects
    static MultiBranchPipeline pullRequestFlowTestPipeline = null;

    /**
     * pullRequestFlowTest
     *
     * This test case performs a complete PR workflow, verifying
     * the correct behavior of the Blue Ocean Pull Requests tab
     * along the way.
     *
     *   1. Create the multibranch project using a declarative
     *      Jenkinsfile in the resources folder.
     *   2. Verify that there's nothing in the PR tab.
     *   3. Create a PR
     *   4. Do a rescan of the MBP
     *   5. Verify that that the PR tab now has something in it
     *   6. Check that it can be rebuilt with the Run button
     *   7. Check that the OPEN lozenge works, and takes you to the run.
     *   8. Dismiss that run with the Close button (the 'X') at top right,
     *      and verify that we're taken back to the PR tab
     *   9. Merge with a specific commit message that makes use of the
     *      name of the test we're running
     *   10. Issue a rescan
     *   11. PRs tab should now be empty
     *   12. Clean up.
     *
     */

    @Test
    // @Retry(3)
    public void pullRequestFlowTest () throws IOException, GitAPIException, InterruptedException {
        // Create our repo
        logger.info("Creating pipeline " + pullRequestFlowTest);
        URL pullRequestFlowTestJenkinsfile = Resources.getResource(ParallelNavigationTest.class, "PullRequestTest/Jenkinsfile");
        Files.copy(new File(pullRequestFlowTestJenkinsfile.getFile()), new File(git.gitDirectory, "Jenkinsfile"));
        git.addAll();
        git.commit("Initial commit for " + pullRequestFlowTest);
        logger.info("Committed Jenkinsfile for " + pullRequestFlowTest);
        pullRequestFlowTestPipeline = mbpFactory.pipeline(pullRequestFlowTest).createPipeline(git);
        logger.info("Finished creating " + pullRequestFlowTest);
        logger.info("Beginning the test");
        pullRequestFlowTestPipeline.getRunDetailsPipelinePage().open(1);
        wait.until(By.xpath("//*[text()=\"stage-1-before-PR\"]"));
        logger.info("Original version found, now time to cut a PR");
        // Incredibly, Firefox figured out this selector on the first try.
        wait.click(By.cssSelector("svg.ResultPageHeader-close"));
        logger.info("Dismissed Result Page");
        // if this works I am going to shit a brick
        // wait.click(By.cssSelector("Header-pageTabs.a.pr"));
        wait.click(By.xpath("//*[text()=\"Pull Requests\"]"));
        wait.until(By.xpath("//*[text()=\"You don't have any open pull requests\"]"));
        // Now we need to make a change to the git repo
        git.createBranch("new-branch");
        URL secondPullRequestFlowTestJenkinsfile = Resources.getResource(ParallelNavigationTest.class, "PullRequestTest/Jenkinsfile.pr");
        Files.copy(new File(secondPullRequestFlowTestJenkinsfile.getFile()), new File(git.gitDirectory, "Jenkinsfile"));
        git.addAll();
        git.commit("Second commit for " + pullRequestFlowTest);
        // There's some "classic API" call that can be used to trigger a rescan.
        // What is it?
        pullRequestFlowTestPipeline.rescanThisPipeline();
        // Now when we look at the PRs tab we should get something.
        wait.click(By.xpath("//*[text()=\"Branches\"]"));
        wait.until(By.xpath("//*[text()=\"You don't have any open pull requests\"]"));
        // pullRequestFlowTestPipeline.getRunDetailsPipelinePage().open(1);
        // Thread.sleep(60000);
    }

    @AfterClass
    public static void deleteTestPipelines() throws IOException, GitAPIException, InterruptedException {
        // Needs to be changed to delete only those we're creating.
        MultiBranchPipeline[] listOfPipelineJobs = {pullRequestFlowTestPipeline};
        for (MultiBranchPipeline pipelineToCleanup:listOfPipelineJobs) {
            /*
            stopAllRuns and deleteThisPipeline both provide their own
            logger messages, no need to create new ones here.
            */
            pipelineToCleanup.stopAllRuns();
            pipelineToCleanup.deleteThisPipeline(pipelineToCleanup.getName());
        }
    }


}
