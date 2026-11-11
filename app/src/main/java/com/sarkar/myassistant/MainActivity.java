package com.sarkar.myassistant;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity for My Assistant
 * Production-ready offline P2P audio & video calling via Wi-Fi Hotspot using WebRTC and local TCP signaling.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MyAssistant";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int SIGNALING_PORT = 8888;
    private static final String DEFAULT_HOTSPOT_GATEWAY = "192.168.43.1";

    // UI elements
    private SurfaceViewRenderer localSurfaceView;
    private SurfaceViewRenderer remoteSurfaceView;
    private View localSurfaceContainer;
    private ScrollView connectContainer;
    private View callControls;
    private TextView tvLocalIpBanner;
    private TextView tvDeviceIp;
    private TextView tvServerStatus;
    private TextView tvCallStatus;
    private EditText etPeerIp;
    private Button btnStartCall;
    private Button btnFillHotspotIp;
    private ImageButton btnCopyIp;
    private ImageButton btnToggleMic;
    private ImageButton btnToggleVideo;
    private ImageButton btnSwitchCamera;
    private ImageButton btnToggleSpeaker;
    private ImageButton btnHangup;

    // WebRTC Core
    private EglBase rootEglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private SurfaceTextureHelper surfaceTextureHelper;
    private CameraVideoCapturer videoCapturer;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;

    // Audio & State
    private AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();

    private boolean isMicMuted = false;
    private boolean isVideoDisabled = false;
    private boolean isSpeakerOn = true;
    private boolean isCallActive = false;
    private boolean isFrontCamera = true;
    private String localIpAddress = "127.0.0.1";

    // Local TCP Signaling
    private ServerSocket serverSocket;
    private Socket activeSignalingSocket;
    private PrintWriter signalingWriter;
    private volatile boolean isServerRunning = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupAudio();
        displayLocalIp();

        if (checkPermissions()) {
            startApp();
        } else {
            requestPermissions();
        }
    }

    private void initViews() {
        localSurfaceView = findViewById(R.id.local_surface_view);
        remoteSurfaceView = findViewById(R.id.remote_surface_view);
        localSurfaceContainer = findViewById(R.id.local_surface_container);
        connectContainer = findViewById(R.id.connect_container);
        callControls = findViewById(R.id.call_controls);
        tvLocalIpBanner = findViewById(R.id.tv_local_ip_banner);
        tvDeviceIp = findViewById(R.id.tv_device_ip);
        tvServerStatus = findViewById(R.id.tv_server_status);
        tvCallStatus = findViewById(R.id.tv_call_status);
        etPeerIp = findViewById(R.id.et_peer_ip);
        btnStartCall = findViewById(R.id.btn_start_call);
        btnFillHotspotIp = findViewById(R.id.btn_fill_hotspot_ip);
        btnCopyIp = findViewById(R.id.btn_copy_ip);
        btnToggleMic = findViewById(R.id.btn_toggle_mic);
        btnToggleVideo = findViewById(R.id.btn_toggle_video);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        btnToggleSpeaker = findViewById(R.id.btn_toggle_speaker);
        btnHangup = findViewById(R.id.btn_hangup);

        btnFillHotspotIp.setOnClickListener(v -> etPeerIp.setText(DEFAULT_HOTSPOT_GATEWAY));

        btnCopyIp.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Local IP", localIpAddress);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copied IP: " + localIpAddress, Toast.LENGTH_SHORT).show();
        });

        btnStartCall.setOnClickListener(v -> {
            String peerIp = etPeerIp.getText().toString().trim();
            if (peerIp.isEmpty()) {
                Toast.makeText(this, "Please enter peer IP address", Toast.LENGTH_SHORT).show();
                return;
            }
            startCallAsCaller(peerIp);
        });

        btnHangup.setOnClickListener(v -> endCall(true));

        btnToggleMic.setOnClickListener(v -> toggleMic());
        btnToggleVideo.setOnClickListener(v -> toggleVideo());
        btnSwitchCamera.setOnClickListener(v -> switchCamera());
        btnToggleSpeaker.setOnClickListener(v -> toggleSpeaker());
    }

    private void setupAudio() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
        }
    }

    private boolean checkPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        requestPermissions(
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                PERMISSION_REQUEST_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                startApp();
            } else {
                Toast.makeText(this, "Camera and Microphone permissions are required for calling.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startApp() {
        initWebRTC();
        startSignalingServer();
    }

    private void displayLocalIp() {
        localIpAddress = getLocalIpAddress();
        tvLocalIpBanner.setText("My IP: " + localIpAddress + ":" + SIGNALING_PORT);
        tvDeviceIp.setText(localIpAddress);
        tvServerStatus.setText("● TCP Server listening on port " + SIGNALING_PORT);
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            String fallbackIp = null;
            for (NetworkInterface intf : interfaces) {
                Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                while (enumIpAddr.hasMoreElements()) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        String ip = inetAddress.getHostAddress();
                        String name = intf.getName().toLowerCase();
                        if (name.contains("ap") || name.contains("softap") || name.contains("wlan")) {
                            return ip;
                        }
                        if (fallbackIp == null && ip != null) {
                            fallbackIp = ip;
                        }
                    }
                }
            }
            if (fallbackIp != null) return fallbackIp;
        } catch (Exception e) {
            Log.e(TAG, "Error obtaining IP: " + e.getMessage());
        }
        return DEFAULT_HOTSPOT_GATEWAY;
    }

    // ---------------------------------------------------------
    // WebRTC Initialization
    // ---------------------------------------------------------

    private void initWebRTC() {
        rootEglBase = EglBase.create();

        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                        .setEnableInternalTracer(true)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory encoderFactory =
                new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory =
                new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        localSurfaceView.init(rootEglBase.getEglBaseContext(), null);
        localSurfaceView.setMirror(true);
        localSurfaceView.setEnableHardwareScaler(true);
        localSurfaceView.setZOrderMediaOverlay(true);

        remoteSurfaceView.init(rootEglBase.getEglBaseContext(), null);
        remoteSurfaceView.setMirror(false);
        remoteSurfaceView.setEnableHardwareScaler(true);

        createLocalMediaTracks();
    }

    private void createLocalMediaTracks() {
        // Video Track
        videoCapturer = createCameraCapturer();
        if (videoCapturer != null) {
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
            videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
            videoCapturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
            videoCapturer.startCapture(1280, 720, 30);

            localVideoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);
            localVideoTrack.addSink(localSurfaceView);
        }

        // Audio Track
        MediaConstraints audioConstraints = new MediaConstraints();
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);
    }

    private CameraVideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(this)
                ? new Camera2Enumerator(this)
                : new Camera1Enumerator(true);

        final String[] deviceNames = enumerator.getDeviceNames();
        // Look for front camera first
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    isFrontCamera = true;
                    return capturer;
                }
            }
        }
        // Fallback to back camera
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    isFrontCamera = false;
                    return capturer;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------
    // PeerConnection Creation
    // ---------------------------------------------------------

    private void createPeerConnection() {
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(new ArrayList<>());
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "onSignalingChange: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: " + iceConnectionState);
                mainHandler.post(() -> {
                    if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                        tvCallStatus.setText("Call Connected (P2P Hotspot)");
                        tvCallStatus.setTextColor(0xFF22C55E);
                    } else if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED ||
                            iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                        tvCallStatus.setText("Call Disconnected");
                        tvCallStatus.setTextColor(0xFFEF4444);
                    }
                });
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: " + iceGatheringState);
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: " + iceCandidate);
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "candidate");
                    json.put("sdpMid", iceCandidate.sdpMid);
                    json.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                    json.put("candidate", iceCandidate.sdp);
                    sendMessage(json.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "Error encoding candidate: " + e.getMessage());
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.d(TAG, "onAddStream: " + mediaStream.videoTracks.size());
                if (!mediaStream.videoTracks.isEmpty()) {
                    VideoTrack remoteTrack = mediaStream.videoTracks.get(0);
                    mainHandler.post(() -> remoteTrack.addSink(remoteSurfaceView));
                }
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {}

            @Override
            public void onDataChannel(DataChannel dataChannel) {}

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                MediaStreamTrack track = rtpReceiver.track();
                if (track instanceof VideoTrack) {
                    mainHandler.post(() -> ((VideoTrack) track).addSink(remoteSurfaceView));
                }
            }

            @Override
            public void onTrack(RtpTransceiver transceiver) {
                MediaStreamTrack track = transceiver.getReceiver().track();
                if (track instanceof VideoTrack) {
                    mainHandler.post(() -> ((VideoTrack) track).addSink(remoteSurfaceView));
                }
            }
        });

        if (peerConnection != null) {
            if (localVideoTrack != null) {
                peerConnection.addTrack(localVideoTrack, Collections.singletonList("stream_1"));
            }
            if (localAudioTrack != null) {
                peerConnection.addTrack(localAudioTrack, Collections.singletonList("stream_1"));
            }
        }
    }

    // ---------------------------------------------------------
    // Offline TCP Signaling Server & Client
    // ---------------------------------------------------------

    private void startSignalingServer() {
        networkExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket(SIGNALING_PORT);
                Log.d(TAG, "Signaling server listening on port " + SIGNALING_PORT);

                while (isServerRunning) {
                    Socket client = serverSocket.accept();
                    Log.d(TAG, "Incoming signaling connection from: " + client.getInetAddress());

                    activeSignalingSocket = client;
                    signalingWriter = new PrintWriter(client.getOutputStream(), true);

                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "Incoming connection from " + client.getInetAddress().getHostAddress(), Toast.LENGTH_SHORT).show();
                        if (!isCallActive) {
                            showInCallUi("Incoming call connected");
                            createPeerConnection();
                        }
                    });

                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        handleSignalingMessage(line);
                    }
                }
            } catch (IOException e) {
                if (isServerRunning) {
                    Log.e(TAG, "Signaling server error: " + e.getMessage());
                }
            }
        });
    }

    private void startCallAsCaller(String peerIp) {
        showInCallUi("Calling " + peerIp + "...");
        createPeerConnection();

        networkExecutor.execute(() -> {
            try {
                Log.d(TAG, "Connecting to signaling server at: " + peerIp + ":" + SIGNALING_PORT);
                activeSignalingSocket = new Socket(peerIp, SIGNALING_PORT);
                signalingWriter = new PrintWriter(activeSignalingSocket.getOutputStream(), true);

                mainHandler.post(() -> {
                    Toast.makeText(MainActivity.this, "Connected to " + peerIp + "! Establishing WebRTC...", Toast.LENGTH_SHORT).show();
                    createOffer();
                });

                BufferedReader reader = new BufferedReader(new InputStreamReader(activeSignalingSocket.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    handleSignalingMessage(line);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to connect to peer: " + e.getMessage());
                mainHandler.post(() -> {
                    Toast.makeText(MainActivity.this, "Failed to connect to " + peerIp + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                    endCall(false);
                });
            }
        });
    }

    private void sendMessage(String message) {
        networkExecutor.execute(() -> {
            if (signalingWriter != null) {
                signalingWriter.println(message);
            }
        });
    }

    private void handleSignalingMessage(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type");

            if ("offer".equals(type)) {
                String sdp = json.getString("sdp");
                mainHandler.post(() -> onRemoteOfferReceived(sdp));
            } else if ("answer".equals(type)) {
                String sdp = json.getString("sdp");
                mainHandler.post(() -> onRemoteAnswerReceived(sdp));
            } else if ("candidate".equals(type)) {
                String sdpMid = json.getString("sdpMid");
                int sdpMLineIndex = json.getInt("sdpMLineIndex");
                String candidate = json.getString("candidate");
                mainHandler.post(() -> {
                    if (peerConnection != null) {
                        peerConnection.addIceCandidate(new IceCandidate(sdpMid, sdpMLineIndex, candidate));
                    }
                });
            } else if ("bye".equals(type)) {
                mainHandler.post(() -> {
                    Toast.makeText(MainActivity.this, "Peer hung up", Toast.LENGTH_SHORT).show();
                    endCall(false);
                });
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing signaling message: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------
    // WebRTC SDP Offer / Answer Handling
    // ---------------------------------------------------------

    private void createOffer() {
        if (peerConnection == null) return;

        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {}

                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "offer");
                            json.put("sdp", sessionDescription.description);
                            sendMessage(json.toString());
                        } catch (JSONException e) {
                            Log.e(TAG, "Error encoding offer: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {}

                    @Override
                    public void onSetFailure(String s) {}
                }, sessionDescription);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "createOffer failed: " + s);
            }

            @Override
            public void onSetFailure(String s) {}
        }, sdpMediaConstraints);
    }

    private void onRemoteOfferReceived(String sdp) {
        if (peerConnection == null) {
            createPeerConnection();
        }

        SessionDescription remoteDesc = new SessionDescription(SessionDescription.Type.OFFER, sdp);
        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {}

            @Override
            public void onSetSuccess() {
                createAnswer();
            }

            @Override
            public void onCreateFailure(String s) {}

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "setRemoteDescription offer failed: " + s);
            }
        }, remoteDesc);
    }

    private void createAnswer() {
        if (peerConnection == null) return;

        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {}

                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "answer");
                            json.put("sdp", sessionDescription.description);
                            sendMessage(json.toString());
                        } catch (JSONException e) {
                            Log.e(TAG, "Error encoding answer: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {}

                    @Override
                    public void onSetFailure(String s) {}
                }, sessionDescription);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "createAnswer failed: " + s);
            }

            @Override
            public void onSetFailure(String s) {}
        }, sdpMediaConstraints);
    }

    private void onRemoteAnswerReceived(String sdp) {
        if (peerConnection == null) return;

        SessionDescription remoteDesc = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {}

            @Override
            public void onSetSuccess() {
                Log.d(TAG, "setRemoteDescription answer successful");
            }

            @Override
            public void onCreateFailure(String s) {}

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "setRemoteDescription answer failed: " + s);
            }
        }, remoteDesc);
    }

    // ---------------------------------------------------------
    // Call Controls & UI Transitions
    // ---------------------------------------------------------

    private void showInCallUi(String statusText) {
        isCallActive = true;
        connectContainer.setVisibility(View.GONE);
        callControls.setVisibility(View.VISIBLE);
        tvCallStatus.setText(statusText);
        tvCallStatus.setTextColor(0xFF38BDF8);
    }

    private void endCall(boolean notifyPeer) {
        if (notifyPeer) {
            try {
                JSONObject bye = new JSONObject();
                bye.put("type", "bye");
                sendMessage(bye.toString());
            } catch (JSONException ignored) {}
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        if (activeSignalingSocket != null && !activeSignalingSocket.isClosed()) {
            try {
                activeSignalingSocket.close();
            } catch (IOException ignored) {}
            activeSignalingSocket = null;
        }
        signalingWriter = null;

        mainHandler.post(() -> {
            isCallActive = false;
            callControls.setVisibility(View.GONE);
            connectContainer.setVisibility(View.VISIBLE);
            remoteSurfaceView.clearImage();
            displayLocalIp();
            Toast.makeText(MainActivity.this, "Call Ended", Toast.LENGTH_SHORT).show();
        });
    }

    private void toggleMic() {
        if (localAudioTrack != null) {
            isMicMuted = !isMicMuted;
            localAudioTrack.setEnabled(!isMicMuted);
            btnToggleMic.setImageResource(isMicMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            btnToggleMic.setBackgroundResource(isMicMuted ? R.drawable.bg_control_circle_active : R.drawable.bg_control_circle);
            Toast.makeText(this, isMicMuted ? "Microphone Muted" : "Microphone Unmuted", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleVideo() {
        if (localVideoTrack != null) {
            isVideoDisabled = !isVideoDisabled;
            localVideoTrack.setEnabled(!isVideoDisabled);
            btnToggleVideo.setImageResource(isVideoDisabled ? R.drawable.ic_videocam_off : R.drawable.ic_videocam);
            btnToggleVideo.setBackgroundResource(isVideoDisabled ? R.drawable.bg_control_circle_active : R.drawable.bg_control_circle);
            localSurfaceContainer.setVisibility(isVideoDisabled ? View.GONE : View.VISIBLE);
            Toast.makeText(this, isVideoDisabled ? "Camera Disabled" : "Camera Enabled", Toast.LENGTH_SHORT).show();
        }
    }

    private void switchCamera() {
        if (videoCapturer != null) {
            videoCapturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                @Override
                public void onCameraSwitchDone(boolean isFront) {
                    isFrontCamera = isFront;
                    localSurfaceView.setMirror(isFront);
                    Toast.makeText(MainActivity.this, isFront ? "Front Camera" : "Back Camera", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onCameraSwitchError(String error) {
                    Toast.makeText(MainActivity.this, "Camera switch error: " + error, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void toggleSpeaker() {
        if (audioManager != null) {
            isSpeakerOn = !isSpeakerOn;
            audioManager.setSpeakerphoneOn(isSpeakerOn);
            btnToggleSpeaker.setBackgroundResource(isSpeakerOn ? R.drawable.bg_control_circle : R.drawable.bg_control_circle_active);
            Toast.makeText(this, isSpeakerOn ? "Speakerphone On" : "Earpiece Audio", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isServerRunning = false;

        endCall(true);

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }

        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException ignored) {}
            videoCapturer.dispose();
            videoCapturer = null;
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        if (localSurfaceView != null) {
            localSurfaceView.release();
        }
        if (remoteSurfaceView != null) {
            remoteSurfaceView.release();
        }
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
        if (rootEglBase != null) {
            rootEglBase.release();
            rootEglBase = null;
        }
    }
}
