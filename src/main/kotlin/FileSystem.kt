interface File {
    val name: String
    val isDirectory: Boolean

    val isFile: Boolean
        get() = !isDirectory

    fun readAllBytes(): ByteArray
    val path: String

    fun list(): Array<File>
}


/**
 * abstract of file system, unlike windows, path is separated by '/'
 */
interface FileSystem {

    /**
     * open path as directory, return the file, support only absolute path
     */
    fun open(path: String): File?


    /**
     * save content to path, create new file if path not exists
     * throws exception if path is a directory
     */
    fun write(path: String, content: ByteArray)

    /**
     * create a new directory
     */
    fun mkdir(path: String)

}
