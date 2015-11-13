package cn.jiajixin.nuwa.util

import com.android.builder.internal.packaging.Packager
import com.android.builder.model.SigningConfig
import com.android.builder.signing.DefaultSigningConfig
import com.android.builder.signing.SignedJarBuilder
import com.android.builder.signing.SigningException
import com.android.ide.common.signing.CertificateInfo
import com.android.ide.common.signing.KeystoreHelper
import com.android.ide.common.signing.KeytoolException
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project

import java.security.NoSuchAlgorithmException

/**
 * Created by jixin.jia on 15/11/10.
 */
class NuwaAndroidUtils {

    private static final String PATCH_NAME = "patch.jar"
    private static final String PATCH_SIGN_NAME = "patch_sign.jar"

    public static String getApplication(File manifestFile) {
        def manifest = new XmlParser().parse(manifestFile)
        def androidTag = new groovy.xml.Namespace("http://schemas.android.com/apk/res/android", 'android')
        def applicationName = manifest.application[0].attribute(androidTag.name)

        if (applicationName != null) {
            return applicationName.replace(".", "/") + ".class"
        }
        return null;
    }

    private static String getAndroidHome(Project project) {
        def rootDir = project.rootDir
        def localProperties = new File(rootDir, "local.properties")
        if (localProperties.exists()) {
            Properties properties = new Properties()
            localProperties.withInputStream { instr ->
                properties.load(instr)
            }
            def sdkDir = properties.getProperty('sdk.dir')
            return sdkDir
        }
    }

    public static dex(Project project, File classDir) {
        if (classDir.listFiles().size()) {
            def sdkDir = System.getenv("ANDROID_HOME")
            if (!sdkDir) {
                sdkDir = getAndroidHome(project);
            }

            if (sdkDir) {
                def cmdExt = Os.isFamily(Os.FAMILY_WINDOWS) ? '.bat' : ''
                def stdout = new ByteArrayOutputStream()
                def dexJarFile = new File(classDir.getParent(), PATCH_NAME);
                def dexJarFileSign = new File(classDir.getParent(), PATCH_SIGN_NAME)
                project.exec {
                    commandLine "${sdkDir}/build-tools/${project.android.buildToolsVersion}/dx${cmdExt}",
                            '--dex',
                            "--output=${dexJarFile.absolutePath}",
                            "${classDir.absolutePath}"
                    standardOutput = stdout
                }
                def error = stdout.toString().trim()
                if (error) {
                    println "dex error:" + error
                } else {
                    def signingConfigs = project.android.signingConfigs;

                    if (signingConfigs.size() > 0) {
                        def releaseConfig = project.android.signingConfigs.getAt(0);

                        def signingConfig = new DefaultSigningConfig("dodo");
                        signingConfig.keyAlias = releaseConfig.keyAlias;
                        signingConfig.keyPassword = releaseConfig.keyPassword;
                        signingConfig.storeFile = releaseConfig.storeFile;
                        signingConfig.storePassword = releaseConfig.storePassword;
                        signJar(dexJarFile, signingConfig, dexJarFileSign)
                    }
                }
            } else {
                throw new InvalidUserDataException('$ANDROID_HOME is not defined')
            }
        }
    }

    public static void signJar(File inFile, SigningConfig signingConfig, File outFile)
            throws IOException, KeytoolException, SigningException, NoSuchAlgorithmException,
                    SignedJarBuilder.IZipEntryFilter.ZipAbortException, SigningException {

        def certificateInfo = null;
        if (signingConfig != null && signingConfig.isSigningReady()) {
            certificateInfo = KeystoreHelper.getCertificateInfo(signingConfig.getStoreType(),
                    signingConfig.getStoreFile(), signingConfig.getStorePassword(),
                    signingConfig.getKeyPassword(), signingConfig.getKeyAlias());
            if (certificateInfo == null) {
                throw new SigningException("Failed to read key from keystore");
            }
        }

        def signedJarBuilder = new SignedJarBuilder(
                new FileOutputStream(outFile),
                certificateInfo != null ? certificateInfo.getKey() : null,
                certificateInfo != null ? certificateInfo.getCertificate() : null,
                Packager.getLocalVersion(), "");


        signedJarBuilder.writeZip(new FileInputStream(inFile));
        signedJarBuilder.close();

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
