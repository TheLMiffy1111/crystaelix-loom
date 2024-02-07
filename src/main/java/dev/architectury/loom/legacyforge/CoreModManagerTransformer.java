package dev.architectury.loom.legacyforge;

import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARRAYLENGTH;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.IFNONNULL;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.IF_ACMPEQ;
import static org.objectweb.asm.Opcodes.IF_ICMPLT;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;

import dev.architectury.loom.forge.ForgeVersion;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Transforms Forge's CoreModManager class to search the classpath for coremods.
 * For motivation, see comments at usage site.
 */
public class CoreModManagerTransformer extends ClassVisitor {
	private static final String FORGE_PACKAGE = "net/minecraftforge/";
	private static final String FORGE_CLASS = FORGE_PACKAGE + "fml/relauncher/CoreModManager";
	public static final String FORGE_FILE = FORGE_CLASS + ".class";
	private static final String CPW_PACKAGE = "cpw/mods/";
	private static final String CPW_CLASS = CPW_PACKAGE + "fml/relauncher/CoreModManager";
	public static final String CPW_FILE = CPW_CLASS + ".class";

	private static final String TARGET_METHOD = "discoverCoreMods";
	private static final String OUR_METHOD_NAME = "loom$injectCoremodsFromClasspath";
	private static final String OUR_METHOD_DESCRIPTOR = "(Lnet/minecraft/launchwrapper/LaunchClassLoader;)V";

	private final ForgeVersion forgeVersion;
	private final String pakkage;
	private final String clazz;

	public CoreModManagerTransformer(ClassVisitor classVisitor, ForgeVersion forgeVersion) {
		super(Opcodes.ASM9, classVisitor);
		this.forgeVersion = forgeVersion;
		pakkage = forgeVersion.cpwFml() ? CPW_PACKAGE : FORGE_PACKAGE;
		clazz = forgeVersion.cpwFml() ? CPW_CLASS : FORGE_CLASS;
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);

		// We inject a call to our method, which will discover and load coremods from the classpath, at the very start of the
		// regular discovery method.
		if (name.equals(TARGET_METHOD)) {
			methodVisitor = new InjectCallAtHead(methodVisitor);
		}

