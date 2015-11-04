package cn.jiajixin.nuwa

import org.gradle.api.Project

/**
 * Created by jixin.jia on 15/11/4.
 */
class NuwaExtension {
    String includePackage = null
    HashSet<String> excludeClass = []
    boolean debugOn = true

    NuwaExtension(Project project) {
    }
}
