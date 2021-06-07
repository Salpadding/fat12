import org.apache.commons.codec.binary.Hex
import java.nio.file.Files
import java.nio.file.Path

@JvmInline
value class TableEntry(val id: Int) {
    val unused: Boolean
        get() = id == 0

    val reversed: Boolean
        get() = id >= 0xff0 && id < 0xff7

    val bad: Boolean
        get() = id == 0xff7

    val eoc: Boolean
        get() = id > 0xff7
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

class RootDir(private val fs: Fat12) : File {
    override val name: String
        get() = ""

    override val isDirectory: Boolean
        get() = true

    override val path: String = "/"

    override fun readAllBytes(): ByteArray {
        throw UnsupportedOperationException()
    }

    override fun list(): Array<File> {
        return fs.root.children().map { FileImpl(it, fs, "/") }.toTypedArray()
    }
}

class FileImpl(private val entry: Entry, private val fs: Fat12, private val prefix: String) : File {
    override val name: String
        get() = entry.fullName

    override val isDirectory: Boolean
        get() = entry.isDirectory

    override val path: String = "$prefix$name"

    override fun readAllBytes(): ByteArray {
        if (isDirectory)
            throw RuntimeException("not a regular file")
        return fs.readAllBytes(entry.cluster, entry.size)
    }

    override fun list(): Array<File> {
        val entries = fs.readDirEntries(entry.cluster)
        return entries.map { FileImpl(it, fs, "$path/") }.toTypedArray()
    }
}

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

    fun readDirEntries(start: Int): List<Entry> {
        val maxChildren = SECTION_BYTES / 32
        fd.seek(dataOffset + (start - 2) * SECTION_BYTES)

        val tmp = ByteArray(16)

        val r = mutableListOf<Entry>()
        for (i in 0 until maxChildren) {
            val e = fd.readEntry(tmp)
            if (e.attr == 0)
                continue
            r.add(e)
        }
        return r
    }

    fun readAllBytes(start: Int, size: Int): ByteArray {
        val out = ByteArray(size)

        // convert logical to physical
        var cur = TableEntry(start)

        var read = 0
        val section = ByteArray(SECTION_BYTES)
        while (!cur.eoc && read < size) {
            if (cur.bad)
                throw RuntimeException("bad cluster found")
            if (cur.reversed) {
                throw RuntimeException("access reversed cluster")
            }
            if (cur.unused) {
                throw RuntimeException("unexpected empty sector")
            }

            val toRead = Math.min(size - read, SECTION_BYTES)

            // the 0, 1 entry in fat table is reversed
            fd.seek(dataOffset + (cur.id - 2) * SECTION_BYTES)
            fd.read(section, toRead)
            System.arraycopy(section, 0, out, read, toRead)
            read += toRead

            // convert logical to physical
            cur = TableEntry(fat1[cur.id])
        }
        return out
    }


    override fun open(path: String): File? {
        if (!path.startsWith('/'))
            throw RuntimeException("please use absolute path")
        val stack = path.uppercase().trim().split('/').map { it.trim() }
            .filter { it.isNotEmpty() }

        val fileStack = ArrayDeque<File>()
        fileStack.addLast(RootDir(this))

        for (i in 0 until stack.size) {
            val expected = stack[i]
            if (expected == ".")
                continue
            if (expected == "..") {
                if (!fileStack.isEmpty())
                    fileStack.removeLast()
                continue
            }
            if (!fileStack.last().isDirectory)
                throw RuntimeException("not a directory")

            val list = fileStack.last().list()
            val n = list.find { it.name == expected } ?: return null
            fileStack.addLast(n)
        }

        return fileStack.last()
    }

    // TODO: wrtie file
    override fun write(path: String, content: ByteArray) {
        TODO("Not yet implemented")
    }

    override fun mkdir(path: String) {
        TODO("Not yet implemented")
    }

}

// parse fat12 image file's boot section
fun main() {
    val img = System.getenv("IMAGE") ?: return
    val bytes = Files.readAllBytes(Path.of(img))
    val fd = MemoryFD(bytes)
    val fs: FileSystem = Fat12(fd)
    println(String(fs.open("/drafts/dos.txt")!!.readAllBytes()))
}