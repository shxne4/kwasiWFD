package dev.kwasi.echoservercomplete.network

import android.util.Log
import com.google.gson.Gson
import dev.kwasi.echoservercomplete.models.ContentModel
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.Exception
import kotlin.concurrent.thread

/// The [Server] class has all the functionality that is responsible for the 'server' connection.
/// This is implemented using TCP. This Server class is intended to be run on the GO.

class Server(private val iFaceImpl: NetworkMessageInterface) {
    companion object {
        const val PORT: Int = 9999
    }

    private val svrSocket: ServerSocket = ServerSocket(PORT, 0, InetAddress.getByName("192.168.49.1"))
    private val clientMap: HashMap<String, Socket> = HashMap()

    init {
        thread {
            while (true) {
                try {
                    val clientConnectionSocket = svrSocket.accept()
                    Log.e("SERVER", "The server has accepted a connection: ")
                    handleSocket(clientConnectionSocket)
                } catch (e: Exception) {
                    Log.e("SERVER", "An error has occurred in the server!")
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        socket.inetAddress.hostAddress?.let { clientIp ->
            clientMap[clientIp] = socket
            Log.e("SERVER", "A new connection has been detected from: $clientIp")
            thread {
                val clientReader = socket.inputStream.bufferedReader()
                val clientWriter = socket.outputStream.bufferedWriter()

                while (socket.isConnected) {
                    try {
                        val receivedJson = clientReader.readLine()
                        if (receivedJson != null) {
                            Log.e("SERVER", "Received a message from client $clientIp")
                            val clientContent = Gson().fromJson(receivedJson, ContentModel::class.java)

                            // Send back the same content without reversing
                            val responseContentStr = Gson().toJson(clientContent)
                            clientWriter.write("$responseContentStr\n")
                            clientWriter.flush()

                            // Pass the content to the interface implementation
                            iFaceImpl.onContent(clientContent)
                        }
                    } catch (e: Exception) {
                        Log.e("SERVER", "An error has occurred with the client $clientIp")
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun sendMessage(content: ContentModel, recipientIp: String) {
        val socket = clientMap[recipientIp]
        socket?.let {
            try {
                val clientWriter = it.outputStream.bufferedWriter()
                val contentJson = Gson().toJson(content)
                clientWriter.write("$contentJson\n")
                clientWriter.flush()
            } catch (e: Exception) {
                Log.e("SERVER", "Failed to send message to $recipientIp")
                e.printStackTrace()
            }
        } ?: Log.e("SERVER", "No connection found for $recipientIp")
    }

    fun close() {
        svrSocket.close()
        clientMap.clear()
    }
}
