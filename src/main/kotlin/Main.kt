import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.channels.ServerSocketChannel
import java.net.StandardProtocolFamily
import java.nio.file.Paths
import java.io.FileOutputStream
import java.io.File

class UnixSocketServer(private val socketPath: String, private val filePath: String) {

    private val messageBuffer = ByteBuffer.allocate(1024)

    fun start() {
        val address = UnixDomainSocketAddress.of(Paths.get(socketPath))

        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
            serverChannel.bind(address)
            println("Server listening on $socketPath")

            while (true) {
                val clientChannel = serverChannel.accept()
                clientChannel.use {
                    messageBuffer.clear()
                    val bytesRead = clientChannel.read(messageBuffer)

                    if (bytesRead > 0) {
                        messageBuffer.flip()
                        handleMessage(messageBuffer, clientChannel)
                    }
                }
            }
        }
    }

    private fun handleMessage(buffer: ByteBuffer, clientChannel: SocketChannel) {
        if (buffer.remaining() < 8) {
            sendError(clientChannel, "Invalid message length.")
            return
        }

        val messageType = buffer.get()
        buffer.position(buffer.position() + 3) // Skip reserved bytes
        val contentLength = buffer.int

        if (contentLength != buffer.remaining()) {
            sendError(clientChannel, "Content length mismatch.")
            return
        }

        when (messageType) {
            0x1.toByte() -> handleOk(clientChannel, contentLength)
            0x2.toByte() -> handleWrite(buffer, clientChannel)
            0x3.toByte() -> handleClear(clientChannel, contentLength)
            0x4.toByte() -> handleError(buffer)
            0x5.toByte() -> handlePing(clientChannel, contentLength)
            else -> sendError(clientChannel, "Unknown message type.")
        }
    }

    private fun handleOk(clientChannel: SocketChannel, contentLength: Int) {
        if (contentLength == 0) {
            println("Received Ok message")
        } else {
            sendError(clientChannel, "Invalid Ok message")
        }
    }

    private fun handleWrite(buffer: ByteBuffer, clientChannel: SocketChannel) {
        val content = ByteArray(buffer.remaining())
        buffer.get(content)

        try {
            FileOutputStream(filePath, true).use { it.write(content) }
            sendOk(clientChannel)
        } catch (e: Exception) {
            sendError(clientChannel, "Failed to write to file: ${e.message}")
        }
    }

    private fun handleClear(clientChannel: SocketChannel, contentLength: Int) {
        if (contentLength != 0) {
            sendError(clientChannel, "Invalid Clear message.")
            return
        }

        try {
            File(filePath).writeText("")
            sendOk(clientChannel)
        } catch (e: Exception) {
            sendError(clientChannel, "Failed to clear file: ${e.message}")
        }
    }

    private fun handleError(buffer: ByteBuffer) {
        val content = ByteArray(buffer.remaining())
        buffer.get(content)
        println("Error message received: ${String(content)}")
    }

    private fun handlePing(clientChannel: SocketChannel, contentLength: Int) {
        if (contentLength == 0) {
            sendOk(clientChannel)
        } else {
            sendError(clientChannel, "Invalid Ping message")
        }
    }

    private fun sendOk(clientChannel: SocketChannel) {
        clientChannel.write(ByteBuffer.wrap(byteArrayOf(0x1)))
    }

    private fun sendError(clientChannel: SocketChannel, errorMessage: String) {
        println("Error: $errorMessage")
        val errorContent = errorMessage.toByteArray()
        val responseBuffer = ByteBuffer.allocate(5 + errorContent.size)
        responseBuffer.put(0x4) // Error message type
        responseBuffer.put(ByteArray(3)) // Reserved bytes
        responseBuffer.putInt(errorContent.size)
        responseBuffer.put(errorContent)
        responseBuffer.flip()
        clientChannel.write(responseBuffer)
    }
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: <socket path> <file path>")
        return
    }

    val server = UnixSocketServer(args[0], args[1])
    server.start()
}