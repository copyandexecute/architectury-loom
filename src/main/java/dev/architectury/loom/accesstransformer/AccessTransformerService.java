package dev.architectury.loom.accesstransformer;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import dev.architectury.loom.forge.AccessTransformSetMapper;
import dev.architectury.loom.forge.config.UserdevConfig;
import dev.architectury.loom.forge.tool.ForgeToolService;
import dev.architectury.loom.mappings.MappingOption;
import dev.architectury.loom.util.DependencyDownloader;
import dev.architectury.loom.util.TempFiles;
import org.cadixdev.at.AccessTransformSet;
import org.cadixdev.at.io.AccessTransformFormats;
import org.cadixdev.lorenz.MappingSet;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;

import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.LoomVersions;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;
import net.fabricmc.lorenztiny.TinyMappingsReader;

/**
 * A service that executes the access transformer tool.
 * The tool information and the AT files are specified in the options.
 */
public final class AccessTransformerService extends Service<AccessTransformerService.Options> {
	public static final ServiceType<Options, AccessTransformerService> TYPE = new ServiceType<>(Options.class, AccessTransformerService.class);

	public interface Options extends Service.Options {
		@InputFiles
		ConfigurableFileCollection getAccessTransformers();

		@Input
		Property<String> getMainClass();

		@Classpath
		ConfigurableFileCollection getClasspath();

		@Nested
		Property<ForgeToolService.Options> getToolServiceOptions();

		@Nested
		@Optional
		Property<TinyMappingsService.Options> getMappingsServiceOptions();
	}

	private static Provider<Options> createOptions(Project project, Object atFiles, boolean forLoaderAts) {
		return TYPE.create(project, options -> {
			LoomVersions accessTransformer = chooseAccessTransformer(project);
			String mainClass = accessTransformer.equals(LoomVersions.ACCESS_TRANSFORMERS_NEO)
					? "net.neoforged.accesstransformer.cli.TransformerProcessor"
					: "net.minecraftforge.accesstransformer.TransformerProcessor";
			FileCollection classpath = new DependencyDownloader(project)
					.add(accessTransformer.mavenNotation())
					.add(LoomVersions.ASM.mavenNotation())
					.add("org.ow2.asm:asm-commons:9.7") // Required for AT CLI
					.add("org.ow2.asm:asm-tree:9.7") // Required for AT CLI
					.add("net.sf.jopt-simple:jopt-simple:5.0.4") // Required for AT CLI
					.add("org.antlr:antlr4-runtime:4.9.1") // Required for AT CLI
					.add("org.apache.logging.log4j:log4j-api:2.17.1") // Required for AT CLI
					.add("org.apache.logging.log4j:log4j-core:2.17.1") // Required for AT CLI
					.platform(LoomVersions.ACCESS_TRANSFORMERS_LOG4J_BOM.mavenNotation())
					.download();

			options.getMainClass().set(mainClass);
			options.getAccessTransformers().from(atFiles);
			options.getClasspath().from(classpath);
			options.getToolServiceOptions().set(ForgeToolService.createOptions(project));

			if (forLoaderAts) {
				LoomGradleExtension extension = LoomGradleExtension.get(project);

				if (extension.isLegacyForge()) {
					options.getMappingsServiceOptions().set(extension.getMappingConfiguration().getMappingsServiceOptions(project, MappingOption.WITH_SRG));
				}
			}
		});
	}

	public static Provider<Options> createOptions(Project project, Object atFiles) {
		return createOptions(project, atFiles, false);
	}

	public static Provider<Options> createOptionsForLoaderAts(Project project, TempFiles tempFiles) {
		final Provider<List<String>> atFiles = project.provider(() -> {
			LoomGradleExtension extension = LoomGradleExtension.get(project);
			Path userdevJar = extension.getForgeUserdevProvider().getUserdevJar().toPath();
			return extractAccessTransformers(userdevJar, extension.getForgeUserdevProvider().getConfig().ats(), tempFiles);
		});
		return createOptions(project, atFiles, true);
	}

