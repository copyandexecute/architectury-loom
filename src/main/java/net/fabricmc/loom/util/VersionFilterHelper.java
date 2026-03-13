/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2025 FabricMC
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

package net.fabricmc.loom.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.gradle.api.Project;
import org.gradle.api.invocation.Gradle;

/**
 * Detects target MC versions from Gradle task requests and allows skipping
 * Loom setup for non-targeted versions in multi-version Stonecutter projects.
 */
public final class VersionFilterHelper {
	private VersionFilterHelper() {
	}

	/**
	 * Returns true if the given project's version segment is not among the targeted versions,
	 * meaning Loom setup can be skipped for this project.
	 */
	public static boolean shouldSkipSetup(Project project) {
		if (Boolean.getBoolean("loom.disable.version.filter")) {
			return false;
		}

		Set<String> targetedVersions = getTargetedVersions(project.getGradle());

		if (targetedVersions == null) {
			return false;
		}

		String projectVersion = extractVersionFromProject(project);

		if (projectVersion == null) {
			return false;
		}

		return !targetedVersions.contains(projectVersion);
	}

	private static String extractVersionFromProject(Project project) {
		String name = project.getName();

		if (isVersionSegment(name)) {
			return name;
		}

		return null;
	}

	/**
	 * Parses targeted versions from task requests. Returns null if all versions should be configured.
	 * Computed fresh each time - the parsing is trivial and system property caching
	 * would be unsafe across Gradle daemon invocations.
	 */
	private static Set<String> getTargetedVersions(Gradle gradle) {
		List<String> taskArgs = gradle.getStartParameter().getTaskRequests().stream()
				.flatMap(request -> request.getArgs().stream())
				.toList();

		if (taskArgs.isEmpty()) {
			return null;
		}

		Set<String> versions = new HashSet<>();

		for (String taskArg : taskArgs) {
			String version = extractVersionFromTaskPath(taskArg);

			if (version == null) {
				// Task without version segment (e.g. "build", "clean") -> configure all
				return null;
			}

			versions.add(version);
		}

		return versions.isEmpty() ? null : versions;
	}

	/**
	 * Extracts the version segment from a task path.
	 * ":client:1.21.11-fabric:runClient" -> "1.21.11-fabric"
	 * "build" -> null
	 */
	private static String extractVersionFromTaskPath(String taskPath) {
		String[] segments = taskPath.split(":");

		for (String segment : segments) {
			if (isVersionSegment(segment)) {
				return segment;
			}
		}

		return null;
	}

	/**
	 * Heuristic: a version segment starts with a digit and contains both '.' and '-'.
	 * Examples: "1.21.11-fabric", "1.8.9-forge", "1.20.1-neoforge"
	 */
	private static boolean isVersionSegment(String segment) {
		if (segment.isEmpty()) {
			return false;
		}

		return Character.isDigit(segment.charAt(0))
				&& segment.contains(".")
				&& segment.contains("-");
	}
}
