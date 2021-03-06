package hudson.plugins.android_emulator.builder;

import static hudson.plugins.android_emulator.AndroidEmulator.log;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.EnvironmentContributingAction;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.plugins.android_emulator.AndroidEmulator;
import hudson.plugins.android_emulator.AndroidEmulator.DescriptorImpl;
import hudson.plugins.android_emulator.Messages;
import hudson.plugins.android_emulator.SdkInstallationException;
import hudson.plugins.android_emulator.SdkInstaller;
import hudson.plugins.android_emulator.sdk.AndroidSdk;
import hudson.plugins.android_emulator.sdk.Tool;
import hudson.plugins.android_emulator.util.Utils;
import hudson.tasks.Builder;
import hudson.util.ForkOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractBuilder extends Builder {

    /** Environment variable set by the plugin to specify the serial of the started AVD. */
    private static final String DEVICE_SERIAL_VARIABLE = "ANDROID_AVD_DEVICE";

    /** Environment variable set by the plugin to specify the telnet interface port. */
    private static final String DEVICE_USER_PORT_VARIABLE = "ANDROID_AVD_USER_PORT";

    /**
     * Gets an Android SDK instance, ready for use.
     *
     * @param build The build for which we should retrieve the SDK instance.
     * @param launcher The launcher for the remote node.
     * @param listener The listener used to get the environment variables.
     * @return An Android SDK instance, or {@code null} if none could be found or installed.
     */
    protected static AndroidSdk getAndroidSdk(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener) throws IOException, InterruptedException {
        boolean shouldInstallSdk = true;
        boolean keepInWorkspace = false;
        DescriptorImpl descriptor = Hudson.getInstance().getDescriptorByType(DescriptorImpl.class);
        if (descriptor != null) {
            shouldInstallSdk = descriptor.shouldInstallSdk;
            keepInWorkspace = descriptor.shouldKeepInWorkspace;
        }

        // Get configured, expanded Android SDK root value
        String androidHome = Utils.expandVariables(build, listener,
                Utils.getConfiguredAndroidHome());
        EnvVars envVars = Utils.getEnvironment(build, listener);

        // Retrieve actual SDK root based on given value
        Node node = Computer.currentComputer().getNode();
        String discoveredAndroidHome = Utils.discoverAndroidHome(launcher, node, envVars,
                androidHome);

        // Get Android SDK object from the given root (or locate on PATH)
        final String androidSdkHome = (envVars != null && keepInWorkspace ? envVars
                .get("WORKSPACE") : null);
        AndroidSdk androidSdk = Utils
                .getAndroidSdk(launcher, discoveredAndroidHome, androidSdkHome);

        // Check whether we should install the SDK
        if (androidSdk == null) {
            PrintStream logger = listener.getLogger();
            if (!shouldInstallSdk) {
                // Couldn't find an SDK, don't want to install it, give up
                log(logger, Messages.SDK_TOOLS_NOT_FOUND());
                return null;
            }

            // Ok, let's download and install the SDK
            log(logger, Messages.INSTALLING_SDK());
            try {
                androidSdk = SdkInstaller.install(launcher, listener, null);
            } catch (SdkInstallationException e) {
                log(logger, Messages.SDK_INSTALLATION_FAILED(), e);
                return null;
            }

            // Check whether anything went wrong
            if (androidSdk == null) {
                log(logger, Messages.SDK_INSTALLATION_FAILED());
                return null;
            }
        }

        // Export environment variables
        final String sdkRoot = androidSdk.getSdkRoot();
        build.addAction(new EnvironmentContributingAction() {

            public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars envVars) {
                if (envVars != null) {
                    envVars.put("ANDROID_HOME", sdkRoot);
                }
            }

            public String getUrlName() {
                return null;
            }

            public String getIconFileName() {
                return null;
            }

            public String getDisplayName() {
                return null;
            }
        });

        return androidSdk;
    }

    /**
     * Gets the Android device identifier for this job, defaulting to the AVD started by this plugin.
     *
     * @param build The build for which we should retrieve the SDK instance.
     * @param listener The listener used to get the environment variables.
     * @return The device identifier (defaulting to the value of "<tt>-s $ANDROID_AVD_DEVICE</tt>").
     */
    protected static String getDeviceIdentifier(AbstractBuild<?, ?> build, BuildListener listener) {
        String deviceSerial = expandVariable(build, listener, DEVICE_SERIAL_VARIABLE);
        if (deviceSerial == null) {
            // No emulator was started by this plugin; assume only one device is attached
            return "";
        }

        // Use the serial of the emulator started by this plugin
        return String.format("-s %s", deviceSerial);
    }

    /**
     * Gets the Android device identifier for this job, defaulting to the AVD started by this plugin.
     *
     * @param build The build for which we should retrieve the SDK instance.
     * @param listener The listener used to get the environment variables.
     * @return The device identifier (defaulting to the value of "<tt>-s $ANDROID_AVD_DEVICE</tt>").
     */
    protected static int getDeviceTelnetPort(AbstractBuild<?, ?> build, BuildListener listener) {
        String devicePort = expandVariable(build, listener, DEVICE_USER_PORT_VARIABLE);
        if (devicePort == null) {
            // No emulator was started by this plugin; assume only one device is attached
            return 5554;
        }

        // Use the serial of the emulator started by this plugin
        return Integer.parseInt(devicePort);
    }

    /**
     * Expands the given variable name to its value from the given build and environment.

     * @param build Used to get build-specific variables.
     * @param listener Used to get environment variables.
     * @param variable The name of the variable to expand.
     * @return The value of the expanded variable, or {@code null} if it could not be resolved.
     */
    private static String expandVariable(AbstractBuild<?, ?> build, BuildListener listener,
            String variable) {
        String varFormat = String.format("$%s", variable);
        String value = Utils.expandVariables(build, listener, varFormat);
        if (value.equals(varFormat)) {
            // Variable did not expand to anything
            return null;
        }

        // Return expanded value
        return value;
    }

    /**
     * Uninstalls the Android package corresponding to the given APK file from an Android device.
     *
     * @param build The build for which we should uninstall the package.
     * @param launcher The launcher for the remote node.
     * @param logger Where log output should be redirected to.
     * @param androidSdk The Android SDK to use.
     * @param deviceIdentifier The device from which the package should be removed.
     * @param apkPath The path to the APK file.
     * @return {@code true} iff uninstallation completed successfully.
     * @throws IOException If execution failed.
     * @throws InterruptedException If execution failed.
     */
    protected static boolean uninstallApk(AbstractBuild<?, ?> build, Launcher launcher,
            PrintStream logger, AndroidSdk androidSdk, String deviceIdentifier, FilePath apkPath)
                throws IOException, InterruptedException {
        // Get package ID to uninstall
        String packageId = getPackageIdForApk(build, launcher, logger, androidSdk, apkPath);
        return uninstallApk(build, launcher, logger, androidSdk, deviceIdentifier, packageId);
    }

    /**
     * Uninstalls the given Android package ID from the given Android device.
     *
     * @param build The build for which we should uninstall the package.
     * @param launcher The launcher for the remote node.
     * @param logger Where log output should be redirected to.
     * @param androidSdk The Android SDK to use.
     * @param deviceIdentifier The device from which the package should be removed.
     * @param packageId The ID of the Android package to remove from the given device.
     * @return {@code true} iff uninstallation completed successfully.
     * @throws IOException If execution failed.
     * @throws InterruptedException If execution failed.
     */
    protected static boolean uninstallApk(AbstractBuild<?, ?> build, Launcher launcher,
            PrintStream logger, AndroidSdk androidSdk, String deviceIdentifier, String packageId)
                throws IOException, InterruptedException {
        AndroidEmulator.log(logger, Messages.UNINSTALLING_APK(packageId));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ForkOutputStream forkStream = new ForkOutputStream(logger, stdout);
        String adbArgs = String.format("%s uninstall %s", deviceIdentifier, packageId);
        Utils.runAndroidTool(launcher, build.getEnvironment(TaskListener.NULL),
                forkStream, logger, androidSdk, Tool.ADB, adbArgs, null);

        // The package manager simply returns "Success" or "Failure" on stdout
        return stdout.toString().contains("Success");
    }

    /**
     * Determines the package ID of an APK file.
     *
     * @param build The build for which we should uninstall the package.
     * @param launcher The launcher for the remote node.
     * @param logger Where log output should be redirected to.
     * @param androidSdk The Android SDK to use.
     * @param apkPath The path to the APK file.
     * @return The package ID for the given APK, or {@code null} if it could not be determined.
     * @throws IOException If execution failed.
     * @throws InterruptedException If execution failed.
     */
    private static String getPackageIdForApk(AbstractBuild<?, ?> build, Launcher launcher,
            PrintStream logger, AndroidSdk androidSdk, FilePath apkPath)
                throws IOException, InterruptedException {
        // Run aapt command on given APK
        ByteArrayOutputStream aaptOutput = new ByteArrayOutputStream();
        String args = String.format("dump badging \"%s\"", apkPath.getName());
        Utils.runAndroidTool(launcher, aaptOutput, logger, androidSdk, Tool.AAPT, args, apkPath.getParent());

        // Determine package ID from aapt output
        String packageId = null;
        String aaptResult = aaptOutput.toString();
        if (aaptResult.length() > 0) {
            Matcher matcher = Pattern.compile("package: +name='([^']+)'").matcher(aaptResult);
            if (matcher.find()) {
                packageId = matcher.group(1);
            }
        }

        return packageId;
    }

}
