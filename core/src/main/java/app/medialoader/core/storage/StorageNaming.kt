package app.medialoader.core.storage

/** Target relative to public Downloads. Actual MediaStore writing comes later. */
object StorageNaming {
    const val RELATIVE_DIRECTORY = "Download/MediaLoader/"

    /** Existing names are supplied by storage; do not trust URLs as filenames. */
    fun availableName(originalName: String, existingNames: Set<String>): String {
        require(originalName.isNotBlank())
        require('/' !in originalName && '\\' !in originalName)
        if (originalName !in existingNames) return originalName
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val extension = if (dot > 0) originalName.substring(dot) else ""
        var index = 1
        while ("${base}_${index}${extension}" in existingNames) index++
        return "${base}_${index}${extension}"
    }
}
