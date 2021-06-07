interface File {
    /**
     * name of the file
     */
    val name: String

    /**
     * if file is a directory
     */
    val isDirectory: Boolean

    val isFile: Boolean
        get() = !isDirectory

    /**
     * read file as byte array
     */
    fun readAllBytes(): ByteArray

    /**
     * absolute path
     */
    val absPath: String

    /**
     * list directory children
     */
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
