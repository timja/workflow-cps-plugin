/*
 * The MIT License
 *
 * Copyright (c) 2026, Jenkins project contributors
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

package org.jenkinsci.plugins.workflow.cps;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hudson.model.Executor;
import hudson.model.Result;
import java.util.concurrent.TimeUnit;
import jenkins.model.CauseOfInterruption;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class CpsFlowExecutionHaltTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void haltStopsTheStepsWithoutRunningMoreOfTheProgram() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "try { semaphore 'wait' } catch (e) { echo 'caught' } finally { echo 'finally ran' }; echo 'after'",
                true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait/1", b);
        CpsFlowExecution execution = (CpsFlowExecution) b.getExecution();
        execution
                .halt(new FlowInterruptedException(
                        Result.NOT_BUILT, true, new CauseOfInterruption.UserInterruption("x")))
                .get(30, TimeUnit.SECONDS);
        // the stopped step's outcome was handed to the CPS VM thread: let it go through its queue
        CpsThreadGroup g = execution.programPromise.get();
        g.scheduleRun().get(30, TimeUnit.SECONDS);
        g.scheduleRun().get(30, TimeUnit.SECONDS);
        assertFalse("the program did not end", execution.isComplete());
        assertTrue("nor did the build", b.isBuilding());
        assertNull("not completed", b.getResult());
        String log = JenkinsRule.getLog(b);
        assertThat(log, not(containsString("caught")));
        assertThat(log, not(containsString("finally ran")));
        assertThat(log, not(containsString("after")));
        // the owner lets go of the build without completing it
        FlowExecutionList.get().unregister(b.asFlowExecutionOwner());
        Executor executor = b.getExecutor();
        if (executor != null && executor.getAsynchronousExecution() != null) {
            executor.getAsynchronousExecution().completed(null);
        }
        assertNull(b.getResult());
    }
}
