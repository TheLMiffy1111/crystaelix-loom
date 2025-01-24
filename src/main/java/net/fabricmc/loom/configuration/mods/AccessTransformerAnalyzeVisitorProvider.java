/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024 FabricMC
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

package net.fabricmc.loom.configuration.mods;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Manifest;

import dev.architectury.at.AccessTransformSet;
import dev.architectury.loom.metadata.ModMetadataFile;
import dev.architectury.loom.metadata.ModMetadataFiles;
import dev.architectury.loom.util.LegacyFmlAccessTransformReader;
import org.objectweb.asm.ClassVisitor;

import net.fabricmc.loom.configuration.mods.dependency.ModDependency;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.tinyremapper.TinyRemapper;

public record AccessTransformerAnalyzeVisitorProvider(AccessTransformSet accessTransformSet) implements TinyRemapper.AnalyzeVisitorProvider {
	static AccessTransformerAnalyzeVisitorProvider createFromMods(List<ModDependency> mods, ModPlatform platform) throws IOException {
		AccessTransformSet accessTransformSet = AccessTransformSet.create();

		for (ModDependency mod : mods) {
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mod.getInputFile(), false)) {
				Set<Path> atPaths = new TreeSet<>();

				if (platform == ModPlatform.FORGE) {
					atPaths.add(fs.getPath(Constants.Forge.ACCESS_TRANSFORMER_PATH));
				}

				if (platform == ModPlatform.NEOFORGE) {
					ModMetadataFile modMetadata = ModMetadataFiles.fromJar(mod.getInputFile());

					if (modMetadata != null) {
						for (String atFile : modMetadata.getAccessTransformers(ModPlatform.NEOFORGE)) {
							atPaths.add(fs.getPath(atFile));
						}
					}
				}

				if (platform.isLegacyForgeLike()) {
					Path manifestPath = fs.getPath("META-INF", "MANIFEST.MF");

					if (Files.exists(manifestPath)) {
						Manifest manifest = new Manifest(new ByteArrayInputStream(Files.readAllBytes(manifestPath)));
						String atList = manifest.getMainAttributes().getValue(Constants.LegacyForge.ACCESS_TRANSFORMERS_MANIFEST_KEY);

						if (atList != null) {
							for (String atFile : atList.split(" ")) {
								atPaths.add(fs.getPath("META-INF", atFile));
							}
						}
					}

					Files.walk(fs.getPath("/"), 1).filter(path -> path.toString().endsWith("at.cfg")).forEach(atPaths::add);
				}

				for (Path atPath : atPaths) {
					if (Files.exists(atPath)) {
						LegacyFmlAccessTransformReader.read(Files.newBufferedReader(atPath, StandardCharsets.UTF_8), accessTransformSet);
					}
				}
			}
		}

		return new AccessTransformerAnalyzeVisitorProvider(accessTransformSet);
	}

	@Override
	public ClassVisitor insertAnalyzeVisitor(int mrjVersion, String className, ClassVisitor next) {
		return new AccessTransformerClassVisitor(Constants.ASM_VERSION, next, accessTransformSet);
	}
}