package com.tyron.builder.compiler;


import static com.android.tools.build.bundletool.BundleToolMain.HELP_CMD;

import com.android.tools.build.bundletool.BundleToolMain;
import com.android.tools.build.bundletool.commands.AddTransparencyCommand;
import com.android.tools.build.bundletool.commands.BuildApksCommand;
import com.android.tools.build.bundletool.commands.BuildBundleCommand;
import com.android.tools.build.bundletool.commands.CheckTransparencyCommand;
import com.android.tools.build.bundletool.commands.DumpCommand;
import com.android.tools.build.bundletool.commands.ExtractApksCommand;
import com.android.tools.build.bundletool.commands.GetDeviceSpecCommand;
import com.android.tools.build.bundletool.commands.GetSizeCommand;
import com.android.tools.build.bundletool.commands.InstallApksCommand;
import com.android.tools.build.bundletool.commands.InstallMultiApksCommand;
import com.android.tools.build.bundletool.commands.ValidateBundleCommand;
import com.android.tools.build.bundletool.commands.VersionCommand;
import com.android.tools.build.bundletool.device.AdbServer;
import com.android.tools.build.bundletool.device.DdmlibAdbServer;
import com.android.tools.build.bundletool.flags.FlagParser;
import com.android.tools.build.bundletool.flags.ParsedFlags;
import com.android.tools.build.bundletool.model.version.BundleToolVersion;
import com.tyron.builder.BuildModule;
import com.tyron.builder.exception.CompilationFailedException;

import java.util.ArrayList;
import java.util.Optional;

/**
 * A wrapper around {@link BundleToolMain}.
 *
 * Used to access bundle tool without it calling {@link System#exit(int)}
 */
public class BundleTool {

    private final ArrayList<String> commands;
    private final String mApkInputPath;
    private final String mApkOutputPath;

    public BundleTool(String inputPath, String outputPath) {
        commands = new ArrayList<>();
        mApkInputPath = inputPath;
        mApkOutputPath = outputPath;
    }

    public void aab() throws Exception {
        commands.add("build-bundle");
        commands.add("--modules=" + mApkInputPath);
        commands.add("--output=" + mApkOutputPath);

        main(commands.toArray(new String[0]));
    }

    public void apk() throws Exception {
        commands.add("build-apks");
        commands.add("--bundle=" + mApkInputPath);
        commands.add("--output=" + mApkOutputPath);
        commands.add("--mode=universal");
        commands.add("--aapt2=" +
                     BuildModule.getContext().getApplicationInfo().nativeLibraryDir +
                     "/libaapt2.so");


        main(commands.toArray(new String[0]));
    }

    public static void main(String[] args) throws CompilationFailedException {
        final ParsedFlags flags;
        try {
            flags = new FlagParser().parse(args);
        } catch (FlagParser.FlagParseException e) {
            throw new CompilationFailedException("Error while parsing the flags: " + e.getMessage());
        }
        Optional<String> command = flags.getMainCommand();
        if (!command.isPresent()) {
            BundleToolMain.help();
            throw new CompilationFailedException("You need to specify a command.");
        }

        try {
            switch (command.get()) {
                case BuildBundleCommand.COMMAND_NAME:
                    BuildBundleCommand.fromFlags(flags).execute();
                    break;
                case BuildApksCommand.COMMAND_NAME:
                    try (AdbServer adbServer = DdmlibAdbServer.getInstance()) {
                        BuildApksCommand.fromFlags(flags, adbServer).execute();
                    }
                    break;
                case ExtractApksCommand.COMMAND_NAME:
                    ExtractApksCommand.fromFlags(flags).execute();
                    break;
                case GetDeviceSpecCommand.COMMAND_NAME:
                    // We have to destroy ddmlib resources at the end of the command.
                    try (AdbServer adbServer = DdmlibAdbServer.getInstance()) {
                        GetDeviceSpecCommand.fromFlags(flags, adbServer).execute();
                    }
                    break;
                case InstallApksCommand.COMMAND_NAME:
                    try (AdbServer adbServer = DdmlibAdbServer.getInstance()) {
                        InstallApksCommand.fromFlags(flags, adbServer).execute();
                    }
                    break;
                case InstallMultiApksCommand.COMMAND_NAME:
                    try (AdbServer adbServer = DdmlibAdbServer.getInstance()) {
                        InstallMultiApksCommand.fromFlags(flags, adbServer).execute();
                    }
                    break;
                case ValidateBundleCommand.COMMAND_NAME:
                    ValidateBundleCommand.fromFlags(flags).execute();
                    break;
                case DumpCommand.COMMAND_NAME:
                    DumpCommand.fromFlags(flags).execute();
                    break;
                case GetSizeCommand.COMMAND_NAME:
                    GetSizeCommand.fromFlags(flags).execute();
                    break;
                case VersionCommand.COMMAND_NAME:
                    VersionCommand.fromFlags(flags, System.out).execute();
                    break;
                case AddTransparencyCommand.COMMAND_NAME:
                    AddTransparencyCommand.fromFlags(flags).execute();
                    break;
                case CheckTransparencyCommand.COMMAND_NAME:
                    try (AdbServer adbServer = DdmlibAdbServer.getInstance()) {
                        CheckTransparencyCommand.fromFlags(flags, adbServer).execute();
                    }
                    break;
                default:
                    throw new CompilationFailedException(String.format("Error: Unrecognized command '%s'.%n%n%n", command.get()));
            }
        } catch (Exception e) {
            throw new CompilationFailedException("[BT:" + BundleToolVersion.getCurrentVersion() + "] Error: " + e.getMessage());
        }
    }
}
