package io.blueocean.ath.pages.blue;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import io.blueocean.ath.BaseUrl;
import io.blueocean.ath.Locate;
import io.blueocean.ath.WaitUtil;
import io.blueocean.ath.WebDriverMixin;
import io.blueocean.ath.factory.ActivityPageFactory;
import io.blueocean.ath.factory.PullRequestsPageFactory;
import io.blueocean.ath.factory.RunDetailsPipelinePageFactory;
import io.blueocean.ath.model.AbstractPipeline;
import org.apache.log4j.Logger;
import org.eclipse.jgit.annotations.Nullable;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.PageFactory;
import org.openqa.selenium.support.ui.ExpectedConditions;

import javax.inject.Inject;

public class PullRequestsPage implements WebDriverMixin {
    private Logger logger = Logger.getLogger(BranchPage.class);

    private WebDriver driver;
    private AbstractPipeline pipeline;

    @Inject
    PullRequestsPageFactory pullRequestsPageFactory;

    @Inject
    ActivityPageFactory activityPageFactory;

    @Inject
    RunDetailsPipelinePageFactory runDetailsPipelinePageFactory;

    @Inject
    WaitUtil wait;

    @Inject
    EditorPage editorPage;

    @Inject
    public PullRequestsPage(WebDriver driver) {
        this.driver = driver;
        PageFactory.initElements(driver, this);
    }

    @AssistedInject
    public PullRequestsPage(WebDriver driver, @Assisted @Nullable AbstractPipeline pipeline) {
        this.pipeline = pipeline;
        this.driver = driver;
        PageFactory.initElements(driver, this);
    }

    public PullRequestsPage checkUrl() {
        wait.until(ExpectedConditions.urlContains(pipeline.getUrl() + "/branches"), 30000);
        wait.until(By.cssSelector("div.multibranch-table"));
        return this;
    }

    public ActivityPage clickHistoryButton(String branch) {
        wait.until(By.cssSelector("div[data-branch='" + branch + "'] a.history-button")).click();
        logger.info("Clicked history button of branch " + branch);
        return activityPageFactory.withPipeline(pipeline).checkUrl(branch);
    }

    public EditorPage openEditor(String branch) {
        wait.until(By.cssSelector("div[data-branch='" + branch + "'] a.pipeline-editor-link")).click();
        logger.info("Clicked Editor button of branch " + branch);
        return editorPage;
    }

    /**
     * Check whether the specified branch is favorited (or not)
     * @param branchName
     * @param isFavorite
     * @return builder
     */
    public PullRequestsPage checkFavoriteStatus(String branchName, boolean isFavorite) {
        WebElement favorite = findBranchRow(branchName).findElement(By.cssSelector(".Favorite input"));
        wait.until(ExpectedConditions.elementSelectionStateToBe(favorite, isFavorite));
        return this;
    }

    /**
     * Toggle the favorite status for specified branch
     * @param branchName
     * @return builder
     */
    public PullRequestsPage toggleFavoriteStatus(String branchName) {
        WebElement favorite = findBranchRow(branchName).findElement(By.cssSelector(".Favorite label"));
        wait.click(Locate.byElem(favorite));
        return this;
    }

    /**
     * Open run details for the specified branch (by clicking on its row)
     * @param branchName
     * @return
     */
    public RunDetailsPipelinePage openRunDetails(String branchName) {
        findBranchRow(branchName).click();
        return runDetailsPipelinePageFactory.withPipeline(pipeline);
    }

    private WebElement findBranchRow(String branchName) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("div[data-branch='" + branchName + "']")));
    }
}
