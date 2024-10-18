package dev.kwasi.echoservercomplete

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.kwasi.echoservercomplete.chatlist.ChatListAdapter
import dev.kwasi.echoservercomplete.models.ContentModel
import dev.kwasi.echoservercomplete.network.Client
import dev.kwasi.echoservercomplete.network.NetworkMessageInterface
import dev.kwasi.echoservercomplete.network.Server
import dev.kwasi.echoservercomplete.peerlist.PeerListAdapter
import dev.kwasi.echoservercomplete.peerlist.PeerListAdapterInterface
import dev.kwasi.echoservercomplete.wifidirect.WifiDirectInterface
import dev.kwasi.echoservercomplete.wifidirect.WifiDirectManager
import java.security.MessageDigest
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class CommunicationActivity : AppCompatActivity(), WifiDirectInterface, PeerListAdapterInterface, NetworkMessageInterface {
    // Hardcoded list of student IDs
    private val studentIDs = listOf("816035115", "816035116", "816035117", "816035118", "816035119")


    private val verifiedStudents = mutableMapOf<String, String>()  // IP -> Student ID
    private val studentChallenges = mutableMapOf<String, Pair<String, Int>>()  // IP -> (StudentID, R)
    private val authenticatedStudents = mutableSetOf<String>()  // Authenticated student IPs

    private var wfdManager: WifiDirectManager? = null

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private var peerListAdapter:PeerListAdapter? = null
    private var chatListAdapter:ChatListAdapter? = null

    private var wfdAdapterEnabled = false
    private var wfdHasConnection = false
    private var hasDevices = false
    private var server: Server? = null
    private var client: Client? = null
    private var deviceIp: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        wfdManager?.disconnect()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_communication)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val manager: WifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val channel = manager.initialize(this, mainLooper, null)
        wfdManager = WifiDirectManager(manager, channel, this)

        peerListAdapter = PeerListAdapter(this)
        val rvPeerList: RecyclerView= findViewById(R.id.rvPeerListing)
        rvPeerList.adapter = peerListAdapter
        rvPeerList.layoutManager = LinearLayoutManager(this)

        chatListAdapter = ChatListAdapter()
        val rvChatList: RecyclerView = findViewById(R.id.rvChat)
        rvChatList.adapter = chatListAdapter
        rvChatList.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        wfdManager?.also {
            registerReceiver(it, intentFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        wfdManager?.also {
            unregisterReceiver(it)
        }
    }
    fun createGroup(view: View) {
        wfdManager?.createGroup()
    }

    fun discoverNearbyPeers(view: View) {
        wfdManager?.discoverPeers()
    }

    private fun updateUI(){
        //The rules for updating the UI are as follows:
        // IF the WFD adapter is NOT enabled then
        //      Show UI that says turn on the wifi adapter
        // ELSE IF there is NO WFD connection then i need to show a view that allows the user to either
            // 1) create a group with them as the group owner OR
            // 2) discover nearby groups
        // ELSE IF there are nearby groups found, i need to show them in a list
        // ELSE IF i have a WFD connection i need to show a chat interface where i can send/receive messages
        val wfdAdapterErrorView:ConstraintLayout = findViewById(R.id.clWfdAdapterDisabled)
        wfdAdapterErrorView.visibility = if (!wfdAdapterEnabled) View.VISIBLE else View.GONE

        val wfdNoConnectionView:ConstraintLayout = findViewById(R.id.clNoWifiDirectConnection)
        wfdNoConnectionView.visibility = if (wfdAdapterEnabled && !wfdHasConnection) View.VISIBLE else View.GONE

        val rvPeerList: RecyclerView= findViewById(R.id.rvPeerListing)
        rvPeerList.visibility = if (wfdAdapterEnabled && !wfdHasConnection && hasDevices) View.VISIBLE else View.GONE

        val wfdConnectedView:ConstraintLayout = findViewById(R.id.clHasConnection)
        wfdConnectedView.visibility = if(wfdHasConnection)View.VISIBLE else View.GONE
    }

    fun sendMessage(view: View) {
        val etMessage:EditText = findViewById(R.id.etMessage)
        val etString = etMessage.text.toString()
        val content = ContentModel(etString, deviceIp)
        etMessage.text.clear()
        client?.sendMessage(content)
        chatListAdapter?.addItemToEnd(content)

    }

    override fun onWiFiDirectStateChanged(isEnabled: Boolean) {
        wfdAdapterEnabled = isEnabled
        var text = "There was a state change in the WiFi Direct. Currently it is "
        text = if (isEnabled){
            "$text enabled!"
        } else {
            "$text disabled! Try turning on the WiFi adapter"
        }

        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.show()
        updateUI()
    }

    override fun onPeerListUpdated(deviceList: Collection<WifiP2pDevice>) {
        val toast = Toast.makeText(this, "Updated listing of nearby WiFi Direct devices", Toast.LENGTH_SHORT)
        toast.show()
        hasDevices = deviceList.isNotEmpty()
        peerListAdapter?.updateList(deviceList)
        updateUI()
    }

    override fun onGroupStatusChanged(groupInfo: WifiP2pGroup?) {
        val ssidTextView: TextView = findViewById(R.id.networkID)
        val passwordTextView: TextView = findViewById(R.id.password)

        val text = if (groupInfo == null){
            "Group is not formed"
        } else {
            "Group has been formed"
        }
        val toast = Toast.makeText(this, text , Toast.LENGTH_SHORT)
        toast.show()

        ssidTextView.text = groupInfo?.networkName ?: "Not Connected"
        passwordTextView.text = groupInfo?.passphrase ?: "Not Available"

        wfdHasConnection = groupInfo != null

        if (groupInfo == null){
            server?.close()
            client?.close()
        } else if (groupInfo.isGroupOwner && server == null){
            server = Server(this)
            deviceIp = "192.168.49.1"
        } else if (!groupInfo.isGroupOwner && client == null) {
            client = Client(this)
            deviceIp = client!!.ip
        }
    }

    override fun onDeviceStatusChanged(thisDevice: WifiP2pDevice) {
        val toast = Toast.makeText(this, "Device parameters have been updated" , Toast.LENGTH_SHORT)
        toast.show()
    }

    override fun onPeerClicked(peer: WifiP2pDevice) {
        wfdManager?.connectToPeer(peer)
    }


    override fun onContent(content: ContentModel) {
        val message = content.message
        val senderIp = content.senderIp

        // Step 1: Handle Student ID submission
        if (message.matches(Regex("\\d{9}"))) { // Assuming Student IDs are 9-digit numbers
            val studentID = message.trim()

            // Step 2: Verify if the Student ID is in the hardcoded list
            if (studentIDs.contains(studentID)) {
                // Student ID is valid, send acknowledgment and wait for "I am here"
                verifiedStudents[senderIp] = studentID
                val ackMessage = ContentModel("StudentID verified. Please send 'I am here'", deviceIp)
                server?.sendMessage(ackMessage, senderIp)  // Acknowledge valid Student ID
            } else {
                // Invalid Student ID, ignore further messages
                val toast = Toast.makeText(this, "Invalid Student ID from $senderIp", Toast.LENGTH_SHORT)
                toast.show()
            }

        } else if (message == "I am here") {
            // Step 3: Check if the student sent a valid Student ID before
            val studentID = verifiedStudents[senderIp]

            if (studentID != null) {
                // Generate random number R for challenge
                val randomNumber = generateRandomNumber()

                // Save the student's IP address, Student ID, and random number for later verification
                studentChallenges[senderIp] = Pair(studentID, randomNumber)

                // Send R to the student (This should not be shown in the chat)
                val challengeMessage = ContentModel(randomNumber.toString(), deviceIp)
                server?.sendMessage(challengeMessage, senderIp)  // Send R to the student

            } else {
                // "I am here" received without a valid Student ID
                val toast = Toast.makeText(this, "StudentID required before 'I am here' from $senderIp", Toast.LENGTH_SHORT)
                toast.show()
            }

        } else {
            // Step 4: Handle challenge-response from the student (Student sends encrypted R)
            val challengeData = studentChallenges[senderIp]
            if (challengeData != null) {
                val (studentID, expectedRandom) = challengeData

                // Decrypt the received message using the hash of the Student ID
                val studentIDHash = hashStudentID(studentID)
                val decryptedRandom = decryptMessage(message, studentIDHash)

                // Step 5: Verify if the decrypted random number matches the original R
                if (decryptedRandom == expectedRandom) {
                    // Authentication successful, allow further communication
                    studentChallenges.remove(senderIp)  // Remove challenge after successful auth
                    authenticatedStudents.add(senderIp)  // Mark the student as authenticated

                    val toast = Toast.makeText(this, "Student $studentID authenticated", Toast.LENGTH_SHORT)
                    toast.show()
                } else {
                    // Failed verification, ignore further messages from this student
                    val toast = Toast.makeText(this, "Authentication failed for $studentID", Toast.LENGTH_SHORT)
                    toast.show()
                }

            } else if (authenticatedStudents.contains(senderIp)) {
                // If the student is already authenticated, show the message in the chat
                runOnUiThread {
                    chatListAdapter?.addItemToEnd(content)
                }
            }
        }
    }

    //runOnUiThread {
      //  chatListAdapter?.addItemToEnd(content)
    //}

    // Helper function to generate a random number R
    private fun generateRandomNumber(): Int {
        return Random().nextInt(1000000) // Random number between 0 and 999999
    }

    // Function to handle the encrypted response from the student
    private fun handleEncryptedResponse(content: ContentModel) {
        val encryptedResponse = content.message
        val studentId = content.sender

        // Step 4: Verify if the student has an outstanding challenge
        val randomR = studentChallenges[studentId] ?: return

        // Step 5: Compute hash of StudentID
        val hashedId = hashStudentID(studentId)

        // Step 6: Decrypt the student's response using hashedId
        val decryptedR = decryptMessage(encryptedResponse, hashedId)

        // Step 7: Verify if decrypted R matches the original R
        if (decryptedR == randomR.toString()) {
            // Authentication successful, proceed with encrypted communication
            Toast.makeText(this, "Student $studentId authenticated!", Toast.LENGTH_SHORT).show()
        } else {
            // Authentication failed, ignore the student
            Toast.makeText(this, "Authentication failed for $studentId", Toast.LENGTH_SHORT).show()
        }
    }

    // Function to hash the Student ID
    private fun hashStudentID(studentId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = digest.digest(studentId.toByteArray())
        return hashedBytes.joinToString("") { "%02x".format(it) }
    }

    // Function to decrypt the student's response
    private fun decryptMessage(encryptedMessage: String, hashedKey: String): String {
        val keySpec = SecretKeySpec(hashedKey.toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec)

        val decryptedBytes = cipher.doFinal(encryptedMessage.toByteArray())
        return String(decryptedBytes)
    }


}