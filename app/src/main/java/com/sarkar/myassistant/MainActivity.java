package com.sarkar.myassistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
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
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity for My Assistant
 * Offline P2P audio & video calling over Wi-Fi Hotspot (192.168.43.x) with WebRTC,
 * strict FIFO TCP signaling, smart connection diagnostics, Telegram Admin alerts,
 * and local/remote hardware system controls.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MyAssistant";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int REQUEST_ENABLE_DEVICE_ADMIN = 102;
    private static final int SIGNALING_PORT = 8888;
    private static final String DEFAULT_HOTSPOT_GATEWAY = "192.168.43.1";

    // UI elements
    private SurfaceViewRenderer localSurfaceView;
    private SurfaceViewRenderer remoteSurfaceView;
    private View localSurfaceContainer;
    private ScrollView connectContainer;
    private View callControls;
    private View diagnosticErrorCard;
    private TextView tvDiagnosticReason;
    private TextView tvLocalIpBanner;
    private TextView tvDeviceIp;
    private TextView tvServerStatus;
    private TextView tvActivePeersCount;
    private TextView tvIncomingStatus;
    private TextView tvConnectionHealth;
    private TextView tvTelegramStatus;
    private TextView tvCallStatus;
    private TextView tvCallIceCounters;
    private EditText etPeerIp;
    private EditText etUsername;
    private Button btnStartCall;
    private Button btnFillHotspotIp;
    private Button btnRefreshIp;
    private Button btnRegisterUser;
    private Button btnSyncTelegram;
    private Button btnTorchToggle;
    private Button btnLockScreen;
    private Button btnDeviceStatus;
    private Button btnRemoteTorch;
    private Button btnRemoteStatus;
    private Button btnRemoteLock;
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
    private VideoTrack remoteVideoTrack;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;

    // Strict FIFO ICE & SDP Synchronization
    private final Object iceLock = new Object();
    private final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();
    private boolean isRemoteDescriptionSet = false;
    private int localIceCount = 0;
    private int localIpv4HostCount = 0;
    private int remoteIceCount = 0;
    private String currentIceStateStr = "NEW";
    private boolean byeReceived = false;
    private final Runnable disconnectTimeoutTask = this::handleIceDisconnectTimeout;

    // Audio & Hardware Controls
    private AudioManager audioManager;
    private CameraManager cameraManager;
    private String rearCameraIdForTorch;
    private boolean isTorchOn = false;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName deviceAdminComponent;

    // Threading
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();
    // Single-threaded writer guarantees strict FIFO order (offer/answer before candidates)
    private final ExecutorService signalingWriteExecutor = Executors.newSingleThreadExecutor();

    // State
    private boolean isMicMuted = false;
    private boolean isVideoDisabled = false;
    private boolean isSpeakerOn = true;
    private volatile boolean isCallActive = false;
    private boolean isFrontCamera = true;
    private String localIpAddress = "0.0.0.0";
    private String activePeerIp = "";
    private int activePeersCount = 0;

    // Local TCP Signaling
    private ServerSocket serverSocket;
    private final Object socketLock = new Object();
    private Socket activeSignalingSocket;
    private PrintWriter signalingWriter;
    private volatile boolean isServerRunning = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initSystemServices();
        initViews();
        setupAudio();
        refreshNetworkInfo();

        // Notify Telegram Admin on App Start
        sendTelegramAlert("APP_STARTED", "My Assistant launched on IP " + localIpAddress + ":" + SIGNALING_PORT);

        if (checkPermissions()) {
            startApp();
        } else {
            requestPermissions();
        }
    }

    private void initSystemServices() {
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager != null) {
            try {
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                    Boolean hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    if (Boolean.TRUE.equals(hasFlash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        rearCameraIdForTorch = id;
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Torch lookup error: " + e.getMessage());
            }
        }
        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        deviceAdminComponent = new ComponentName(this, MyAdminReceiver.class);
    }

    private void initViews() {
        localSurfaceView = findViewById(R.id.local_surface_view);
        remoteSurfaceView = findViewById(R.id.remote_surface_view);
        localSurfaceContainer = findViewById(R.id.local_surface_container);
        connectContainer = findViewById(R.id.connect_container);
        callControls = findViewById(R.id.call_controls);
        diagnosticErrorCard = findViewById(R.id.diagnostic_error_card);
        tvDiagnosticReason = findViewById(R.id.tv_diagnostic_reason);
        tvLocalIpBanner = findViewById(R.id.tv_local_ip_banner);
        tvDeviceIp = findViewById(R.id.tv_device_ip);
        tvServerStatus = findViewById(R.id.tv_server_status);
        tvActivePeersCount = findViewById(R.id.tv_active_peers_count);
        tvIncomingStatus = findViewById(R.id.tv_incoming_status);
        tvConnectionHealth = findViewById(R.id.tv_connection_health);
        tvTelegramStatus = findViewById(R.id.tv_telegram_status);
        tvCallStatus = findViewById(R.id.tv_call_status);
        tvCallIceCounters = findViewById(R.id.tv_call_ice_counters);
        etPeerIp = findViewById(R.id.et_peer_ip);
        etUsername = findViewById(R.id.et_username);
        btnStartCall = findViewById(R.id.btn_start_call);
        btnFillHotspotIp = findViewById(R.id.btn_fill_hotspot_ip);
        btnRefreshIp = findViewById(R.id.btn_refresh_ip);
        btnRegisterUser = findViewById(R.id.btn_register_user);
        btnSyncTelegram = findViewById(R.id.btn_sync_telegram);
        btnTorchToggle = findViewById(R.id.btn_torch_toggle);
        btnLockScreen = findViewById(R.id.btn_lock_screen);
        btnDeviceStatus = findViewById(R.id.btn_device_status);
        btnRemoteTorch = findViewById(R.id.btn_remote_torch);
        btnRemoteStatus = findViewById(R.id.btn_remote_status);
        btnRemoteLock = findViewById(R.id.btn_remote_lock);
        btnCopyIp = findViewById(R.id.btn_copy_ip);
        btnToggleMic = findViewById(R.id.btn_toggle_mic);
        btnToggleVideo = findViewById(R.id.btn_toggle_video);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        btnToggleSpeaker = findViewById(R.id.btn_toggle_speaker);
        btnHangup = findViewById(R.id.btn_hangup);

        etUsername.setText(TelegramNotifier.getRegisteredUsername(this));

        btnFillHotspotIp.setOnClickListener(v -> etPeerIp.setText(DEFAULT_HOTSPOT_GATEWAY));

        btnRefreshIp.setOnClickListener(v -> {
            refreshNetworkInfo();
            Toast.makeText(this, "Refreshed IP: " + localIpAddress, Toast.LENGTH_SHORT).show();
        });

        btnCopyIp.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Local IP", localIpAddress));
                Toast.makeText(this, "Copied IP: " + localIpAddress, Toast.LENGTH_SHORT).show();
            }
        });

        btnStartCall.setOnClickListener(v -> {
            String peerIp = etPeerIp.getText().toString().trim();
            if (!NetworkDiagnostics.isValidIpv4(peerIp)) {
                showDiagnosticFailure("Wrong IP address format: \"" + peerIp + "\" (Use e.g. 192.168.43.1)");
                return;
            }
            if (peerIp.equals(localIpAddress)) {
                showDiagnosticFailure("Cannot call your own device IP (" + localIpAddress + ")");
                return;
            }
            startCallAsCaller(peerIp);
        });

        btnRegisterUser.setOnClickListener(v -> {
            String username = etUsername.getText().toString().trim();
            if (username.isEmpty()) {
                Toast.makeText(this, "Enter a username first", Toast.LENGTH_SHORT).show();
                return;
            }
            TelegramNotifier.saveRegisteredUsername(this, username);
            Toast.makeText(this, "Username saved: " + username, Toast.LENGTH_SHORT).show();
            sendTelegramAlert("USERNAME_REGISTERED", "User registered as '" + username + "' on IP " + localIpAddress);
        });

        btnSyncTelegram.setOnClickListener(v -> {
            tvTelegramStatus.setText("Telegram Bot: Syncing Admin Chat ID...");
            TelegramNotifier.syncAdminChatId(this, (success, msg) -> mainHandler.post(() -> {
                tvTelegramStatus.setText("Telegram: " + msg);
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }));
        });

        btnTorchToggle.setOnClickListener(v -> toggleTorch());
        btnLockScreen.setOnClickListener(v -> triggerScreenLock());
        btnDeviceStatus.setOnClickListener(v -> showDeviceStatusDialog());

        btnRemoteTorch.setOnClickListener(v -> sendRemoteCommand("torch_toggle"));
        btnRemoteStatus.setOnClickListener(v -> sendRemoteCommand("device_status"));
        btnRemoteLock.setOnClickListener(v -> sendRemoteCommand("lock_screen"));

        btnHangup.setOnClickListener(v -> endCall(true, "Call ended by user"));
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
                showDiagnosticFailure("Camera & Microphone permissions denied");
                Toast.makeText(this, "Camera and Microphone permissions are required for calling.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startApp() {
        initWebRTC();
        startSignalingServer();
    }

    private void refreshNetworkInfo() {
        localIpAddress = NetworkDiagnostics.getBestLocalIpAddress();
        tvLocalIpBanner.setText("My IP: " + localIpAddress + ":" + SIGNALING_PORT);
        tvDeviceIp.setText("My Hotspot IP: " + localIpAddress);
        if ("0.0.0.0".equals(localIpAddress)) {
            tvConnectionHealth.setText("No Wi-Fi/Hotspot");
            tvConnectionHealth.setTextColor(0xFFF59E0B);
        } else if (!isCallActive) {
            tvConnectionHealth.setText("Ready (LAN)");
            tvConnectionHealth.setTextColor(0xFF38BDF8);
        }
        updateLiveCounters();
    }

    private void updateLiveCounters() {
        mainHandler.post(() -> {
            tvActivePeersCount.setText(String.valueOf(activePeersCount));
            tvCallIceCounters.setText(
                    "Peers: " + activePeersCount
                            + " | Local ICE: " + localIceCount + " (IPv4: " + localIpv4HostCount + ")"
                            + " | Remote ICE: " + remoteIceCount
            );
        });
    }

    private void showDiagnosticFailure(String reason) {
        mainHandler.post(() -> {
            diagnosticErrorCard.setVisibility(View.VISIBLE);
            tvDiagnosticReason.setText(reason);
            tvConnectionHealth.setText("Failed");
            tvConnectionHealth.setTextColor(0xFFEF4444);
        });
    }

    private void clearDiagnosticFailure() {
        mainHandler.post(() -> diagnosticErrorCard.setVisibility(View.GONE));
    }

    private void sendTelegramAlert(String event, String details) {
        TelegramNotifier.sendStatusAlert(this, event, details, (success, msg) ->
                mainHandler.post(() -> tvTelegramStatus.setText("Telegram: " + msg))
        );
    }

    // ---------------------------------------------------------
    // WebRTC Initialization (Hotspot-Optimized)
    // ---------------------------------------------------------

    private void initWebRTC() {
        rootEglBase = EglBase.create();

        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                        .setEnableInternalTracer(true)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        // CRITICAL FIX FOR ANDROID HOTSPOT:
        // disableNetworkMonitor = true allows WebRTC to discover Linux ap0/softap0/wlan1 interfaces
        // (such as 192.168.43.1 on Hotspot Host) that Android ConnectivityManager omits.
        // networkIgnoreMask ignores Cellular so offline calls never route over mobile data.
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableNetworkMonitor = true;
        options.networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR;

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
        remoteSurfaceView.setZOrderMediaOverlay(false);

        createLocalMediaTracks();
    }

    private void createLocalMediaTracks() {
        videoCapturer = createCameraCapturer();
        if (videoCapturer != null) {
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
            videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
            videoCapturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
            videoCapturer.startCapture(1280, 720, 30);

            localVideoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);
            localVideoTrack.addSink(localSurfaceView);
        }

        MediaConstraints audioConstraints = new MediaConstraints();
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);
    }

    private CameraVideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(this)
                ? new Camera2Enumerator(this)
                : new Camera1Enumerator(true);

        final String[] deviceNames = enumerator.getDeviceNames();
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    isFrontCamera = true;
                    return capturer;
                }
            }
        }
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
    // PeerConnection Creation & Strict ICE Synchronization
    // ---------------------------------------------------------

    private void createPeerConnection() {
        synchronized (iceLock) {
            pendingRemoteCandidates.clear();
            isRemoteDescriptionSet = false;
            localIceCount = 0;
            localIpv4HostCount = 0;
            remoteIceCount = 0;
            byeReceived = false;
            currentIceStateStr = "NEW";
        }
        updateLiveCounters();

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(new ArrayList<>());
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        rtcConfig.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "onSignalingChange: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: " + iceConnectionState);
                currentIceStateStr = iceConnectionState.name();

                mainHandler.post(() -> {
                    mainHandler.removeCallbacks(disconnectTimeoutTask);

                    if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED
                            || iceConnectionState == PeerConnection.IceConnectionState.COMPLETED) {
                        activePeersCount = 1;
                        tvCallStatus.setText("Call Connected (P2P Hotspot)");
                        tvCallStatus.setTextColor(0xFF22C55E);
                        tvConnectionHealth.setText("Healthy (P2P)");
                        tvConnectionHealth.setTextColor(0xFF22C55E);
                        tvIncomingStatus.setText("In Call (" + activePeerIp + ")");
                        clearDiagnosticFailure();
                        updateLiveCounters();
                    } else if (iceConnectionState == PeerConnection.IceConnectionState.CHECKING) {
                        tvCallStatus.setText("Negotiating P2P ICE...");
                        tvCallStatus.setTextColor(0xFF38BDF8);
                        tvConnectionHealth.setText("Checking ICE");
                        tvConnectionHealth.setTextColor(0xFF38BDF8);
                    } else if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                        tvCallStatus.setText("Connection Interrupted - Inspecting...");
                        tvCallStatus.setTextColor(0xFFF59E0B);
                        tvConnectionHealth.setText("Interrupted");
                        tvConnectionHealth.setTextColor(0xFFF59E0B);
                        // Allow brief transient recovery; diagnose if not recovered within 3.5s
                        mainHandler.postDelayed(disconnectTimeoutTask, 3500);
                    } else if (iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                        activePeersCount = 0;
                        String reason = NetworkDiagnostics.diagnoseWebRtcFailure(
                                localIpAddress,
                                activePeerIp,
                                localIceCount,
                                localIpv4HostCount,
                                remoteIceCount,
                                isSignalingSocketConnected(),
                                byeReceived
                        );
                        tvCallStatus.setText("Call Disconnected: " + reason);
                        tvCallStatus.setTextColor(0xFFEF4444);
                        showDiagnosticFailure(reason);
                        updateLiveCounters();
                    }
                });
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: " + iceGatheringState);
                if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE && localIpv4HostCount == 0) {
                    mainHandler.post(() -> {
                        String reason = "ICE Candidate gathering failed (No local IPv4 hotspot address)";
                        tvCallStatus.setText("Call Disconnected: " + reason);
                        tvCallStatus.setTextColor(0xFFEF4444);
                        showDiagnosticFailure(reason);
                    });
                }
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: " + iceCandidate.sdp);
                synchronized (iceLock) {
                    localIceCount++;
                    if (iceCandidate.sdp != null && !iceCandidate.sdp.contains(".local")
                            && !iceCandidate.sdp.contains(" 127.") && iceCandidate.sdp.contains(".")) {
                        localIpv4HostCount++;
                    }
                }
                updateLiveCounters();

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
                if (!mediaStream.videoTracks.isEmpty()) {
                    attachRemoteVideoTrack(mediaStream.videoTracks.get(0));
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
                    attachRemoteVideoTrack((VideoTrack) track);
                }
            }

            @Override
            public void onTrack(RtpTransceiver transceiver) {
                MediaStreamTrack track = transceiver.getReceiver().track();
                if (track instanceof VideoTrack) {
                    attachRemoteVideoTrack((VideoTrack) track);
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

    private void attachRemoteVideoTrack(VideoTrack track) {
        mainHandler.post(() -> {
            if (remoteVideoTrack != track) {
                if (remoteVideoTrack != null) {
                    try {
                        remoteVideoTrack.removeSink(remoteSurfaceView);
                    } catch (Exception ignored) {}
                }
                remoteVideoTrack = track;
                remoteVideoTrack.setEnabled(true);
                remoteVideoTrack.addSink(remoteSurfaceView);
            }
        });
    }

    private void handleIceDisconnectTimeout() {
        if (!isCallActive) return;
        if (!"CONNECTED".equals(currentIceStateStr) && !"COMPLETED".equals(currentIceStateStr)) {
            activePeersCount = 0;
            String reason = NetworkDiagnostics.diagnoseWebRtcFailure(
                    localIpAddress,
                    activePeerIp,
                    localIceCount,
                    localIpv4HostCount,
                    remoteIceCount,
                    isSignalingSocketConnected(),
                    byeReceived
            );
            tvCallStatus.setText("Call Disconnected: " + reason);
            tvCallStatus.setTextColor(0xFFEF4444);
            showDiagnosticFailure(reason);
            updateLiveCounters();
        }
    }

    // ---------------------------------------------------------
    // Offline TCP Signaling Server & Client (Strict FIFO)
    // ---------------------------------------------------------

    private void startSignalingServer() {
        serverExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(SIGNALING_PORT));
                Log.d(TAG, "Signaling server listening on port " + SIGNALING_PORT);

                mainHandler.post(() -> {
                    tvServerStatus.setText("● TCP Server listening on port " + SIGNALING_PORT);
                    tvServerStatus.setTextColor(0xFF22C55E);
                    tvIncomingStatus.setText("Listening :8888");
                    tvIncomingStatus.setTextColor(0xFF22C55E);
                });

                while (isServerRunning) {
                    Socket client = serverSocket.accept();
                    String clientIp = client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : "Peer";
                    Log.d(TAG, "Incoming signaling connection from: " + clientIp);

                    networkExecutor.execute(() -> handleAcceptedClient(client, clientIp));
                }
            } catch (IOException e) {
                if (isServerRunning) {
                    Log.e(TAG, "Signaling server error: " + e.getMessage());
                    mainHandler.post(() -> {
                        tvServerStatus.setText("● Server error: " + e.getMessage());
                        tvServerStatus.setTextColor(0xFFEF4444);
                        showDiagnosticFailure("TCP Server bind failed on port " + SIGNALING_PORT + ": " + e.getMessage());
                    });
                }
            }
        });
    }

    private void handleAcceptedClient(Socket client, String clientIp) {
        try {
            client.setTcpNoDelay(true);
            client.setKeepAlive(true);
            setActiveSocket(client);
            activePeerIp = clientIp;

            mainHandler.post(() -> {
                clearDiagnosticFailure();
                tvIncomingStatus.setText("Incoming: " + clientIp);
                tvIncomingStatus.setTextColor(0xFF38BDF8);
                Toast.makeText(MainActivity.this, "Incoming call from " + clientIp, Toast.LENGTH_SHORT).show();
                if (!isCallActive) {
                    showInCallUi("Incoming call from " + clientIp + "...");
                    createPeerConnection();
                }
            });

            readSignalingLoop(client);
        } catch (IOException e) {
            Log.e(TAG, "Accepted client socket error: " + e.getMessage());
            if (isCallActive && !byeReceived) {
                String reason = NetworkDiagnostics.diagnoseSocketFailure(localIpAddress, clientIp, e);
                endCall(false, reason);
            }
        }
    }

    private void startCallAsCaller(String peerIp) {
        refreshNetworkInfo();
        clearDiagnosticFailure();
        activePeerIp = peerIp;
        showInCallUi("Connecting to " + peerIp + ":" + SIGNALING_PORT + "...");
        createPeerConnection();

        sendTelegramAlert("CALL_INITIATED", "Calling peer " + peerIp + " from " + localIpAddress);

        networkExecutor.execute(() -> {
            Socket socket = null;
            try {
                Log.d(TAG, "Connecting to signaling server at: " + peerIp + ":" + SIGNALING_PORT);
                socket = NetworkDiagnostics.createBoundLocalSocket(MainActivity.this, peerIp, SIGNALING_PORT, 6000);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                setActiveSocket(socket);

                mainHandler.post(() -> {
                    tvIncomingStatus.setText("Dialed: " + peerIp);
                    tvIncomingStatus.setTextColor(0xFF38BDF8);
                    tvCallStatus.setText("TCP Linked! Creating SDP Offer...");
                    createOffer();
                });

                readSignalingLoop(socket);
            } catch (IOException e) {
                Log.e(TAG, "Failed to connect to peer: " + e.getMessage());
                String reason = NetworkDiagnostics.diagnoseSocketFailure(localIpAddress, peerIp, e);
                mainHandler.post(() -> {
                    showDiagnosticFailure(reason);
                    Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
                    endCall(false, reason);
                });
            }
        });
    }

    private void setActiveSocket(Socket socket) throws IOException {
        synchronized (socketLock) {
            if (activeSignalingSocket != null && !activeSignalingSocket.isClosed() && activeSignalingSocket != socket) {
                try {
                    activeSignalingSocket.close();
                } catch (IOException ignored) {}
            }
            activeSignalingSocket = socket;
            signalingWriter = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }
    }

    private boolean isSignalingSocketConnected() {
        synchronized (socketLock) {
            return activeSignalingSocket != null && !activeSignalingSocket.isClosed() && activeSignalingSocket.isConnected();
        }
    }

    private void readSignalingLoop(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            handleSignalingMessage(line);
        }
        // EOF reached: peer closed TCP socket
        if (isCallActive && !byeReceived) {
            mainHandler.post(() -> {
                String reason = "Peer closed signaling socket unexpectedly";
                showDiagnosticFailure(reason);
                endCall(false, reason);
            });
        }
    }

    private void sendMessage(String message) {
        signalingWriteExecutor.execute(() -> {
            synchronized (socketLock) {
                if (signalingWriter != null) {
                    signalingWriter.println(message);
                    signalingWriter.flush();
                }
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
                String candidateStr = json.getString("candidate");
                IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, candidateStr);

                mainHandler.post(() -> onRemoteIceCandidateReceived(candidate));
            } else if ("bye".equals(type)) {
                byeReceived = true;
                mainHandler.post(() -> {
                    Toast.makeText(MainActivity.this, "Peer ended the call", Toast.LENGTH_SHORT).show();
                    endCall(false, "Peer ended the call");
                });
            } else if ("cmd".equals(type)) {
                String action = json.optString("action");
                mainHandler.post(() -> executeRemoteCommand(action));
            } else if ("cmd_result".equals(type)) {
                String result = json.optString("result");
                mainHandler.post(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Remote Peer Response")
                        .setMessage(result)
                        .setPositiveButton("OK", null)
                        .show());
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing signaling message: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------
    // WebRTC SDP Offer / Answer & Strict FIFO Candidate Drain
    // ---------------------------------------------------------

    private void onRemoteIceCandidateReceived(IceCandidate candidate) {
        synchronized (iceLock) {
            remoteIceCount++;
            if (peerConnection == null || !isRemoteDescriptionSet) {
                pendingRemoteCandidates.add(candidate);
                Log.d(TAG, "Queued remote ICE candidate (awaiting setRemoteDescription). Queue size: " + pendingRemoteCandidates.size());
            } else {
                peerConnection.addIceCandidate(candidate);
            }
        }
        updateLiveCounters();
    }

    private void drainPendingIceCandidates() {
        synchronized (iceLock) {
            isRemoteDescriptionSet = true;
            if (peerConnection != null && !pendingRemoteCandidates.isEmpty()) {
                Log.d(TAG, "Draining " + pendingRemoteCandidates.size() + " queued remote ICE candidates");
                for (IceCandidate candidate : pendingRemoteCandidates) {
                    peerConnection.addIceCandidate(candidate);
                }
                pendingRemoteCandidates.clear();
            }
        }
    }

    private void createOffer() {
        if (peerConnection == null) return;

        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription localSdp) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}

                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "offer");
                            json.put("sdp", localSdp.description);
                            sendMessage(json.toString());
                        } catch (JSONException e) {
                            Log.e(TAG, "Error encoding offer: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {}

                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "setLocalDescription offer failed: " + s);
                    }
                }, localSdp);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "createOffer failed: " + s);
                mainHandler.post(() -> showDiagnosticFailure("SDP Offer creation failed: " + s));
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
                drainPendingIceCandidates();
                createAnswer();
            }

            @Override
            public void onCreateFailure(String s) {}

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "setRemoteDescription offer failed: " + s);
                mainHandler.post(() -> showDiagnosticFailure("SDP Offer parse failed: " + s));
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
            public void onCreateSuccess(SessionDescription localSdp) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}

                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "answer");
                            json.put("sdp", localSdp.description);
                            sendMessage(json.toString());
                        } catch (JSONException e) {
                            Log.e(TAG, "Error encoding answer: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {}

                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "setLocalDescription answer failed: " + s);
                    }
                }, localSdp);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "createAnswer failed: " + s);
                mainHandler.post(() -> showDiagnosticFailure("SDP Answer creation failed: " + s));
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
                Log.d(TAG, "setRemoteDescription answer successful - draining ICE queue");
                drainPendingIceCandidates();
            }

            @Override
            public void onCreateFailure(String s) {}

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "setRemoteDescription answer failed: " + s);
                mainHandler.post(() -> showDiagnosticFailure("SDP Answer failed: " + s));
            }
        }, remoteDesc);
    }

    // ---------------------------------------------------------
    // Hardware & Remote System Commands
    // ---------------------------------------------------------

    private String toggleTorch() {
        if (cameraManager == null || rearCameraIdForTorch == null) {
            String msg = "Flashlight hardware not available on this device";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return msg;
        }
        try {
            boolean nextState = !isTorchOn;
            cameraManager.setTorchMode(rearCameraIdForTorch, nextState);
            isTorchOn = nextState;
            btnTorchToggle.setText(isTorchOn ? "Torch: ON" : "Torch: OFF");
            String msg = "Flashlight turned " + (isTorchOn ? "ON" : "OFF");
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return msg;
        } catch (Exception e) {
            String msg = isCallActive && !isFrontCamera
                    ? "Rear camera is streaming video. Flip to Front camera to use Torch."
                    : "Torch error: " + e.getMessage();
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return msg;
        }
    }

    private String triggerScreenLock() {
        if (devicePolicyManager != null && devicePolicyManager.isAdminActive(deviceAdminComponent)) {
            devicePolicyManager.lockNow();
            return "Screen locked successfully";
        } else {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Enable Device Admin to allow My Assistant Screen Lock control.");
            startActivityForResult(intent, REQUEST_ENABLE_DEVICE_ADMIN);
            return "Prompted user to enable Screen Lock Device Admin";
        }
    }

    private void showDeviceStatusDialog() {
        refreshNetworkInfo();
        String status = NetworkDiagnostics.collectDeviceStatus(
                this,
                localIpAddress,
                isTorchOn,
                currentIceStateStr,
                activePeersCount
        );
        new AlertDialog.Builder(this)
                .setTitle("Device & Network Status")
                .setMessage(status)
                .setPositiveButton("OK", null)
                .setNeutralButton("Send to Telegram", (d, w) ->
                        sendTelegramAlert("DEVICE_STATUS_CHECK", status))
                .show();
    }

    private void sendRemoteCommand(String action) {
        if (!isSignalingSocketConnected()) {
            Toast.makeText(this, "No active peer signaling socket", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("type", "cmd");
            cmd.put("action", action);
            sendMessage(cmd.toString());
            Toast.makeText(this, "Sent remote command: " + action, Toast.LENGTH_SHORT).show();
        } catch (JSONException ignored) {}
    }

    private void executeRemoteCommand(String action) {
        String result;
        if ("torch_toggle".equals(action)) {
            result = toggleTorch();
        } else if ("lock_screen".equals(action)) {
            result = triggerScreenLock();
        } else if ("device_status".equals(action)) {
            refreshNetworkInfo();
            result = NetworkDiagnostics.collectDeviceStatus(
                    this, localIpAddress, isTorchOn, currentIceStateStr, activePeersCount);
        } else {
            result = "Unknown command: " + action;
        }
        try {
            JSONObject resp = new JSONObject();
            resp.put("type", "cmd_result");
            resp.put("result", result);
            sendMessage(resp.toString());
        } catch (JSONException ignored) {}
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
        updateLiveCounters();
    }

    private void endCall(boolean notifyPeer, String reason) {
        mainHandler.removeCallbacks(disconnectTimeoutTask);

        if (notifyPeer) {
            try {
                JSONObject bye = new JSONObject();
                bye.put("type", "bye");
                sendMessage(bye.toString());
            } catch (JSONException ignored) {}
        }

        if (remoteVideoTrack != null) {
            try {
                remoteVideoTrack.removeSink(remoteSurfaceView);
            } catch (Exception ignored) {}
            remoteVideoTrack = null;
        }

        if (peerConnection != null) {
            try {
                peerConnection.close();
                peerConnection.dispose();
            } catch (Exception ignored) {}
            peerConnection = null;
        }

        synchronized (iceLock) {
            pendingRemoteCandidates.clear();
            isRemoteDescriptionSet = false;
        }

        synchronized (socketLock) {
            if (activeSignalingSocket != null && !activeSignalingSocket.isClosed()) {
                try {
                    activeSignalingSocket.close();
                } catch (IOException ignored) {}
                activeSignalingSocket = null;
            }
            signalingWriter = null;
        }

        mainHandler.post(() -> {
            isCallActive = false;
            activePeersCount = 0;
            activePeerIp = "";
            callControls.setVisibility(View.GONE);
            connectContainer.setVisibility(View.VISIBLE);
            remoteSurfaceView.clearImage();
            tvIncomingStatus.setText("Listening :8888");
            tvIncomingStatus.setTextColor(0xFF22C55E);
            refreshNetworkInfo();
            if (reason != null && !reason.isEmpty() && !reason.equals("Call ended by user") && !reason.equals("Peer ended the call")) {
                showDiagnosticFailure(reason);
            }
            Toast.makeText(MainActivity.this, reason != null ? reason : "Call Ended", Toast.LENGTH_SHORT).show();
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

        endCall(true, null);

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
