/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2024 FabricMC
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

package net.fabricmc.loom.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import javax.inject.Inject;

import com.google.gson.JsonObject;
import dev.architectury.loom.extensions.ModBuildExtensions;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerRemapper;
import net.fabricmc.accesswidener.AccessWidenerWriter;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.nesting.JarNester;
import net.fabricmc.loom.build.nesting.NestableJarGenerationTask;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerFile;
import net.fabricmc.loom.configuration.mods.ArtifactMetadata;
import net.fabricmc.loom.task.service.ClientEntriesService;
import net.fabricmc.loom.task.service.MappingsService;
import net.fabricmc.loom.task.service.MixinRefmapService;
import net.fabricmc.loom.task.service.TinyRemapperService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.SidedClassVisitor;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;
import net.fabricmc.loom.util.fmj.FabricModJsonUtils;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public abstract class RemapJarTask extends AbstractRemapJarTask {
	@InputFiles
	public abstract ConfigurableFileCollection getNestedJars();

	@Input
	public abstract Property<Boolean> getAddNestedDependencies();

	/**
	 * Whether to optimize the fabric.mod.json file, by default this is false.
	 *
	 * <p>The schemaVersion entry will be placed first in the json file
	 */
	@Input
	public abstract Property<Boolean> getOptimizeFabricModJson();

	/**
	 * Gets the jar paths to the access wideners that will be converted to ATs for Forge runtime.
	 * If you specify multiple files, they will be merged into one.
	 *
	 * <p>The specified files will be converted and removed from the final jar.
	 *
	 * @return the property containing access widener paths in the final jar
	 */
	@Input
	public abstract SetProperty<String> getAtAccessWideners();

	/**
	 * Configures whether to read mixin configs from jar manifest
	 * if a fabric.mod.json cannot be found.
	 *
	 * <p>This is enabled by default on Forge, but not on other platforms.
	 *
	 * @return the property
	 */
	@Input
	public abstract Property<Boolean> getReadMixinConfigsFromManifest();

	/**
	 * Sets the "accessWidener" property in the fabric.mod.json, if the project is
	 * using access wideners.
	 *
	 * @return the property
	 */
	@Input
	public abstract Property<Boolean> getInjectAccessWidener();

	/**
	 * The path of the access widener to inject if {@link #getInjectAccessWidener() injectAccessWidener} is enabled.
	 */
	@InputFile
	@Optional
	public abstract RegularFileProperty getInjectedAccessWidenerPath();

	@Input
	@ApiStatus.Internal
	protected abstract Property<ModPlatform> getModPlatform();

	@Input
	@ApiStatus.Internal
	public abstract Property<Boolean> getUseMixinAP();
	@Nested
	public abstract Property<TinyRemapperService.Options> getTinyRemapperServiceOptions();
	@Nested
	public abstract ListProperty<MixinRefmapService.Options> getMixinRefmapServiceOptions();

	@Inject
	public RemapJarTask() {
		super();
		final ConfigurationContainer configurations = getProject().getConfigurations();
		getClasspath().from(configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME));
		getAddNestedDependencies().convention(true).finalizeValueOnRead();
		getOptimizeFabricModJson().convention(false).finalizeValueOnRead();
		getReadMixinConfigsFromManifest().convention(LoomGradleExtension.get(getProject()).isForgeLike()).finalizeValueOnRead();
		getInjectAccessWidener().convention(false);

		TaskProvider<NestableJarGenerationTask> processIncludeJars = getProject().getTasks().named(Constants.Task.PROCESS_INCLUDE_JARS, NestableJarGenerationTask.class);
		getNestedJars().from(getProject().fileTree(processIncludeJars.get().getOutputDirectory()));
		getNestedJars().builtBy(processIncludeJars);

		getUseMixinAP().set(LoomGradleExtension.get(getProject()).getMixin().getUseLegacyMixinAp());

		// Make outputs reproducible by default
		setReproducibleFileOrder(true);
		setPreserveFileTimestamps(false);

		getJarType().set("classes");

		getTinyRemapperServiceOptions().set(TinyRemapperService.createOptions(this));
		getMixinRefmapServiceOptions().set(MixinRefmapService.createOptions(this));

		getModPlatform().value(LoomGradleExtension.get(getProject()).getPlatform()).finalizeValue();

		getInjectedAccessWidenerPath().convention(LoomGradleExtension.get(getProject()).getAccessWidenerPath());
	}

	@TaskAction
	public void run() {
		submitWork(RemapAction.class, params -> {
			if (getAddNestedDependencies().get()) {
				params.getNestedJars().from(getNestedJars());
			}

			if (!params.namespacesMatch()) {
				params.getTinyRemapperServiceOptions().set(getTinyRemapperServiceOptions());
				params.getMixinRefmapServiceOptions().set(getMixinRefmapServiceOptions());

				params.getRemapClasspath().from(getClasspath());

				final boolean mixinAp = getUseMixinAP().get();
				params.getUseMixinExtension().set(!mixinAp);

				// Add the mixin refmap remap type to the manifest
				// This is used by the mod dependency remapper to determine if it should remap the refmap
				// or if the refmap should be remapped by mixin at runtime.
				final var refmapRemapType = mixinAp ? ArtifactMetadata.MixinRemapType.MIXIN : ArtifactMetadata.MixinRemapType.STATIC;
				params.getManifestAttributes().put(Constants.Manifest.MIXIN_REMAP_TYPE, refmapRemapType.manifestValue());
			} else if (getModPlatform().get().isSrgForgeLike()) {
				throw new RuntimeException("Forge must have useLegacyMixinAp enabled");
			}

			params.getPlatform().set(getModPlatform());

			if (getInjectAccessWidener().get() && getInjectedAccessWidenerPath().isPresent()) {
				params.getInjectAccessWidener().set(getInjectedAccessWidenerPath());
			}

			params.getReadMixinConfigsFromManifest().set(getReadMixinConfigsFromManifest());
			params.getAtAccessWideners().set(getAtAccessWideners());

			params.getOptimizeFmj().set(getOptimizeFabricModJson().get());
		});
	}

	public interface RemapParams extends AbstractRemapParams {
		ConfigurableFileCollection getNestedJars();

		ConfigurableFileCollection getRemapClasspath();

		Property<ModPlatform> getPlatform();

		RegularFileProperty getInjectAccessWidener();
		Property<Boolean> getReadMixinConfigsFromManifest();
		SetProperty<String> getAtAccessWideners();

		Property<Boolean> getUseMixinExtension();
		Property<Boolean> getOptimizeFmj();

		Property<TinyRemapperService.Options> getTinyRemapperServiceOptions();
		ListProperty<MixinRefmapService.Options> getMixinRefmapServiceOptions();
	}

	public abstract static class RemapAction extends AbstractRemapAction<RemapParams> {
		private static final Logger LOGGER = LoggerFactory.getLogger(RemapAction.class);

		private @Nullable TinyRemapperService tinyRemapperService;
		private @Nullable TinyRemapper tinyRemapper;

		public RemapAction() {
		}

		@Override
		public void execute() {
			try (var serviceFactory = new ScopedServiceFactory()) {
				LOGGER.info("Remapping {} to {}", inputFile, outputFile);

				this.tinyRemapperService = getParameters().getTinyRemapperServiceOptions().isPresent()
						? serviceFactory.get(getParameters().getTinyRemapperServiceOptions().get())
						: null;

				prepare();

				if (tinyRemapperService != null) {
					tinyRemapper = tinyRemapperService.getTinyRemapperForRemapping();

					remap();
				} else {
					Files.copy(inputFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
				}

				if (getParameters().getClientOnlyEntries().isPresent()) {
					markClientOnlyClasses();
				}

				if (!injectAccessWidener()) {
					remapAccessWidener();
				}

				addRefmaps(serviceFactory);
				addNestedJars();

				if (getParameters().getAtAccessWideners().isPresent()) {
					final Provider<MappingsService.Options> mappingsServiceOptions = getParameters().getTinyRemapperServiceOptions()
							.flatMap(TinyRemapperService.Options::getMappings)
							.map(mappingsOptions -> mappingsOptions.get(0));
					ModBuildExtensions.convertAwToAt(serviceFactory, getParameters().getAtAccessWideners().get(), outputFile, mappingsServiceOptions);
				}

				if (!getParameters().getPlatform().get().isForgeLike()) {
					modifyJarManifest();
				}

				rewriteJar();

				if (getParameters().getOptimizeFmj().get()) {
					optimizeFMJ();
				}

				if (tinyRemapperService != null) {
					tinyRemapperService.close();
				}

				LOGGER.debug("Finished remapping {}", inputFile);
			} catch (Exception e) {
				try {
					Files.deleteIfExists(outputFile);
				} catch (IOException ex) {
					LOGGER.error("Failed to delete output file", ex);
				}

				throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Failed to remap", e);
			}
		}

		private void prepare() {
			final Path inputFile = getParameters().getInputFile().getAsFile().get().toPath();

			if (tinyRemapperService != null) {
				tinyRemapperService.getTinyRemapperForInputs().readInputsAsync(tinyRemapperService.getOrCreateTag(inputFile), inputFile);
			}
		}

		private void remap() throws IOException {
			Objects.requireNonNull(tinyRemapperService, "tinyRemapperService");
			Objects.requireNonNull(tinyRemapper, "tinyRemapper");

			try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputFile).build()) {
				outputConsumer.addNonClassFiles(inputFile);
				tinyRemapper.apply(outputConsumer, tinyRemapperService.getOrCreateTag(inputFile));
			}
		}

		private void markClientOnlyClasses() throws IOException {
			final Stream<Pair<String, ZipUtils.UnsafeUnaryOperator<byte[]>>> tranformers = getParameters().getClientOnlyEntries().get().stream()
					.map(s -> new Pair<>(s,
							(ZipUtils.AsmClassOperator) classVisitor -> SidedClassVisitor.CLIENT.insertApplyVisitor(null, classVisitor)
					));

			ZipUtils.transform(outputFile, tranformers);
		}

		private boolean injectAccessWidener() throws IOException {
			if (!getParameters().getInjectAccessWidener().isPresent()) return false;

			Path path = getParameters().getInjectAccessWidener().getAsFile().get().toPath();

			byte[] remapped = remapAccessWidener(Files.readAllBytes(path));

			ZipUtils.add(outputFile, path.getFileName().toString(), remapped);

			if (getParameters().getPlatform().get() == ModPlatform.QUILT) {
				ZipUtils.transformJson(JsonObject.class, outputFile, Map.of("quilt.mod.json", json -> {
					json.addProperty("access_widener", path.getFileName().toString());
					return json;
				}));
				return true;
			}

			ZipUtils.transformJson(JsonObject.class, outputFile, Map.of("fabric.mod.json", json -> {
				json.addProperty("accessWidener", path.getFileName().toString());
				return json;
			}));

			return true;
		}

		private void remapAccessWidener() throws IOException {
			if (getParameters().namespacesMatch()) {
				return;
			}

			final AccessWidenerFile accessWidenerFile = AccessWidenerFile.fromModJar(inputFile);

			if (accessWidenerFile == null) {
				return;
			}

			byte[] remapped = remapAccessWidener(accessWidenerFile.content());

			// Finally, replace the output with the remaped aw
			ZipUtils.replace(outputFile, accessWidenerFile.path(), remapped);
		}

		private byte[] remapAccessWidener(byte[] input) {
			Objects.requireNonNull(tinyRemapper, "tinyRemapper");

			int version = AccessWidenerReader.readVersion(input);

			AccessWidenerWriter writer = new AccessWidenerWriter(version);
			AccessWidenerRemapper remapper = new AccessWidenerRemapper(
					writer,
					tinyRemapper.getEnvironment().getRemapper(),
					getParameters().getSourceNamespace().get(),
					getParameters().getTargetNamespace().get()
			);
			AccessWidenerReader reader = new AccessWidenerReader(remapper);
			reader.read(input);

			return writer.write();
		}

		private void addNestedJars() {
			FileCollection nestedJars = getParameters().getNestedJars();

			if (nestedJars.isEmpty()) {
				LOGGER.info("No jars to nest");
				return;
			}

			JarNester.nestJars(nestedJars.getFiles(), outputFile.toFile(), getParameters().getPlatform().get(), LOGGER);
		}

		private void addRefmaps(ServiceFactory serviceFactory) throws IOException {
			if (getParameters().getUseMixinExtension().getOrElse(false)) {
				return;
			}

			for (MixinRefmapService.Options options : getParameters().getMixinRefmapServiceOptions().get()) {
				MixinRefmapService mixinRefmapService = serviceFactory.get(options);
				mixinRefmapService.applyToJar(outputFile, getParameters().getReadMixinConfigsFromManifest().get());
			}
		}

		private void optimizeFMJ() throws IOException {
			if (!ZipUtils.contains(outputFile, FabricModJsonFactory.FABRIC_MOD_JSON)) {
				return;
			}

			ZipUtils.transformJson(JsonObject.class, outputFile, FabricModJsonFactory.FABRIC_MOD_JSON, FabricModJsonUtils::optimizeFmj);
		}
	}

	@Override
	protected Provider<? extends ClientEntriesService.Options> getClientOnlyEntriesOptionsProvider(SourceSet clientSourceSet) {
		return ClientEntriesService.Classes.createOptions(getProject(), clientSourceSet);
	}
}
