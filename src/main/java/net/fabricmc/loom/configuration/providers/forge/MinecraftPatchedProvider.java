/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2024 FabricMC
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

package net.fabricmc.loom.configuration.providers.forge;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import dev.architectury.loom.forge.UserdevConfig;
import dev.architectury.loom.forge.tool.ForgeToolValueSource;
import dev.architectury.loom.util.MappingOption;
import dev.architectury.loom.util.TempFiles;
import net.minecraftforge.fart.api.Transformer;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.IntermediaryNamespaces;
import net.fabricmc.loom.configuration.accesstransformer.AccessTransformerJarProcessor;
import net.fabricmc.loom.configuration.providers.forge.legacy.MinecraftLegacyPatchedProvider;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpConfigProvider;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpExecutor;
import net.fabricmc.loom.configuration.providers.forge.minecraft.ForgeMinecraftProvider;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyDownloader;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.function.FsPathConsumer;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.srg.CoreModClassRemapper;
import net.fabricmc.loom.util.srg.InnerClassRemapper;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.MetaInfFixer;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

public class MinecraftPatchedProvider {
	protected static final String LOOM_PATCH_VERSION_KEY = "Loom-Patch-Version";
	protected static final String CURRENT_LOOM_PATCH_VERSION = "9";
	protected static final String NAME_MAPPING_SERVICE_PATH = "/inject/META-INF/services/cpw.mods.modlauncher.api.INameMappingService";

	protected final Project project;
	protected final Logger logger;
	protected final MinecraftProvider minecraftProvider;
	protected final Type type;

	// Step 1: Remap Minecraft to intermediate mappings, merge if needed
	protected Path minecraftIntermediateJar;
	// Step 2: Binary Patch
	protected Path minecraftPatchedIntermediateJar;
	// Step 3: Access Transform
	protected Path minecraftPatchedIntermediateAtJar;
	// Step 4: Remap Patched AT & Forge to official
	protected Path minecraftPatchedJar;
	protected Path minecraftClientExtra;

	protected boolean dirty = false;

	public static MinecraftPatchedProvider get(Project project) {
		MinecraftProvider provider = LoomGradleExtension.get(project).getMinecraftProvider();

		if (provider instanceof ForgeMinecraftProvider patched) {
			return patched.getPatchedProvider();
		} else {
			throw new UnsupportedOperationException("Project " + project.getPath() + " does not use MinecraftPatchedProvider!");
		}
	}

	public static MinecraftPatchedProvider create(Project project, MinecraftProvider minecraftProvider, Type type) {
		return LoomGradleExtension.get(project).isLegacyForgeLike()
				? new MinecraftLegacyPatchedProvider(project, minecraftProvider, type)
				: new MinecraftPatchedProvider(project, minecraftProvider, type);
	}

	public MinecraftPatchedProvider(Project project, MinecraftProvider minecraftProvider, Type type) {
		this.project = project;
		this.logger = project.getLogger();
		this.minecraftProvider = minecraftProvider;
		this.type = type;
	}

	protected LoomGradleExtension getExtension() {
		return LoomGradleExtension.get(project);
	}

	protected void initPatchedFiles() {
		String loader = getExtension().isNeoForge() ? "neoforge" : "forge";
		String forgeVersion = getExtension().getForgeProvider().getVersion().getCombined();
		Path forgeWorkingDir = ForgeProvider.getForgeCache(project);
		// Note: strings used instead of platform id since FML requires one of these exact strings
		// depending on the loader to recognise Minecraft.
		String patchId = loader + "-" + forgeVersion + "-";

		minecraftProvider.setJarPrefix(patchId);

		final String intermediateId = getExtension().isNeoForge() ? "mojang" : "srg";
		minecraftIntermediateJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-" + intermediateId + ".jar");
		minecraftPatchedIntermediateJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-" + intermediateId + "-patched.jar");
		minecraftPatchedIntermediateAtJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-" + intermediateId + "-at-patched.jar");
		minecraftPatchedJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-patched.jar");
		minecraftClientExtra = forgeWorkingDir.resolve("client-extra.jar");
	}

	protected void cleanAllCache() throws IOException {
		for (Path path : getGlobalCaches()) {
			Files.deleteIfExists(path);
		}
	}

	protected Path[] getGlobalCaches() {
		return new Path[] {
				minecraftIntermediateJar,
				minecraftPatchedIntermediateJar,
				minecraftPatchedIntermediateAtJar,
				minecraftPatchedJar,
				minecraftClientExtra,
		};
	}

