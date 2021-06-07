import java.nio.charset.StandardCharsets

/**
 * extract device as file descriptor, this interface is compatible with unix file descriptor
 */
interface FD {
    /**
     * see man 2 read
     */
    fun read(buf: ByteArray, size: Int = buf.size): Int

    /**
     * see man 2 write
     */
    fun write(buf: ByteArray, size: Int = buf.size): Int

    /**
     * close file descriptor
     */
    fun close()

    /**
     * see man 2 lseek
     */
    fun seek(offset: Long = 0, whence: Whence = Whence.SEEK_SET): Long
}


enum class Whence {
    SEEK_SET, SEEK_CUR, SEEK_END
}


/**
 * copy content of file descriptor into memory, any operation on file descriptor will keep just in memory until close() called
 */
class MemoryFD(val data: ByteArray) : FD {
    private var pos: Int = 0

    private val remain: Int
        get() = data.size - pos

    override fun read(buf: ByteArray, size: Int): Int {
        if (pos + size > data.size)
            throw RuntimeException("read overflow")
        val cnt = Math.min(size, remain)
        if (cnt == 0)
            return 0
        System.arraycopy(data, pos, buf, 0, cnt)
        pos += cnt
        return cnt
    }

    override fun write(buf: ByteArray, size: Int): Int {
        TODO("Not yet implemented")
    }

    override fun close() {
    }

    override fun seek(offset: Long, whence: Whence): Long {
        if (whence == Whence.SEEK_CUR) {
            this.pos += offset.toInt()
            return this.pos.toLong()
        }

        if (whence != Whence.SEEK_SET)
            throw RuntimeException("not implemented")
        this.pos = offset.toInt()
        return this.pos.toLong()
    }

}

/**
 * read a little endian integer from file descriptor
 */
fun FD.readInt(tmp: ByteArray, size: Int): Int {
    if (this.read(tmp, size) != size)
        throw RuntimeException("read failed")
    var ret = 0
    for (i in 0 until size) {
        ret = ret or (tmp[i].toUByte().toInt() shl (i * 8))
    }
    return ret
}

/**
 * read a string from file descriptor
 */
fun FD.readString(tmp: ByteArray, size: Int): String {
    if (this.read(tmp, size) != size)
        throw RuntimeException("read failed")
    return String(tmp, 0, size, StandardCharsets.US_ASCII)
}

/**
 * read data into byte array
 */
fun FD.readFull(tmp: ByteArray): ByteArray {
    if (this.read(tmp) != tmp.size)
        throw RuntimeException("read failed")
    return tmp
}

fun FD.readEntry(tmp: ByteArray): Entry {
    val reversed = ByteArray(10)
    return Entry(
        this.readString(tmp, 8).trim(),
        this.readString(tmp, 3).trim(),
        this.readInt(tmp, 1),
        this.readFull(reversed).toHexString(),
        this.readInt(tmp, 2),
        this.readInt(tmp, 2),
        this.readInt(tmp, 2),
        this.readInt(tmp, 4)
    )
}

/**
 * parse boot section
 */
fun FD.parseBootSection(tmp: ByteArray): BootSector {
    val code = ByteArray(448)
    val sec = BootSector(
        // jmp, oem, bytes per section
        this.readInt(tmp, 3), this.readString(tmp, 8), this.readInt(tmp, 2),
        // section per cluster, reversed sections, num fats
        this.readInt(tmp, 1), this.readInt(tmp, 2), this.readInt(tmp, 1),
        // rootEntCnt, total section 16, media
        this.readInt(tmp, 2), this.readInt(tmp, 2), this.readInt(tmp, 1),
        // fat size 16, section per track, heads
        this.readInt(tmp, 2), this.readInt(tmp, 2), this.readInt(tmp, 2),
        // hidden sections, total sections 32, driver number
        this.readInt(tmp, 4), this.readInt(tmp, 4), this.readInt(tmp, 1),
        // reversed1, bootSig, volId
        this.readInt(tmp, 1), this.readInt(tmp, 1), this.readInt(tmp, 4).toUInt().toString(16),
        // vollab, file system type, code
        this.readString(tmp, 11), this.readString(tmp, 8), this.readFull(code).toHexString(),
        // end
        this.readInt(tmp, 2)
    )
    if (sec.end != 0xAA55)
        throw RuntimeException("invalid boot sector, end = ${sec.end.toString(16)}")
    return sec
}