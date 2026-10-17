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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.util.concurrent.FutureCallback;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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

    /** Saves and notifications in the order they happened, for builds of job {@code p}. */
    static final List<String> events = new CopyOnWriteArrayList<>();

    @Test
    public void notifiedWithConsistentSavePoints() throws Exception {
        events.clear();
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

        int before = rec.saves.size();
        SemaphoreStep.success("wait/1", null);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        assertThat("saved again as the program advanced", rec.saves.size(), greaterThan(before));

        // every notification comes right after a save of the build record
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).equals("notified")) {
                assertTrue("build.xml saved before notification " + i + " in " + events, i > 0);
                assertEquals("build.xml saved before notification " + i + " in " + events, "saved", events.get(i - 1));
            }
        }
    }

    @Test
    public void throwingListenerDoesNotAffectBuild() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo 'one'; echo 'two'", true));
        r.buildAndAssertSuccess(p);
        Thrower thrower = r.jenkins.getExtensionList(ProgramSavedListener.class).get(Thrower.class);
        assertThat("listener was called", thrower.calls, greaterThan(0));
        Recorder rec = r.jenkins.getExtensionList(ProgramSavedListener.class).get(Recorder.class);
        assertThat("other listeners still notified", rec.saves.size(), greaterThan(0));
    }

    @Test
    public void notNotifiedWhenResumeIsBlocked() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setResumeBlocked(true);
        p.setDefinition(new CpsFlowDefinition("echo 'one'; semaphore 'wait'; echo 'two'", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait/1", b);
        SemaphoreStep.success("wait/1", null);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        Recorder rec = r.jenkins.getExtensionList(ProgramSavedListener.class).get(Recorder.class);
        assertThat(rec.saves, empty());
    }

    @Test
    public void notNotifiedForSaveToExplicitFile() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("semaphore 'wait'", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait/1", b);
        Recorder rec = r.jenkins.getExtensionList(ProgramSavedListener.class).get(Recorder.class);
        CpsFlowExecution execution = (CpsFlowExecution) b.getExecution();
        File copy = new File(b.getRootDir(), "copy.dat");
        CompletableFuture<Integer> notifiedBefore = new CompletableFuture<>();
        execution.runInCpsVmThread(new FutureCallback<>() {
            @Override
            public void onSuccess(CpsThreadGroup g) {
                try {
                    // on the CPS VM thread, so no other save can interleave
                    int n = rec.saves.size();
                    g.saveProgram(copy);
                    notifiedBefore.complete(rec.saves.size() - n);
                } catch (Throwable t) {
                    notifiedBefore.completeExceptionally(t);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                notifiedBefore.completeExceptionally(t);
            }
        });
        assertEquals("no notification for the explicit file", 0, (int) notifiedBefore.get(30, TimeUnit.SECONDS));
        assertTrue(copy.isFile());
        SemaphoreStep.success("wait/1", null);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    record Save(CpsFlowExecution execution, File file, boolean existed) {}

    @TestExtension
    public static class Recorder implements ProgramSavedListener {
        final List<Save> saves = new CopyOnWriteArrayList<>();

        @Override
        public void onProgramSaved(CpsFlowExecution execution, File programDat) {
            saves.add(new Save(execution, programDat, programDat.isFile()));
            events.add("notified");
        }
    }

    @TestExtension("notifiedWithConsistentSavePoints")
    public static class RecordSaves extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof WorkflowRun run && run.getParent().getName().equals("p")) {
                events.add("saved");
            }
        }
    }

    @TestExtension("throwingListenerDoesNotAffectBuild")
    public static class Thrower implements ProgramSavedListener {
        volatile int calls;

        @Override
        public void onProgramSaved(CpsFlowExecution execution, File programDat) {
            calls++;
            throw new LinkageError("simulated plugin mismatch");
        }
    }
}
