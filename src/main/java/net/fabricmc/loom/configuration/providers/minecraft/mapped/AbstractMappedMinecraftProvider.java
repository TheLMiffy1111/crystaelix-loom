/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2022 FabricMC
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

package net.fabricmc.loom.configuration.providers.minecraft.mapped;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;

import dev.architectury.loom.util.MappingOption;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.build.IntermediaryNamespaces;
import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.configuration.providers.forge.minecraft.ForgeMinecraftProvider;
import net.fabricmc.loom.configuration.providers.mappings.IntermediaryMappingsProvider;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJar;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.configuration.providers.minecraft.SignatureFixerApplyVisitor;
import net.fabricmc.loom.extension.LoomFiles;
import net.fabricmc.loom.util.SidedClassVisitor;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.srg.InnerClassRemapper;
import net.fabricmc.loom.util.srg.RemapObjectHolderVisitor;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public abstract class AbstractMappedMinecraftProvider<M extends MinecraftProvider> implements MappedMinecraftProvider.ProviderImpl {
	protected final M minecraftProvider;
	private final Project project;
	protected final LoomGradleExtension extension;

	public AbstractMappedMinecraftProvider(Project project, M minecraftProvider) {
		this.minecraftProvider = minecraftProvider;
		this.project = project;
		this.extension = LoomGradleExtension.get(project);
	}

	public abstract MappingsNamespace getTargetNamespace();

	public abstract List<RemappedJars> getRemappedJars();

	// Returns a list of MinecraftJar.Type's that this provider exports to be used as a dependency
	public List<MinecraftJar.Type> getDependencyTypes() {
		return Collections.emptyList();
	}

	public List<MinecraftJar> provide(ProvideContext context) throws Exception {
		final List<RemappedJars> remappedJars = getRemappedJars();
		final List<MinecraftJar> minecraftJars = remappedJars.stream()
				.map(RemappedJars::outputJar)
				.toList();

		if (remappedJars.isEmpty()) {
			throw new IllegalStateException("No remapped jars provided");
		}

		if (!areOutputsValid(remappedJars) || context.refreshOutputs() || !hasBackupJars(minecraftJars)) {
			try {
				remapInputs(remappedJars, context.configContext());
				createBackupJars(minecraftJars);
			} catch (Throwable t) {
				cleanOutputs(remappedJars);

				throw new RuntimeException("Failed to remap minecraft", t);
			}
		}

		if (context.applyDependencies()) {
			final List<MinecraftJar.Type> dependencyTargets = getDependencyTypes();

			if (!dependencyTargets.isEmpty()) {
				MinecraftSourceSets.get(getProject()).applyDependencies(
						(configuration, type) -> getProject().getDependencies().add(configuration, getDependencyNotation(type)),
						dependencyTargets
				);
			}
		}

		return minecraftJars;
	}

	// Create two copies of the remapped jar, the backup jar is used as the input of genSources
	public static Path getBackupJarPath(MinecraftJar minecraftJar) {
		final Path outputJarPath = minecraftJar.getPath();
		return outputJarPath.resolveSibling(outputJarPath.getFileName() + ".backup");
	}

	protected boolean hasBackupJars(List<MinecraftJar> minecraftJars) {
		for (MinecraftJar minecraftJar : minecraftJars) {
			if (!Files.exists(getBackupJarPath(minecraftJar))) {
				return false;
			}
		}

		return true;
	}

	protected void createBackupJars(List<MinecraftJar> minecraftJars) throws IOException {
		for (MinecraftJar minecraftJar : minecraftJars) {
			Files.copy(minecraftJar.getPath(), getBackupJarPath(minecraftJar), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public record ProvideContext(boolean applyDependencies, boolean refreshOutputs, ConfigContext configContext) {
		ProvideContext withApplyDependencies(boolean applyDependencies) {
			return new ProvideContext(applyDependencies, refreshOutputs(), configContext());
		}
	}

	@Override
	public Path getJar(MinecraftJar.Type type) {
		return getMavenHelper(type).getOutputFile(null);
	}

	public enum MavenScope {
		// Output files will be stored per project
		LOCAL(LoomFiles::getLocalMinecraftRepo),
		// Output files will be stored globally
		GLOBAL(LoomFiles::getGlobalMinecraftRepo);

		private final Function<LoomFiles, File> fileFunction;

		MavenScope(Function<LoomFiles, File> fileFunction) {
			this.fileFunction = fileFunction;
		}

		public Path getRoot(LoomGradleExtension extension) {
			return fileFunction.apply(extension.getFiles()).toPath();
		}
	}

	public abstract MavenScope getMavenScope();

	public LocalMavenHelper getMavenHelper(MinecraftJar.Type type) {
		return new LocalMavenHelper("net.minecraft", getName(type), getVersion(), null, getMavenScope().getRoot(extension));
	}

	protected String getName(MinecraftJar.Type type) {
		final String intermediateName = extension.getIntermediateMappingsProvider().getName();

		var sj = new StringJoiner("-");
		sj.add("minecraft");
		sj.add(type.toString());

		// Include the intermediate mapping name if it's not the default intermediary
		if (!intermediateName.equals(IntermediaryMappingsProvider.NAME)) {
			sj.add(intermediateName);
		}

		if (getTargetNamespace() != MappingsNamespace.NAMED) {
			sj.add(getTargetNamespace().name());
		}

		return minecraftProvider.getJarPrefix() + sj.toString().toLowerCase(Locale.ROOT);
	}

	protected String getVersion() {
		return "%s-%s".formatted(extension.getMinecraftProvider().minecraftVersion(), extension.getMappingConfiguration().mappingsIdentifier());
	}

	protected String getDependencyNotation(MinecraftJar.Type type) {
		return "net.minecraft:%s:%s".formatted(getName(type), getVersion());
	}

	private boolean areOutputsValid(List<RemappedJars> remappedJars) {
		for (RemappedJars remappedJar : remappedJars) {
			if (!getMavenHelper(remappedJar.type()).exists(null)) {
				return false;
			}
		}

		// Architectury: regenerate jars if patches have changed.
		if (minecraftProvider instanceof ForgeMinecraftProvider withForge && withForge.getPatchedProvider().isDirty()) {
			return false;
		}

		return true;
	}

	private void remapInputs(List<RemappedJars> remappedJars, ConfigContext configContext) throws IOException {
		cleanOutputs(remappedJars);

		for (RemappedJars remappedJar : remappedJars) {
			remapJar(remappedJar, configContext);
		}
	}

	private void remapJar(RemappedJars remappedJars, ConfigContext configContext) throws IOException {
		final MappingConfiguration mappingConfiguration = extension.getMappingConfiguration();
		final String fromM = remappedJars.sourceNamespace().toString();
		final String toM = getTargetNamespace().toString();

		Files.deleteIfExists(remappedJars.outputJarPath());

		final Set<String> classNames = extension.isForgeLike() ? InnerClassRemapper.readClassNames(remappedJars.inputJar()) : Set.of();
		final Map<String, String> remappedSignatures = SignatureFixerApplyVisitor.getRemappedSignatures(getTargetNamespace() == MappingsNamespace.INTERMEDIARY, mappingConfiguration, getProject(), configContext.serviceFactory(), toM);
		final MinecraftVersionMeta.JavaVersion javaVersion = minecraftProvider.getVersionInfo().javaVersion();
		final boolean fixRecords = javaVersion != null && javaVersion.majorVersion() >= 16;

		TinyRemapper remapper = TinyRemapperHelper.getTinyRemapper(getProject(), configContext.serviceFactory(), fromM, toM, fixRecords, (builder) -> {
			builder.extraPostApplyVisitor(new SignatureFixerApplyVisitor(remappedSignatures));
			if (extension.isNeoForge()) builder.extension(new MixinExtension(inputTag -> true));
			configureRemapper(remappedJars, builder);
		}, classNames);

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(remappedJars.outputJarPath()).build()) {
			outputConsumer.addNonClassFiles(remappedJars.inputJar());

			for (Path path : remappedJars.remapClasspath()) {
				remapper.readClassPath(path);
			}

			remapper.readInputs(remappedJars.inputJar());
			remapper.apply(outputConsumer);
		} catch (Exception e) {
			throw new RuntimeException("Failed to remap JAR " + remappedJars.inputJar() + " with mappings from " + mappingConfiguration.tinyMappings, e);
		} finally {
			remapper.finish();
		}

		getMavenHelper(remappedJars.type()).savePom();

		if (extension.isForgeLikeAndOfficial()) {
			final MappingOption mappingOption = MappingOption.forPlatform(extension);
			final TinyMappingsService mappingsService = extension.getMappingConfiguration().getMappingsService(project, configContext.serviceFactory(), mappingOption);
			final String className;

			if (extension.isNeoForge()) {
				className = "net.neoforged.neoforge.registries.ObjectHolderRegistry";
			} else {
				className = "net.minecraftforge.registries.ObjectHolderRegistry";
			}

			final String sourceNamespace = IntermediaryNamespaces.runtimeIntermediary(project);
			final MemoryMappingTree mappings = mappingsService.getMappingTree();
			RemapObjectHolderVisitor.remapObjectHolder(remappedJars.outputJar().getPath(), className, mappings, sourceNamespace, "named");
		}
	}

	protected void configureRemapper(RemappedJars remappedJars, TinyRemapper.Builder tinyRemapperBuilder) {
	}

	// Configure the remapper to add the client @Environment annotation to all classes in the client jar.
	public static void configureSplitRemapper(RemappedJars remappedJars, TinyRemapper.Builder tinyRemapperBuilder) {
		final MinecraftJar outputJar = remappedJars.outputJar();
		assert !outputJar.isMerged();

		if (outputJar.includesClient()) {
			assert !outputJar.includesServer();
			tinyRemapperBuilder.extraPostApplyVisitor(SidedClassVisitor.CLIENT);
		}
	}

	private void cleanOutputs(List<RemappedJars> remappedJars) throws IOException {
		for (RemappedJars remappedJar : remappedJars) {
			Files.deleteIfExists(remappedJar.outputJarPath());
			Files.deleteIfExists(getBackupJarPath(remappedJar.outputJar()));
		}
	}

	public Project getProject() {
		return project;
	}

	public M getMinecraftProvider() {
		return minecraftProvider;
	}

	public record RemappedJars(Path inputJar, MinecraftJar outputJar, MappingsNamespace sourceNamespace, Path... remapClasspath) {
		public Path outputJarPath() {
			return outputJar().getPath();
		}

		public String name() {
			return outputJar().getName();
		}

		public MinecraftJar.Type type() {
			return outputJar().getType();
		}
	}
}
