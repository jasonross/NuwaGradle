package cn.jiajixin.nuwa

import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.InvalidUserDataException
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
    def patchList = []
    private static final String HASH_SEPARATOR = ":"

    @Override
    void apply(Project project) {
        project.extensions.create("nuwa", NuwaExtension, project)

        project.afterEvaluate {

            //get extension setting
            def extension = project.extensions.findByName("nuwa") as NuwaExtension
            includePackage = extension.includePackage
            excludeClass = extension.excludeClass
            debugOn = extension.debugOn

            //if old nuwa dir valid
            def oldNuwaDir
            if (project.hasProperty('NuwaDir')) {
                oldNuwaDir = new File(project.NuwaDir)
                if (!oldNuwaDir.exists()) {
                    throw new InvalidUserDataException("${project.NuwaDir} does not exist")
                }
                if (!oldNuwaDir.isDirectory()) {
                    throw new InvalidUserDataException("${project.NuwaDir} is not directory")
                }
            }

            //generate patches
            def generatePatches = "generatePatches"
            project.task(generatePatches) << {
                def sdkDir = System.getenv("ANDROID_HOME")
                if (sdkDir) {
                    def cmdExt = Os.isFamily(Os.FAMILY_WINDOWS) ? '.bat' : ''
                    patchList.each { patchDir ->
                        project.exec {
                            commandLine "${sdkDir}/build-tools/${project.android.buildToolsVersion}/dx${cmdExt}",
                                    '--dex',
                                    "--output=${new File(patchDir.getParent(), "patch.jar")}",
                                    "${patchDir}"
                        }
                    }
                } else {
                    throw new InvalidUserDataException('$ANDROID_HOME is not defined')
                }
            }
            project.tasks[generatePatches].dependsOn project.tasks["assemble"]


            project.android.applicationVariants.each { variant ->

                if (variant.name.contains("debug") && !debugOn) {

                } else {
                    def processManifestTask = project.tasks.findByName("process${variant.name.capitalize()}Manifest")
                    def compileJavaTask = project.tasks.findByName("compile${variant.name.capitalize()}Java")
                    def preDexTask = project.tasks.findByName("preDex${variant.name.capitalize()}")
                    def dexTask = project.tasks.findByName("dex${variant.name.capitalize()}")
                    def proguardTask = project.tasks.findByName("proguard${variant.name.capitalize()}")
                    def patchTask = "generate${variant.name.capitalize()}Patch"

                    def manifestFile = processManifestTask.outputs.files.files[0]

                    // proguard -applymapping
                    if (oldNuwaDir) {
                        if (proguardTask) {
                            def oldMapFile = new File("${oldNuwaDir}/${variant.dirName}/mapping.txt");
                            if (oldMapFile.exists()) {
                                proguardTask.applymapping(oldMapFile)
                            } else {
                                throw new InvalidUserDataException("$oldMapFile does not exist")
                            }
                        }
                    }

                    //parse hash.txt to map
                    def hashMap
                    if (oldNuwaDir) {
                        def oldHashFile = new File("${oldNuwaDir}/${variant.dirName}/hash.txt");
                        if (oldHashFile.exists()) {
                            hashMap = [:]
                            oldHashFile.eachLine {
                                def list = it.split(HASH_SEPARATOR)
                                if (list.size() == 2) {
                                    hashMap.put(list[0], list[1])
                                }
                            }
                        } else {
                            throw new InvalidUserDataException("$oldHashFile does not exist")
                        }
                    }

                    def nuwaDir
                    def patchDir

                    compileJavaTask.doFirst {
                        // exclude application
                        def applicationName = findApplication(manifestFile)
                        if (applicationName != null) {
                            excludeClass.add(applicationName)
                        }

                        // create hash file
                        nuwaDir = new File("${project.buildDir}/outputs/nuwa")
                        def outputDir = new File("${nuwaDir}/${variant.dirName}")
                        outputDir.mkdirs()
                        def hashFile = new File(outputDir, "hash.txt")
                        if (!hashFile.exists()) {
                            hashFile.createNewFile()
                        }

                        //mkdir patch
                        if (oldNuwaDir) {
                            patchDir = new File("${nuwaDir}/${variant.dirName}/patch")
                            patchDir.mkdirs()
                            patchList.add(patchDir)
                        }


                        if (preDexTask) {
                            //predex jar
                            Set<File> inputFiles = preDexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (shouldProcessPreDexJar(path)) {
                                    preDexTask.doFirst {
                                        processJar(hashFile, inputFile, patchDir, hashMap)
                                    }
                                    preDexTask.doLast {
                                        restoreFile(inputFile)
                                    }
                                }
                            }

                            //dex classes
                            inputFiles = dexTask.inputs.files.files
                            println inputFiles
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                println path
                                if (path.endsWith(".class") && !path.contains("/R\$") && !path.endsWith("/R.class") && !path.endsWith("/BuildConfig.class")) {
                                    if (isIncluded(path)) {
                                        dexTask.doFirst {
                                            if (!isExcluded(path)) {
                                                def bytes = processClass(inputFile)

                                                path = path.split("${variant.dirName}/")[1]
                                                def hash = DigestUtils.shaHex(bytes)
                                                hashFile.append(path + HASH_SEPARATOR + hash + "\n")

                                                if (hashMap) {
                                                    copyToNuwaPatch(path, hash, hashMap, inputFile.bytes, touchFile(patchDir, path))
                                                }
                                            }
                                        }
                                        dexTask.doLast {
                                            restoreFile(inputFile)
                                        }
                                    }
                                }
                            }
                        } else {
                            //dex jar
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".jar")) {
                                    dexTask.doFirst {
                                        processJar(hashFile, inputFile, patchDir, hashMap)
                                    }
                                    dexTask.doLast {
                                        restoreFile(inputFile)
                                    }
                                }
                            }
                        }


                    }

                    //copy mapping.txt to nuva dir
                    dexTask.doFirst {
                        if (proguardTask) {
                            def mapFile = new File("${project.buildDir}/outputs/mapping/${variant.dirName}/mapping.txt")
                            def newMapFile = new File("${nuwaDir}/${variant.dirName}/mapping.txt");
                            FileUtils.copyFile(mapFile, newMapFile)
                        }
                    }

                    //generate patch
                    project.task(patchTask) << {
                        if (patchDir) {
                            def sdkDir = System.getenv("ANDROID_HOME")
                            if (sdkDir) {
                                def cmdExt = Os.isFamily(Os.FAMILY_WINDOWS) ? '.bat' : ''
                                def stdout = new ByteArrayOutputStream()
                                project.exec {
                                    commandLine "${sdkDir}/build-tools/${project.android.buildToolsVersion}/dx${cmdExt}",
                                            '--dex',
                                            "--output=${new File(patchDir.getParent(), "patch.jar")}",
                                            "${patchDir}"
                                    standardOutput = stdout
                                }
                            } else {
                                throw new InvalidUserDataException('$ANDROID_HOME is not defined')
                            }
                        }
                    }
                    project.tasks[patchTask].dependsOn dexTask
                }
            }
        }
    }

    //is file excluded
    def boolean isExcluded(String path) {
        def isExcluded = false;
        excludeClass.each { exclude ->
            if (path.endsWith(exclude)) {
                isExcluded = true
            }
        }
        return isExcluded
    }

    //is file included
    def boolean isIncluded(String path) {
        if (includePackage.size() == 0) {
            return true
        }

        def isIncluded = false;
        includePackage.each { include ->
            if (path.contains(include)) {
                isIncluded = true
            }
        }
        return isIncluded
    }

    //should process predex jar
    def boolean shouldProcessPreDexJar(String path) {
        return path.endsWith("classes.jar") && !path.contains("com.android.support") && !path.contains("/android/m2repository");
    }

    //should process class in jar
    def boolean shouldProcessClassInJar(String entryName) {
        return entryName.endsWith(".class") && !entryName.startsWith("cn/jiajixin/nuwa/") && isIncluded(entryName) && !excludeClass.contains(entryName) && !entryName.contains("android/support/")
    }

    //find application name
    def String findApplication(File manifestFile) {
        def manifest = new XmlParser().parse(manifestFile)
        def androidtag = new groovy.xml.Namespace("http://schemas.android.com/apk/res/android", 'android')
        def applicationName = manifest.application[0].attribute(androidtag.name)

        if (applicationName != null) {
            return applicationName.replace(".", "/") + ".class"
        }
        return null;
    }

    //refer hack class when object init
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

    //process class
    def byte[] processClass(File file) {
        def bakClass = new File(file.getParent(), file.name + ".bak")
        def optClass = new File(file.getParent(), file.name + ".opt")

        FileInputStream inputStream = new FileInputStream(file);
        FileOutputStream outputStream = new FileOutputStream(optClass)

        def bytes = referHackWhenInit(inputStream);
        outputStream.write(bytes)
        inputStream.close()
        outputStream.close()
        file.renameTo(bakClass)
        optClass.renameTo(file)
        return bytes
    }

    //process jar
    def processJar(File hashFile, File jarFile, File patchDir, Map map) {
        if (jarFile) {
            def bakJar = new File(jarFile.getParent(), jarFile.name + ".bak")
            def optJar = new File(jarFile.getParent(), jarFile.name + ".opt")

            def file = new JarFile(jarFile);
            Enumeration enumeration = file.entries();
            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(optJar));

            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumeration.nextElement();
                String entryName = jarEntry.getName();
                ZipEntry zipEntry = new ZipEntry(entryName);

                InputStream inputStream = file.getInputStream(jarEntry);
                jarOutputStream.putNextEntry(zipEntry);

                if (shouldProcessClassInJar(entryName)) {
                    def bytes = referHackWhenInit(inputStream);
                    jarOutputStream.write(bytes);

                    def hash = DigestUtils.shaHex(bytes)
                    hashFile.append(entryName + HASH_SEPARATOR + hash + "\n")

                    if (map) {
                        copyToNuwaPatch(entryName, hash, map, bytes, touchFile(patchDir, entryName))
                    }
                } else {
                    jarOutputStream.write(IOUtils.toByteArray(inputStream));
                }
                jarOutputStream.closeEntry();
            }
            jarOutputStream.close();
            file.close();

            jarFile.renameTo(bakJar)
            optJar.renameTo(jarFile)
        }

    }

    //restore file
    def restoreFile(File file) {
        def bakJar = new File(file.getParent(), file.name + ".bak")
        if (bakJar.exists()) {
            file.delete()
            bakJar.renameTo(file)
        }
    }

    //copy class to nuwa path
    def copyToNuwaPatch(String name, String hash, Map map, byte[] bytes, File file) {
        if (map) {
            def value = map.get(name)

            def write = false
            if (value) {
                if (!value.equals(hash)) {
                    write = true
                }
            } else {
                write = true
            }
            if (write) {
                if (!file.exists()) {
                    file.createNewFile()
                }
                FileUtils.writeByteArrayToFile(file, bytes)
            }
        }
    }

    def File touchFile(File dir, String path) {
        def file = new File("${dir}/${path}")
        file.getParentFile().mkdirs()
        return file
    }

}
