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

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

public class ProgramSavedListenerTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void notifiedWithConsistentSavePoints() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo 'one'; semaphore 'wait'; echo 'two'", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait/1", b);

        Recorder rec = r.jenkins.getExtensionList(ProgramSavedListener.class).get(Recorder.class);
        // saved once the CPS VM thread goes idle, just after the step started
        await().atMost(Duration.ofSeconds(30)).until(() -> !rec.saves.isEmpty());
        Save last = rec.saves.get(rec.saves.size() - 1);
        assertEquals(b.getExecution(), last.execution);
        assertEquals(new File(b.getRootDir(), "program.dat"), last.file);
        assertTrue("program.dat existed when notified", last.existed);
        assertTrue("build.xml existed when notified", last.recordExisted);

        int before = rec.saves.size();
        SemaphoreStep.success("wait/1", null);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        assertThat("saved again as the program advanced", rec.saves.size(), greaterThan(before));
    }

    record Save(CpsFlowExecution execution, File file, boolean existed, boolean recordExisted) {}

    @TestExtension
    public static class Recorder implements ProgramSavedListener {
        final List<Save> saves = new CopyOnWriteArrayList<>();

        @Override
        public void onProgramSaved(CpsFlowExecution execution, File programDat) {
            File record;
            try {
                record = new File(execution.getOwner().getRootDir(), "build.xml");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            saves.add(new Save(execution, programDat, programDat.isFile(), record.isFile()));
        }
    }
}