	protected void checkCache() throws IOException {
		if (getExtension().refreshDeps() || Stream.of(getGlobalCaches()).anyMatch(Files::notExists)
				|| !isPatchedJarUpToDate(minecraftPatchedJar)) {
			cleanAllCache();
		}
	}

	public void provide() throws Exception {
		initPatchedFiles();
		checkCache();

		this.dirty = false;

		if (Files.notExists(minecraftIntermediateJar)) {
			this.dirty = true;

			try (var tempFiles = new TempFiles()) {
				McpExecutor executor = createMcpExecutor(tempFiles.directory("loom-mcp"));
				Path output = executor.enqueue("rename").execute();
				Files.copy(output, minecraftIntermediateJar);
			}
		}

		if (dirty || Files.notExists(minecraftPatchedIntermediateJar)) {
			this.dirty = true;
			patchJars(minecraftIntermediateJar, minecraftPatchedIntermediateJar, type);
			mergeForge(minecraftPatchedIntermediateJar);
		}

		if (dirty || Files.notExists(minecraftPatchedIntermediateAtJar)) {
			this.dirty = true;
			accessTransformForge();
		}
	}

	public void remapJar(ServiceFactory serviceFactory) throws Exception {
		if (dirty) {
			String sourceNamespace = IntermediaryNamespaces.intermediary(project);
			remapPatchedJar(serviceFactory, minecraftPatchedIntermediateAtJar, minecraftPatchedJar, sourceNamespace, "official");
			remapCoreMods(minecraftPatchedJar, serviceFactory);
			applyLoomPatchVersion(minecraftPatchedJar);
			fillClientExtraJar();
		}

		DependencyProvider.addDependency(project, minecraftClientExtra, Constants.Configurations.FORGE_EXTRA);
	}

	private void fillClientExtraJar() throws IOException {
		Files.deleteIfExists(minecraftClientExtra);
		FileSystemUtil.getJarFileSystem(minecraftClientExtra, true).close();

		copyNonClassFiles(minecraftProvider.getMinecraftClientJar().toPath(), minecraftClientExtra);
	}

	private TinyRemapper buildRemapper(ServiceFactory serviceFactory, Path input, String from, String to) throws IOException {
		final MappingOption mappingOption = MappingOption.forPlatform(getExtension());
		TinyMappingsService mappingsService = getExtension().getMappingConfiguration().getMappingsService(project, serviceFactory, mappingOption);
		MemoryMappingTree mappings = mappingsService.getMappingTree();

		TinyRemapper.Builder builder = TinyRemapper.newRemapper()
				.withMappings(TinyRemapperHelper.create(mappings, from, to, true))
				.withMappings(InnerClassRemapper.of(InnerClassRemapper.readClassNames(input), mappings, from, to))
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true);

		if (getExtension().isNeoForge()) {
			builder.extension(new MixinExtension(inputTag -> true));
		}

