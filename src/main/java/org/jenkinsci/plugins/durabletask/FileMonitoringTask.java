/*
 * The MIT License
 *
 * Copyright 2014 CloudBees, Inc.
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

package org.jenkinsci.plugins.durabletask;

import com.google.common.io.Files;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.DaemonThreadFactory;
import hudson.remoting.NamingThreadFactory;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CountingInputStream;
import javax.annotation.CheckForNull;

/**
 * A task which forks some external command and then waits for log and status files to be updated/created.
 */
public abstract class FileMonitoringTask extends DurableTask {

    private static final Logger LOGGER = Logger.getLogger(FileMonitoringTask.class.getName());

    private static final String COOKIE = "JENKINS_SERVER_COOKIE";

    private static String cookieFor(FilePath workspace) {
        return "durable-" + Util.getDigestOf(workspace.getRemote());
    }

    @Override public final Controller launch(EnvVars env, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
        return launchWithCookie(workspace, launcher, listener, env, COOKIE, cookieFor(workspace));
    }

    protected FileMonitoringController launchWithCookie(FilePath workspace, Launcher launcher, TaskListener listener, EnvVars envVars, String cookieVariable, String cookieValue) throws IOException, InterruptedException {
        envVars.put(cookieVariable, cookieValue); // ensure getCharacteristicEnvVars does not match, so Launcher.killAll will leave it alone
        return doLaunch(workspace, launcher, listener, envVars);
    }

    /**
     * Should start a process which sends output to {@linkplain FileMonitoringController#getLogFile(FilePath) log file}
     * in the workspace and finally writes its exit code to {@linkplain FileMonitoringController#getResultFile(FilePath) result file}.
     * @param workspace the workspace to use
     * @param launcher a way to launch processes
     * @param listener build console log
     * @param envVars recommended environment for the subprocess
     * @return a specialized controller
     */
    protected FileMonitoringController doLaunch(FilePath workspace, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        throw new AbstractMethodError("override either doLaunch or launchWithCookie");
    }

