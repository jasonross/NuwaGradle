package cn.jiajixin.nuwa

import cn.jiajixin.nuwa.util.*
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project

class NuwaPlugin implements Plugin<Project> {
    HashSet<String> includePackage
    HashSet<String> excludeClass
    def debugOn
    def patchList = []
    def beforeDexTasks = []
    private static final String NUWA_DIR = "NuwaDir"
    private static final String NUWA_PATCHES = "nuwaPatches"

    private static final String MAPPING_TXT = "mapping.txt"
    private static final String HASH_TXT = "hash.txt"

    private static final String DEBUG = "debug"


    @Override
    void apply(Project project) {

        project.extensions.create("nuwa", NuwaExtension, project)



        project.afterEvaluate {
            def extension = project.extensions.findByName("nuwa") as NuwaExtension
            includePackage = extension.includePackage
            excludeClass = extension.excludeClass
            debugOn = extension.debugOn

            project.android.applicationVariants.each { variant ->

                if (!variant.name.contains(DEBUG) || (variant.name.contains(DEBUG) && debugOn)) {

                    Map hashMap
                    File nuwaDir
                    File patchDir

                    def preDexTask = project.tasks.findByName("preDex${variant.name.capitalize()}")
                    def dexTask = project.tasks.findByName("dex${variant.name.capitalize()}")
                    def proguardTask = project.tasks.findByName("proguard${variant.name.capitalize()}")

                    def processManifestTask = project.tasks.findByName("process${variant.name.capitalize()}Manifest")
                    def manifestFile = processManifestTask.outputs.files.files[0]

                    def oldNuwaDir = NuwaFileUtils.getFileFromProperty(project, NUWA_DIR)
                    if (oldNuwaDir) {
                        def mappingFile = NuwaFileUtils.getVariantFile(oldNuwaDir, variant, MAPPING_TXT)
                        NuwaAndroidUtils.applymapping(proguardTask, mappingFile)
                    }
                    if (oldNuwaDir) {
                        def hashFile = NuwaFileUtils.getVariantFile(oldNuwaDir, variant, HASH_TXT)
                        hashMap = NuwaMapUtils.parseMap(hashFile)
                    }

                    def dirName = variant.dirName
                    nuwaDir = new File("${project.buildDir}/outputs/nuwa")
                    def outputDir = new File("${nuwaDir}/${dirName}")
                    def hashFile = new File(outputDir, "hash.txt")

                    Closure nuwaPrepareClosure = {
                        def applicationName = NuwaAndroidUtils.getApplication(manifestFile)
                        if (applicationName != null) {
                            excludeClass.add(applicationName)
                        }

                        outputDir.mkdirs()
                        if (!hashFile.exists()) {
                            hashFile.createNewFile()
                        }

                        if (oldNuwaDir) {
                            patchDir = new File("${nuwaDir}/${dirName}/patch")
                            patchDir.mkdirs()
                            patchList.add(patchDir)
                        }
                    }

                    def nuwaPatch = "nuwa${variant.name.capitalize()}Patch"
                    project.task(nuwaPatch) << {
                        if (patchDir) {
                            NuwaAndroidUtils.dex(project, patchDir)
                        }
                    }
                    def nuwaPatchTask = project.tasks[nuwaPatch]

                    Closure copyMappingClosure = {
                        if (proguardTask) {
                            def mapFile = new File("${project.buildDir}/outputs/mapping/${variant.dirName}/mapping.txt")
                            def newMapFile = new File("${nuwaDir}/${variant.dirName}/mapping.txt");
                            FileUtils.copyFile(mapFile, newMapFile)
                        }
                    }

                    if (preDexTask) {
                        def nuwaJarBeforePreDex = "nuwaJarBeforePreDex${variant.name.capitalize()}"
                        project.task(nuwaJarBeforePreDex) << {
                            Set<File> inputFiles = preDexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (NuwaProcessor.shouldProcessPreDexJar(path)) {
                                    NuwaProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }
                            }
                        }
                        def nuwaJarBeforePreDexTask = project.tasks[nuwaJarBeforePreDex]
                        nuwaJarBeforePreDexTask.dependsOn preDexTask.taskDependencies.getDependencies(preDexTask)
                        preDexTask.dependsOn nuwaJarBeforePreDexTask

                        nuwaJarBeforePreDexTask.doFirst(nuwaPrepareClosure)

                        def nuwaClassBeforeDex = "nuwaClassBeforeDex${variant.name.capitalize()}"
                        project.task(nuwaClassBeforeDex) << {
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".class") && !path.contains("/R\$") && !path.endsWith("/R.class") && !path.endsWith("/BuildConfig.class")) {
                                    if (NuwaSetUtils.isIncluded(path, includePackage)) {
                                        if (!NuwaSetUtils.isExcluded(path, excludeClass)) {
                                            def bytes = NuwaProcessor.processClass(inputFile)
                                            path = path.split("${dirName}/")[1]
                                            def hash = DigestUtils.shaHex(bytes)
                                            hashFile.append(NuwaMapUtils.format(path, hash))

                                            if (NuwaMapUtils.notSame(hashMap, path, hash)) {
                                                NuwaFileUtils.copyBytesToFile(inputFile.bytes, NuwaFileUtils.touchFile(patchDir, path))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        def nuwaClassBeforeDexTask = project.tasks[nuwaClassBeforeDex]
                        nuwaClassBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        dexTask.dependsOn nuwaClassBeforeDexTask

                        nuwaClassBeforeDexTask.doLast(copyMappingClosure)

                        nuwaPatchTask.dependsOn nuwaClassBeforeDexTask
                        beforeDexTasks.add(nuwaClassBeforeDexTask)
                    } else {
                        def nuwaJarBeforeDex = "nuwaJarBeforeDex${variant.name.capitalize()}"
                        project.task(nuwaJarBeforeDex) << {
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".jar")) {
                                    NuwaProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }
                            }
                        }
                        def nuwaJarBeforeDexTask = project.tasks[nuwaJarBeforeDex]
                        nuwaJarBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        dexTask.dependsOn nuwaJarBeforeDexTask

                        nuwaJarBeforeDexTask.doFirst(nuwaPrepareClosure)
                        nuwaJarBeforeDexTask.doLast(copyMappingClosure)

                        nuwaPatchTask.dependsOn nuwaJarBeforeDexTask
                        beforeDexTasks.add(nuwaJarBeforeDexTask)
                    }

                }
            }

            project.task(NUWA_PATCHES) << {
                patchList.each { patchDir ->
                    NuwaAndroidUtils.dex(project, patchDir)
                }
            }
            beforeDexTasks.each {
                project.tasks[NUWA_PATCHES].dependsOn it
            }
        }
    }
}


