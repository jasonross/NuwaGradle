package cn.jiajixin.nuwa.util

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project

/**
 * Created by jixin.jia on 15/11/10.
 */
class NuwaAndroidUtils {

    private static final String PATCH_NAME = "patch.jar"

    public static String getApplication(File manifestFile) {
        def manifest = new XmlParser().parse(manifestFile)
        def androidTag = new groovy.xml.Namespace("http://schemas.android.com/apk/res/android", 'android')
        def applicationName = manifest.application[0].attribute(androidTag.name)

        if (applicationName != null) {
            return applicationName.replace(".", "/") + ".class"
        }
        return null;
    }

    public static dex(Project project, File classDir) {
        if (classDir.listFiles().size()) {
            def sdkDir
  
            Properties properties = new Properties()
            File localProps = project.rootProject.file("local.properties")
            if (localProps.exists()) {
                properties.load(localProps.newDataInputStream())
                sdkDir = properties.getProperty("sdk.dir")
            } else {
                sdkDir = System.getenv("ANDROID_HOME")
            }
            if (sdkDir) {
                def cmdExt = Os.isFamily(Os.FAMILY_WINDOWS) ? '.bat' : ''
                def stdout = new ByteArrayOutputStream()
                project.exec {
                    commandLine "${sdkDir}/build-tools/${project.android.buildToolsVersion}/dx${cmdExt}",
                            '--dex',
                            "--output=${new File(classDir.getParent(), PATCH_NAME).absolutePath}",
                            "${classDir.absolutePath}"
                    standardOutput = stdout
                }
                def error = stdout.toString().trim()
                if (error) {
                    println "dex error:" + error
                }
            } else {
                throw new InvalidUserDataException('$ANDROID_HOME is not defined')
            }
        }
    }

    public static applymapping(DefaultTask proguardTask, File mappingFile) {
        if (proguardTask) {
            if (mappingFile.exists()) {
                proguardTask.applymapping(mappingFile)
            } else {
                println "$mappingFile does not exist"
            }
        }
    }
}