	/**
	 * Creates options for loader ATs with pre-extracted AT file paths.
	 * Use this when AT files need special handling (e.g., filtering legacy formats).
	 */
	public static Provider<Options> createOptionsForLoaderAts(Project project, TempFiles tempFiles, List<String> atFilePaths) {
		return createOptions(project, atFilePaths, true);
	}

	/**
	 * Extracts and filters legacy AT files from a JAR.
	 * This handles legacy FML AT formats that the modern parser doesn't support:
	 * - Wildcards like "* # all fields" and "*() # all methods"
	 * - Dot-notation for types (Lnet.minecraft.item.Item;) instead of slash-notation
	 * - Missing return types on constructors
	 */
	public static List<String> extractAndFilterLegacyAts(Path jar, UserdevConfig.AccessTransformerLocation location, TempFiles tempFiles) throws IOException {
		return extractAccessTransformers(jar, location, tempFiles);
	}

	private static List<String> extractAccessTransformers(Path jar, UserdevConfig.AccessTransformerLocation location, TempFiles tempFiles) throws IOException {
		final List<String> extracted = new ArrayList<>();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jar)) {
			for (Path atFile : getAccessTransformerPaths(fs, location)) {
				byte[] atBytes;

				try {
					atBytes = Files.readAllBytes(atFile);
				} catch (NoSuchFileException e) {
					continue;
				}

				// Filter out legacy AT wildcards that the modern parser doesn't support
				// (e.g., "* # all fields" or "*() # all methods")
				String content = new String(atBytes, java.nio.charset.StandardCharsets.UTF_8);
				String filtered = filterLegacyAtWildcards(content);

				Path tmpFile = tempFiles.file("at-conf", ".cfg");
				Files.write(tmpFile, filtered.getBytes(java.nio.charset.StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				extracted.add(tmpFile.toAbsolutePath().toString());
			}
		}

		return extracted;
	}

	/**
	 * Filters and fixes legacy AT entries that the modern parser doesn't support.
	 * Legacy FML used:
	 * - Wildcards like "* # all fields" and "*() # all methods"
	 * - Dot-notation for types (Lnet.minecraft.item.Item;) instead of slash-notation (Lnet/minecraft/item/Item;)
	 * - Missing return types on constructors (<init>(...) should be <init>(...)V)
	 * The modern AccessTransform library can't parse these formats.
	 */
	private static String filterLegacyAtWildcards(String content) {
		StringBuilder result = new StringBuilder();

		for (String line : content.split("\n")) {
			String trimmed = line.trim();

			// Keep comments and empty lines
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				result.append(line).append("\n");
				continue;
			}

			// Split by whitespace to check for wildcards
			String[] parts = trimmed.split("\\s+");

			// Format is: access class.name member [descriptor] [# comment]
			// We need to skip lines where member is "*" or "*()"
			if (parts.length >= 3) {
				String member = parts[2];

				if (member.equals("*") || member.equals("*()") || member.endsWith("*()")) {
					// Skip this line - wildcard not supported
					continue;
				}
			}

			// Fix dot-notation in type descriptors (Lnet.minecraft. -> Lnet/minecraft/)
			// Only convert dots to slashes inside type descriptors (L...;)
			String fixedLine = fixLegacyTypeDescriptors(line);

			// Fix missing return type on constructors: <init>(...) -> <init>(...)V
			fixedLine = fixMissingReturnType(fixedLine);

			result.append(fixedLine).append("\n");
		}

		return result.toString();
	}

	/**
	 * Fixes method descriptors that are missing a return type.
	 * For example: <init>(Lnet/minecraft/item/Item$ToolMaterial;) -> <init>(Lnet/minecraft/item/Item$ToolMaterial;)V
	 */
	private static String fixMissingReturnType(String line) {
		// Match pattern: ends with ) but not )V, )I, )Z, etc. (no return type)
		// This typically happens with constructors
		java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\([^)]*\\))(?=[\\s#]|$)");
		java.util.regex.Matcher matcher = pattern.matcher(line);
		StringBuffer sb = new StringBuffer();

		while (matcher.find()) {
			String desc = matcher.group(1);
			// Check if it's missing a return type (ends with just ")")
			// A valid descriptor would be like "(...)V" or "(...)Lcom/example/Class;"
			// If we see "(...)" followed by whitespace, comment, or end of line, it's missing the return type
			// Use Matcher.quoteReplacement to escape any $ signs in the descriptor
			matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(desc + "V"));
		}

		matcher.appendTail(sb);
		return sb.toString();
	}

	/**
	 * Fixes legacy type descriptors that use dot-notation instead of slash-notation.
	 * For example: Lnet.minecraft.item.Item; -> Lnet/minecraft/item/Item;
	 */
	private static String fixLegacyTypeDescriptors(String line) {
		StringBuilder result = new StringBuilder();
		boolean inTypeDescriptor = false;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);

			if (c == 'L' && !inTypeDescriptor) {
				// Start of a type descriptor
				inTypeDescriptor = true;
				result.append(c);
			} else if (c == ';' && inTypeDescriptor) {
				// End of a type descriptor
				inTypeDescriptor = false;
				result.append(c);
			} else if (c == '.' && inTypeDescriptor) {
				// Convert dot to slash inside type descriptors
				result.append('/');
			} else {
				result.append(c);
			}
		}

		return result.toString();
	}

	private static List<Path> getAccessTransformerPaths(FileSystemUtil.Delegate fs, UserdevConfig.AccessTransformerLocation location) throws IOException {
		return location.visitIo(directory -> {
			Path dirPath = fs.getPath(directory);

			try (Stream<Path> paths = Files.list(dirPath)) {
				return paths.toList();
			}
		}, paths -> paths.stream().map(fs::getPath).toList());
	}

	public AccessTransformerService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	private static LoomVersions chooseAccessTransformer(Project project) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		boolean serverBundleMetadataPresent = extension.getMinecraftProvider().getServerBundleMetadata() != null;

		if (!serverBundleMetadataPresent) {
			return LoomVersions.ACCESS_TRANSFORMERS;
		} else if (extension.isNeoForge()) {
			MinecraftVersionMeta.JavaVersion javaVersion = extension.getMinecraftProvider().getVersionInfo().javaVersion();

			if (javaVersion != null && javaVersion.majorVersion() >= 21) {
				return LoomVersions.ACCESS_TRANSFORMERS_NEO;
			}
		}

		return LoomVersions.ACCESS_TRANSFORMERS_NEW;
	}

	public void execute(Path input, Path output) throws IOException {
		try (TempFiles tempFiles = new TempFiles()) {
			execute(input, output, tempFiles);
		}
	}

	public void execute(Path input, Path output, TempFiles tempFiles) throws IOException {
		final List<String> args = new ArrayList<>();
		args.add("--inJar");
		args.add(input.toAbsolutePath().toString());
		args.add("--outJar");
		args.add(output.toAbsolutePath().toString());

		Collection<File> atFiles = getOptions().getAccessTransformers().getFiles();

		if (getOptions().getMappingsServiceOptions().isPresent()) {
			TinyMappingsService mappingsService = getServiceFactory().get(getOptions().getMappingsServiceOptions());
			MappingTree mappingTree = mappingsService.getMappingTree();
			MappingSet mappingSet = new TinyMappingsReader(mappingTree, "srg", "official").read();

			Collection<File> mappedAtFiles = new ArrayList<>();

			for (File atFile : atFiles) {
				AccessTransformSet accessTransformSet = AccessTransformSet.create();

				try (Reader reader = new FileReader(atFile)) {
					AccessTransformFormats.FML.read(reader, accessTransformSet);
				}

				accessTransformSet = AccessTransformSetMapper.remap(accessTransformSet, mappingSet);

				Path mappedAtFile = tempFiles.file("at-conf", ".cfg");
				AccessTransformFormats.FML.write(mappedAtFile, accessTransformSet);
				mappedAtFiles.add(mappedAtFile.toFile());
			}

			atFiles = mappedAtFiles;
		}

		for (File atFile : atFiles) {
			args.add("--atFile");
			args.add(atFile.getAbsolutePath());
		}

		final ForgeToolService toolService = getServiceFactory().get(getOptions().getToolServiceOptions());
		toolService.exec(spec -> {
			spec.getMainClass().set(getOptions().getMainClass());
			spec.setArgs(args);
			spec.setClasspath(getOptions().getClasspath());
		});
	}
}
