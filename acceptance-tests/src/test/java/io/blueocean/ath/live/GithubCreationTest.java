package io.blueocean.ath.live;

import com.google.common.io.Files;
import com.google.common.io.Resources;
import io.blueocean.ath.ATHJUnitRunner;
import io.blueocean.ath.CustomJenkinsServer;
import io.blueocean.ath.Login;
import io.blueocean.ath.Retry;
import io.blueocean.ath.factory.MultiBranchPipelineFactory;
import io.blueocean.ath.model.MultiBranchPipeline;
import io.blueocean.ath.pages.blue.ActivityPage;
import io.blueocean.ath.pages.blue.BranchPage;
import io.blueocean.ath.pages.blue.DashboardPage;
import io.blueocean.ath.pages.blue.EditorPage;
import io.blueocean.ath.pages.blue.GithubCreationPage;
import io.blueocean.ath.pages.blue.PullRequestsPage;
import io.blueocean.ath.sse.SSEClientRule;
import org.apache.log4j.Logger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.kohsuke.github.GHContentUpdateResponse;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import javax.inject.Inject;
import javax.validation.constraints.AssertTrue;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Properties;

@Login
@RunWith(ATHJUnitRunner.class)
public class GithubCreationTest{
    private Logger logger = Logger.getLogger(GithubCreationTest.class);

    private Properties props = new Properties();
    private String token;
    private String organization;
    private String repo;
    private Boolean deleteRepo = false;
    private Boolean randomSuffix = false;

    private GitHub github;
    private GHRepository ghRepository;

    private MultiBranchPipeline testCreatePullRequestPipeline = null;

    @Inject
    GithubCreationPage creationPage;

    @Inject
    MultiBranchPipelineFactory mbpFactory;

    @Inject @Rule
    public SSEClientRule sseClient;

    @Inject ActivityPage activityPage;

    @Inject BranchPage branchPage;

    @Inject EditorPage editorPage;

    @Inject DashboardPage dashboardPage;

    @Inject PullRequestsPage pullRequestsPage;

    @Inject
    CustomJenkinsServer jenkins;

    /**
     * Cleans up repository after the test has completed.
     *
     * @throws IOException
     */
    @After
    public void deleteRepository() throws IOException {
        if(deleteRepo) {
            try {
                GHRepository repositoryToDelete = github.getRepository(organization + "/" + repo);
                repositoryToDelete.delete();
                logger.info("Deleted repository " + repo);
            } catch (FileNotFoundException e) {

            }
        }
    }

    /**
     * Every test in this class gets a blank github repository created for them.
     *
     * @throws IOException
     */
    @Before
    public void createBlankRepo() throws IOException {
        props.load(new FileInputStream("live.properties"));
        token = props.getProperty("github.token");
        organization = props.getProperty("github.org");
        repo = props.getProperty("github.repo");
        deleteRepo = Boolean.parseBoolean(props.getProperty("github.deleteRepo", "false"));
        randomSuffix = Boolean.parseBoolean(props.getProperty("github.randomSuffix", "false"));

        Assert.assertNotNull(token);
        Assert.assertNotNull(organization);
        Assert.assertNotNull(repo);

        logger.info("Loaded test properties");
        if(randomSuffix) {
            SecureRandom random = new SecureRandom();
            repo = repo + "-" + new BigInteger(50, random).toString(16);
        }

        github = GitHub.connectUsingOAuth(token);
        Assert.assertTrue(github.isCredentialValid());
        logger.info("Github credentials are valid");

        deleteRepository();

        ghRepository = github.createRepository(repo)
            .autoInit(true)
            .create();
        logger.info("Created repository " + repo);
    }

    /**
     * Create our MultiBranchPipeline object here
     */
    public MultiBranchPipeline createMultiBranchPipeline(String repo) {
        testCreatePullRequestPipeline = mbpFactory.pipeline(repo);
        return testCreatePullRequestPipeline;
    }

    /**
     * This test tests the github creation flow.
     *
     * Creates a github repo with a sample Jenkinsfile
     *
     */
    @Test
   // @Retry(3)
    public void testCreatePipelineFull() throws IOException {
        byte[] content = "stage('build') { echo 'yes' }".getBytes("UTF-8");
        GHContentUpdateResponse updateResponse = ghRepository.createContent(content, "Jenkinsfile", "Jenkinsfile", "master");
        ghRepository.createRef("refs/heads/branch1", updateResponse.getCommit().getSHA1());
        logger.info("Created master and branch1 branches in " + repo);
        ghRepository.createContent("hi there","newfile", "newfile", "branch1");

        creationPage.createPipeline(token, organization, repo);
    }

    /**
     * This test walks through a Pull Request flow..
     *
     * -Creates a github repo with a sample Jenkinsfile and follows
     *  the typical create flow
     * -Navigates back to the top level Dashboard page
     * -Creates a PR in the GH repo
     * -Triggers a rescan of the multibranch pipeline
     * -Opens the PR tab to verify that we actually have something there.
     *
     */
    @Test
    public void testCreatePullRequest() throws IOException {
        String branchToCreate = "new-branch";
        // Initialize our MultiBranchPipeline object as `repo` so that we
        // can trigger a rescan of it.
        // testCreatePullRequestPipeline = mbpFactory.pipeline(repo);
        createMultiBranchPipeline(repo);
        byte[] firstJenkinsfile = "stage('first-build') { echo 'first-build' }".getBytes("UTF-8");
        GHContentUpdateResponse initialUpdateResponse = ghRepository.createContent(firstJenkinsfile, "firstJenkinsfile", "Jenkinsfile", "master");
        ghRepository.createRef(("refs/heads/" + branchToCreate), initialUpdateResponse.getCommit().getSHA1());
        logger.info("Created master and " + branchToCreate + " branches in " + repo);

        ghRepository.createContent("hi there","newfile", "new-file", branchToCreate);
        creationPage.createPipeline(token, organization, repo);
        dashboardPage.open();
        ghRepository.createPullRequest(
            "Add new-file to our repo",
            branchToCreate,
            "master",
            "My first pull request is very exciting.");
        // Fire the rescan.
        testCreatePullRequestPipeline.rescanThisPipeline();
        // Right now we're at dashboard page, so we need to first click on
        // the name of the pipeline we just created
        dashboardPage.clickPipeline(repo);
        logger.info("Clicked the pipeline " + repo + " on dashboardPage");
        // Navigate to the pullRequestsPage
        pullRequestsPage.open(repo);
        pullRequestsPage.getCurrentUrl();
    }

    @Test
    @Retry(3)
    public void testTokenValidation_failed() throws IOException {
        jenkins.deleteUserDomainCredential("alice", "blueocean-github-domain", "github");
        creationPage.navigateToCreation();
        creationPage.selectGithubCreation();
        creationPage.validateGithubOauthToken("foo");
        creationPage.findFormErrorMessage("Invalid access token.");
    }
}
