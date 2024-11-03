import org.junit.jupiter.api.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnixSocketServerTest {

    private val socketPath = "/tmp/test_socket.sock"
    private val filePath = "/tmp/test_file.txt"
    private lateinit var server: Thread

    @BeforeAll
    fun setUp() {
        // Initialize server in a separate thread
        server = Thread {
            UnixSocketServer(socketPath, filePath).start()
        }
        server.start()
        Thread.sleep(1000) // Allow time for the server to start
    }

    @AfterAll
    fun tearDown() {
        Files.deleteIfExists(Paths.get(socketPath))
        Files.deleteIfExists(Paths.get(filePath))
        server.interrupt()
    }

    private fun connectClient(): SocketChannel {
        return SocketChannel.open(UnixDomainSocketAddress.of(Paths.get(socketPath)))
    }

    @Test
    fun `test Ok message`() {
        val client = connectClient()
        val buffer = ByteBuffer.allocate(8)
        buffer.put(0x1.toByte()) // Message type Ok
        buffer.put(ByteArray(3)) // Reserved bytes
        buffer.putInt(0) // Content length
        buffer.flip()

        client.write(buffer)
        Thread.sleep(100) // Wait for server response

        val responseBuffer = ByteBuffer.allocate(8)
        val bytesRead = client.read(responseBuffer)
        client.close()

        assertEquals(-1, bytesRead, "Expected no response for Ok message")
    }

    @Test
    fun `test Write message`() {
        val client = connectClient()
        val content = "Hello, World!".toByteArray()
        val buffer = ByteBuffer.allocate(8 + content.size)
        buffer.put(0x2.toByte()) // Message type Write
        buffer.put(ByteArray(3)) // Reserved bytes
        buffer.putInt(content.size) // Content length
        buffer.put(content) // Content
        buffer.flip()

        client.write(buffer)

        val responseBuffer = ByteBuffer.allocate(8)
        client.read(responseBuffer)
        responseBuffer.flip()
        client.close()

        // Verify that "Hello, World!" was appended to the file
        val fileContent = File(filePath).readText()
        assertTrue(fileContent.contains("Hello, World!"), "File content should include 'Hello, World!'")
    }

    @Test
    fun `test Clear message`() {
        // Ensure the file has initial content
        File(filePath).writeText("Some initial content")

        val client = connectClient()
        val buffer = ByteBuffer.allocate(8)
        buffer.put(0x3.toByte()) // Message type Clear
        buffer.put(ByteArray(3)) // Reserved bytes
        buffer.putInt(0) // Content length
        buffer.flip()

        client.write(buffer)

        val responseBuffer = ByteBuffer.allocate(8)
        client.read(responseBuffer)
        responseBuffer.flip()
        client.close()

        // Verify that the file content is cleared
        val fileContent = File(filePath).readText()
        assertEquals("", fileContent, "File should be empty after Clear message")
    }

    @Test
    fun `test Error message`() {
        val client = connectClient()
        val errorMessage = "Something went wrong!".toByteArray()
        val buffer = ByteBuffer.allocate(8 + errorMessage.size)
        buffer.put(0x4.toByte()) // Message type Error
        buffer.put(ByteArray(3)) // Reserved bytes
        buffer.putInt(errorMessage.size) // Content length
        buffer.put(errorMessage) // Error content
        buffer.flip()

        client.write(buffer)
        client.close()
    }

    @Test
    fun `test Ping message`() {
        val client = connectClient()
        val buffer = ByteBuffer.allocate(8)
        buffer.put(0x5.toByte()) // Message type Ping
        buffer.put(ByteArray(3)) // Reserved bytes
        buffer.putInt(0) // Content length
        buffer.flip()

        client.write(buffer)

        val responseBuffer = ByteBuffer.allocate(8)
        client.read(responseBuffer)
        responseBuffer.flip()
        client.close()

        // Check if the response is Ok
        assertEquals(0x1, responseBuffer.get().toInt(), "Expected Ok response for Ping message")
    }
}
