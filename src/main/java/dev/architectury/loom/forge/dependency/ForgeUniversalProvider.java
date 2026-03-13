/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2021 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.architectury.loom.forge.dependency;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.gradle.api.Project;

import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.download.Download;

public class ForgeUniversalProvider extends DependencyProvider {
	private File forge;

	public ForgeUniversalProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		forge = new File(getExtension().getForgeProvider().getGlobalCache(), "forge-universal.jar");

		if (!forge.exists() || refreshDeps()) {
			// Try normal Gradle resolution first
			Optional<File> dep;

			try {
				dep = dependency.resolveFile();
			} catch (Exception e) {
				// For very old Forge (1.7.10), Gradle can't resolve the dependency
				// Download directly via HTTP instead
				provideVeryOldForge();
				return;
			}

			if (dep.isPresent()) {
				Files.copy(dep.get().toPath(), forge.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} else {
				// Fallback to direct download
				provideVeryOldForge();
			}
		}
	}

	private void provideVeryOldForge() throws Exception {
		ForgeProvider.ForgeVersion version = getExtension().getForgeProvider().getVersion();
		String ver = version.getCombined();

		// Build Maven URL path
		String baseUrl = Constants.FORGE_MAVEN + "/net/minecraftforge/forge/" + ver + "/forge-" + ver;

		getProject().getLogger().lifecycle(":downloading Forge universal (1.7.10)");
		Download.create(baseUrl + "-universal.jar").downloadPath(forge.toPath());
	}

	public File getForge() {
		return forge;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.FORGE_UNIVERSAL;
	}
}
