import org.apache.commons.codec.binary.Hex
import java.nio.file.Files
import java.nio.file.Path


/**
 * abstract of file system, unlike windows, path is separated by '/'
 */
interface FileSystem {
    // open directory, return the children, currently support only absolute path
    fun openDir(path: String): Array<String>

    // open a regular file
    fun openFile(path: String): ByteArray

    // save content to file
    fun write(path: String, content: ByteArray)

    // make a new directory
    fun mkdir(path: String)

    // return true if regular file found
    fun isRegular(path: String): Boolean
}


// structure of boot section
data class BootSector(
    val jump: Int, val oem: String, val bytesPerSection: Int,
    val sectionPerCluster: Int, val reversedSections: Int, val fatCount: Int,
    val rootEnt: Int, val totalSections: Int, val media: Int,
    val fatSize: Int, val sectionPerTrack: Int, val heads: Int,
    val hiddenSections: Int, val totalSections32: Int, val driverNumber: Int,
    val reversed1: Int, val bootSig: Int, val volId: String,
    val volLab: String, val fileSystemType: String, val code: String,
    val end: Int
)


fun ByteArray.toHexString(): String {
    return Hex.encodeHexString(this)
}


const val SECTION_BYTES = 512

// fat table contains 9 section
const val TABLE_BYTES = 9 * SECTION_BYTES

const val FAT12_BITS = 12

const val MAX_TABLE_SIZE = TABLE_BYTES * 8 / FAT12_BITS

/**
 * directory entry, 32 bytes
 */
data class Entry(
    // 8, 3, 1
    val name: String, val ext: String, val attr: Int,
    // 10, 2, 2
    val reversed: String, val writeTime: Int, val writeDate: Int,
    // 2, 4
    val cluster: Int, val size: Int
) {
    val fullName: String
        get() = if (ext.isEmpty()) {
            name
        } else {
            "$name.$ext"
        }

    val isDirectory: Boolean
        get() = (attr and SUB_DIRECTORY) != 0
}

const val SUB_DIRECTORY = 0x10

// root directory of fat12
class Root(val fd: FD, val offset: Long, val size: Int) {
    private val buf = ByteArray(16)

    fun children(): List<Entry> {
        fd.seek(offset)
        val r = mutableListOf<Entry>()
        for (i in 0 until size) {
            val e = fd.readEntry(buf)
            if (e.attr == 0)
                continue
            r.add(e)
        }
        return r
    }
}

// file allocation table in 12bits
class Table(val fd: FD, val offset: Long) {


    // an fat12 entry is 1.5 byte
    private val buf = ByteArray(2)

    operator fun get(index: Int): Int {
        if (index >= MAX_TABLE_SIZE)
            throw RuntimeException("table access overflow")

        val offsetBits = index * FAT12_BITS
        val offsetBytes = offsetBits / 8
        val mod = offsetBits % 8
        fd.seek(offset + offsetBytes, Whence.SEEK_SET)
        fd.read(buf, 2)
        return if (mod == 0)
            ((buf[1].toUByte().toInt() and 0x0f) shl 8) or (buf[0].toUByte().toInt())
        else
            ((buf[0].toUByte().toInt() and 0xf0) ushr 4) or (buf[1].toUByte().toInt() shl 4)
    }

    fun isUnused(value: Int): Boolean {
        return value == 0
    }

    fun isReversed(value: Int): Boolean {
        return value >= 0xff0 && value < 0xff7
    }

    fun isBad(value: Int): Boolean {
        return value == 0xff7
    }

    fun isEnd(value: Int): Boolean {
        return value >= 0xff8
    }
}

class Fat12(val fd: FD) : FileSystem {
    val boot: BootSector
    val fat1: Table
    val fat2: Table
    val root: Root
    val dataOffset: Long

    init {
        val tmp = ByteArray(16)
        fd.seek(0)
        boot = fd.parseBootSection(tmp)
        fat1 = Table(fd, fd.seek(0, Whence.SEEK_CUR))
        fat2 = Table(fd, fat1.offset + TABLE_BYTES)
        root = Root(fd, fat2.offset + TABLE_BYTES, boot.rootEnt)
        dataOffset = root.offset + boot.rootEnt * 32
    }


    private fun readAllBytes(start: Int, size: Int): ByteArray {
        val out = ByteArray(size)

        // convert logical to physical
        var cur = start

        var read = 0
        val section = ByteArray(SECTION_BYTES)
        while (!fat1.isEnd(cur) && read < size) {
            if (fat1.isBad(cur))
                throw RuntimeException("bad cluster found")
            if (fat1.isReversed(cur)) {
                throw RuntimeException("access reversed cluster")
            }
            if (fat1.isUnused(cur)) {
                throw RuntimeException("unexpected empty sector")
            }

            val toRead = Math.min(size - read, SECTION_BYTES)

            // the 0, 1 entry in fat table is reversed
            fd.seek(dataOffset + (cur - 2) * SECTION_BYTES)
            fd.read(section, toRead)
            System.arraycopy(section, 0, out, read, toRead)
            read += toRead

            // convert logical to physical
            cur = fat1[cur]
        }
        return out
    }

    // TODO: openDir
    override fun openDir(path: String): Array<String> {
        return emptyArray()
    }

    override fun openFile(path: String): ByteArray {
        for (child in root.children()) {
            if (child.fullName == path) {
                return readAllBytes(child.cluster, child.size)
            }
        }
        throw RuntimeException("file $path not found")
    }

    // TODO: wrtie file
    override fun write(path: String, content: ByteArray) {
        TODO("Not yet implemented")
    }

    override fun mkdir(path: String) {
        TODO("Not yet implemented")
    }

    override fun isRegular(path: String): Boolean {
        return false
    }
}

// parse fat12 image file's boot section
fun main() {
    val img = System.getenv("IMAGE") ?: return
    val bytes = Files.readAllBytes(Path.of(img))
    val fd = MemoryFD(bytes)
    val fs = Fat12(fd)
    println(
        String(
            fs.openFile(fs.root.children()[1].fullName)
        )
    )
}