		return methodVisitor;
	}

	@Override
	public void visitEnd() {
		// We add the following method, which will find all coremods on the classpath, and load them.
		//
		//	private static void loom$injectCoremodsFromClasspath(LaunchClassLoader classLoader) throws Exception {
		//		Set<String> coreMods = new HashSet<>();
		//		for (FMLPluginWrapper a : loadPlugins) coreMods.add(a.coreModInstance.getClass().getName());
		//		List<String> tweaks = (List<String>) Launch.blackboard.get("TweakClasses");
		//		loop: for (URL url : classLoader.getURLs()) {
		//			if (!url.getProtocol().startsWith("file")) continue;
		//			File file = new File(url.toURI().getPath());
		//			if (!file.exists()) continue;
		//			Manifest manifest = null;
		//			if (file.isDirectory()) {
		//				File manifestFile = new File(file, "META-INF/MANIFEST.MF");
		//				if (manifestFile.exists()) try (FileInputStream stream = new FileInputStream(manifestFile)) {
		//					manifest = new Manifest(stream);
		//				}
		//			} else if (file.getName().endsWith("jar")) try (JarFile jar = new JarFile(file)) {
		//				manifest = jar.getManifest();
		//				if (manifest != null) {
		//					String ats = manifest.getMainAttributes().getValue("FMLAT");
		//					if (ats != null && !ats.isEmpty()) ModAccessTransformer.addJar(jar, ats);
		//				}
		//			}
		//			if (manifest != null) {
		//				String tweak = manifest.getMainAttributes().getValue("TweakClass");
		//				if (tweak != null) {
		//					if (!tweaks.contains(tweak)) {
		//						Integer sortOrder = Ints.tryParse(Strings.nullToEmpty(manifest.getMainAttributes().getValue("TweakOrder")));
		//						handleCascadingTweak(file, null, tweak, classLoader, sortOrder == null ? 0 : sortOrder);
		//					}
		//					continue;
		//				}
		//				String coreMod = manifest.getMainAttributes().getValue("FMLCorePlugin");
		//				if (coreMod != null) {
		//					for (FMLPluginWrapper plugin : loadPlugins) if (plugin.coreModInstance.getClass().getName().equals(coreMod)) continue loop;
		//					loadCoreMod(classLoader, coreMod, file);
		//				}
		//			}
		//		}
		//	}
		//
		// Converted to ASM via the "ASM Bytecode Viewer" plugin:
		{
			MethodVisitor methodVisitor = super.visitMethod(ACC_PRIVATE | ACC_STATIC, OUR_METHOD_NAME, OUR_METHOD_DESCRIPTOR, null, null);
			methodVisitor.visitCode();
			Label label0 = new Label();
			Label label1 = new Label();
			Label label2 = new Label();
			methodVisitor.visitTryCatchBlock(label0, label1, label2, null);
			Label label3 = new Label();
			Label label4 = new Label();
			methodVisitor.visitTryCatchBlock(label3, label4, label4, null);
			Label label5 = new Label();
			Label label6 = new Label();
			Label label7 = new Label();
			methodVisitor.visitTryCatchBlock(label5, label6, label7, null);
			Label label8 = new Label();
			Label label9 = new Label();
			methodVisitor.visitTryCatchBlock(label8, label9, label9, null);
			Label label10 = new Label();
			methodVisitor.visitLabel(label10);
			methodVisitor.visitLineNumber(751, label10);
			methodVisitor.visitTypeInsn(NEW, "java/util/HashSet");
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false);
			methodVisitor.visitVarInsn(ASTORE, 1);
			Label label11 = new Label();
			methodVisitor.visitLabel(label11);
			methodVisitor.visitLineNumber(752, label11);
			methodVisitor.visitFieldInsn(GETSTATIC, pakkage + "fml/relauncher/CoreModManager", "loadPlugins", "Ljava/util/List;");
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
			methodVisitor.visitVarInsn(ASTORE, 3);
			Label label12 = new Label();
			methodVisitor.visitJumpInsn(GOTO, label12);
			Label label13 = new Label();
			methodVisitor.visitLabel(label13);
			methodVisitor.visitFrame(Opcodes.F_FULL, 4, new Object[] {"net/minecraft/launchwrapper/LaunchClassLoader", "java/util/Set", Opcodes.TOP, "java/util/Iterator"}, 0, new Object[] {});
			methodVisitor.visitVarInsn(ALOAD, 3);
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
			methodVisitor.visitTypeInsn(CHECKCAST, pakkage + "fml/relauncher/CoreModManager$FMLPluginWrapper");
			methodVisitor.visitVarInsn(ASTORE, 2);
			Label label14 = new Label();
			methodVisitor.visitLabel(label14);
			methodVisitor.visitVarInsn(ALOAD, 1);
			methodVisitor.visitVarInsn(ALOAD, 2);
			methodVisitor.visitFieldInsn(GETFIELD, pakkage + "fml/relauncher/CoreModManager$FMLPluginWrapper", "coreModInstance", "L" + pakkage + "fml/relauncher/IFMLLoadingPlugin;");
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true);
			methodVisitor.visitInsn(POP);
			methodVisitor.visitLabel(label12);
			methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 3);
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
			methodVisitor.visitJumpInsn(IFNE, label13);
			Label label15 = new Label();
			methodVisitor.visitLabel(label15);
			methodVisitor.visitLineNumber(753, label15);
			methodVisitor.visitFieldInsn(GETSTATIC, "net/minecraft/launchwrapper/Launch", "blackboard", "Ljava/util/Map;");
			methodVisitor.visitLdcInsn("TweakClasses");
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
			methodVisitor.visitTypeInsn(CHECKCAST, "java/util/List");
			methodVisitor.visitVarInsn(ASTORE, 2);
			Label label16 = new Label();
			methodVisitor.visitLabel(label16);
			methodVisitor.visitLineNumber(754, label16);
			methodVisitor.visitVarInsn(ALOAD, 0);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/launchwrapper/LaunchClassLoader", "getURLs", "()[Ljava/net/URL;", false);
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitVarInsn(ASTORE, 6);
			methodVisitor.visitInsn(ARRAYLENGTH);
			methodVisitor.visitVarInsn(ISTORE, 5);
			methodVisitor.visitInsn(ICONST_0);
			methodVisitor.visitVarInsn(ISTORE, 4);
			Label label17 = new Label();
			methodVisitor.visitJumpInsn(GOTO, label17);
			Label label18 = new Label();
			methodVisitor.visitLabel(label18);
			methodVisitor.visitFrame(Opcodes.F_FULL, 7, new Object[] {"net/minecraft/launchwrapper/LaunchClassLoader", "java/util/Set", "java/util/List", Opcodes.TOP, Opcodes.INTEGER, Opcodes.INTEGER, "[Ljava/net/URL;"}, 0, new Object[] {});
			methodVisitor.visitVarInsn(ALOAD, 6);
			methodVisitor.visitVarInsn(ILOAD, 4);
			methodVisitor.visitInsn(AALOAD);
			methodVisitor.visitVarInsn(ASTORE, 3);
			Label label19 = new Label();
			methodVisitor.visitLabel(label19);
			methodVisitor.visitLineNumber(755, label19);
			methodVisitor.visitVarInsn(ALOAD, 3);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/net/URL", "getProtocol", "()Ljava/lang/String;", false);
			methodVisitor.visitLdcInsn("file");
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false);
			Label label20 = new Label();
			methodVisitor.visitJumpInsn(IFNE, label20);
			Label label21 = new Label();
			methodVisitor.visitJumpInsn(GOTO, label21);
			methodVisitor.visitLabel(label20);
			methodVisitor.visitLineNumber(756, label20);
			methodVisitor.visitFrame(Opcodes.F_FULL, 7, new Object[] {"net/minecraft/launchwrapper/LaunchClassLoader", "java/util/Set", "java/util/List", "java/net/URL", Opcodes.INTEGER, Opcodes.INTEGER, "[Ljava/net/URL;"}, 0, new Object[] {});
			methodVisitor.visitTypeInsn(NEW, "java/io/File");
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitVarInsn(ALOAD, 3);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/net/URL", "toURI", "()Ljava/net/URI;", false);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/net/URI", "getPath", "()Ljava/lang/String;", false);
			methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false);
			methodVisitor.visitVarInsn(ASTORE, 7);
			Label label22 = new Label();
			methodVisitor.visitLabel(label22);
			methodVisitor.visitLineNumber(757, label22);
			methodVisitor.visitVarInsn(ALOAD, 7);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/io/File", "exists", "()Z", false);
			Label label23 = new Label();
			methodVisitor.visitJumpInsn(IFNE, label23);
			methodVisitor.visitJumpInsn(GOTO, label21);
			methodVisitor.visitLabel(label23);
			methodVisitor.visitLineNumber(758, label23);
			methodVisitor.visitFrame(Opcodes.F_APPEND, 1, new Object[] {"java/io/File"}, 0, null);
			methodVisitor.visitInsn(ACONST_NULL);
			methodVisitor.visitVarInsn(ASTORE, 8);
			Label label24 = new Label();
			methodVisitor.visitLabel(label24);
			methodVisitor.visitLineNumber(759, label24);
			methodVisitor.visitVarInsn(ALOAD, 7);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/io/File", "isDirectory", "()Z", false);
			Label label25 = new Label();
			methodVisitor.visitJumpInsn(IFEQ, label25);
			Label label26 = new Label();
			methodVisitor.visitLabel(label26);
			methodVisitor.visitLineNumber(760, label26);
			methodVisitor.visitTypeInsn(NEW, "java/io/File");
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitVarInsn(ALOAD, 7);
			methodVisitor.visitLdcInsn("META-INF/MANIFEST.MF");
			methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/io/File;Ljava/lang/String;)V", false);
			methodVisitor.visitVarInsn(ASTORE, 9);
			Label label27 = new Label();
			methodVisitor.visitLabel(label27);
			methodVisitor.visitLineNumber(761, label27);
			methodVisitor.visitVarInsn(ALOAD, 9);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/io/File", "exists", "()Z", false);
			Label label28 = new Label();
			methodVisitor.visitJumpInsn(IFEQ, label28);
			methodVisitor.visitInsn(ACONST_NULL);
			methodVisitor.visitVarInsn(ASTORE, 10);
			methodVisitor.visitInsn(ACONST_NULL);
			methodVisitor.visitVarInsn(ASTORE, 11);
			methodVisitor.visitLabel(label3);
			methodVisitor.visitTypeInsn(NEW, "java/io/FileInputStream");
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitVarInsn(ALOAD, 9);
			methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/io/FileInputStream", "<init>", "(Ljava/io/File;)V", false);
			methodVisitor.visitVarInsn(ASTORE, 12);
			methodVisitor.visitLabel(label0);
			methodVisitor.visitLineNumber(762, label0);
			methodVisitor.visitTypeInsn(NEW, "java/util/jar/Manifest");
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitVarInsn(ALOAD, 12);
			methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/jar/Manifest", "<init>", "(Ljava/io/InputStream;)V", false);
			methodVisitor.visitVarInsn(ASTORE, 8);
			methodVisitor.visitLabel(label1);
			methodVisitor.visitLineNumber(763, label1);
			methodVisitor.visitVarInsn(ALOAD, 12);
			methodVisitor.visitJumpInsn(IFNULL, label28);
			methodVisitor.visitVarInsn(ALOAD, 12);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/io/FileInputStream", "close", "()V", false);
			methodVisitor.visitJumpInsn(GOTO, label28);
			methodVisitor.visitLabel(label2);
			methodVisitor.visitFrame(Opcodes.F_FULL, 13, new Object[] {"net/minecraft/launchwrapper/LaunchClassLoader", "java/util/Set", "java/util/List", "java/net/URL", Opcodes.INTEGER, Opcodes.INTEGER, "[Ljava/net/URL;", "java/io/File", "java/util/jar/Manifest", "java/io/File", "java/lang/Throwable", "java/lang/Throwable", "java/io/FileInputStream"}, 1, new Object[] {"java/lang/Throwable"});
			methodVisitor.visitVarInsn(ASTORE, 10);
			methodVisitor.visitVarInsn(ALOAD, 12);
			Label label29 = new Label();
			methodVisitor.visitJumpInsn(IFNULL, label29);
			methodVisitor.visitVarInsn(ALOAD, 12);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/io/FileInputStream", "close", "()V", false);
			methodVisitor.visitLabel(label29);
			methodVisitor.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitInsn(ATHROW);
			methodVisitor.visitLabel(label4);
			methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
			methodVisitor.visitVarInsn(ASTORE, 11);
			methodVisitor.visitVarInsn(ALOAD, 10);
			Label label30 = new Label();
			methodVisitor.visitJumpInsn(IFNONNULL, label30);
			methodVisitor.visitVarInsn(ALOAD, 11);
			methodVisitor.visitVarInsn(ASTORE, 10);
			Label label31 = new Label();
			methodVisitor.visitJumpInsn(GOTO, label31);
			methodVisitor.visitLabel(label30);
			methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitVarInsn(ALOAD, 11);
			methodVisitor.visitJumpInsn(IF_ACMPEQ, label31);
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitVarInsn(ALOAD, 11);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "addSuppressed", "(Ljava/lang/Throwable;)V", false);
			methodVisitor.visitLabel(label31);
			methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitInsn(ATHROW);
			methodVisitor.visitLabel(label25);
			methodVisitor.visitLineNumber(764, label25);
			methodVisitor.visitFrame(Opcodes.F_CHOP, 3, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 7);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/io/File", "getName", "()Ljava/lang/String;", false);
			methodVisitor.visitLdcInsn("jar");
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "endsWith", "(Ljava/lang/String;)Z", false);
			methodVisitor.visitJumpInsn(IFEQ, label28);
			methodVisitor.visitInsn(ACONST_NULL);
			methodVisitor.visitVarInsn(ASTORE, 9);
			methodVisitor.visitInsn(ACONST_NULL);
			methodVisitor.visitVarInsn(ASTORE, 10);
			methodVisitor.visitLabel(label8);
			methodVisitor.visitTypeInsn(NEW, "java/util/jar/JarFile");
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitVarInsn(ALOAD, 7);
			methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/jar/JarFile", "<init>", "(Ljava/io/File;)V", false);
			methodVisitor.visitVarInsn(ASTORE, 11);
			methodVisitor.visitLabel(label5);
			methodVisitor.visitLineNumber(765, label5);
			methodVisitor.visitVarInsn(ALOAD, 11);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/JarFile", "getManifest", "()Ljava/util/jar/Manifest;", false);
			methodVisitor.visitVarInsn(ASTORE, 8);
			Label label32 = new Label();
			methodVisitor.visitLabel(label32);
			methodVisitor.visitLineNumber(766, label32);
			methodVisitor.visitVarInsn(ALOAD, 8);
			methodVisitor.visitJumpInsn(IFNULL, label6);
			Label label33 = new Label();
			methodVisitor.visitLabel(label33);
			methodVisitor.visitLineNumber(767, label33);
			methodVisitor.visitVarInsn(ALOAD, 8);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/Manifest", "getMainAttributes", "()Ljava/util/jar/Attributes;", false);
			methodVisitor.visitLdcInsn("FMLAT");
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/Attributes", "getValue", "(Ljava/lang/String;)Ljava/lang/String;", false);
			methodVisitor.visitVarInsn(ASTORE, 12);
			Label label34 = new Label();
			methodVisitor.visitLabel(label34);
			methodVisitor.visitLineNumber(768, label34);
			methodVisitor.visitVarInsn(ALOAD, 12);
			methodVisitor.visitJumpInsn(IFNULL, label6);
			methodVisitor.visitVarInsn(ALOAD, 12);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
			methodVisitor.visitJumpInsn(IFNE, label6);
			methodVisitor.visitVarInsn(ALOAD, 11);

			if (!forgeVersion.versionModDirs()) {
				methodVisitor.visitVarInsn(ALOAD, 12);
				methodVisitor.visitMethodInsn(INVOKESTATIC, pakkage + "fml/common/asm/transformers/ModAccessTransformer", "addJar", "(Ljava/util/jar/JarFile;Ljava/lang/String;)V", false);
			} else {
				methodVisitor.visitMethodInsn(INVOKESTATIC, pakkage + "fml/common/asm/transformers/ModAccessTransformer", "addJar", "(Ljava/util/jar/JarFile;)V", false);
			}

			methodVisitor.visitLabel(label6);
			methodVisitor.visitLineNumber(770, label6);
			methodVisitor.visitFrame(Opcodes.F_APPEND, 3, new Object[] {"java/lang/Throwable", "java/lang/Throwable", "java/util/jar/JarFile"}, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 11);
			methodVisitor.visitJumpInsn(IFNULL, label28);
			methodVisitor.visitVarInsn(ALOAD, 11);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/JarFile", "close", "()V", false);
			methodVisitor.visitJumpInsn(GOTO, label28);
			methodVisitor.visitLabel(label7);
			methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
			methodVisitor.visitVarInsn(ASTORE, 9);
			methodVisitor.visitVarInsn(ALOAD, 11);
			Label label35 = new Label();
			methodVisitor.visitJumpInsn(IFNULL, label35);
			methodVisitor.visitVarInsn(ALOAD, 11);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/JarFile", "close", "()V", false);
			methodVisitor.visitLabel(label35);
			methodVisitor.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 9);
			methodVisitor.visitInsn(ATHROW);
			methodVisitor.visitLabel(label9);
			methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
			methodVisitor.visitVarInsn(ASTORE, 10);
			methodVisitor.visitVarInsn(ALOAD, 9);
			Label label36 = new Label();
			methodVisitor.visitJumpInsn(IFNONNULL, label36);
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitVarInsn(ASTORE, 9);
			Label label37 = new Label();
			methodVisitor.visitJumpInsn(GOTO, label37);
			methodVisitor.visitLabel(label36);
			methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 9);
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitJumpInsn(IF_ACMPEQ, label37);
			methodVisitor.visitVarInsn(ALOAD, 9);
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "addSuppressed", "(Ljava/lang/Throwable;)V", false);
			methodVisitor.visitLabel(label37);
			methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 9);
			methodVisitor.visitInsn(ATHROW);
			methodVisitor.visitLabel(label28);
			methodVisitor.visitLineNumber(771, label28);
			methodVisitor.visitFrame(Opcodes.F_CHOP, 2, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 8);
			methodVisitor.visitJumpInsn(IFNULL, label21);
			Label label38 = new Label();
			methodVisitor.visitLabel(label38);
			methodVisitor.visitLineNumber(772, label38);
			methodVisitor.visitVarInsn(ALOAD, 8);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/Manifest", "getMainAttributes", "()Ljava/util/jar/Attributes;", false);
			methodVisitor.visitLdcInsn("TweakClass");
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/Attributes", "getValue", "(Ljava/lang/String;)Ljava/lang/String;", false);
			methodVisitor.visitVarInsn(ASTORE, 9);
			Label label39 = new Label();
			methodVisitor.visitLabel(label39);
			methodVisitor.visitLineNumber(773, label39);
			methodVisitor.visitVarInsn(ALOAD, 9);
			Label label40 = new Label();
			methodVisitor.visitJumpInsn(IFNULL, label40);
			Label label41 = new Label();
			methodVisitor.visitLabel(label41);
			methodVisitor.visitLineNumber(774, label41);
			methodVisitor.visitVarInsn(ALOAD, 2);
			methodVisitor.visitVarInsn(ALOAD, 9);
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "contains", "(Ljava/lang/Object;)Z", true);
			methodVisitor.visitJumpInsn(IFNE, label21);
			Label label42 = new Label();
			methodVisitor.visitLabel(label42);
			methodVisitor.visitLineNumber(775, label42);
			methodVisitor.visitVarInsn(ALOAD, 8);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/Manifest", "getMainAttributes", "()Ljava/util/jar/Attributes;", false);
			methodVisitor.visitLdcInsn("TweakOrder");
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/Attributes", "getValue", "(Ljava/lang/String;)Ljava/lang/String;", false);
			methodVisitor.visitMethodInsn(INVOKESTATIC, "com/google/common/base/Strings", "nullToEmpty", "(Ljava/lang/String;)Ljava/lang/String;", false);
			methodVisitor.visitMethodInsn(INVOKESTATIC, "com/google/common/primitives/Ints", "tryParse", "(Ljava/lang/String;)Ljava/lang/Integer;", false);
			methodVisitor.visitVarInsn(ASTORE, 10);
			Label label43 = new Label();
			methodVisitor.visitLabel(label43);
			methodVisitor.visitLineNumber(776, label43);
			methodVisitor.visitVarInsn(ALOAD, 2);
			methodVisitor.visitVarInsn(ALOAD, 9);
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
			methodVisitor.visitInsn(POP);
			Label label44 = new Label();
			methodVisitor.visitLabel(label44);
			methodVisitor.visitLineNumber(777, label44);
			methodVisitor.visitFieldInsn(GETSTATIC, pakkage + "fml/relauncher/CoreModManager", "tweakSorting", "Ljava/util/Map;");
			methodVisitor.visitVarInsn(ALOAD, 9);
			methodVisitor.visitVarInsn(ALOAD, 10);
			Label label45 = new Label();
			methodVisitor.visitJumpInsn(IFNONNULL, label45);
			methodVisitor.visitInsn(ICONST_0);
			Label label46 = new Label();
			methodVisitor.visitJumpInsn(GOTO, label46);
			methodVisitor.visitLabel(label45);
			methodVisitor.visitFrame(Opcodes.F_FULL, 11, new Object[] {"net/minecraft/launchwrapper/LaunchClassLoader", "java/util/Set", "java/util/List", "java/net/URL", Opcodes.INTEGER, Opcodes.INTEGER, "[Ljava/net/URL;", "java/io/File", "java/util/jar/Manifest", "java/lang/String", "java/lang/Integer"}, 2, new Object[] {"java/util/Map", "java/lang/String"});
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
			methodVisitor.visitLabel(label46);
			methodVisitor.visitFrame(Opcodes.F_FULL, 11, new Object[] {"net/minecraft/launchwrapper/LaunchClassLoader", "java/util/Set", "java/util/List", "java/net/URL", Opcodes.INTEGER, Opcodes.INTEGER, "[Ljava/net/URL;", "java/io/File", "java/util/jar/Manifest", "java/lang/String", "java/lang/Integer"}, 3, new Object[] {"java/util/Map", "java/lang/String", Opcodes.INTEGER});
			methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
			methodVisitor.visitInsn(POP);
			Label label47 = new Label();
			methodVisitor.visitLabel(label47);
			methodVisitor.visitLineNumber(779, label47);
			methodVisitor.visitJumpInsn(GOTO, label21);
			methodVisitor.visitLabel(label40);
			methodVisitor.visitLineNumber(781, label40);
			methodVisitor.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 8);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/Manifest", "getMainAttributes", "()Ljava/util/jar/Attributes;", false);
			methodVisitor.visitLdcInsn("FMLCorePlugin");
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/Attributes", "getValue", "(Ljava/lang/String;)Ljava/lang/String;", false);
			methodVisitor.visitVarInsn(ASTORE, 10);
			Label label48 = new Label();
			methodVisitor.visitLabel(label48);
			methodVisitor.visitLineNumber(782, label48);
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitJumpInsn(IFNULL, label21);
			Label label49 = new Label();
			methodVisitor.visitLabel(label49);
			methodVisitor.visitLineNumber(783, label49);
			methodVisitor.visitFieldInsn(GETSTATIC, pakkage + "fml/relauncher/CoreModManager", "loadPlugins", "Ljava/util/List;");
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
			methodVisitor.visitVarInsn(ASTORE, 12);
			Label label50 = new Label();
			methodVisitor.visitJumpInsn(GOTO, label50);
			Label label51 = new Label();
			methodVisitor.visitLabel(label51);
			methodVisitor.visitFrame(Opcodes.F_FULL, 13, new Object[] {"net/minecraft/launchwrapper/LaunchClassLoader", "java/util/Set", "java/util/List", "java/net/URL", Opcodes.INTEGER, Opcodes.INTEGER, "[Ljava/net/URL;", "java/io/File", "java/util/jar/Manifest", "java/lang/String", "java/lang/String", Opcodes.TOP, "java/util/Iterator"}, 0, new Object[] {});
			methodVisitor.visitVarInsn(ALOAD, 12);
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
			methodVisitor.visitTypeInsn(CHECKCAST, pakkage + "fml/relauncher/CoreModManager$FMLPluginWrapper");
			methodVisitor.visitVarInsn(ASTORE, 11);
			Label label52 = new Label();
			methodVisitor.visitLabel(label52);
			methodVisitor.visitVarInsn(ALOAD, 11);
			methodVisitor.visitFieldInsn(GETFIELD, pakkage + "fml/relauncher/CoreModManager$FMLPluginWrapper", "coreModInstance", "L" + pakkage + "fml/relauncher/IFMLLoadingPlugin;");
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
			methodVisitor.visitJumpInsn(IFEQ, label50);
			methodVisitor.visitJumpInsn(GOTO, label21);
			methodVisitor.visitLabel(label50);
			methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 12);
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
			methodVisitor.visitJumpInsn(IFNE, label51);
			Label label53 = new Label();
			methodVisitor.visitLabel(label53);
			methodVisitor.visitLineNumber(784, label53);
			methodVisitor.visitVarInsn(ALOAD, 0);
			methodVisitor.visitVarInsn(ALOAD, 10);
			methodVisitor.visitVarInsn(ALOAD, 7);
			methodVisitor.visitMethodInsn(INVOKESTATIC, pakkage + "fml/relauncher/CoreModManager", "loadCoreMod", "(Lnet/minecraft/launchwrapper/LaunchClassLoader;Ljava/lang/String;Ljava/io/File;)L" + pakkage + "fml/relauncher/CoreModManager$FMLPluginWrapper;", false);
			methodVisitor.visitInsn(POP);
			methodVisitor.visitLabel(label21);
			methodVisitor.visitLineNumber(754, label21);
			methodVisitor.visitFrame(Opcodes.F_FULL, 7, new Object[] {"net/minecraft/launchwrapper/LaunchClassLoader", "java/util/Set", "java/util/List", Opcodes.TOP, Opcodes.INTEGER, Opcodes.INTEGER, "[Ljava/net/URL;"}, 0, new Object[] {});
			methodVisitor.visitIincInsn(4, 1);
			methodVisitor.visitLabel(label17);
			methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			methodVisitor.visitVarInsn(ILOAD, 4);
			methodVisitor.visitVarInsn(ILOAD, 5);
			methodVisitor.visitJumpInsn(IF_ICMPLT, label18);
			Label label54 = new Label();
			methodVisitor.visitLabel(label54);
			methodVisitor.visitLineNumber(788, label54);
			methodVisitor.visitInsn(RETURN);
			Label label55 = new Label();
			methodVisitor.visitLabel(label55);
			methodVisitor.visitLocalVariable("classLoader", "Lnet/minecraft/launchwrapper/LaunchClassLoader;", null, label10, label55, 0);
			methodVisitor.visitLocalVariable("coreMods", "Ljava/util/Set;", "Ljava/util/Set<Ljava/lang/String;>;", label11, label55, 1);
			methodVisitor.visitLocalVariable("a", "L" + pakkage + "fml/relauncher/CoreModManager$FMLPluginWrapper;", null, label14, label12, 2);
			methodVisitor.visitLocalVariable("tweaks", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;", label16, label55, 2);
			methodVisitor.visitLocalVariable("url", "Ljava/net/URL;", null, label19, label21, 3);
			methodVisitor.visitLocalVariable("file", "Ljava/io/File;", null, label22, label21, 7);
			methodVisitor.visitLocalVariable("manifest", "Ljava/util/jar/Manifest;", null, label24, label21, 8);
			methodVisitor.visitLocalVariable("manifestFile", "Ljava/io/File;", null, label27, label25, 9);
			methodVisitor.visitLocalVariable("stream", "Ljava/io/FileInputStream;", null, label0, label29, 12);
			methodVisitor.visitLocalVariable("jar", "Ljava/util/jar/JarFile;", null, label5, label35, 11);
			methodVisitor.visitLocalVariable("ats", "Ljava/lang/String;", null, label34, label6, 12);
			methodVisitor.visitLocalVariable("tweak", "Ljava/lang/String;", null, label39, label21, 9);
			methodVisitor.visitLocalVariable("sortOrder", "Ljava/lang/Integer;", null, label43, label47, 10);
			methodVisitor.visitLocalVariable("coreMod", "Ljava/lang/String;", null, label48, label21, 10);
			methodVisitor.visitLocalVariable("plugin", "L" + pakkage + "fml/relauncher/CoreModManager$FMLPluginWrapper;", null, label52, label50, 11);
			methodVisitor.visitMaxs(4, 13);
			methodVisitor.visitEnd();
		}

		super.visitEnd();
	}

	private class InjectCallAtHead extends MethodVisitor {
		private InjectCallAtHead(MethodVisitor methodVisitor) {
			super(Opcodes.ASM9, methodVisitor);
		}

		@Override
		public void visitCode() {
			super.visitCode();

			super.visitVarInsn(Opcodes.ALOAD, 1);
			super.visitMethodInsn(Opcodes.INVOKESTATIC, clazz, OUR_METHOD_NAME, OUR_METHOD_DESCRIPTOR, false);
		}
	}
}