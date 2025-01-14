/*
 * The MIT License
 *
 * Copyright 2017 Gabriel Loewen
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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Proc;
import hudson.Launcher;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import hudson.model.TaskListener;
import java.io.IOException;
import org.kohsuke.stapler.DataBoundConstructor;
import java.io.OutputStream;
import java.nio.charset.Charset;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Runs a Powershell script
 */
public final class PowershellScript extends FileMonitoringTask {
    private final String script;
    private String powershellBinary = "powershell";
    private boolean loadProfile;
    private boolean capturingOutput;

    @DataBoundConstructor public PowershellScript(String script) {
        this.script = script;
    }

    public String getPowershellBinary() {
        return powershellBinary;
    }

    @DataBoundSetter
    public void setPowershellBinary(String powershellBinary) {
        this.powershellBinary = powershellBinary;
    }

    public boolean isLoadProfile() {
        return loadProfile;
    }

    @DataBoundSetter
    public void setLoadProfile(boolean loadProfile) {
        this.loadProfile = loadProfile;
    }

    public String getScript() {
        return script;
    }

    @Override public void captureOutput() {
        capturingOutput = true;
    }

    @SuppressFBWarnings(value="VA_FORMAT_STRING_USES_NEWLINE", justification="%n from master might be \\n")
    @Override protected FileMonitoringController doLaunch(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        List<String> args = new ArrayList<String>();
        PowershellController c = new PowershellController(ws);

        String cmd;
        if (capturingOutput) {
            cmd = String.format(". '%s'; Execute-AndWriteOutput -MainScript '%s' -OutputFile '%s' -LogFile '%s' -ResultFile '%s' -CaptureOutput;",
                quote(c.getPowerShellHelperFile(ws)),
                quote(c.getPowerShellScriptFile(ws)),
                quote(c.getOutputFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        } else {
            cmd = String.format(". '%s'; Execute-AndWriteOutput -MainScript '%s' -LogFile '%s' -ResultFile '%s';",
                quote(c.getPowerShellHelperFile(ws)),
                quote(c.getPowerShellWrapperFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        }

        String powershellArgs;
        if (launcher.isUnix()) {
            powershellArgs = "-NonInteractive";
        } else {
            powershellArgs = "-NonInteractive -ExecutionPolicy Bypass";
        }
        if (!loadProfile) {
            powershellArgs = "-NoProfile " + powershellArgs;
        }
        args.add(powershellBinary);
        args.addAll(Arrays.asList(powershellArgs.split(" ")));
        args.addAll(Arrays.asList("-Command", cmd));

        String scriptWrapper = String.format("[CmdletBinding()]\r\n" +
                                             "param()\r\n" +
                                             "& %s %s -Command '& ''%s''; exit $LASTEXITCODE;';\r\n" +
                                             "exit $LASTEXITCODE;", powershellBinary, powershellArgs, quote(quote(c.getPowerShellScriptFile(ws))));

        // Add an explicit exit to the end of the script so that exit codes are propagated
        // Fix https://issues.jenkins.io/browse/JENKINS-59529?jql=text%20~%20%22powershell%22
        // Wrap script in try/catch in order to propagate PowerShell errors like: command/script not recognized,
        // parameter not found, parameter validation failed, etc. In PowerShell, $LASTEXITCODE applies **only** to the
        // invocation of native apps and is not set when built-in PowerShell commands or script invocation fails.
        // While you **could** prepend your script with "$ErrorActionPreference = 'Stop'; <script>" to get the step
        // to fail on a PowerShell error, that is not discoverable resulting in issues like 59529 being submitted.
        // The problem with setting $ErrorActionPreference before the script is that value propagates into the script
        // which may not be what the user wants.
        // One consequence of leaving the "exit $LASTEXITCODE" is if the last native command in a script exits with
        // a non-zero exit code, the step will fail. That may sound obvious but most PowerShell scripters are not used
        // to that. PowerShell doesn't have the equivalent of "set -e" yet. However, in the context of a build system,
        // I believe we should err on the side of false negatives instead of false positives. If a scripter doesn't
        // want a non-zero exit code to fail the step, they can do the following (PS >= v7) to reset $LASTEXITCODE:
        // whoami -f || $($global:LASTEXITCODE = 0)
        String scriptWithExit = "try { " + script + " } catch { throw }\r\nexit $LASTEXITCODE";

        // Copy the helper script from the resources directory into the workspace
        c.getPowerShellHelperFile(ws).copyFrom(getClass().getResource("powershellHelper.ps1"));

        if (launcher.isUnix() || "pwsh".equals(powershellBinary)) {
            // There is no need to add a BOM with Open PowerShell / PowerShell Core
            c.getPowerShellScriptFile(ws).write(scriptWithExit, "UTF-8");
            if (!capturingOutput) {
                c.getPowerShellWrapperFile(ws).write(scriptWrapper, "UTF-8");
            }
        } else {
            // Write the Windows PowerShell scripts out with a UTF8 BOM
            writeWithBom(c.getPowerShellScriptFile(ws), scriptWithExit);
            if (!capturingOutput) {
                writeWithBom(c.getPowerShellWrapperFile(ws), scriptWrapper);
            }
        }

        Launcher.ProcStarter ps = launcher.launch().cmds(args).envs(escape(envVars)).pwd(ws).quiet(true);
        ps.readStdout().readStderr();
        Proc p = ps.start();
        c.registerForCleanup(p.getStdout());
        c.registerForCleanup(p.getStderr());

        return c;
    }

    private static String quote(FilePath f) {
        return quote(f.getRemote());
    }

    private static String quote(String f) {
        return f.replace("'", "''");
    }

    // In order for PowerShell to properly read a script that contains unicode characters the script should have a BOM, but there is no built in support for
    // writing UTF-8 with BOM in Java.  This code writes a UTF-8 BOM before writing the file contents.
    private static void writeWithBom(FilePath f, String contents) throws IOException, InterruptedException {
        OutputStream out = f.write();
        out.write(new byte[] { (byte)0xEF, (byte)0xBB, (byte)0xBF });
        out.write(contents.getBytes(Charset.forName("UTF-8")));
        out.flush();
        out.close();
    }

    private static final class PowershellController extends FileMonitoringController {
        private PowershellController(FilePath ws) throws IOException, InterruptedException {
            super(ws);
        }

        public FilePath getPowerShellScriptFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellScript.ps1");
        }

        public FilePath getPowerShellHelperFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellHelper.ps1");
        }

        public FilePath getPowerShellWrapperFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellWrapper.ps1");
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.PowershellScript_powershell();
        }

        public ListBoxModel doFillPowershellBinary() {
            return new ListBoxModel(new Option("powershell"), new Option("pwsh"));
        }

    }

}
