package edu.umkc.ofcontroller;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.app.Activity;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;


class Controller implements Runnable
{
    // Load libraries
    static {
        System.loadLibrary("gnustl_shared");
        System.loadLibrary("ofcontroller");
    }

    // Start controller with port 6653, thread number 4
    // TODO Put port and thread numbers static variable at top
    public void run() {
        startController(6653, 4);
    }

    native void startController(int port, int nthreads);
}


public class OFController extends Activity
{
    // File directory and handling
    public static File fileDir;
    // Processes
    public static Process process;
    public static Process processArp;
	final private int logUpdaterInterval = 1000;
    // ARP Table arrays (IP and MAC)
    public ArrayList<String> device_ips;
    public ArrayList<String> device_macs;
    public String line;
    public FileInputStream in;
    public BufferedReader br;
    public ControllerSync cntrlSync;
    // Development info
    public int devCount;
    // Variables
    // Text views for UI log display
	private TextView switchTextView;
    private TextView controllerTextView;
    // Handler for logs
    private Handler logUpdaterHandler;
    private BufferedReader bufferedReader;
    // Running the log update
    private Runnable logUpdater = new Runnable() {
		@Override
		public void run() {
			updateSwitchLog();
            updateControllerLog();
			logUpdaterHandler.postDelayed(logUpdater, logUpdaterInterval);
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState)
    {
        // Create view
		super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ofcontroller);

        //Controller UI
        controllerTextView = (TextView)findViewById(R.id.controllerView);
        final ScrollView controllerSV = (ScrollView)findViewById(R.id.scrollView);

        //switch UI
        switchTextView = (TextView)findViewById(R.id.switchView);
        final ScrollView sv = (ScrollView)findViewById(R.id.scrollView2);

        // Run flow execution
        execution_flow();

        // Handle log
        logUpdaterHandler = new Handler();
        logUpdater.run();

    }