    /**
     * JENKINS-40734: blocks the substitutions of {@link EnvVars#overrideExpandingAll} done by {@link Launcher}.
     */
    protected static Map<String, String> escape(EnvVars envVars) {
        Map<String, String> m = new TreeMap<String, String>();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            m.put(entry.getKey(), entry.getValue().replace("$", "$$"));
        }
        return m;
    }

    /**
     * Tails a log file and watches for an exit status file.
     * Must be remotable so that {@link #watch} can transfer the implementation.
     */
    protected static class FileMonitoringController extends Controller {

        /** Absolute path of {@link #controlDir(FilePath)}. */
        String controlDir;

        /**
         * @deprecated used only in pre-1.8
         */
        private String id;

        /**
         * Byte offset in the file that has been reported thus far.
         * Only used if {@link #writeLog(FilePath, OutputStream)} is used; not used for {@link #watch}.
         */
        private long lastLocation;

        protected FileMonitoringController(FilePath ws) throws IOException, InterruptedException {
            // can't keep ws reference because Controller is expected to be serializable
            ws.mkdirs();
            FilePath cd = tempDir(ws).child("durable-" + Util.getDigestOf(UUID.randomUUID().toString()).substring(0,8));
            cd.mkdirs();
            controlDir = cd.getRemote();
        }

        @Override public final boolean writeLog(FilePath workspace, OutputStream sink) throws IOException, InterruptedException {
            FilePath log = getLogFile(workspace);
            Long newLocation = log.act(new WriteLog(lastLocation, new RemoteOutputStream(sink)));
            if (newLocation != null) {
                LOGGER.log(Level.FINE, "copied {0} bytes from {1}", new Object[] {newLocation - lastLocation, log});
                lastLocation = newLocation;
                return true;
            } else {
                return false;
            }
        }
        private static class WriteLog extends MasterToSlaveFileCallable<Long> {
            private final long lastLocation;
            private final OutputStream sink;
            WriteLog(long lastLocation, OutputStream sink) {
                this.lastLocation = lastLocation;
                this.sink = sink;
            }
            @Override public Long invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                long len = f.length();
                if (len > lastLocation) {
                    RandomAccessFile raf = new RandomAccessFile(f, "r");
                    try {
                        raf.seek(lastLocation);
                        long toRead = len - lastLocation;
                        if (toRead > Integer.MAX_VALUE) { // >2Gb of output at once is unlikely
                            throw new IOException("large reads not yet implemented");
                        }
                        byte[] buf = new byte[(int) toRead];
                        raf.readFully(buf);
                        sink.write(buf);
                    } finally {
                        raf.close();
                    }
                    return len;
                } else {
                    return null;
                }
            }
        }

        /** Avoids excess round-tripping when reading status file. */
        static class StatusCheck extends MasterToSlaveFileCallable<Integer> {
            @Override
            @CheckForNull
            public Integer invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                if (f.exists() && f.length() > 0) {
                    try {
                        String fileString = Files.readFirstLine(f, Charset.defaultCharset());
                        if (fileString == null || fileString.isEmpty()) {
                            return null;
                        } else {
                            fileString = fileString.trim();
                            if (fileString.isEmpty()) {
                                return null;
                            } else {
                                return Integer.parseInt(fileString);
                            }
                        }
                    } catch (NumberFormatException x) {
                        throw new IOException("corrupted content in " + f + ": " + x, x);
                    }
                }
                return null;
            }
        }

        static final StatusCheck STATUS_CHECK_INSTANCE = new StatusCheck();

        @Override public Integer exitStatus(FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
            return exitStatus(workspace, listener);
        }

        /**
         * Like {@link #exitStatus(FilePath, Launcher, TaskListener)} but not requesting a {@link Launcher}, which would not be available in {@link #watch} mode anyway.
         */
        protected @CheckForNull Integer exitStatus(FilePath workspace, TaskListener listener) throws IOException, InterruptedException {
            FilePath status = getResultFile(workspace);
            return status.act(STATUS_CHECK_INSTANCE);
        }

        @Override public byte[] getOutput(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            return getOutput(workspace);
        }

        /**
         * Like {@link #getOutput(FilePath, Launcher)} but not requesting a {@link Launcher}, which would not be available in {@link #watch} mode anyway.
         */
        protected byte[] getOutput(FilePath workspace) throws IOException, InterruptedException {
            try (InputStream is = getOutputFile(workspace).read()) {
                return IOUtils.toByteArray(is);
            }
        }

        @Override public final void stop(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            launcher.kill(Collections.singletonMap(COOKIE, cookieFor(workspace)));
            // TODO after 10s, if the control dir still exists, write a flag file and have the Watcher shut down (interrupting any ongoing handler.output call if possible)
        }

        @Override public void cleanup(FilePath workspace) throws IOException, InterruptedException {
            controlDir(workspace).deleteRecursive();
        }

        /**
         * Directory in which this controller can place files.
         * Unique among all the controllers sharing the same workspace.
         */
        public FilePath controlDir(FilePath ws) throws IOException, InterruptedException {
            if (controlDir != null) { // normal case
                return ws.child(controlDir); // despite the name, this is an absolute path
            }
            assert id != null;
            FilePath cd = ws.child("." + id); // compatibility with 1.6
            if (!cd.isDirectory()) {
                cd = ws.child(".jenkins-" + id); // compatibility with 1.7
            }
            controlDir = cd.getRemote();
            id = null;
            LOGGER.info("using migrated control directory " + controlDir + " for remainder of this task");
            return cd;
        }

        // TODO 1.652 use WorkspaceList.tempDir
        private static FilePath tempDir(FilePath ws) {
            return ws.sibling(ws.getName() + System.getProperty(WorkspaceList.class.getName(), "@") + "tmp");
        }

        /**
         * File in which the exit code of the process should be reported.
         */
        public FilePath getResultFile(FilePath workspace) throws IOException, InterruptedException {
            return controlDir(workspace).child("jenkins-result.txt");
        }

        /**
         * File in which the stdout/stderr (or, if {@link #captureOutput} is called, just stderr) is written.
         */
        public FilePath getLogFile(FilePath workspace) throws IOException, InterruptedException {
            return controlDir(workspace).child("jenkins-log.txt");
        }

        /**
         * File in which the stdout is written, if {@link #captureOutput} is called.
         */
        public FilePath getOutputFile(FilePath workspace) throws IOException, InterruptedException {
            return controlDir(workspace).child("output.txt");
        }

        @Override public String getDiagnostics(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            FilePath cd = controlDir(workspace);
            VirtualChannel channel = cd.getChannel();
            String node = (channel instanceof Channel) ? ((Channel) channel).getName() : null;
            String location = node != null ? cd.getRemote() + " on " + node : cd.getRemote();
            StringWriter w = new StringWriter();
            Integer code = exitStatus(workspace, launcher, new StreamTaskListener(w));
            if (code != null) {
                return w + "completed process (code " + code + ") in " + location;
            } else {
                return w + "awaiting process completion in " + location;
            }
        }

        @Override public void watch(FilePath workspace, Handler handler, TaskListener listener) throws IOException, InterruptedException, ClassCastException {
            workspace.actAsync(new StartWatching(this, handler, listener));
            LOGGER.log(Level.FINE, "started asynchronous watch in {0}", controlDir);
        }

        /**
         * File in which a last-read position is stored if {@link #watch} is used.
         */
        public FilePath getLastLocationFile(FilePath workspace) throws IOException, InterruptedException {
            return controlDir(workspace).child("last-location.txt");
        }

        private static final long serialVersionUID = 1L;
    }

    private static ScheduledExecutorService watchService;
    private synchronized static ScheduledExecutorService watchService() {
        if (watchService == null) {
            // TODO 2.105+ use ClassLoaderSanityThreadFactory
            watchService = new /*ErrorLogging*/ScheduledThreadPoolExecutor(5, new NamingThreadFactory(new DaemonThreadFactory(), "FileMonitoringTask watcher"));
        }
        return watchService;
    }

    private static class StartWatching extends MasterToSlaveFileCallable<Void> {

        private static final long serialVersionUID = 1L;

        private final FileMonitoringController controller;
        private final Handler handler;
        private final TaskListener listener;

        StartWatching(FileMonitoringController controller, Handler handler, TaskListener listener) {
            this.controller = controller;
            this.handler = handler;
            this.listener = listener;
        }

        @Override public Void invoke(File workspace, VirtualChannel channel) throws IOException, InterruptedException {
            watchService().submit(new Watcher(controller, new FilePath(workspace), handler, listener));
            return null;
        }

    }

    private static class Watcher implements Runnable {

        private final FileMonitoringController controller;
        private final FilePath workspace;
        private final Handler handler;
        private final TaskListener listener;

        Watcher(FileMonitoringController controller, FilePath workspace, Handler handler, TaskListener listener) {
            this.controller = controller;
            this.workspace = workspace;
            this.handler = handler;
            this.listener = listener;
        }

        @Override public void run() {
            try {
                Integer exitStatus = controller.exitStatus(workspace, listener); // check before collecting output, in case the process is just now finishing
                long lastLocation = 0;
                FilePath lastLocationFile = controller.getLastLocationFile(workspace);
                if (lastLocationFile.exists()) {
                    lastLocation = Long.parseLong(lastLocationFile.readToString());
                }
                FilePath logFile = controller.getLogFile(workspace);
                long len = logFile.length();
                if (len > lastLocation) {
                    assert !logFile.isRemote();
                    try (FileChannel ch = FileChannel.open(Paths.get(logFile.getRemote()), StandardOpenOption.READ)) {
                        CountingInputStream cis = new CountingInputStream(Channels.newInputStream(ch.position(lastLocation)));
                        handler.output(cis);
                        lastLocationFile.write(Long.toString(lastLocation + cis.getByteCount()), null);
                    }
                }
                if (exitStatus != null) {
                    byte[] output;
                    if (controller.getOutputFile(workspace).exists()) {
                        output = controller.getOutput(workspace);
                    } else {
                        output = null;
                    }
                    handler.exited(exitStatus, output);
                    controller.cleanup(workspace);
                } else {
                    // Could use an adaptive timeout as in DurableTaskStep.Execution in polling mode,
                    // though less relevant here since there is no network overhead to the check.
                    watchService().schedule(this, 100, TimeUnit.MILLISECONDS);
                }
            } catch (Exception x) {
                // note that LOGGER here is going to the agent log, not master log
                LOGGER.log(Level.WARNING, "giving up on watching " + controller.controlDir, x);
            }
        }

    }

}
