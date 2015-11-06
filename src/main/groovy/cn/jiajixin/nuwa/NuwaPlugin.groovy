package cn.jiajixin.nuwa

import org.apache.commons.io.IOUtils

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry


class NuwaPlugin implements Plugin<Project> {
    HashSet<String> includePackage
    HashSet<String> excludeClass
    def debugOn
    def log

    @Override
    void apply(Project project) {
        project.extensions.create("nuwa", NuwaExtension, project)

        project.afterEvaluate {

            //get extension setting
            def extension = project.extensions.findByName("nuwa") as NuwaExtension
            includePackage = extension.includePackage
            excludeClass = extension.excludeClass
            debugOn = extension.debugOn

            //create log file
            def nuwa = new File(project.buildDir.absolutePath + File.separator + "outputs" + File.separator + "nuwa")
            log = new File(nuwa, "log-${System.currentTimeMillis()}.txt")


            project.android.applicationVariants.each { variant ->

                if (variant.name.contains("debug") && !debugOn) {

                } else {
                    def processManifest = project.tasks.findByName("process${variant.name.capitalize()}Manifest")
                    def compileSources = project.tasks.findByName("compile${variant.name.capitalize()}Sources")
                    def preDex = project.tasks.findByName("preDex${variant.name.capitalize()}")
                    def dex = project.tasks.findByName("dex${variant.name.capitalize()}")

                    def manifestFile = processManifest.outputs.files.files[0]
                    compileSources.doFirst {
                        def applicationName = findApplication(manifestFile)
                        if (applicationName != null) {
                            excludeClass.add(applicationName)
                        }
                        nuwa.mkdirs()
                        if (!log.exists()) {
                            log.createNewFile()
                        }
                        log.append("\n${variant.name.capitalize()}:\n")
                    }

                    if (preDex != null) {
                        Set<File> inputFiles = preDex.inputs.files.files
                        inputFiles.each { inputFile ->
                            def path = inputFile.absolutePath
                            if (shouldProcessPreDexJar(path)) {
                                preDex.doFirst {
                                    processJar(inputFile)
                                }
                                preDex.doLast {
                                    restoreFile(inputFile)
                                }
                            }
                        }

                        inputFiles = dex.inputs.files.files
                        inputFiles.each { inputFile ->
                            def path = inputFile.absolutePath

                            if (path.endsWith(".class") && !path.contains("/R\$") && !path.endsWith("/R.class") && !path.endsWith("/BuildConfig.class")) {
                                if (isInclued(path)) {
                                    dex.doFirst {
                                        if (!isExclued(path)) {
                                            log.append(path + "\n")
                                            processClass(inputFile)
                                        }
                                    }
                                    dex.doLast {
                                        restoreFile(inputFile)
                                    }
                                }
                            }
                        }


                    } else {
                        Set<File> inputFiles = dex.inputs.files.files
                        inputFiles.each { inputFile ->
                            def path = inputFile.absolutePath
                            if (path.endsWith(".jar")) {
                                dex.doFirst {
                                    processJar(inputFile)
                                }
                                dex.doLast {
                                    restoreFile(inputFile)
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    def boolean isExclued(String path) {
        def isExcluded = false;
        excludeClass.each { exclude ->
            if (path.endsWith(exclude)) {
                isExcluded = true
            }
        }
        return isExcluded
    }

    def boolean isInclued(String path) {
        if (includePackage.size() == 0) {
            return true
        }

        def isInclued = false;
        includePackage.each { include ->
            if (path.contains(include)) {
                isInclued = true
            }
        }
        return isInclued
    }

    def boolean shouldProcessPreDexJar(String path) {
        return path.endsWith("classes.jar") && !path.contains("com.android.support") && !path.contains("/android/m2repository");
    }

    def boolean shouldProcessClassInJar(String entryName) {
        return entryName.endsWith(".class") && !entryName.startsWith("cn/jiajixin/nuwa/") && isInclued(entryName) && !excludeClass.contains(entryName) && !entryName.contains("android/support/")
    }

    def String findApplication(File manifestFile) {
        def manifest = new XmlParser().parse(manifestFile)
        def androidtag = new groovy.xml.Namespace("http://schemas.android.com/apk/res/android", 'android')
        def applicationName = manifest.application[0].attribute(androidtag.name)

        if (applicationName != null) {
            return applicationName.replace(".", "/") + ".class"
        }
        return null;
    }


    byte[] referHackWhenInit(InputStream inputStream) {
        ClassReader cr = new ClassReader(inputStream);
        ClassWriter cw = new ClassWriter(cr, 0);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM4, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {

                MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
                mv = new MethodVisitor(Opcodes.ASM4, mv) {
                    @Override
                    void visitInsn(int opcode) {
                        if ("<init>".equals(name) && opcode == Opcodes.RETURN) {
                            super.visitLdcInsn(Type.getType("Lcn/jiajixin/nuwa/Hack;"));
                        }
                        super.visitInsn(opcode);
                    }
                }
                return mv;
            }

        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    def processClass(File file) {
        def bakClass = new File(file.getParent(), file.name + ".bak")
        def optClass = new File(file.getParent(), file.name + ".opt")

        FileInputStream inputStream = new FileInputStream(file);
        FileOutputStream outputStream = new FileOutputStream(optClass)

        outputStream.write(referHackWhenInit(inputStream))
        inputStream.close()
        outputStream.close()
        file.renameTo(bakClass)
        optClass.renameTo(file)
    }

    def processJar(File file) {
        if (file != null) {
            def bakJar = new File(file.getParent(), file.name + ".bak")
            def optJar = new File(file.getParent(), file.name + ".opt")

            def jarFile = new JarFile(file);
            Enumeration enumeration = jarFile.entries();
            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(optJar));

            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumeration.nextElement();
                String entryName = jarEntry.getName();
                ZipEntry zipEntry = new ZipEntry(entryName);

                InputStream inputStream = jarFile.getInputStream(jarEntry);
                jarOutputStream.putNextEntry(zipEntry);

                if (shouldProcessClassInJar(entryName)) {
                    def bytes = referHackWhenInit(inputStream);
                    jarOutputStream.write(bytes);
                    log.append(entryName + "\n")
                } else {
                    jarOutputStream.write(IOUtils.toByteArray(inputStream));
                }
                jarOutputStream.closeEntry();
            }
            jarOutputStream.close();
            jarFile.close();

            file.renameTo(bakJar)
            optJar.renameTo(file)
        }

    }


    def restoreFile(File file) {
        def bakJar = new File(file.getParent(), file.name + ".bak")
        if (bakJar.exists()) {
            file.delete()
            bakJar.renameTo(file)
        }
    }

}
