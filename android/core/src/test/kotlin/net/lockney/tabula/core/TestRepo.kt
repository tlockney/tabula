package net.lockney.tabula.core

import java.io.File

/** Locates the repository root from the test working directory (the module dir). */
object TestRepo {
    fun root(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "schema/settings.schema.json").isFile) return dir
            dir = dir.parentFile
        }
        error("repository root not found from ${System.getProperty("user.dir")}")
    }
}
