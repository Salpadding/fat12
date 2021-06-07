import org.apache.commons.codec.binary.Hex

val tableData = "f0ffff034000056000078000ffaf"

val fd = MemoryFD(Hex.decodeHex(tableData))
val table = Table(fd, 0)

fun main() {
    for(i in 2..7) {
        println(table[i] == i + 1)
    }
}