    public void execution_flow(){

        try {
            // Setup for reading a Arp Table File
            fileDir = getFilesDir();

            // Getting library file from the device /lib for libfluid arp scan
            processArp = Runtime.getRuntime().exec(new String[]{"su", "-c", fileDir.getParent() + "/lib/libarpscan.so"});

            // Arrays to store arp table
            device_ips = new ArrayList<String>();
            device_macs = new ArrayList<String>();

            // TODO Find out why this is here, I don't like it
            Thread.sleep(10000);

            // Reading a txt arp table of device ids
            in = new FileInputStream("/sdcard/Dfluid/devices.txt");
            br = new BufferedReader(new InputStreamReader(in));
            devCount = 0;
            cntrlSync = new ControllerSync();


            // Log.i("Something is","wrong");

            // Reading the file
            while ((line = br.readLine()) != null) {
                devCount++;
                if (devCount == 1) {
                    cntrlSync.setLocal_ip(line.split(" ")[0]);
                    Log.i("localip", cntrlSync.getLocal_ip());
                    cntrlSync.setLocal_mac(line.split(" ")[1]);
                    Log.i("localmac", cntrlSync.getLocal_mac());
                }

                Log.i("devcount",String.valueOf(devCount));

                // Add mac and ip into array
                // TODO find why macs add at 1, make no sense
                device_ips.add(line.split(" ")[0]);
                device_macs.add(line.split(" ")[1]);
            }

            // TODO add a catch here

            /** thread to acknowledge that this device is master controller or not  **/

            // See if there is a master
            cntrlSync.masterCheck();

            // If many devices
            if (devCount > 1) {
                // TODO probe all the controllers on the network

                // Disable controller
                cntrlSync.setMaster(false);

                // Connection to a switch
                final DatagramSocket slaveSocket;

                Log.i("deviceips", device_ips.get(0));
                Log.i("deviceips", device_ips.get(1));

                // Create a new master
                Thread temp_t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {

                            // UDP Packet creation
                            DatagramPacket masterCheckPkt = null;
                            DatagramSocket slaveSocket = new DatagramSocket(9002);

                            // Compare ips in file to
                            for (String tmpIp : device_ips) {
                                Log.i("tmpIp", tmpIp);

                                // If the IP is IP is local (assumes the first line or local ip is my ip) if local then skip
                                if (tmpIp.compareTo(cntrlSync.getLocal_ip()) == 0) {// && tmpIp.split(".")[3].compareTo("1") != 0 && tmpIp.split(".")[3].compareTo("255") != 0 ) {
                                    continue;
                                }

                                // Creating a packet with the word master
                                masterCheckPkt = new DatagramPacket("master".getBytes(), "master".getBytes().length, InetAddress.getByName(tmpIp), 9004);
                                Log.i("ips:", tmpIp);

                                // Send the packet to other device
                                slaveSocket.send(masterCheckPkt);

                            }

                            // Listen for master
                            while (true) {

                                // Creating array to read the packet
                                byte[] recvMaster = new byte[2048];
                                final DatagramPacket recpkt = new DatagramPacket(recvMaster, recvMaster.length);
                                Log.i("recpkt:", "No packet has been recieved so far");

                                // Receive packet
                                slaveSocket.receive(recpkt);
                                final String recpktbody = new String(recpkt.getData());
                                Log.i("recpkt data:",recpktbody);

                                // If the packet starts with the word yes, set IP to master
                                if (recpktbody.startsWith("yes")) {
                                    cntrlSync.setMaster_ip(recpkt.getAddress().toString().substring(1));
                                    Log.i("Master ip", cntrlSync.getMaster_ip());
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();

                        }
                    }
                });

                // Create a thread
                temp_t.start();
                temp_t.join();
            }

            // For only one device
            else {
                    cntrlSync.setMaster(true);// set this device as a masterController
                    cntrlSync.setMaster_ip(cntrlSync.getLocal_ip());
                    cntrlSync.setMaster_mac(cntrlSync.getLocal_mac());
                    //Log.i("masterip", cntrlSync.getMaster_ip());
                    //Log.i("mastermac", cntrlSync.getMaster_mac());
                    Thread t = new Thread(new Controller());
                    t.start();
            }


            // Send Heartbeat
            cntrlSync.heartbeat();           // acknowledge heartbeat messages from slave controllers
            leaderIp(cntrlSync);
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", getFilesDir().getParent() + "/lib/libofswitch.so"});


        } catch (IOException e) {
            e.printStackTrace();
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    public void leaderIp(final ControllerSync ctrlSync) {
        (new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final DatagramSocket serverSocket = new DatagramSocket(9000);
                    byte[] receiveData = new byte[1024];
                    byte[] sendData = new byte[1024];


                    while(true) {

                        // Receive packet
                        final DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                        Log.i("DroidFluid", "WAITING...");
                        serverSocket.receive(receivePacket);

                        // Read the packet
                        final String sentence = new String(receivePacket.getData());
                        Log.i("DroidFluid", "RECEIVED: " + sentence);

                        // Get ip and port from packet
                        final int port = receivePacket.getPort();
                        final InetAddress IPAddress = receivePacket.getAddress();

                        // Get master ip
                        sendData = ctrlSync.getMaster_ip().getBytes();

                        // Send back the master ip
                        final DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
                        serverSocket.send(sendPacket);
                    }
                }
                catch (Exception e) {
                    Log.i("DroidFluid", "Error: " + e.getMessage());
                }
            }
        })).start();
    }
    // update logging for switch
    void updateSwitchLog()
    {
        try {
			final StringBuilder log = new StringBuilder();
            bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (bufferedReader.ready()) {
                final String sline = bufferedReader.readLine();

                log.append(sline + "\n");
            }

            if (!log.toString().isEmpty()) {
                Log.i("DroidFluid", "Native message: " + log.toString());

                switchTextView.setText(log.toString());
            }
		} catch (IOException e) {
            switchTextView.setText(e.getMessage());
		}
    }

    // Update log for controller
    void updateControllerLog() {
        String pid = android.os.Process.myPid() + "";
        try {
            Process processs = Runtime.getRuntime().exec(
                    "logcat -d OFCONTROLLER:V *:S");
            BufferedReader bufferedReaderCntrl = new BufferedReader(
                    new InputStreamReader(processs.getInputStream()));

            StringBuilder log = new StringBuilder();
            String logline;
            while ((logline = bufferedReaderCntrl.readLine()) != null) {
                if (logline.contains(pid)) {
                    log.append(logline.split(": ")[1] + "\n");
                }
            }

            controllerTextView.setText(log.toString());
        } catch (IOException e) {
        }
    }
}
