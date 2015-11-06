package cn.jiajixin.nuwa

import org.gradle.api.Project

/**
 * Created by jixin.jia on 15/11/4.
 */
class NuwaExtension {
    HashSet<String> includePackage = []
    HashSet<String> excludeClass = []
    boolean debugOn = true

    NuwaExtension(Project project) {
    }
}