		return builder.build();
	}

	private void fixParameterAnnotation(Path jarFile) throws Exception {
		logger.info(":fixing parameter annotations for " + jarFile.toAbsolutePath());
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jarFile, false)) {
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter();
			Transformer transformer = Transformer.parameterAnnotationFixerFactory().create(null);

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] bytes = Files.readAllBytes(file);
					Transformer.ClassEntry entry = Transformer.ClassEntry.create(file.toString(), 0, bytes);
					byte[] out = transformer.process(entry).getData();

					if (!Arrays.equals(bytes, out)) {
						Files.write(file, out);
					}
				});
			}

			completer.complete();
		}

		logger.info(":fixed parameter annotations for " + jarFile.toAbsolutePath() + " in " + stopwatch);
	}

	private void deleteParameterNames(Path jarFile) throws Exception {
		logger.info(":deleting parameter names for " + jarFile.toAbsolutePath());
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jarFile, false)) {
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter();
			Pattern vignetteParameters = Pattern.compile("p_[0-9a-zA-Z]+_(?:[0-9a-zA-Z]+_)?");

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] bytes = Files.readAllBytes(file);
					ClassReader reader = new ClassReader(bytes);
					ClassWriter writer = new ClassWriter(0);

					reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
						@Override
						public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
							return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
								@Override
								public void visitParameter(String name, int access) {
									if (name != null && vignetteParameters.matcher(name).matches()) {
										super.visitParameter(null, access);
									} else {
										super.visitParameter(name, access);
									}
								}

								@Override
								public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
									if (!vignetteParameters.matcher(name).matches()) {
										super.visitLocalVariable(name, descriptor, signature, start, end, index);
									}
								}
							};
						}
					}, 0);

					byte[] out = writer.toByteArray();

					if (!Arrays.equals(bytes, out)) {
						Files.write(file, out);
					}
				});
			}

			completer.complete();
		}

		logger.info(":deleted parameter names for " + jarFile.toAbsolutePath() + " in " + stopwatch);
	}

	private File getForgeJar() {
		return getExtension().getForgeUniversalProvider().getForge();
	}

	private File getForgeUserdevJar() {
		return getExtension().getForgeUserdevProvider().getUserdevJar();
	}

	private boolean isPatchedJarUpToDate(Path jar) throws IOException {
		if (Files.notExists(jar)) return false;

		byte[] manifestBytes = ZipUtils.unpackNullable(jar, "META-INF/MANIFEST.MF");

		if (manifestBytes == null) {
			return false;
		}

		Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
		Attributes attributes = manifest.getMainAttributes();
		String value = attributes.getValue(LOOM_PATCH_VERSION_KEY);

		if (Objects.equals(value, CURRENT_LOOM_PATCH_VERSION)) {
			return true;
		} else {
			logger.lifecycle(":forge patched jars not up to date. current version: " + value);
			return false;
		}
	}

	protected void accessTransformForge() throws IOException {
		Path input = minecraftPatchedIntermediateJar;
		Path target = minecraftPatchedIntermediateAtJar;
		accessTransform(project, input, target);
	}

	public static void accessTransform(Project project, Path input, Path target) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();

		project.getLogger().lifecycle(":access transforming minecraft");

		LoomGradleExtension extension = LoomGradleExtension.get(project);
		Path userdevJar = extension.getForgeUserdevProvider().getUserdevJar().toPath();
		Files.deleteIfExists(target);

		try (
				TempFiles tempFiles = new TempFiles();
				FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(userdevJar)
		) {
			AccessTransformerJarProcessor.executeAt(project, input, target, args -> {
				for (String atFile : extractAccessTransformers(userdevJar, extension.getForgeUserdevProvider().getConfig().ats(), tempFiles)) {
					args.add("--atFile");
					args.add(atFile);
				}
			});
		}

		project.getLogger().lifecycle(":access transformed minecraft in " + stopwatch.stop());
	}

	private static List<String> extractAccessTransformers(Path jar, UserdevConfig.AccessTransformerLocation location, TempFiles tempFiles) throws IOException {
		final List<String> extracted = new ArrayList<>();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jar)) {
			for (Path atFile : getAccessTransformerPaths(fs, location)) {
				List<String> atLines;

				try {
					atLines = Files.readAllLines(atFile, StandardCharsets.UTF_8);
				} catch (NoSuchFileException e) {
					continue;
				}

				// Fix 1.7 ats
				String atStr = atLines.stream()
						.map(line -> line.contains("<") && line.endsWith(")") ? line + "V" : line)
						.collect(Collectors.joining("\n"));

				Path tmpFile = tempFiles.file("at-conf", ".cfg");
				Files.writeString(tmpFile, atStr, StandardCharsets.UTF_8);
				extracted.add(tmpFile.toAbsolutePath().toString());
			}
		}

		return extracted;
	}

	private static List<Path> getAccessTransformerPaths(FileSystemUtil.Delegate fs, UserdevConfig.AccessTransformerLocation location) throws IOException {
		return location.visitIo(directory -> {
			Path dirPath = fs.getPath(directory);

			try (Stream<Path> paths = Files.list(dirPath)) {
				return paths.toList();
			}
		}, paths -> paths.stream().map(fs::getPath).toList());
	}

	protected void remapPatchedJar(ServiceFactory serviceFactory, Path mcInput, Path mcOutput, String from, String to) throws Exception {
		logger.lifecycle(":remapping minecraft (TinyRemapper, {} -> {})", from, to);
		Path forgeJar = getForgeJar().toPath();
		Path forgeUserdevJar = getForgeUserdevJar().toPath();
		Files.deleteIfExists(mcOutput);

		TinyRemapper remapper = buildRemapper(serviceFactory, mcInput, from, to);

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(mcOutput).build()) {
			outputConsumer.addNonClassFiles(mcInput);
			remapper.readInputs(mcInput);
			remapper.apply(outputConsumer);
		} finally {
			remapper.finish();
		}
	}

	protected void mergeForge(Path input) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":merging forge");

		try (TempFiles tempFiles = new TempFiles()) {
			Path output = tempFiles.file("forge-merged", ".tmp.jar");
			Path forgeJar = getForgeJar().toPath();
			Path forgeUserdevJar = getForgeUserdevJar().toPath();
			Files.deleteIfExists(output);

			TinyRemapper remapper = TinyRemapper.newRemapper().build();

			try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build()) {
				outputConsumer.addNonClassFiles(input);
				outputConsumer.addNonClassFiles(forgeJar, remapper, List.of(MetaInfFixer.INSTANCE, new UserdevFilter()));

				InputTag mcTag = remapper.createInputTag();
				InputTag forgeTag = remapper.createInputTag();
				List<CompletableFuture<?>> futures = new ArrayList<>();
				futures.add(remapper.readInputsAsync(mcTag, input));
				futures.add(remapper.readInputsAsync(forgeTag, forgeJar));

				if (getExtension().isModernForgeLike()) {
					futures.add(remapper.readInputsAsync(forgeTag, forgeUserdevJar));
				}

				CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
				remapper.apply(outputConsumer, mcTag);
				remapper.apply(outputConsumer, forgeTag);
			} finally {
				remapper.finish();
			}

			copyUserdevFiles(forgeUserdevJar, output);

			Files.copy(output, input, StandardCopyOption.REPLACE_EXISTING);
		}

		logger.lifecycle(":merged forge in" + stopwatch.stop());
	}

	private void remapCoreMods(Path patchedJar, ServiceFactory serviceFactory) throws Exception {
		final MappingOption mappingOption = MappingOption.forPlatform(getExtension());
		final TinyMappingsService mappingsService = getExtension().getMappingConfiguration().getMappingsService(project, serviceFactory, mappingOption);
		final MappingTree mappings = mappingsService.getMappingTree();
		CoreModClassRemapper.remapJar(project, getExtension().getPlatform().get(), patchedJar, mappings);
	}

	protected void patchJars(Path input, Path output, Type type) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");
		patchJars(input, output, type.patches.apply(getExtension().getPatchProvider(), getExtension().getForgeUserdevProvider()));

		copyMissingClasses(input, output);
		deleteParameterNames(output);

		if (getExtension().isForgeLikeAndNotOfficial()) {
			fixParameterAnnotation(output);
		}

		logger.lifecycle(":patched jars in " + stopwatch.stop());
	}

	private void patchJars(Path clean, Path output, Path patches) {
		ForgeToolValueSource.exec(project, spec -> {
			UserdevConfig.BinaryPatcherConfig config = getExtension().getForgeUserdevProvider().getConfig().binpatcher();
			final FileCollection download = DependencyDownloader.download(project, config.dependency());
			spec.classpath(download);
			spec.getMainClass().set(getMainClass(download));

			for (String arg : config.args()) {
				String actual = switch (arg) {
				case "{clean}" -> clean.toAbsolutePath().toString();
				case "{output}" -> output.toAbsolutePath().toString();
				case "{patch}" -> patches.toAbsolutePath().toString();
				default -> arg;
				};
				spec.args(actual);
			}
		});
	}

	private static String getMainClass(final Iterable<File> files) {
		String mainClass = null;
		IOException ex = null;

		for (File file : files) {
			if (file.getName().endsWith(".jar")) {
				try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(file.toPath())) {
					final Path mfPath = fs.getPath("META-INF/MANIFEST.MF");

					if (Files.exists(mfPath)) {
						try (InputStream in = Files.newInputStream(mfPath)) {
							mainClass = new Manifest(in).getMainAttributes().getValue("Main-Class");
						}
					}
				} catch (final IOException e) {
					if (ex == null) {
						ex = e;
					} else {
						ex.addSuppressed(e);
					}
				}

				if (mainClass != null) {
					break;
				}
			}
		}

		if (mainClass == null) {
			if (ex != null) {
				throw new UncheckedIOException(ex);
			} else {
				throw new RuntimeException("Failed to find main class");
			}
		}

		return mainClass;
	}

	private void walkFileSystems(Path source, Path target, Predicate<Path> filter, Function<FileSystem, Iterable<Path>> toWalk, FsPathConsumer action)
			throws IOException {
		try (FileSystemUtil.Delegate sourceFs = FileSystemUtil.getJarFileSystem(source, false);
				FileSystemUtil.Delegate targetFs = FileSystemUtil.getJarFileSystem(target, false)) {
			for (Path sourceDir : toWalk.apply(sourceFs.get())) {
				Path dir = sourceDir.toAbsolutePath();
				if (!Files.exists(dir)) continue;
				Files.walk(dir)
						.filter(Files::isRegularFile)
						.filter(filter)
						.forEach(it -> {
							boolean root = dir.getParent() == null;

							try {
								Path relativeSource = root ? it : dir.relativize(it);
								Path targetPath = targetFs.get().getPath(relativeSource.toString());
								action.accept(sourceFs.get(), targetFs.get(), it, targetPath);
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						});
			}
		}
	}

	protected void walkFileSystems(Path source, Path target, Predicate<Path> filter, FsPathConsumer action) throws IOException {
		walkFileSystems(source, target, filter, FileSystem::getRootDirectories, action);
	}

	private void copyMissingClasses(Path source, Path target) throws IOException {
		walkFileSystems(source, target, it -> it.toString().endsWith(".class"), (sourceFs, targetFs, sourcePath, targetPath) -> {
			if (Files.exists(targetPath)) return;
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	private void copyNonClassFiles(Path source, Path target) throws IOException {
		Predicate<Path> filter = file -> {
			String s = file.toString();
			return !s.endsWith(".class") && !s.startsWith("/META-INF");
		};

		walkFileSystems(source, target, filter, this::copyReplacing);
	}

	protected void copyReplacing(FileSystem sourceFs, FileSystem targetFs, Path sourcePath, Path targetPath) throws IOException {
		Path parent = targetPath.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
	}

	private void copyUserdevFiles(Path source, Path target) throws IOException {
		// Removes the Forge name mapping service definition so that our own is used.
		// If there are multiple name mapping services with the same "understanding" pair
		// (source -> target namespace pair), modlauncher throws a fit and will crash.
		// To use our YarnNamingService instead of MCPNamingService, we have to remove this file.
		Predicate<Path> filter = file -> !file.toString().endsWith(".class") && !file.toString().equals(NAME_MAPPING_SERVICE_PATH);

		walkFileSystems(source, target, filter, fs -> Collections.singleton(fs.getPath("inject")), (sourceFs, targetFs, sourcePath, targetPath) -> {
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	public void applyLoomPatchVersion(Path target) throws IOException {
		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(target, false)) {
			Path manifestPath = delegate.get().getPath("META-INF/MANIFEST.MF");

			Preconditions.checkArgument(Files.exists(manifestPath), "META-INF/MANIFEST.MF does not exist in patched srg jar!");
			Manifest manifest = new Manifest();

			if (Files.exists(manifestPath)) {
				try (InputStream stream = Files.newInputStream(manifestPath)) {
					manifest.read(stream);
					manifest.getMainAttributes().putValue(LOOM_PATCH_VERSION_KEY, CURRENT_LOOM_PATCH_VERSION);
				}
			}

			try (OutputStream stream = Files.newOutputStream(manifestPath, StandardOpenOption.CREATE)) {
				manifest.write(stream);
			}
		}
	}

	public McpExecutor createMcpExecutor(Path cache) {
		return createMcpExecutor(cache, type);
	}

	public McpExecutor createMcpExecutor(Path cache, Type type) {
		McpConfigProvider provider = getExtension().getMcpConfigProvider();
		return new McpExecutor(project, minecraftProvider, cache, provider, type.mcpId);
	}

	public Path getMinecraftIntermediateJar() {
		return minecraftIntermediateJar;
	}

	public Path getMinecraftPatchedIntermediateJar() {
		return minecraftPatchedIntermediateJar;
	}

	public Path getMinecraftPatchedJar() {
		return minecraftPatchedJar;
	}

	/**
	 * Checks whether the provider's state is dirty (regenerating jars).
	 */
	public boolean isDirty() {
		return dirty;
	}

	public enum Type {
		CLIENT_ONLY("client", "client", (patch, userdev) -> patch.clientPatches),
		SERVER_ONLY("server", "server", (patch, userdev) -> patch.serverPatches),
		MERGED("merged", "joined", (patch, userdev) -> userdev.joinedPatches);

		public final String id;
		public final String mcpId;
		public final BiFunction<PatchProvider, ForgeUserdevProvider, Path> patches;

		Type(String id, String mcpId, BiFunction<PatchProvider, ForgeUserdevProvider, Path> patches) {
			this.id = id;
			this.mcpId = mcpId;
			this.patches = patches;
		}
	}

	public final class UserdevFilter implements OutputConsumerPath.ResourceRemapper {
		@Override
		public boolean canTransform(TinyRemapper tinyRemapper, Path path) {
			List<String> patterns = getExtension().getForgeUserdevProvider().getConfig().universalFilters();
			return !patterns.isEmpty() && patterns.stream().noneMatch(pattern -> path.toString().matches(pattern));
		}

		@Override
		public void transform(Path path, Path path1, InputStream inputStream, TinyRemapper tinyRemapper) throws IOException {
			// Remove anything that userdev wants to be filtered
		}
	}
}
