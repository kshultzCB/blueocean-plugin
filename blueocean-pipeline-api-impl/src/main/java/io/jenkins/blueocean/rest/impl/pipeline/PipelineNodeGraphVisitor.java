package io.jenkins.blueocean.rest.impl.pipeline;

import com.google.common.base.Predicate;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jenkins.blueocean.rest.Reachable;
import io.jenkins.blueocean.rest.hal.Link;
import io.jenkins.blueocean.rest.model.BluePipelineNode;
import io.jenkins.blueocean.rest.model.BluePipelineStep;
import io.jenkins.blueocean.rest.model.BlueRun;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.NotExecutedNodeAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.ForkScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.MemoryFlowChunk;
import org.jenkinsci.plugins.workflow.graphanalysis.StandardChunkVisitor;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.GenericStatus;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.StageChunkFinder;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.StatusAndTiming;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.TimingInfo;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * @author Vivek Pandey
 */
public class PipelineNodeGraphVisitor extends StandardChunkVisitor implements NodeGraphBuilder{
    private final WorkflowRun run;

    private final ArrayDeque<FlowNodeWrapper> parallelBranches = new ArrayDeque<>();

    public final ArrayDeque<FlowNodeWrapper> nodes = new ArrayDeque<>();

    private FlowNode firstExecuted = null;

    private FlowNodeWrapper nextStage;

    private FlowNode parallelEnd;

    public final Map<String, FlowNodeWrapper> nodeMap = new LinkedHashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(PipelineNodeGraphVisitor.class);

    private static final boolean isNodeVisitorDumpEnabled = Boolean.getBoolean("NODE-DUMP-ENABLED");

    private final Stack<FlowNode> nestedStages = new Stack<>();
    private final Stack<FlowNode> nestedbranches = new Stack<>();

    private final ArrayDeque<FlowNode> pendingInputSteps = new ArrayDeque<>();
    private final ArrayDeque<FlowNode> queuedNodes = new ArrayDeque<>();
    private final ArrayDeque<FlowNode> runningNodes = new ArrayDeque<>();

    private final Stack<FlowNode> parallelBranchEndNodes = new Stack<>();

    private final InputAction inputAction;

    private StepStartNode agentNode = null;

    private final static String PARALLEL_SYNTHETIC_STAGE_NAME = "Parallel";

    public PipelineNodeGraphVisitor(WorkflowRun run) {
        this.run = run;
        this.inputAction = run.getAction(InputAction.class);
        FlowExecution execution = run.getExecution();
        if(execution!=null) {
            ForkScanner.visitSimpleChunks(execution.getCurrentHeads(), this, new StageChunkFinder());
        }
    }

    @Override
    public void chunkStart(@Nonnull FlowNode startNode, @CheckForNull FlowNode beforeBlock, @Nonnull ForkScanner scanner) {
        super.chunkStart(startNode, beforeBlock, scanner);
        if(isNodeVisitorDumpEnabled)
            dump(String.format("chunkStart=> id: %s, name: %s, function: %s", startNode.getId(),
                    startNode.getDisplayName(), startNode.getDisplayFunctionName()));

        if(PipelineNodeUtil.isSyntheticStage(startNode)){
            return;
        }
        if (NotExecutedNodeAction.isExecuted(startNode)) {
            firstExecuted = startNode;
        }

    }

    @Override
    public void chunkEnd(@Nonnull FlowNode endNode, @CheckForNull FlowNode afterBlock, @Nonnull ForkScanner scanner) {
        super.chunkEnd(endNode, afterBlock, scanner);

        if(isNodeVisitorDumpEnabled)
            dump(String.format("chunkEnd=> id: %s, name: %s, function: %s, type:%s", endNode.getId(),
                    endNode.getDisplayName(), endNode.getDisplayFunctionName(), endNode.getClass()));

        if(isNodeVisitorDumpEnabled && endNode instanceof StepEndNode){
            dump("\tStartNode: "+((StepEndNode) endNode).getStartNode());
        }

        if (endNode instanceof StepStartNode && PipelineNodeUtil.isAgentStart(endNode)) {
            agentNode = (StepStartNode) endNode;
        }

        // capture orphan branches
        captureOrphanParallelBranches();

        //if block stage node push it to stack as it may have nested stages
        if(parallelEnd == null &&
                endNode instanceof StepEndNode
                && !PipelineNodeUtil.isSyntheticStage(((StepEndNode) endNode).getStartNode()) //skip synthetic stages
                && PipelineNodeUtil.isStage(((StepEndNode) endNode).getStartNode())) {


            //XXX: There seems to be bug in eventing, chunkEnd is sent twice for the same FlowNode
            //     Lets peek and if the last one is same as this endNode then skip adding it
            FlowNode node=null;
            if(!nestedStages.empty()){
                node = nestedStages.peek();
            }
            if(node == null || !node.equals(endNode)){
                nestedStages.push(endNode);
            }

        }
        firstExecuted = null;

        // if we're using marker-based (and not block-scoped) stages, add the last node as part of its contents
        if (!(endNode instanceof BlockEndNode)) {
            atomNode(null, endNode, afterBlock, scanner);
        }
    }

