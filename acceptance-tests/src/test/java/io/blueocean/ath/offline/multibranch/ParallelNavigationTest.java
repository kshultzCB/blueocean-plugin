package io.blueocean.ath.offline.multibranch;

import com.google.common.io.Files;
import com.google.common.io.Resources;
import io.blueocean.ath.ATHJUnitRunner;
import io.blueocean.ath.GitRepositoryRule;
import io.blueocean.ath.WaitUtil;
import io.blueocean.ath.api.classic.ClassicJobApi;
import io.blueocean.ath.factory.MultiBranchPipelineFactory;
import io.blueocean.ath.model.MultiBranchPipeline;
import org.apache.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.By;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.URL;

@RunWith(ATHJUnitRunner.class)
public class ParallelNavigationTest {

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

    /**
     * This checks that we can run a pipeline with 2 long running parallel branches.
     * You should be able to click between one and the other and see it progressing.
     */
    @Test
    public void parallelNavigationTest () throws IOException, GitAPIException, InterruptedException {
        String pipelineName = "ParallelNavigationTest_tested";

        URL jenkinsFile = Resources.getResource(ParallelNavigationTest.class, "ParallelNavigationTest/Jenkinsfile");
        Files.copy(new File(jenkinsFile.getFile()), new File(git.gitDirectory, "Jenkinsfile"));
        git.addAll();
        git.commit("initial commit");
        logger.info("Committed Jenkinsfile");

        MultiBranchPipeline pipeline = mbpFactory.pipeline(pipelineName).createPipeline(git);
        pipeline.getRunDetailsPipelinePage().open(1);

        // at first we see branch one
        wait.until(By.xpath("//*[text()=\"firstBranch\"]"));

        // and clicking on the unselected node will yield us the second branch
        wait.click(By.xpath("//*[contains(@class, 'pipeline-node')][3]"));
        wait.until(By.xpath("//*[text()=\"secondBranch\"]"));

        logger.info("Stopping all Pipeline runs");
        pipeline.stopAllRuns();

        /*
        TODO:
        Navigate out of this place before the delete, to circumvent the unnecessary
        browser console error messages I see after doing the delete, but with the
        pipeline still on screen.
         */

        // This will provide its own logger message, no need for another.
        pipeline.deleteThisPipeline(pipeline.getName());
    }


    /**
     * This checks that you can have input on 2 different parallel branches. There are no stages either side of the parallel construct.
     * One at a time, the proceed button will be clicked.
     */
    @Test
    public void parallelNavigationTestInput () throws IOException, GitAPIException, InterruptedException {
        String pipelineName = "ParallelNavigationTest_tested_input";

        URL jenkinsFile = Resources.getResource(ParallelNavigationTest.class, "ParallelNavigationTest/Jenkinsfile.input");
        Files.copy(new File(jenkinsFile.getFile()), new File(git.gitDirectory, "Jenkinsfile"));
        git.addAll();
        git.commit("initial commit");
        logger.info("Commited Jenkinsfile");

        MultiBranchPipeline pipeline = mbpFactory.pipeline(pipelineName).createPipeline(git);
        pipeline.getRunDetailsPipelinePage().open(1);

        // at first we see branch one
        wait.until(By.xpath("//*[text()=\"firstBranch\"]"));
        wait.until(By.cssSelector(".btn.inputStepSubmit")).click();

        // and clicking on the unselected node will yield us the second branch
        wait.click(By.xpath("//*[contains(@class, 'pipeline-node')][3]"));
        wait.until(By.xpath("//*[text()=\"secondBranch\"]"));
        wait.click(By.cssSelector(".btn.inputStepSubmit"));
    }


}
