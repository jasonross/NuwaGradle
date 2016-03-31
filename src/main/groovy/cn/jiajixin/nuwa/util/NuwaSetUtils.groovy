package cn.jiajixin.nuwa.util

/**
 * Created by jixin.jia on 15/11/10.
 */
class NuwaSetUtils {
    public static boolean isExcluded(String path, Set<String> excludeClass) {
        excludeClass.each { exclude ->
            if (path.endsWith(exclude)) {
                return true
            }
        }
        return false
    }

    public static boolean isIncluded(String path, Set<String> includePackage) {
        if (includePackage.size() == 0) {
            return false
        }

        includePackage.each { include ->
            if (path.contains(include)) {
                return true
            }
        }
        return false
    }
}
