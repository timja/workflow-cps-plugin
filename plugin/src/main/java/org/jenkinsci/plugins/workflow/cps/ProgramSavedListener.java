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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Notified each time a running build's program state has been saved, for example to replicate it elsewhere
 * so that another controller can resume the build.
 *
 * <p>When this is called, {@code program.dat} has just been atomically replaced, the flow nodes it refers to
 * have been flushed, and the build record reflects the current heads:
 * together these files form a consistent save point from which the build can be resumed.
 * Called on the CPS VM thread, so implementations must be quick (for example copy the files and upload them later)
 * and must not block on the build.
 */
@Restricted(Beta.class)
public interface ProgramSavedListener extends ExtensionPoint {

    /**
     * @param execution the execution whose program was saved
     * @param programDat the file just written
     */
    void onProgramSaved(@NonNull CpsFlowExecution execution, @NonNull File programDat);

    static void fireSaved(@NonNull CpsFlowExecution execution, @NonNull File programDat) {
        for (ProgramSavedListener l : ExtensionList.lookup(ProgramSavedListener.class)) {
            try {
                l.onProgramSaved(execution, programDat);
            } catch (RuntimeException e) {
                Logger.getLogger(ProgramSavedListener.class.getName())
                        .log(Level.WARNING, l + " failed on program save of " + execution, e);
            }
        }
    }
}