    // This gets triggered on encountering a new chunk (stage or branch)
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification = "chunk.getLastNode() is marked non null but is null sometimes, when JENKINS-40200 is fixed we will remove this check ")
    @Override
    protected void handleChunkDone(@Nonnull MemoryFlowChunk chunk) {
        if(isNodeVisitorDumpEnabled)
            dump(String.format("handleChunkDone=> id: %s, name: %s, function: %s", chunk.getFirstNode().getId(),
                    chunk.getFirstNode().getDisplayName(), chunk.getFirstNode().getDisplayFunctionName()));

        if(PipelineNodeUtil.isSyntheticStage(chunk.getFirstNode())){
            return;
        }

        // its stage inside parallel, we skip it and clear nest stages collected inside parallel
        if(parallelEnd != null){
            return;
        }

        if(!nestedStages.empty()){
            nestedStages.pop(); //we throw away nested stages
            if(!nestedStages.empty()){ //there is still a nested stage, return
                return;
            }
        }

        TimingInfo times = null;

        //TODO: remove chunk.getLastNode() != null check based on how JENKINS-40200 gets resolved
        if (firstExecuted != null && chunk.getLastNode() != null) {
            times = StatusAndTiming.computeChunkTiming(run, chunk.getPauseTimeMillis(), firstExecuted, chunk.getLastNode(), chunk.getNodeAfter());
        }

        if(times == null){
            times = new TimingInfo();
        }

        NodeRunStatus status;
        boolean skippedStage = PipelineNodeUtil.isSkippedStage(chunk.getFirstNode());
        if(skippedStage){
            status = new NodeRunStatus(BlueRun.BlueRunResult.NOT_BUILT, BlueRun.BlueRunState.SKIPPED);
        }else if (firstExecuted == null) {
            status = new NodeRunStatus(GenericStatus.NOT_EXECUTED);
        }else if(chunk.getLastNode() != null){
            status = new NodeRunStatus(StatusAndTiming
                    .computeChunkStatus2(run, chunk.getNodeBefore(),
                            firstExecuted, chunk.getLastNode(), chunk.getNodeAfter()));
        }else{
            status = new NodeRunStatus(firstExecuted);
        }

        if (!pendingInputSteps.isEmpty()) {
            status = new NodeRunStatus(BlueRun.BlueRunResult.UNKNOWN, BlueRun.BlueRunState.PAUSED);
        }
        FlowNodeWrapper stage = new FlowNodeWrapper(chunk.getFirstNode(),
                status, times, run);

        stage.setCauseOfFailure(PipelineNodeUtil.getCauseOfBlockage(stage.getNode(), agentNode));

        nodes.push(stage);
        nodeMap.put(stage.getId(), stage);
        if(!skippedStage && !parallelBranches.isEmpty()){
            Iterator<FlowNodeWrapper> branches = parallelBranches.descendingIterator();
            while(branches.hasNext()){
                FlowNodeWrapper p = branches.next();
                p.addParent(stage);
                stage.addEdge(p);
            }
        }else{
            if(nextStage != null) {
                nextStage.addParent(stage);
                stage.addEdge(nextStage);
            }
        }
        parallelBranches.clear();
        this.nextStage = stage;
    }

    @Override
    protected void resetChunk(@Nonnull MemoryFlowChunk chunk) {
        super.resetChunk(chunk);
        firstExecuted = null;
        pendingInputSteps.clear();
    }

    @Override
    public void parallelStart(@Nonnull FlowNode parallelStartNode, @Nonnull FlowNode branchNode, @Nonnull ForkScanner scanner) {
        if(isNodeVisitorDumpEnabled) {
            dump(String.format("parallelStart=> id: %s, name: %s, function: %s", parallelStartNode.getId(),
                    parallelStartNode.getDisplayName(), parallelStartNode.getDisplayFunctionName()));
            dump(String.format("\tbranch=> id: %s, name: %s, function: %s", branchNode.getId(),
                    branchNode.getDisplayName(), branchNode.getDisplayFunctionName()));
        }

        if(nestedbranches.size() != parallelBranchEndNodes.size()){
            logger.error(String.format("nestedBranches size: %s not equal to parallelBranchEndNodes: %s",
                    nestedbranches.size(), parallelBranchEndNodes.size()));
            return;
        }

        while(!nestedbranches.empty() && !parallelBranchEndNodes.empty()){
            FlowNode branchStartNode = nestedbranches.pop();

            FlowNode endNode = parallelBranchEndNodes.pop();

            TimingInfo times;
            NodeRunStatus status;

            if(endNode != null) {
                times = StatusAndTiming.computeChunkTiming(run, chunk.getPauseTimeMillis(), branchStartNode, endNode,
                        chunk.getNodeAfter());
                if(endNode instanceof StepAtomNode){
                    if(PipelineNodeUtil.isPausedForInputStep((StepAtomNode) endNode, inputAction)) {
                        status = new NodeRunStatus(BlueRun.BlueRunResult.UNKNOWN, BlueRun.BlueRunState.PAUSED);
                    }else{
                        status = new NodeRunStatus(endNode);
                    }
                }else {
                    GenericStatus genericStatus = StatusAndTiming.computeChunkStatus2(run,
                            parallelStartNode, branchStartNode, endNode, parallelEnd);
                    status = new NodeRunStatus(genericStatus);
                }
            }else{
                long startTime = System.currentTimeMillis();
                if (branchStartNode.getAction(TimingAction.class) != null) {
                    startTime = TimingAction.getStartTime(branchStartNode);
                }
                times = new TimingInfo(System.currentTimeMillis() - startTime,
                        chunk.getPauseTimeMillis(),
                        startTime);
                status = new NodeRunStatus(BlueRun.BlueRunResult.UNKNOWN, BlueRun.BlueRunState.RUNNING);
            }
            assert times != null; //keep FB happy

            FlowNodeWrapper branch = new FlowNodeWrapper(branchStartNode, status, times, run);

            if(nextStage!=null) {
                branch.addEdge(nextStage);
            }
            parallelBranches.push(branch);
        }

        FlowNodeWrapper[] sortedBranches = parallelBranches.toArray(new FlowNodeWrapper[parallelBranches.size()]);
        Arrays.sort(sortedBranches, new Comparator<FlowNodeWrapper>() {
            @Override
            public int compare(FlowNodeWrapper o1, FlowNodeWrapper o2) {
                return o1.getDisplayName().compareTo(o2.getDisplayName());
            }
        });

        parallelBranches.clear();
        for(int i=0; i< sortedBranches.length; i++){
            parallelBranches.push(sortedBranches[i]);
        }
        for(FlowNodeWrapper p:parallelBranches){
            nodes.push(p);
            nodeMap.put(p.getId(), p);
        }

        //reset parallelEnd node for next parallel block
        this.parallelEnd = null;
    }

    @Override
    public void parallelEnd(@Nonnull FlowNode parallelStartNode, @Nonnull FlowNode parallelEndNode, @Nonnull ForkScanner scanner) {
        if(isNodeVisitorDumpEnabled) {
            dump(String.format("parallelEnd=> id: %s, name: %s, function: %s", parallelEndNode.getId(),
                    parallelEndNode.getDisplayName(), parallelEndNode.getDisplayFunctionName()));
            if(parallelEndNode instanceof StepEndNode){
                dump(String.format("parallelEnd=> id: %s, StartNode: %s, name: %s, function: %s", parallelEndNode.getId(),
                        ((StepEndNode) parallelEndNode).getStartNode().getId(),((StepEndNode) parallelEndNode).getStartNode().getDisplayName(), ((StepEndNode) parallelEndNode).getStartNode().getDisplayFunctionName()));
            }
        }
        captureOrphanParallelBranches();
        this.parallelEnd = parallelEndNode;
    }

    @Override
    public void parallelBranchStart(@Nonnull FlowNode parallelStartNode, @Nonnull FlowNode branchStartNode, @Nonnull ForkScanner scanner) {
        if(isNodeVisitorDumpEnabled)
            dump(String.format("parallelBranchStart=> id: %s, name: %s, function: %s", branchStartNode.getId(),
                    branchStartNode.getDisplayName(), branchStartNode.getDisplayFunctionName()));

        nestedbranches.push(branchStartNode);
    }

    @Override
    public void parallelBranchEnd(@Nonnull FlowNode parallelStartNode, @Nonnull FlowNode branchEndNode, @Nonnull ForkScanner scanner) {
        if(isNodeVisitorDumpEnabled) {
            dump(String.format("parallelBranchEnd=> id: %s, name: %s, function: %s, type: %s", branchEndNode.getId(),
                    branchEndNode.getDisplayName(), branchEndNode.getDisplayFunctionName(), branchEndNode.getClass()));
            if(branchEndNode instanceof StepEndNode){
                dump(String.format("parallelBranchEnd=> id: %s, StartNode: %s, name: %s, function: %s", branchEndNode.getId(),
                        ((StepEndNode) branchEndNode).getStartNode().getId(),((StepEndNode) branchEndNode).getStartNode().getDisplayName(),
                        ((StepEndNode) branchEndNode).getStartNode().getDisplayFunctionName()));
            }

        }
        parallelBranchEndNodes.add(branchEndNode);
    }

    @Override
    public void atomNode(@CheckForNull FlowNode before, @Nonnull FlowNode atomNode,
                         @CheckForNull FlowNode after, @Nonnull ForkScanner scan) {
        if(isNodeVisitorDumpEnabled)
            dump(String.format("atomNode=> id: %s, name: %s, function: %s, type: %s", atomNode.getId(),
                    atomNode.getDisplayName(), atomNode.getDisplayFunctionName(), atomNode.getClass()));

        if(atomNode instanceof FlowStartNode){
            captureOrphanParallelBranches();
            return;
        }

        if (NotExecutedNodeAction.isExecuted(atomNode)) {
            firstExecuted = atomNode;
        }
        long pause = PauseAction.getPauseDuration(atomNode);
        chunk.setPauseTimeMillis(chunk.getPauseTimeMillis()+pause);

        if(atomNode instanceof StepAtomNode
                && PipelineNodeUtil.isPausedForInputStep((StepAtomNode) atomNode, inputAction)){
            pendingInputSteps.add(atomNode);
        }
    }

    private void dump(String str){
        System.out.println(str);
    }

    @Override
    public List<FlowNodeWrapper> getPipelineNodes() {
        return new ArrayList<>(nodes);
    }

    @Override
    public List<BluePipelineNode> getPipelineNodes(final Link parent) {
        List<BluePipelineNode> nodes = new ArrayList<>();
        for(FlowNodeWrapper n: this.nodes){
            nodes.add(new PipelineNodeImpl(n,new Reachable() {
                @Override
                public Link getLink() {
                    return parent;
                }
            }, run));
        }
        return nodes;
    }

    @Override
    public List<BluePipelineStep> getPipelineNodeSteps(final String nodeId, Link parent) {
        FlowExecution execution = run.getExecution();
        if(execution == null){
            logger.debug(String.format("Pipeline %s, runid %s  has null execution", run.getParent().getName(), run.getId()));
            return Collections.emptyList();
        }
        DepthFirstScanner depthFirstScanner = new DepthFirstScanner();
        //If blocked scope, get the end node
        FlowNode n = depthFirstScanner.findFirstMatch(execution.getCurrentHeads(), new Predicate<FlowNode>() {
            @Override
            public boolean apply(@Nullable FlowNode input) {
                return (input!= null && input.getId().equals(nodeId) &&
                        (PipelineNodeUtil.isStage(input) || PipelineNodeUtil.isParallelBranch(input)));
            }
        });

        if(n == null){ //if no node found or the node is not stage or parallel we return empty steps
            return Collections.emptyList();
        }
        PipelineStepVisitor visitor = new PipelineStepVisitor(run, n);
        ForkScanner.visitSimpleChunks(execution.getCurrentHeads(), visitor, new StageChunkFinder());
        List<BluePipelineStep> steps = new ArrayList<>();
        for(FlowNodeWrapper node: visitor.getSteps()){
            steps.add(new PipelineStepImpl(node, parent));
        }
        return steps;
    }

    @Override
    public List<BluePipelineStep> getPipelineNodeSteps(Link parent) {
        FlowExecution execution = run.getExecution();
        if(execution == null){
            return Collections.emptyList();
        }
        PipelineStepVisitor visitor = new PipelineStepVisitor(run, null);
        ForkScanner.visitSimpleChunks(execution.getCurrentHeads(), visitor, new StageChunkFinder());
        List<BluePipelineStep> steps = new ArrayList<>();
        for(FlowNodeWrapper node: visitor.getSteps()){
            steps.add(new PipelineStepImpl(node, parent));
        }
        return steps;
    }

    @Override
    public BluePipelineStep getPipelineNodeStep(String id, Link parent) {
        FlowExecution execution = run.getExecution();
        if(execution == null){
            return null;
        }
        PipelineStepVisitor visitor = new PipelineStepVisitor(run, null);
        ForkScanner.visitSimpleChunks(execution.getCurrentHeads(), visitor, new StageChunkFinder());
        FlowNodeWrapper node = visitor.getStep(id);
        if( node == null){
            return null;
        }
        return new PipelineStepImpl(node, parent);
    }

    @Override
    public List<BluePipelineNode> union(List<FlowNodeWrapper> that, final Link parent) {
        List<FlowNodeWrapper> currentNodes = new ArrayList<>(nodes);
        int currentNodeSize = nodes.size();
        int futureNodeSize = that.size();
        if(currentNodeSize < futureNodeSize){
            for(int i=currentNodeSize; i < futureNodeSize; i++){
                FlowNodeWrapper futureNode = that.get(i);

                //stitch future nodes to last nodes of current incomplete heads
                if(currentNodeSize>0 && i == currentNodeSize){
                    FlowNodeWrapper latestNode = currentNodes.get(i-1);
                    if(latestNode.type == FlowNodeWrapper.NodeType.STAGE){
                        if(futureNode.type == FlowNodeWrapper.NodeType.STAGE){
                            latestNode.addEdge(futureNode);
                        }else if(futureNode.type == FlowNodeWrapper.NodeType.PARALLEL){
                            FlowNodeWrapper thatStage = futureNode.getFirstParent();
                            if(thatStage != null && thatStage.equals(latestNode)){
                                for(FlowNodeWrapper edge:thatStage.edges){
                                    if(!latestNode.edges.contains(edge)){
                                        latestNode.addEdge(edge);
                                    }
                                }
                            }
                        }
                    }else if(latestNode.type == FlowNodeWrapper.NodeType.PARALLEL){
                        FlowNodeWrapper futureStage = null;
                        FlowNodeWrapper thatStage = null;
                        FlowNodeWrapper futureNodeParent = futureNode.getFirstParent();
                        if(futureNode.type == FlowNodeWrapper.NodeType.STAGE){
                            thatStage = futureNode;
                            futureStage = futureNode;
                        }else if(futureNode.type == FlowNodeWrapper.NodeType.PARALLEL &&
                                futureNodeParent != null &&
                                futureNodeParent.equals(latestNode.getFirstParent())){
                            thatStage = futureNode.getFirstParent();
                            if(futureNode.edges.size() > 0){
                                futureStage = futureNode.edges.get(0);
                            }
                        }
                        FlowNodeWrapper stage = latestNode.getFirstParent();
                        if(stage != null){
                            //Add future node as edge to all edges of last stage
                            for(FlowNodeWrapper edge:stage.edges){
                                FlowNodeWrapper node = nodeMap.get(edge.getId());
                                if(node != null && futureStage != null) {
                                    node.addEdge(futureStage);
                                }
                            }

                            //now patch edges in case its partial
                            if(thatStage != null && futureNode.type == FlowNodeWrapper.NodeType.PARALLEL) {
                                for (FlowNodeWrapper edge : thatStage.edges) {
                                    if (!stage.edges.contains(edge)) {
                                        stage.addEdge(edge);
                                    }
                                }
                            }
                        }
                    }
                }
                FlowNodeWrapper n = new FlowNodeWrapper(futureNode.getNode(),
                        new NodeRunStatus(null,null),
                        new TimingInfo(), run);
                n.addEdges(futureNode.edges);
                n.addParents(futureNode.getParents());
                currentNodes.add(n);
            }
        }
        List<BluePipelineNode> newNodes = new ArrayList<>();
        for(FlowNodeWrapper n: currentNodes){
            newNodes.add(new PipelineNodeImpl(n,new Reachable() {
                @Override
                public Link getLink() {
                    return parent;
                }
            },run));
        }
        return newNodes;
    }


    private void captureOrphanParallelBranches(){
        if(!parallelBranches.isEmpty() && (firstExecuted == null
                || !PipelineNodeUtil.isStage(firstExecuted)
        )){
            FlowNodeWrapper synStage = createParallelSyntheticNode();
            if(synStage!=null) {
                nodes.push(synStage);
                nodeMap.put(synStage.getId(), synStage);
                parallelBranches.clear();
                this.nextStage = synStage;
            }
        }
    }

    /**
     * Create synthetic stage that wraps a parallel block at top level, that is not enclosed inside a stage.
     */
    private @Nullable FlowNodeWrapper createParallelSyntheticNode(){

        if(parallelBranches.isEmpty()){
            return null;
        }
        FlowNodeWrapper firstBranch = parallelBranches.getLast();
        FlowNodeWrapper parallel = firstBranch.getFirstParent();
        String firstNodeId = firstBranch.getId();
        List<FlowNode> parents;
        if(parallel != null){
            parents = parallel.getNode().getParents();
        }else{
            parents = new ArrayList<>();
        }
        FlowNode syntheticNode = new FlowNode(firstBranch.getNode().getExecution(),
                createSyntheticStageId(firstNodeId, PARALLEL_SYNTHETIC_STAGE_NAME), parents){
            @Override
            public void save() throws IOException {
                // no-op to avoid JENKINS-45892 violations from serializing the synthetic FlowNode.
            }

            @Override
            protected String getTypeDisplayName() {
                return PARALLEL_SYNTHETIC_STAGE_NAME;
            }
        };

        syntheticNode.addAction(new LabelAction(PARALLEL_SYNTHETIC_STAGE_NAME));

        long duration = 0;
        long pauseDuration = 0;
        long startTime = System.currentTimeMillis();
        boolean isCompleted = true;
        boolean isPaused = false;
        boolean isFailure = false;
        boolean isUnknown = false;
        for(FlowNodeWrapper pb: parallelBranches){
            if(!isPaused && pb.getStatus().getState() == BlueRun.BlueRunState.PAUSED){
                isPaused = true;
            }
            if(isCompleted && pb.getStatus().getState() != BlueRun.BlueRunState.FINISHED){
                isCompleted = false;
            }

            if(!isFailure && pb.getStatus().getResult() == BlueRun.BlueRunResult.FAILURE){
                isFailure = true;
            }
            if(!isUnknown && pb.getStatus().getResult() == BlueRun.BlueRunResult.UNKNOWN){
                isUnknown = true;
            }
            duration += pb.getTiming().getTotalDurationMillis();
            pauseDuration += pb.getTiming().getPauseDurationMillis();
        }

        BlueRun.BlueRunState state = isCompleted ? BlueRun.BlueRunState.FINISHED :
                (isPaused ? BlueRun.BlueRunState.PAUSED : BlueRun.BlueRunState.RUNNING);
        BlueRun.BlueRunResult result = isFailure ? BlueRun.BlueRunResult.FAILURE :
                (isUnknown ? BlueRun.BlueRunResult.UNKNOWN : BlueRun.BlueRunResult.SUCCESS);

        TimingInfo timingInfo = new TimingInfo(duration,pauseDuration, startTime);

        FlowNodeWrapper synStage = new FlowNodeWrapper(syntheticNode,new NodeRunStatus(result, state),timingInfo,run);

        Iterator<FlowNodeWrapper> sortedBranches = parallelBranches.descendingIterator();
        while(sortedBranches.hasNext()){
            FlowNodeWrapper p = sortedBranches.next();
            p.addParent(synStage);
            synStage.addEdge(p);
        }
        return synStage;
    }

    /**
     * Create id of synthetic stage in a deterministic base.
     *
     * For example, an orphan parallel block with id 12 (appears top level not wrapped inside a stage) gets wrapped in a synthetic
     * stage with id: 12-parallel-synthetic. Later client calls nodes API using this id: /nodes/12-parallel-synthetic/ would
     * correctly pick the synthetic stage wrapping parallel block 12 by doing a lookup nodeMap.get("12-parallel-synthetic")
     *
     */
    private @Nonnull String createSyntheticStageId(@Nonnull String firstNodeId, @Nonnull String syntheticStageName){
        return String.format("%s-%s-synthetic",firstNodeId, syntheticStageName.toLowerCase());
    }
}
