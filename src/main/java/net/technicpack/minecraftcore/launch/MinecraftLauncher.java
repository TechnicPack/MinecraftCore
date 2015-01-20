/*
 * This file is part of Technic Minecraft Core.
 * Copyright (C) 2013 Syndicate, LLC
 *
 * Technic Minecraft Core is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Technic Minecraft Core is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * as well as a copy of the GNU Lesser General Public License,
 * along with Technic Minecraft Core.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.technicpack.minecraftcore.launch;

import net.technicpack.launchercore.launch.GameProcess;
import net.technicpack.launchercore.launch.ProcessExitListener;
import net.technicpack.minecraftcore.mojang.auth.MojangUser;
import net.technicpack.launchercore.auth.UserModel;
import net.technicpack.launchercore.launch.LaunchOptions;
import net.technicpack.launchercore.modpacks.ModpackModel;
import net.technicpack.launchercore.install.LauncherDirectories;
import net.technicpack.minecraftcore.mojang.version.MojangVersion;
import net.technicpack.minecraftcore.mojang.version.io.CompleteVersion;
import net.technicpack.minecraftcore.mojang.version.io.Library;
import net.technicpack.platform.IPlatformApi;
import net.technicpack.utilslib.OperatingSystem;
import net.technicpack.utilslib.Utils;
import org.apache.commons.lang3.text.StrSubstitutor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MinecraftLauncher {
    private final LauncherDirectories directories;
    private final IPlatformApi platformApi;
    private final String clientId;
    private final UserModel<MojangUser> userModel;

	public MinecraftLauncher(IPlatformApi platformApi, LauncherDirectories directories, UserModel userModel, String clientId) {
        this.directories = directories;
        this.platformApi = platformApi;
        this.clientId = clientId;
        this.userModel = userModel;
	}

	public GameProcess launch(ModpackModel pack, String javaHome, int memory, LaunchOptions options, CompleteVersion version) throws IOException {
		return launch(pack, javaHome, memory, options, null, version);
	}

	public GameProcess launch(ModpackModel pack, String javaHome, long memory, LaunchOptions options, ProcessExitListener exitListener, MojangVersion version) throws IOException {
		List<String> commands = buildCommands(pack, javaHome, memory, version, options);
		StringBuilder full = new StringBuilder();
		boolean first = true;

		for (String part : commands) {
			if (!first) full.append(" ");
			full.append(part);
			first = false;
		}
		Utils.getLogger().info("Running " + full.toString());
		Process process = new ProcessBuilder(commands).directory(pack.getInstalledDirectory()).redirectErrorStream(true).start();
		GameProcess mcProcess = new GameProcess(commands, process);
		if (exitListener != null) mcProcess.setExitListener(exitListener);

        platformApi.incrementPackRuns(pack.getName());
        if (!Utils.sendTracking("runModpack", pack.getName(), pack.getInstalledVersion().getVersion(), clientId)) {
            Utils.getLogger().info("Failed to record event");
        }

		return mcProcess;
	}

	private List<String> buildCommands(ModpackModel pack, String javaHome, long memory, MojangVersion version, LaunchOptions options) {
		List<String> commands = new ArrayList<String>();
		commands.add(OperatingSystem.getJavaDir(javaHome));

		OperatingSystem operatingSystem = OperatingSystem.getOperatingSystem();

		if (operatingSystem.equals(OperatingSystem.OSX)) {
			//TODO: -Xdock:icon=<icon path>
			commands.add("-Xdock:name=" + pack.getDisplayName());
		} else if (operatingSystem.equals(OperatingSystem.WINDOWS)) {
			// I have no idea if this helps technic or not.
			commands.add("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");
		}
		commands.add("-Xmx" + memory + "m");
		int permSize = 128;
		if (memory >= 2048) {
			permSize = 256;
		}
		commands.add("-XX:MaxPermSize=" + permSize + "m");
		commands.add("-Djava.library.path=" + new File(pack.getBinDir(), "natives").getAbsolutePath());
		// Tell forge 1.5 to download from our mirror instead
		commands.add("-Dfml.core.libraries.mirror=http://mirror.technicpack.net/Technic/lib/fml/%s");
		commands.add("-Dminecraft.applet.TargetDirectory=" +  pack.getInstalledDirectory().getAbsolutePath());
        commands.add("-Djava.net.preferIPv4Stack=true");

        String javaArguments = version.getJavaArguments();

        if (javaArguments != null && !javaArguments.isEmpty()) {
            commands.addAll(Arrays.asList(javaArguments.split(" ")));
        }

		commands.add("-cp");
		commands.add(buildClassPath(pack, version));
		commands.add(version.getMainClass());
		commands.addAll(Arrays.asList(getMinecraftArguments(version, pack.getInstalledDirectory(), userModel.getCurrentUser())));
		options.appendToCommands(commands);

		//TODO: Add all the other less important commands
		return commands;
	}

	private String[] getMinecraftArguments(MojangVersion version, File gameDirectory, MojangUser mojangUser) {
		Map<String, String> map = new HashMap<String, String>();
		StrSubstitutor substitutor = new StrSubstitutor(map);
		String[] split = version.getMinecraftArguments().split(" ");

		map.put("auth_username", mojangUser.getUsername());
		map.put("auth_session", mojangUser.getSessionId());
		map.put("auth_access_token", mojangUser.getAccessToken());

		map.put("auth_player_name", mojangUser.getDisplayName());
		map.put("auth_uuid", mojangUser.getProfile().getId());

		map.put("profile_name", mojangUser.getDisplayName());
		map.put("version_name", version.getId());

		map.put("game_directory", gameDirectory.getAbsolutePath());

		String targetAssets = directories.getAssetsDirectory().getAbsolutePath();

		String assetsKey = version.getAssetsKey();

		if (assetsKey == null || assetsKey.isEmpty()) {
			assetsKey = "legacy";
		}

		if (version.getAreAssetsVirtual()) {
			targetAssets += File.separator + "virtual" + File.separator + assetsKey;
		}

		map.put("game_assets",targetAssets);
		map.put("assets_root", targetAssets);
		map.put("assets_index_name", assetsKey);
		map.put("user_type", mojangUser.getProfile().isLegacy()?"legacy":"mojang");
		map.put("user_properties", mojangUser.getUserPropertiesAsJson());

		for (int i = 0; i < split.length; i++) {
			split[i] = substitutor.replace(split[i]);
		}

		return split;
	}

	private String buildClassPath(ModpackModel pack, MojangVersion version) {
		StringBuilder result = new StringBuilder();
		String separator = System.getProperty("path.separator");

		// Add all the libraries to the classpath.
		for (Library library : version.getLibrariesForOS()) {
			if (library.getNatives() != null) {
				continue;
			}

			// If minecraftforge is described in the libraries, skip it
			// HACK - Please let us get rid of this when we move to actually hosting forge,
			// or at least only do it if the users are sticking with modpack.jar
			if (library.getName().startsWith("net.minecraftforge:minecraftforge") ||
					library.getName().startsWith("net.minecraftforge:forge")) {
				continue;
			}

			File file = new File(directories.getCacheDirectory(), library.getArtifactPath().replace("${arch}", System.getProperty("sun.arch.data.model")));
			if (!file.isFile() || !file.exists()) {
				throw new RuntimeException("Library " + library.getName() + " not found.");
			}

			if (result.length() > 1) {
				result.append(separator);
			}
			result.append(file.getAbsolutePath());
		}

		// Add the modpack.jar to the classpath, if it exists and minecraftforge is not a library already
		File modpack = new File(pack.getBinDir(), "modpack.jar");
		if (modpack.exists()) {
			if (result.length() > 1) {
				result.append(separator);
			}
			result.append(modpack.getAbsolutePath());
		}

		// Add the minecraft jar to the classpath
		File minecraft = new File(pack.getBinDir(), "minecraft.jar");
		if (!minecraft.exists()) {
			throw new RuntimeException("Minecraft not installed for this pack: " + pack);
		}
		if (result.length() > 1) {
			result.append(separator);
		}
		result.append(minecraft.getAbsolutePath());

		return result.toString();
	}
}
