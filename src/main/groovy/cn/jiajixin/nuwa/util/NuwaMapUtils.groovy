package cn.jiajixin.nuwa.util
/**
 * Created by jixin.jia on 15/11/10.
 */
class NuwaMapUtils {
    private static final String MAP_SEPARATOR = ":"

    public static boolean notSame(Map map, String name, String hash) {
        def notSame = false
        if (map) {
            def value = map.get(name)
            if (value) {
                if (!value.equals(hash)) {
                    notSame = true
                }
            } else {
                notSame = true
            }
        }
        return notSame
    }

    public static Map parseMap(File hashFile) {
        def hashMap = [:]
        if (hashFile.exists()) {
            hashFile.eachLine {
                List list = it.split(MAP_SEPARATOR)
                if (list.size() == 2) {
                    hashMap.put(list[0], list[1])
                }
            }
        } else {
            println "$hashFile does not exist"
        }
        return hashMap
    }

    public static format(String path, String hash) {
        return path + MAP_SEPARATOR + hash + "\n"
    }
}
