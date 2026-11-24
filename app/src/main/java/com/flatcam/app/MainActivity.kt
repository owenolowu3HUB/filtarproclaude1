package com.flatcam.app

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.text.TextUtils
import android.util.Rational
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewGroup
import kotlin.math.exp
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * FilTarPro by filmtarget - a manual / flat-profile cinema camera built on the public Camera2 API.
 * It only talks to the camera through this app's own session, so it cannot change the
 * phone's native camera app or any system setting.
 */
class MainActivity : Activity() {

    data class PhotoOpt(val size: Size, val maxRes: Boolean)
    private data class RecCfg(val size: Size, val fps: Int, val hevc: Boolean)
    private class Pill(val box: LinearLayout, val value: TextView)

    // ---------------------------------------------------------------- colours
    private val amber = Color.parseColor("#FFB300")
    private val red = Color.parseColor("#FF3B30")
    private val glass = 0x70000000
    private val hair = 0x33FFFFFF
    private val ui = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- views
    private lateinit var cm: CameraManager
    private lateinit var tv: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var gridView: ThirdsGrid
    private lateinit var hist: HistogramView
    private lateinit var recBtn: RecordButton
    private lateinit var timerTv: TextView
    private lateinit var fmtChip: TextView
    private lateinit var afChip: TextView
    private lateinit var segVideo: TextView
    private lateinit var segPhoto: TextView
    private lateinit var nightBtn: TextView
    private lateinit var lensBtn: TextView
    private lateinit var flashView: View
    private lateinit var strip: LinearLayout
    private lateinit var stripLabel: TextView
    private lateinit var stripSeek: SeekBar
    private lateinit var stripMode: TextView
    private lateinit var stripFocusRow: LinearLayout
    private lateinit var settings: ScrollView
    private lateinit var settingsLayer: FrameLayout
    private lateinit var settingsHost: LinearLayout
    private var gridLayer: FrameLayout? = null
    private var gridOpen = false
    private lateinit var aspectBox: LinearLayout
    private lateinit var aspectVal: TextView
    private var pendingZoom = 0f
    private lateinit var rawSwitch: Switch
    private lateinit var lowBoostRow: View
    private lateinit var videoChip: TextView
    private lateinit var photoChip: TextView
    private lateinit var fpsChip: TextView
    private lateinit var vu: VuView
    private lateinit var meterBtn: TextView
    private lateinit var audioChip: TextView
    private lateinit var audioRowChip: TextView
    private lateinit var thumbView: ImageView
    private lateinit var presetScroll: HorizontalScrollView
    private lateinit var presetRow: LinearLayout
    private lateinit var stripLock: TextView
    private lateinit var meterCard: LinearLayout
    private lateinit var meterText: TextView
    private lateinit var rackChip: TextView
    private val markChips = arrayOfNulls<TextView>(3)
    private val pills = HashMap<Int, Pill>()
    private lateinit var ctl: FrameLayout
    private lateinit var flipBtn: TextView
    private lateinit var settingsBtn: TextView
    private lateinit var padChip: TextView
    private lateinit var timerCol: LinearLayout
    private lateinit var segBox: LinearLayout
    private lateinit var stripBox: LinearLayout
    private lateinit var stripTop: LinearLayout
    private lateinit var stripChips: LinearLayout
    private var portraitUi: Boolean? = null

    // ---------------------------------------------------------------- threading
    private var bgThread: HandlerThread? = null
    private lateinit var bg: Handler
    private val executor = Executor { r -> bg.post(r) }

    // ---------------------------------------------------------------- camera objects
    private var ids: List<String> = emptyList()
    private var backIds: List<String> = emptyList()
    private var frontIds: List<String> = emptyList()
    private var curFacing = 0          // 0 back, 1 front
    private var idIdx = 0
    private var openedId: String? = null
    private var switching = false
    private var mirrorPreview = true
    private lateinit var chars: CameraCharacteristics
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var previewSize = Size(1280, 720)
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var maxReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null

    // ---------------------------------------------------------------- discovered options
    private var videoSizes: List<Size> = emptyList()
    private var photoOpts: List<PhotoOpt> = emptyList()
    private var rawSize: Size? = null
    private var maxAfRegions = 0
    private var faceMode = 0
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var hasLowBoost = false
    private val fpsOpts = intArrayOf(24, 30, 60)
    private val nightFrames = intArrayOf(6, 8, 12)

    // ---------------------------------------------------------------- user state
    private var videoMode = true
    private var recording = false
    private var videoIdx = 0
    private var photoIdx = 0
    private var fpsIdx = 1
    private var rawOn = false
    private var compat264 = false
    private var expMode = 0            // 0 auto, 1 cine auto-ISO, 2 manual
    private var manualFocus = false
    private var manualWb = false
    private var isoP = 0
    private var shutP = 500
    private var focusP = 0
    private var kelvinP = 437
    private var evP = 500
    private var profile = 1            // 0 standard, 1 flat, 2 log-like
    private var edgeLvl = 0
    private var nrLvl = 0
    private var ois = true
    private var eis = false
    private var lowBoost = false
    private var nightOn = false
    private var nightIdx = 1
    private var faceAf = true
    private var showGrid = false
    private var showHist = true
    private var zoom = 1f
    private var selected = 0
    private var retried = false
    private var curRec: RecCfg? = null
    private var recStartMs = 0L

    // ---- locks, focus marks, tap-focus, meter, audio
    private var lockExp = false
    private var lockWb = false
    private var lockEv = false
    private var lockFocus = false
    private val marks = intArrayOf(-1, -1, -1)
    private val markPts = arrayOfNulls<PointF>(3)     // sensor coordinates of the subject a mark was set on
    private var markNext = 0
    private var tappedSensor: PointF? = null
    private val rackOpts = intArrayOf(300, 700, 1500, 3000, 5000, 8000)
    private var rackIdx = 2
    private var rackCurve = 1                          // 0 soft, 1 cine, 2 linear
    private val satOpts = floatArrayOf(1f, 0.8f, 0.6f, 0.45f)
    private var satIdx = 2
    private var wbAutoHold = false
    private var awbSince = 0L
    private var rackAnim: ValueAnimator? = null
    private var flickerHz = 50
    private var afScan = false
    private var scanStart = 0L
    private var waitSettle = false
    private var settledSince = 0L
    private var tapTime = 0L
    private var pointRect: RectF? = null
    @Volatile private var lastFocusDist = 0f
    @Volatile private var lastAperture = 0f
    private var maxAeRegions = 0
    private var aeCompOk = false
    private var analogMax = 0
    private var meterArmed = false
    @Volatile private var meterPhase = 0       // 0 idle, 1 metering, 2 finishing
    private var meterRegion: Rect? = null
    private var meterFace = false
    private var meterStart = 0L
    @Volatile private var meterConv = 0
    private var meterIso = 100
    private var meterNs = 16_000_000L
    private var facesSensor: List<Rect> = emptyList()
    private var prevEff = 0
    private var audioSel = -1                  // -1 auto, 0 phone mic, else device id
    private var usedAudioMode = 1
    private var scoOn = false
    private val audioMgr by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // ---------------------------------------------------------------- live camera readouts
    @Volatile private var lastIso = 100
    @Volatile private var lastExpNs = 10_000_000L
    @Volatile private var faceRects: List<Rect> = emptyList()
    @Volatile private var lastGains: RggbChannelVector? = null
    @Volatile private var lastTransform: ColorSpaceTransform? = null
    private var wbBase: RggbChannelVector? = null

    // ---------------------------------------------------------------- cine auto-ISO state
    private var autoE = 0.0
    private var cineIso = 100
    private var cineShutNs = 16_000_000L
    private var wide = false

    // ---------------------------------------------------------------- focus / tracking state
    @Volatile private var afRegion: MeteringRectangle? = null
    private var appliedRegion: Rect? = null
    private var lastAfUpdate = 0L
    private var afMode = 0             // 0 auto face, 1 face lock, 2 object lock
    private var lockCenter: PointF? = null
    private var primaryCenter: PointF? = null
    private var lastLockRegion: Rect? = null
    private var lockRect: RectF? = null
    private var lostTicks = 0
    private var faceViewRects: List<RectF> = emptyList()
    private val imgRect = RectF()
    private var gbmp: Bitmap? = null
    private var gpx = IntArray(0)
    private var gray = IntArray(0)
    private var gw = 0
    private var gh = 0
    private var tickN = 0

    // ---------------------------------------------------------------- still capture state
    private var pendingRaw: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var awaitingRaw = false
    private var burstExpected = 0
    private val burstFrames = ArrayList<ByteArray>()
    private var burstOrient = 0

    private val fps get() = if (recording) (curRec?.fps ?: fpsOpts[fpsIdx]) else fpsOpts[fpsIdx]

    // =====================================================================
    // Lifecycle
    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        fun facing(id: String) = try { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) } catch (_: Exception) { null }
        backIds = cm.cameraIdList.filter { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
        frontIds = cm.cameraIdList.filter { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
        if (backIds.isEmpty() && frontIds.isEmpty()) { toast("No camera found"); finish(); return }
        curFacing = if (backIds.isNotEmpty()) 0 else 1
        ids = if (curFacing == 0) backIds else frontIds
        buildUi()
        var pin = prefs.getString("pin", null)
        if (pin == null) { pin = "%04d".format((Math.random() * 10000).toInt()); prefs.edit().putString("pin", pin).apply() }
        remote = RemoteServer(this, pin, { c -> onRemote(c) }, { ui.post { lastState = ""; updateMonChip() } })
        remote.start()
        applyMonitorSettings()
    }

    override fun onDestroy() {
        try { remote.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("cam").also { it.start() }
        bg = Handler(bgThread!!.looper)
        ui.post(tick)
        monThread = HandlerThread("mon").also { it.start() }
        monH = Handler(monThread!!.looper)
        monBusy.set(false)
        ui.post(monLoop)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(levelL, it, SensorManager.SENSOR_DELAY_UI) }
        try { dispMgr.registerDisplayListener(dispListener, ui) } catch (_: Exception) {}
        try { inputMgr.registerInputDeviceListener(inputListener, ui) } catch (_: Exception) {}
        refreshPad()
        ui.post { onRotationChanged() }
        Thread { MediaRepo.query(this, false).firstOrNull()?.let { loadThumb(it.uri) } }.start()
        openIfReady()
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        ui.removeCallbacks(monLoop)
        try { sm.unregisterListener(levelL) } catch (_: Exception) {}
        monThread?.quitSafely(); monThread = null; monH = null
        try { dispMgr.unregisterDisplayListener(dispListener) } catch (_: Exception) {}
        try { inputMgr.unregisterInputDeviceListener(inputListener) } catch (_: Exception) {}
        rackAnim?.cancel()
        if (recording) stopRecordingQuiet()
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null
        super.onPause()
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        onRotationChanged()
    }

    private val dispMgr by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    private val dispListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { onRotationChanged() }
    }

    @Suppress("DEPRECATION")
    private fun displayRot(): Int = windowManager.defaultDisplay.rotation

    private fun isPortrait(): Boolean {
        val pt = android.graphics.Point()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(pt)
        return pt.y > pt.x
    }

    /** Phone turned: re-arrange every control for portrait or landscape and re-fit the preview. */
    private fun onRotationChanged() {
        if (!::ctl.isInitialized) return
        val p = isPortrait()
        if (p != portraitUi) applyLayout(p)
        configureTransform()
        applyAspect()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == 2) {
            if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) toast(if (remote.startBluetooth()) "Bluetooth link on" else "Turn Bluetooth on first")
            else toast("Bluetooth permission is needed for the Bluetooth link")
            return
        }
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) openIfReady()
        else toast("Camera and microphone permission are needed")
    }

    private fun openIfReady() {
        if (!::tv.isInitialized || !tv.isAvailable || device != null || bgThread == null) return
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        openCamera()
    }

    // =====================================================================
    // UI construction
    // =====================================================================
    private fun round(color: Int, r: Int = 12, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun label(text: String, size: Float = 12f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun chip(text: String, onClick: (TextView) -> Unit) = label(text, 11f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = round(glass, 14, hair)
        setOnClickListener { onClick(this) }
    }

    private fun lp(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)

    private fun ll(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(this)
        root.addView(tv, lp(-1, -1))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openIfReady()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = configureTransform()
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> configureTransform() }
        gridView = ThirdsGrid(this).apply { visibility = View.GONE }
        root.addView(gridView, lp(-1, -1))
        overlay = OverlayView(this)
        root.addView(overlay, lp(-1, -1))
        setupGestures()
        flashView = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        root.addView(flashView, lp(-1, -1))
        ctl = FrameLayout(this)
        root.addView(ctl, lp(-1, -1))

        makeWidgets()
        setContentView(root)
        applyLayout(isPortrait())
        selectParam(0)
        updateMarkChips()
    }

    /** Every control is created once; applyLayout() only decides where it sits. */
    private fun makeWidgets() {
        timerTv = label("", 13f, red, true).apply { typeface = Typeface.MONOSPACE }
        vu = VuView(this)
        timerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(timerTv)
            addView(vu, ll(dp(72), dp(6)).apply { topMargin = dp(2) })
        }
        segVideo = label("VIDEO", 11f, Color.BLACK, true).apply { gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6)); setOnClickListener { setMode(true) } }
        segPhoto = label("PHOTO", 11f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6)); setOnClickListener { setMode(false) } }
        segBox = LinearLayout(this).apply { background = round(glass, 16, hair); addView(segVideo); addView(segPhoto) }
        fmtChip = label("", 10f, Color.WHITE, true).apply { setPadding(dp(10), 0, dp(6), 0); typeface = Typeface.MONOSPACE; maxLines = 1 }
        afChip = chip("AF") { _ ->
            if (afMode != 0 || lockFocus || manualFocus) releaseLock()
            else toast("Tap a face or object to focus there. Long-press to go back to auto face focus.")
        }
        meterBtn = chip("METER") { toggleMeter() }
        audioChip = chip("MIC") { cycleAudio() }
        padChip = chip("PAD") { showPadHelp() }.apply { visibility = View.GONE }
        for (c in listOf(afChip, meterBtn, audioChip, padChip)) { c.maxLines = 1; c.ellipsize = TextUtils.TruncateAt.END }
        hist = HistogramView(this)
        monChip = chip("MON") { showMonitorDialog() }
        markTv = label("FilTarpro by filmtarget", 9f, Color.WHITE, false).apply { alpha = 0.38f; letterSpacing = 0.08f }
        styleSeg()

        for ((id, name) in listOf(1 to "ISO", 2 to "SHUTTER", 3 to "FOCUS", 4 to "WB", 5 to "EV")) pills[id] = makePill(id, name)
        aspectBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(5)); background = round(glass, 10, hair)
            setOnClickListener { showAspectPanel() }
        }
        aspectBox.addView(label("ASPECT", 9f, 0xFFB0B0B0.toInt()).apply { letterSpacing = 0.12f })
        aspectVal = label("FULL", 13f, Color.WHITE, true).apply { typeface = Typeface.MONOSPACE; maxLines = 1 }
        aspectBox.addView(aspectVal)

        nightBtn = chip("NIGHT") { b ->
            nightOn = !nightOn
            if (nightOn && photoOpts.getOrNull(photoIdx)?.maxRes == true) {
                photoIdx = photoOpts.indexOfFirst { !it.maxRes }.coerceAtLeast(0)
                startPreviewSession()
            }
            b.background = round(if (nightOn) amber else glass, 14, hair)
            b.setTextColor(if (nightOn) Color.BLACK else Color.WHITE)
            if (nightOn) toast("Night stack on: hold still for about 1 second")
        }
        nightBtn.visibility = View.GONE
        recBtn = RecordButton(this).apply { setOnClickListener { onShutter() } }
        lensBtn = chip("LENS 1") { _ -> showLensPanel() }
        flipBtn = chip("FRONT") { _ -> flipCamera() }
        if (backIds.isEmpty() || frontIds.isEmpty()) flipBtn.visibility = View.GONE
        settingsBtn = chip("SETTINGS") { _ -> setSettingsOpen(settingsLayer.visibility != View.VISIBLE) }
        for (c in listOf(lensBtn, flipBtn, settingsBtn, nightBtn)) c.gravity = Gravity.CENTER

        thumbView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(glass, 10, 0x88FFFFFF.toInt())
            clipToOutline = true
            setOnClickListener { startActivity(Intent(this@MainActivity, GalleryActivity::class.java)) }
        }

        // bottom: presets + slider strip
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presetRow); visibility = View.GONE }
        strip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = round(glass, 18, hair); visibility = View.GONE
        }
        stripLabel = label("", 12f, Color.WHITE, true).apply { typeface = Typeface.MONOSPACE }
        stripSeek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(amber)
            thumbTintList = ColorStateList.valueOf(amber)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onSliderChanged(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        stripTop = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(stripLabel, ll(dp(100), -2))
            addView(stripSeek, ll(0, -2, 1f))
        }
        stripFocusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        for (i in 0..2) {
            val c = chip("ABC"[i].toString()) { _ -> markTap(i) }
            c.setPadding(dp(8), dp(6), dp(8), dp(6))
            c.setOnLongClickListener { markSet(i); true }
            markChips[i] = c
            stripFocusRow.addView(c, ll(-2, -2).apply { leftMargin = dp(3) })
        }
        rackChip = chip(rackText()) { _ -> rackIdx = (rackIdx + 1) % rackOpts.size; updateRackUi() }
        rackChip.setPadding(dp(8), dp(6), dp(8), dp(6))
        stripFocusRow.addView(rackChip, ll(-2, -2).apply { leftMargin = dp(3) })
        stripLock = chip("LOCK") { onLockClick() }
        stripMode = chip("AUTO") { onStripMode() }
        stripChips = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(stripFocusRow)
            addView(stripLock, ll(-2, -2).apply { leftMargin = dp(6) })
            addView(stripMode, ll(-2, -2).apply { leftMargin = dp(4) })
        }
        stripBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stripBox.addView(presetScroll, ll(-1, -2).apply { bottomMargin = dp(6) })
        stripBox.addView(strip, ll(-1, -2))

        meterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = round(0xDD101114.toInt(), 16, 0x66FFFFFF); visibility = View.GONE
        }
        meterText = label("", 12f, Color.WHITE, true).apply { typeface = Typeface.MONOSPACE }
        meterCard.addView(meterText)
        val mrow = LinearLayout(this)
        mrow.addView(chip("APPLY") { applyMeter(false) })
        mrow.addView(chip("APPLY + LOCK") { applyMeter(true) }, ll(-2, -2).apply { leftMargin = dp(6) })
        mrow.addView(chip("CLOSE") { closeMeter() }, ll(-2, -2).apply { leftMargin = dp(6) })
        meterCard.addView(mrow, ll(-2, -2).apply { topMargin = dp(8) })

        buildSettings()
    }

    private fun detach(vararg vs: View) { for (v in vs) (v.parent as? ViewGroup)?.removeView(v) }

    private fun applyLayout(portrait: Boolean) {
        portraitUi = portrait
        val all = ArrayList<View>()
        all.addAll(listOf(timerCol, segBox, fmtChip, afChip, meterBtn, audioChip, padChip, hist, nightBtn, recBtn,
            lensBtn, flipBtn, settingsBtn, thumbView, stripBox, meterCard, settingsLayer, stripTop, stripChips, monChip, markTv, aspectBox))
        gridLayer?.let { all.add(it) }
        for (p in pills.values) all.add(p.box)
        detach(*all.toTypedArray())
        ctl.removeAllViews()
        // slider strip: one row in landscape, two rows in portrait
        strip.removeAllViews()
        if (portrait) {
            strip.orientation = LinearLayout.VERTICAL
            strip.addView(stripTop, ll(-1, -2))
            strip.addView(stripChips, ll(-1, -2).apply { topMargin = dp(6) })
        } else {
            strip.orientation = LinearLayout.HORIZONTAL
            strip.addView(stripTop, ll(0, -2, 1f))
            strip.addView(stripChips, ll(-2, -2).apply { leftMargin = dp(8) })
        }
        if (portrait) layoutPortrait() else layoutLandscape()
        gridLayer?.let { if (gridOpen) ctl.addView(it, lp(-1, -1)) }
        refreshFlipUi()
    }

    private fun topGradient() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))

    private fun layoutLandscape() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(16)); background = topGradient()
        }
        top.addView(timerCol, ll(dp(84), -2))
        top.addView(segBox)
        top.addView(monChip, ll(-2, -2).apply { leftMargin = dp(8) })
        top.addView(fmtChip, ll(0, -2, 1f))
        for (c in listOf(afChip, meterBtn, audioChip, padChip)) top.addView(c, ll(-2, -2).apply { leftMargin = dp(6) })
        top.addView(hist, ll(dp(90), dp(34)).apply { leftMargin = dp(10) })
        ctl.addView(top, lp(-1, -2, Gravity.TOP))

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (id in 1..5) left.addView(pills[id]!!.box, ll(dp(78), -2).apply { bottomMargin = dp(6) })
        left.addView(aspectBox, ll(dp(78), -2).apply { bottomMargin = dp(6) })
        ctl.addView(left, lp(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(12); topMargin = dp(26) })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        right.addView(nightBtn, ll(dp(76), -2).apply { bottomMargin = dp(8) })
        right.addView(recBtn, ll(dp(74), dp(74)))
        right.addView(flipBtn, ll(dp(76), -2).apply { topMargin = dp(10) })
        right.addView(lensBtn, ll(dp(76), -2).apply { topMargin = dp(6) })
        right.addView(settingsBtn, ll(dp(76), -2).apply { topMargin = dp(6) })
        ctl.addView(right, lp(-2, -2, Gravity.END or Gravity.TOP).apply { rightMargin = dp(14); topMargin = dp(44) })

        ctl.addView(thumbView, lp(dp(50), dp(50), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(27); bottomMargin = dp(8) })
        val sw = resources.displayMetrics.widthPixels
        ctl.addView(stripBox, lp(min(dp(620), sw - dp(250)), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
        ctl.addView(markTv, lp(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(14); bottomMargin = dp(8) })
        ctl.addView(meterCard, lp(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(70) })
        ctl.addView(settingsLayer, lp(-1, -1)); settingsHost.layoutParams = FrameLayout.LayoutParams(min(dp(380), resources.displayMetrics.widthPixels - dp(40)), -1, Gravity.END).apply { rightMargin = dp(16); topMargin = dp(12); bottomMargin = dp(12) }
    }

    private fun layoutPortrait() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(16)); background = topGradient()
        }
        val r1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        r1.addView(timerCol, ll(dp(84), -2))
        r1.addView(segBox)
        r1.addView(View(this), ll(0, 1, 1f))
        r1.addView(monChip)
        r1.addView(View(this), ll(0, 1, 1f))
        r1.addView(hist, ll(dp(84), dp(32)))
        val r2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        r2.addView(afChip, ll(0, -2, 1.5f))
        r2.addView(meterBtn, ll(0, -2, 1f).apply { leftMargin = dp(5) })
        r2.addView(audioChip, ll(0, -2, 1.3f).apply { leftMargin = dp(5) })
        r2.addView(padChip, ll(0, -2, 0.8f).apply { leftMargin = dp(5) })
        top.addView(r1, ll(-1, -2))
        top.addView(r2, ll(-1, -2).apply { topMargin = dp(8) })
        top.addView(fmtChip, ll(-1, -2).apply { topMargin = dp(6) })
        top.addView(markTv, ll(-2, -2).apply { topMargin = dp(2); leftMargin = dp(10) })
        ctl.addView(top, lp(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), dp(12)) }
        val pr = LinearLayout(this)
        for (id in 1..5) pr.addView(pills[id]!!.box, ll(0, -2, 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        pr.addView(aspectBox, ll(0, -2, 1.15f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        bottom.addView(pr, ll(-1, -2).apply { bottomMargin = dp(8) })
        bottom.addView(stripBox, ll(-1, -2))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(thumbView, ll(dp(54), dp(54)))
        row.addView(View(this), ll(0, 1, 1f))
        row.addView(recBtn, ll(dp(74), dp(74)))
        row.addView(View(this), ll(0, 1, 1f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(flipBtn, ll(dp(76), -2))
        col.addView(lensBtn, ll(dp(76), -2).apply { topMargin = dp(6) })
        row.addView(col)
        bottom.addView(row, ll(-1, -2).apply { topMargin = dp(10) })
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        row2.addView(nightBtn, ll(dp(90), -2))
        row2.addView(settingsBtn, ll(dp(110), -2).apply { leftMargin = dp(8) })
        bottom.addView(row2, ll(-1, -2).apply { topMargin = dp(8) })
        ctl.addView(bottom, lp(-1, -2, Gravity.BOTTOM))

        ctl.addView(meterCard, lp(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(124) })
        ctl.addView(settingsLayer, lp(-1, -1)); settingsHost.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(10); rightMargin = dp(10); topMargin = dp(40); bottomMargin = dp(20) }
    }

    // ---- modal helpers ------------------------------------------------------
    private fun modalOpen() = (::settingsLayer.isInitialized && settingsLayer.visibility == View.VISIBLE) || gridOpen

    private fun setSettingsOpen(open: Boolean) {
        if (open) closeGrid()
        settingsLayer.visibility = if (open) View.VISIBLE else View.GONE
        if (open) { settingsLayer.bringToFront(); settings.scrollTo(0, 0) }
    }

    private fun closeGrid() {
        gridOpen = false
        gridLayer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        gridLayer = null
    }

    /** A pick-from-a-grid panel that sits above everything and swallows touches until BACK is pressed. */
    private fun showGridPanel(title: String, cells: List<View>, cols: Int, extra: View? = null) {
        closeGrid()
        val layer = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true; isFocusable = true }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(0xF0101114.toInt(), 18, hair); setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(chip("‹  BACK") { closeGrid() }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)); setTextColor(Color.BLACK); background = round(amber, 14) })
        head.addView(label(title, 13f, Color.WHITE, true).apply { letterSpacing = 0.15f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head, LinearLayout.LayoutParams(-1, -2))
        extra?.let { card.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        var row: LinearLayout? = null
        for ((i, c) in cells.withIndex()) {
            if (i % cols == 0) { row = LinearLayout(this); col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
            row!!.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
        val rem = cells.size % cols
        if (rem != 0) repeat(cols - rem) { row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) }) }
        val sv = ScrollView(this).apply { addView(col) }
        card.addView(sv, LinearLayout.LayoutParams(-1, -2))
        val w = min(dp(560), resources.displayMetrics.widthPixels - dp(32))
        layer.addView(card, FrameLayout.LayoutParams(w, -2, Gravity.CENTER).apply { topMargin = dp(16); bottomMargin = dp(16) })
        gridLayer = layer; gridOpen = true
        ctl.addView(layer, lp(-1, -1))
    }

    private fun cellBox(selected: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = round(if (selected) 0x44FFB300 else 0x22FFFFFF, 12, if (selected) amber else hair)
    }

    private fun pickRatio(i: Int) {
        ratioIdx = i.coerceIn(0, ratioVals.size - 1)
        applyAspect()
        if (ratioIdx == ratioVals.size - 1) promptCustomRatio()
    }

    private fun showAspectPanel() {
        val port = portraitUi == true
        val screenAr = max(imgRect.width(), 1f).let { if (imgRect.height() > 0f) imgRect.width() / imgRect.height() else 1.78f }
        val cells = ratioNames.indices.map { i ->
            val r0 = if (i == ratioVals.size - 1) customRatio else ratioVals[i]
            val shown = if (r0 <= 0f) screenAr else if (port) 1f / r0 else r0
            val b = cellBox(i == ratioIdx)
            b.addView(RatioThumb(this, shown, i == ratioIdx), LinearLayout.LayoutParams(dp(64), dp(44)))
            b.addView(label(ratioNames[i], 10f, if (i == ratioIdx) amber else Color.WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(4), 0, 0) })
            b.setOnClickListener { closeGrid(); pickRatio(i) }
            b
        }
        val ex = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ex.addView(label("Black bar visibility", 11f, 0xFFB0B0B0.toInt()))
        val sb = SeekBar(this).apply {
            max = barOpts.size - 1; progress = barIdx
            progressTintList = android.content.res.ColorStateList.valueOf(amber); thumbTintList = android.content.res.ColorStateList.valueOf(amber)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) { barIdx = p; applyAspect() } }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        ex.addView(sb)
        val lab = LinearLayout(this)
        lab.addView(label("Solid black", 10f, 0xFFB0B0B0.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
        lab.addView(label("Faint", 10f, 0xFFB0B0B0.toInt()))
        ex.addView(lab)
        showGridPanel("ASPECT RATIO", cells, if (port) 3 else 4, ex)
    }

    // ---- lenses --------------------------------------------------------------
    private class LensItem(val title: String, val sub: String, val idx: Int, val zoomTo: Float)

    private fun eqFocal(id: String): Float? = try {
        val c = cm.getCameraCharacteristics(id)
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (f == null || sz == null) null else f * 43.27f / kotlin.math.hypot(sz.width, sz.height)
    } catch (_: Exception) { null }

    private fun lensName(eq: Float) = when { eq < 18f -> "Ultra-wide"; eq <= 36f -> "Main"; eq <= 65f -> "Short tele"; else -> "Telephoto" }

    private fun lensItems(): List<LensItem> {
        val out = ArrayList<LensItem>()
        if (ids.isEmpty() || !::chars.isInitialized) return out
        if (curFacing == 1) {
            ids.forEachIndexed { i, id -> out.add(LensItem(if (ids.size > 1) "Front ${i + 1}" else "Front", eqFocal(id)?.let { "${it.roundToInt()}mm" } ?: "", i, 1f)) }
            return out
        }
        val eqs = ids.map { eqFocal(it) }
        val ref = eqs.filterNotNull().minByOrNull { abs(it - 26f) } ?: 26f
        ids.forEachIndexed { i, _ ->
            val e = eqs[i]
            if (e == null) out.add(LensItem("Lens ${i + 1}", "", i, 1f))
            else out.add(LensItem(lensName(e), "${e.roundToInt()}mm  ·  %.1fx".format(e / ref), i, 1f))
        }
        val base = eqs.getOrNull(idIdx)
        if (base != null) {
            val phys = if (Build.VERSION.SDK_INT >= 28) chars.physicalCameraIds else emptySet<String>()
            for (pid in phys) {
                if (pid in ids) continue
                val e = eqFocal(pid) ?: continue
                val zr = e / base
                if (zr < zoomMin - 0.01f || zr > zoomMax + 0.01f || abs(zr - 1f) < 0.08f) continue
                if (out.any { abs(it.sub.substringAfter("·", "").trim().removeSuffix("x").toFloatOrNull()?.let { x -> x - e / ref } ?: 9f) < 0.12f }) continue
                out.add(LensItem(lensName(e), "${e.roundToInt()}mm  ·  %.1fx".format(e / ref), idIdx, zr.coerceIn(zoomMin, zoomMax)))
            }
            if (phys.isEmpty() && zoomMin < 0.95f && out.none { it.title == "Ultra-wide" })
                out.add(LensItem("Ultra-wide", "%.1fx".format(zoomMin), idIdx, zoomMin))
        }
        out.sortBy { it.sub.substringAfter("·", it.sub).trim().removeSuffix("x").toFloatOrNull() ?: 1f }
        return out
    }

    private fun lensActive(li: List<LensItem>): Int {
        var best = 0; var bd = Float.MAX_VALUE
        li.forEachIndexed { i, it ->
            if (it.idx != idIdx) return@forEachIndexed
            val d = abs(it.zoomTo - zoom)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun pickLens(n: Int) {
        val li = lensItems()
        val it = li.getOrNull(n) ?: return
        if (it.idx != idIdx) { pendingZoom = it.zoomTo; switchCamera(curFacing, it.idx) }
        else { zoom = it.zoomTo.coerceIn(zoomMin, zoomMax); updateRepeating(); refreshInfo(); refreshFlipUi() }
    }

    private fun showLensPanel() {
        val li = lensItems()
        if (li.size < 2) { toast("This phone has only one ${if (curFacing == 0) "rear" else "front"} lens"); return }
        val act = lensActive(li)
        val cells = li.mapIndexed { i, it ->
            val b = cellBox(i == act)
            b.addView(label(it.title, 12f, if (i == act) amber else Color.WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
            b.addView(label(it.sub, 10f, 0xFFB0B0B0.toInt()).apply { gravity = Gravity.CENTER; maxLines = 1 })
            b.setOnClickListener { closeGrid(); pickLens(i) }
            b
        }
        showGridPanel("${li.size} LENSES  ·  ${if (curFacing == 0) "REAR" else "FRONT"}", cells, if (portraitUi == true) 2 else min(li.size, 4))
    }

    private fun refreshFlipUi() {
        if (!::flipBtn.isInitialized) return
        flipBtn.text = if (curFacing == 0) "FRONT" else "REAR"
        val li = lensItems()
        lensBtn.text = "LENS ${lensActive(li) + 1}/${li.size}"
        lensBtn.visibility = if (li.size > 1) View.VISIBLE else View.GONE
    }

    private fun flipCamera() {
        val target = 1 - curFacing
        if ((if (target == 0) backIds else frontIds).isEmpty()) { toast("No ${if (target == 0) "rear" else "front"} camera"); return }
        switchCamera(target, 0)
    }

    private fun switchCamera(facing: Int, idx: Int) {
        if (recording) { toast("Stop recording before switching camera"); return }
        if (switching) return
        switching = true
        curFacing = facing
        ids = if (facing == 0) backIds else frontIds
        idIdx = idx.coerceIn(0, max(ids.size - 1, 0))
        rackAnim?.cancel()
        closeCamera()
        refreshFlipUi()
        bg.postDelayed({ switching = false; openCamera() }, 350)
    }

    private fun makePill(id: Int, name: String): Pill {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(5)); background = round(glass, 10, hair)
            setOnClickListener { selectParam(if (selected == id) 0 else id) }
        }
        box.addView(label(name, 9f, 0xFFB0B0B0.toInt()).apply { letterSpacing = 0.12f })
        val v = label("-", 13f, Color.WHITE, true).apply { typeface = Typeface.MONOSPACE }
        box.addView(v)
        return Pill(box, v)
    }

    private fun styleSeg() {
        segVideo.setTextColor(if (videoMode) Color.BLACK else Color.WHITE)
        segVideo.background = if (videoMode) round(amber, 16) else null
        segPhoto.setTextColor(if (!videoMode) Color.BLACK else Color.WHITE)
        segPhoto.background = if (!videoMode) round(amber, 16) else null
    }

    private fun setMode(video: Boolean) {
        if (recording || videoMode == video) return
        videoMode = video
        styleSeg()
        recBtn.photo = !video
        nightBtn.visibility = if (video) View.GONE else View.VISIBLE
        startPreviewSession()
        refreshInfo()
    }

    private fun onShutter() {
        if (videoMode) { if (recording) stopRecording() else startRecording(0) } else capture()
    }

    // ---- settings panel ----------------------------------------------------
    private class Row(val kind: Int, val title: String, val opts: () -> List<String>, val idx: () -> Int, val set: (Int) -> Unit)
    private val rows = ArrayList<Row>()

    private fun section(p: LinearLayout, t: String) {
        rows.add(Row(0, t, { emptyList() }, { 0 }, { }))
        p.addView(label(t, 11f, amber, true).apply { letterSpacing = 0.15f; setPadding(0, dp(14), 0, dp(4)) })
    }

    private fun cycleRow(p: LinearLayout, title: String, opts: () -> List<String>, idx: () -> Int, onSet: (Int) -> Unit): TextView {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
        row.addView(label(title, 12f), LinearLayout.LayoutParams(0, -2, 1f))
        val c = chip(opts().getOrNull(idx()) ?: "-") { b ->
            val o = opts(); if (o.isEmpty()) return@chip
            onSet((idx() + 1) % o.size)
            b.text = opts().getOrNull(idx()) ?: "-"
        }
        row.addView(c)
        p.addView(row)
        rows.add(Row(1, title, opts, idx) { i -> onSet(i); c.text = opts().getOrNull(idx()) ?: "-" })
        return c
    }

    private fun toggleRow(p: LinearLayout, title: String, init: Boolean, cb: (Boolean) -> Unit): Switch {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
        row.addView(label(title, 12f), LinearLayout.LayoutParams(0, -2, 1f))
        val s = Switch(this).apply { isChecked = init; setOnCheckedChangeListener { _, c -> cb(c) } }
        row.addView(s)
        p.addView(row)
        rows.add(Row(2, title, { listOf("Off", "On") }, { if (s.isChecked) 1 else 0 }) { i -> s.isChecked = i == 1 })
        return s
    }

    private fun rackText() = "%.1fs".format(rackOpts[rackIdx] / 1000f)
    private val rackRowChips = ArrayList<TextView>()
    private fun updateRackUi() {
        if (::rackChip.isInitialized) rackChip.text = rackText()
        for (c in rackRowChips) c.text = rackText()
    }

    private fun satText(i: Int) = if (satOpts[i] >= 0.99f) "Full colour" else "Flat ${(satOpts[i] * 100).roundToInt()}%"

    private fun buildSettings() {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        settings = ScrollView(this).apply { addView(ll) }
        settingsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(0xF0101114.toInt(), 18, hair) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(14), dp(6)) }
        head.addView(chip("‹  BACK") { setSettingsOpen(false) }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)); setTextColor(Color.BLACK); background = round(amber, 14) })
        head.addView(label("SETTINGS", 13f, Color.WHITE, true).apply { letterSpacing = 0.15f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        settingsHost.addView(head, LinearLayout.LayoutParams(-1, -2))
        settingsHost.addView(settings, LinearLayout.LayoutParams(-1, 0, 1f))
        // full-screen layer: swallows every touch so nothing behind the settings reacts until BACK is pressed
        settingsLayer = FrameLayout(this).apply {
            setBackgroundColor(0x66000000); isClickable = true; isFocusable = true; visibility = View.GONE
            addView(settingsHost, lp(-1, -1))
        }
        val note = 0xFFB0B0B0.toInt()

        section(ll, "LOOK")
        cycleRow(ll, "Profile", { listOf("Standard", "Flat", "Log-like") }, { profile }) {
            profile = it
            if (it > 0) toast("${if (it == 1) "Flat" else "Log-like"}: sharpening forced OFF, colour ${satText(satIdx).lowercase()}")
            wbAutoHold = false; awbSince = SystemClock.elapsedRealtime()
            updateRepeating()
        }
        cycleRow(ll, "Colour saturation (Flat / Log)", { satOpts.indices.map { satText(it) } }, { satIdx }) {
            satIdx = it; wbAutoHold = false; awbSince = SystemClock.elapsedRealtime(); updateRepeating()
        }
        ll.addView(label("Flat and Log always switch sharpening OFF and draw colour at the saturation above, with white balance held steady so the grade stays consistent. Use Full colour to keep the phone's own colour matrix.", 10f, note))
        cycleRow(ll, "Sharpening (Standard profile only)", { listOf("Off", "Low", "Full") }, { edgeLvl }) { edgeLvl = it; updateRepeating() }
        cycleRow(ll, "Noise reduction", { listOf("Off", "Minimal", "Normal") }, { nrLvl }) { nrLvl = it; updateRepeating() }

        section(ll, "EXPOSURE TOOLS")
        ll.addView(label("METER (top bar): tap a face or object to read its exposure. ISO and SHUTTER strips have base presets. Use LOCK on any strip to freeze a value.", 10f, note))
        cycleRow(ll, "Mains frequency (flicker-safe shutters)", { listOf("50 Hz", "60 Hz") }, { if (flickerHz == 50) 0 else 1 }) { flickerHz = if (it == 0) 50 else 60; buildPresets() }

        section(ll, "LOW LIGHT")
        ll.addView(label("Tap ISO, then the mode button, and choose CINE for locked-shutter smart ISO. In PHOTO mode use NIGHT for multi-frame stacking.", 10f, note))
        cycleRow(ll, "Night stack frames", { nightFrames.map { "$it" } }, { nightIdx }) { nightIdx = it }
        val boostSw = toggleRow(ll, "Native low-light boost (AUTO)", false) { lowBoost = it; updateRepeating() }
        lowBoostRow = boostSw.parent as View
        lowBoostRow.visibility = View.GONE

        section(ll, "FOCUS")
        toggleRow(ll, "Face detection and tracking AF", true) { faceAf = it; if (!it) { afRegion = null; appliedRegion = null }; updateRepeating() }
        rackRowChips.add(cycleRow(ll, "Rack focus speed (time for one pull)", { rackOpts.indices.map { "%.1fs".format(rackOpts[it] / 1000f) } }, { rackIdx }) { rackIdx = it; updateRackUi() })
        cycleRow(ll, "Rack curve", { listOf("Soft ease", "Cine ease", "Linear") }, { rackCurve }) { rackCurve = it }
        ll.addView(label("Tap a subject while NOT recording and it is stored as a mark (A, B, C) on screen. While recording, tap a marked subject (or A/B/C) and the lens pulls there smoothly at the speed above.", 10f, note))
        cycleRow(ll, "Focus marks", { listOf("Keep", "Clear all") }, { 0 }) { clearMarks(); toast("Focus marks cleared") }

        section(ll, "MONITOR TOOLS")
        cycleRow(ll, "Focus assist", { listOf("Off", "Isolate (subtle)", "Peaking (classic)") }, { focusMode }) { focusMode = it; applyMonitorSettings() }
        ll.addView(label("ISOLATE gently dims everything that is out of focus, so the sharp zone just stays bright: no coloured lines. PEAKING paints coloured dots on sharp edges. A small meter with a hold marker shows when focus is at its best.", 10f, note))
        cycleRow(ll, "Focus assist sensitivity", { listOf("Low", "Medium", "High") }, { peakSens }) { peakSens = it; applyMonitorSettings() }
        cycleRow(ll, "Peaking colour", { listOf("Red", "Green", "Yellow", "White", "Cyan") }, { peakColIdx }) { peakColIdx = it; applyMonitorSettings() }
        cycleRow(ll, "LUT look (monitor only)", { lutNames() }, { lutIdx }) { lutIdx = it; lutKey = ""; applyMonitorSettings() }
        cycleRow(ll, "Import .cube LUT file", { listOf("Choose file") }, { 0 }) { pickLut() }
        ll.addView(label("LUTs only change what you SEE (and what FilTarView shows). Recorded files stay flat for grading. With a LUT on, the preview is a lighter-resolution image, so switch it off for the sharpest focusing.", 10f, note))
        ll.addView(label("ASPECT ratio: use the ASPECT button next to ISO / SHUTTER / FOCUS on the main screen.", 10f, note))
        cycleRow(ll, "Frame bars opacity", { barOpts.map { "${(it * 100).roundToInt()}%" } }, { barIdx }) { barIdx = it; applyAspect() }
        ll.addView(label("The frame turns with the phone (2.39:1 becomes a tall frame when vertical). Photos are cropped to the frame. Video is recorded full-frame so you can still reframe in post; the ratio is added to the file name.", 10f, note))
        toggleRow(ll, "Level meter (roll and tilt)", true) { levelOn = it; applyMonitorSettings() }

        section(ll, "CAMERA")
        toggleRow(ll, "Mirror the front camera preview", true) { mirrorPreview = it; configureTransform() }
        ll.addView(label("FRONT / REAR button switches cameras. Every control works on both; features the front lens lacks (manual focus, RAW, OIS) are skipped automatically. Saved files are never mirrored.", 10f, note))

        section(ll, "AUDIO")
        audioRowChip = cycleRow(ll, "Input", { audioOptions().map { it.second } }, { audioOptions().indexOfFirst { it.first == audioSel }.coerceAtLeast(0) }) { audioSel = audioOptions()[it].first; updateAudioChip() }
        ll.addView(label("Plug a USB-C receiver (Hollyland, Rode Wireless, etc.) or wired mic, then pick it here. Auto uses it automatically.", 10f, note))

        section(ll, "STABILISATION")
        toggleRow(ll, "Optical (OIS)", true) { ois = it; updateRepeating() }
        toggleRow(ll, "Electronic (EIS), video", false) { eis = it; updateRepeating() }

        section(ll, "FORMAT")
        videoChip = cycleRow(ll, "Video size", { videoSizes.map { "${it.width}x${it.height}" } }, { videoIdx }) { videoIdx = it; refreshInfo(); startPreviewSession() }
        fpsChip = cycleRow(ll, "Frame rate", { fpsOpts.map { "$it" } }, { fpsIdx }) { fpsIdx = it; refreshInfo(); buildPresets(); updateRepeating() }
        photoChip = cycleRow(ll, "Photo size", { photoOpts.map { "${it.size.width}x${it.size.height}" + if (it.maxRes) " MAX" else "" } }, { photoIdx }) { photoIdx = it; refreshInfo(); startPreviewSession() }
        rawSwitch = toggleRow(ll, "Also save RAW DNG", false) { rawOn = it && rawSize != null; startPreviewSession() }
        toggleRow(ll, "H.264 only (max compatibility)", false) { compat264 = it; refreshInfo() }
        ll.addView(label("Turn the phone upright for vertical 9:16 video; the controls re-arrange themselves. The orientation locks while recording so the file is always the right way up.", 10f, note))

        section(ll, "OVERLAYS")
        toggleRow(ll, "Grid", false) { showGrid = it; gridView.visibility = if (it) View.VISIBLE else View.GONE }
        toggleRow(ll, "Histogram", true) { showHist = it; hist.visibility = if (it) View.VISIBLE else View.INVISIBLE }

        section(ll, "BLUETOOTH CONTROLLER")
        ll.addView(label(PAD_HELP, 10f, note))
    }

    // ---- gestures ------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { handleTap(e.x, e.y); return true }
            override fun onLongPress(e: MotionEvent) { releaseLock() }
        })
        val sg = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (zoomMax <= zoomMin) return true
                zoom = (zoom * d.scaleFactor).coerceIn(zoomMin, zoomMax)
                updateRepeating(); refreshInfo()
                return true
            }
        })
        overlay.setOnTouchListener { _, e -> sg.onTouchEvent(e); if (!sg.isInProgress) gd.onTouchEvent(e); true }
    }

    // ---- strip: sliders, presets, locks -------------------------------------------
    private fun isLocked(sel: Int) = when (sel) { 1, 2 -> lockExp; 3 -> lockFocus; 4 -> lockWb; 5 -> lockEv; else -> false }

    private fun selectParam(id: Int) {
        selected = id
        padPresetIdx = -1
        strip.visibility = if (id == 0) View.GONE else View.VISIBLE
        buildPresets()
        refreshStrip(true)
        stylePills()
    }

    private fun stylePills() {
        for ((k, p) in pills) {
            val c = when { isLocked(k) -> red; k == selected -> amber; else -> hair }
            p.box.background = round(glass, 10, c)
        }
    }

    private fun addPreset(label: String, dim: Boolean = false, onClick: () -> Unit) {
        val c = chip(label) { _ -> if (isLocked(selected)) { toast("Locked. Tap LOCKED to unlock."); return@chip }; onClick(); refreshStrip(true); updatePills(); updateRepeating() }
        if (dim) c.setTextColor(0xFFB0B0B0.toInt())
        presetRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(5) })
    }

    private fun goManual() { if (expMode != 2) { seedManualFromLive(); expMode = 2 } }

    private fun buildPresets() {
        if (!::presetRow.isInitialized) return
        presetRow.removeAllViews()
        if (!::chars.isInitialized) { presetScroll.visibility = View.GONE; return }
        when (selected) {
            1 -> {
                val r = isoRange()
                val analog = analogMax
                addPreset("BASE ${r.lower}") { goManual(); isoP = pFromIso(r.lower) }
                for (v in intArrayOf(200, 400, 800, 1600, 3200, 6400)) {
                    if (v <= r.lower || v > r.upper) continue
                    addPreset("$v", analog > 0 && v > analog) { goManual(); isoP = pFromIso(v) }
                }
                if (analog > r.lower && analog <= r.upper) addPreset("CLEAN MAX $analog") { goManual(); isoP = pFromIso(analog) }
            }
            2 -> {
                val f = fps.toDouble()
                val list = ArrayList<Pair<String, Double>>()
                list.add("360° 1/${f.roundToInt()}" to 1e9 / f)
                list.add("180° 1/${(f * 2).roundToInt()}" to 1e9 / (f * 2))
                list.add("90° 1/${(f * 4).roundToInt()}" to 1e9 / (f * 4))
                val hz = flickerHz.toDouble()
                list.add("1/${flickerHz} flicker-safe" to 1e9 / hz)
                list.add("1/${flickerHz * 2} flicker-safe" to 1e9 / (hz * 2))
                for (d in intArrayOf(250, 500, 1000, 2000)) list.add("1/$d" to 1e9 / d)
                val e = expRange()
                for ((lab, ns) in list) {
                    if (ns < e.lower || ns > shutterHi() * 1.02) continue
                    addPreset(lab) { goManual(); shutP = pFromShutter(ns.toLong()) }
                }
            }
            4 -> {
                for ((lab, k) in listOf("TUNGSTEN 3200K" to 3200, "FLUORESCENT 4000K" to 4000, "DAYLIGHT 5600K" to 5600, "CLOUDY 6500K" to 6500, "SHADE 7500K" to 7500)) {
                    addPreset(lab) { if (manualWb || enterManualWb()) kelvinP = ((k - 2000) / 8).coerceIn(0, 1000) }
                }
            }
        }
        presetScroll.visibility = if (presetRow.childCount > 0 && selected != 0) View.VISIBLE else View.GONE
    }

    private fun currentProgress(): Int = when (selected) {
        1 -> if (expMode == 2) isoP else pFromIso(if (expMode == 1) cineIso else lastIso)
        2 -> if (expMode == 2) shutP else pFromShutter(if (expMode == 1) cineShutNs else lastExpNs)
        3 -> if (manualFocus) focusP else pFromDist(lastFocusDist)
        4 -> kelvinP
        5 -> evP
        else -> 0
    }

    private fun refreshStrip(setSeek: Boolean) {
        if (selected == 0 || !::stripSeek.isInitialized) return
        val p = currentProgress()
        if (setSeek) stripSeek.progress = p
        val modes = arrayOf("AUTO", "CINE", "MANUAL")
        val mm = effectiveManual()
        when (selected) {
            1 -> { stripLabel.text = "ISO ${if (expMode == 2) mm.first else if (expMode == 1) cineIso else lastIso}"; stripMode.text = modes[expMode] }
            2 -> {
                val ns = if (expMode == 2) mm.second else if (expMode == 1) cineShutNs else lastExpNs
                stripLabel.text = shutterText(ns) + " " + shutterAngle(ns); stripMode.text = modes[expMode]
            }
            3 -> { stripLabel.text = if (manualFocus) "FOCUS %.0f%%".format(focusP / 10f) else "FOCUS AF"; stripMode.text = if (manualFocus) "MF" else "AF" }
            4 -> {
                stripLabel.text = if (manualWb) "${kelvinFromP(kelvinP).roundToInt()}K" else if (wbAutoHold && flatActive()) "WB HOLD" else "WB AUTO"
                stripMode.text = if (manualWb) "MANUAL" else if (flatActive()) "RE-MEASURE" else "AUTO"
            }
            5 -> {
                stripLabel.text = "EV %+.1f".format(ev())
                stripMode.text = "RESET"
            }
        }
        val locked = isLocked(selected)
        stripSeek.isEnabled = !locked
        stripLock.text = if (locked) "LOCKED" else "LOCK"
        stripLock.background = round(if (locked) red else glass, 14, hair)
        stripFocusRow.visibility = if (selected == 3) View.VISIBLE else View.GONE
    }

    private fun onLockClick() {
        when (selected) {
            1, 2 -> if (!lockExp) { goManual(); lockExp = true } else lockExp = false
            3 -> if (!lockFocus) {
                if (minFocus() <= 0f) { toast("This lens has fixed focus"); return }
                if (!manualFocus) { focusP = pFromDist(lastFocusDist); manualFocus = true }
                afScan = false; waitSettle = false
                lockFocus = true
            } else lockFocus = false
            4 -> if (!lockWb) { if (!manualWb && !enterManualWb()) return; lockWb = true } else lockWb = false
            5 -> lockEv = !lockEv
        }
        refreshStrip(true); stylePills(); updatePills(); updateRepeating()
    }

    private fun onStripMode() {
        if (isLocked(selected)) { toast("Locked. Tap LOCKED to unlock."); return }
        when (selected) {
            1, 2 -> {
                val next = (expMode + 1) % 3
                if (next == 2) seedManualFromLive()
                if (next == 1) { autoE = lastIso.toDouble() * lastExpNs; wide = false }
                expMode = next
            }
            3 -> { manualFocus = !manualFocus && minFocus() > 0f; if (manualFocus) focusP = pFromDist(lastFocusDist); afScan = false; waitSettle = false; if (!manualFocus) { afMode = 0; afRegion = null; appliedRegion = null } }
            4 -> if (manualWb) { manualWb = false; wbAutoHold = false; awbSince = SystemClock.elapsedRealtime() }
                 else if (flatActive()) { wbAutoHold = false; awbSince = SystemClock.elapsedRealtime(); toast("Measuring white balance, then holding it") }
                 else enterManualWb()
            5 -> evP = 500
        }
        refreshStrip(true); updatePills()
        updateRepeating()
    }

    private fun onSliderChanged(p: Int) {
        if (isLocked(selected)) return
        when (selected) {
            1 -> { goManual(); isoP = p }
            2 -> { goManual(); shutP = p }
            3 -> { if (minFocus() <= 0f) { toast("This lens has fixed focus"); return }; rackAnim?.cancel(); manualFocus = true; afScan = false; waitSettle = false; focusP = p }
            4 -> { if (!manualWb && !enterManualWb()) return; kelvinP = p }
            5 -> evP = p
        }
        refreshStrip(false)
        updatePills()
        updateRepeating()
    }

    private fun seedManualFromLive() {
        val iso = if (expMode == 1) cineIso else lastIso
        val ns = if (expMode == 1) cineShutNs else lastExpNs
        isoP = pFromIso(iso); shutP = pFromShutter(ns)
    }

    private fun enterManualWb(): Boolean {
        if (!hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) { toast("Manual white balance not supported on this phone"); return false }
        if (lastGains == null || lastTransform == null) { toast("Camera still starting, try again"); return false }
        wbBase = lastGains
        kelvinP = 437
        manualWb = true
        return true
    }

    private fun updatePills() {
        val m = arrayOf("A", "C", "M")[expMode]
        val mm = effectiveManual()
        val lx = if (lockExp) "L " else "$m "
        pills[1]?.value?.text = lx + (if (expMode == 2) mm.first else if (expMode == 1) cineIso else lastIso)
        val ns = if (expMode == 2) mm.second else if (expMode == 1) cineShutNs else lastExpNs
        pills[2]?.value?.text = (if (lockExp) "L " else "") + shutterText(ns)
        pills[3]?.value?.text = if (lockFocus) "LOCKED" else if (manualFocus) "MF %.0f%%".format(focusP / 10f) else when (afMode) { 1 -> "FACE"; 2 -> "POINT"; else -> "AF" }
        pills[4]?.value?.text = (if (lockWb) "L " else "") + if (manualWb) "${kelvinFromP(kelvinP).roundToInt()}K" else if (wbAutoHold && flatActive()) "HOLD" else "AUTO"
        pills[5]?.value?.text = (if (lockEv) "L " else "") + "%+.1f".format(ev())
        stylePills()
        if (selected != 0) refreshStrip(false)
    }

    // ---- focus marks and rack -----------------------------------------------------
    private fun updateMarkChips() {
        for (i in 0..2) markChips[i]?.text = "ABC"[i].toString() + if (marks[i] >= 0) "•" else ""
    }

    private fun clearMarks() {
        for (i in 0..2) { marks[i] = -1; markPts[i] = null }
        markNext = 0
        updateMarkChips()
    }

    private fun curFocusP() = if (manualFocus) focusP else pFromDist(lastFocusDist)

    private fun markSet(i: Int) {
        if (minFocus() <= 0f) { toast("This lens has fixed focus"); return }
        marks[i] = curFocusP()
        markPts[i] = if (afMode == 2) tappedSensor else null
        updateMarkChips()
        toast("Mark ${"ABC"[i]} stored. Tap it to rack focus there.")
    }

    /** Called when a tapped subject has been focused: remember it so it can be pulled to smoothly later. */
    private fun autoMark(p: Int, pt: PointF?) {
        if (pt == null) return
        // reuse a mark that is already on this subject
        for (i in 0..2) {
            val mp = markPts[i] ?: continue
            if (marks[i] >= 0 && hypot((mp.x - pt.x).toDouble(), (mp.y - pt.y).toDouble()) < 0.06 * visibleRect().width()) {
                marks[i] = p; markPts[i] = pt; updateMarkChips(); return
            }
        }
        val i = markNext
        markNext = (markNext + 1) % 3
        marks[i] = p; markPts[i] = pt
        updateMarkChips()
    }

    private fun markTap(i: Int) { if (marks[i] < 0) markSet(i) else rackTo(marks[i]) }

    private fun nearestMark(x: Float, y: Float): Int {
        var best = -1
        var bd = max(dp(46).toFloat(), 0.09f * imgRect.width())
        for (i in 0..2) {
            val pt = markPts[i] ?: continue
            if (marks[i] < 0) continue
            val v = sensorToView(pt.x, pt.y)
            val d = hypot(v.x - x, v.y - y)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun ease(t: Float): Float = when (rackCurve) {
        0 -> 0.5f - 0.5f * kotlin.math.cos(Math.PI.toFloat() * t)
        1 -> t * t * t * (t * (6f * t - 15f) + 10f)          // smootherstep: very gentle start and landing
        else -> t
    }

    /** Smooth focus pull from the current focus to a stored mark, at the chosen speed and curve. */
    private fun rackTo(target: Int) {
        if (minFocus() <= 0f) { toast("This lens has fixed focus"); return }
        if (lockFocus) { toast("Focus is locked. Unlock it first."); return }
        val from = curFocusP()
        manualFocus = true; afScan = false; waitSettle = false
        rackAnim?.cancel()
        if (abs(target - from) < 3) { focusP = target; updateRepeating(); refreshStrip(true); return }
        focusP = from
        val dur = rackOpts[rackIdx].toLong()
        val t0 = SystemClock.elapsedRealtime()
        rackAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = android.view.animation.LinearInterpolator()
            var last = -1
            addUpdateListener {
                val e = ease(it.animatedValue as Float)
                val p = (from + (target - from) * e).roundToInt().coerceIn(0, 1000)
                if (p != last) {
                    last = p; focusP = p
                    if (selected == 3) { stripSeek.progress = p; refreshStrip(false) }
                    updateRepeating()
                }
            }
            start()
        }
    }

    // ---- audio input --------------------------------------------------------------
    private val externalTypes = intArrayOf(
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26 /* TYPE_BLE_HEADSET */
    )

    private fun externalInputs(): List<AudioDeviceInfo> = try {
        audioMgr.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in externalTypes }
    } catch (_: Exception) { emptyList() }

    private fun typeShort(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED"
        else -> "BLUETOOTH"
    }

    private fun audioOptions(): List<Pair<Int, String>> {
        val l = ArrayList<Pair<Int, String>>()
        l.add(-1 to "Auto (best available)")
        l.add(0 to "Phone mic")
        for (d in externalInputs()) l.add(d.id to "${typeShort(d.type)}: ${d.productName}")
        return l
    }

    /** The external microphone to record from, or null for the phone's own mic. */
    private fun chosenAudio(): AudioDeviceInfo? {
        val ext = externalInputs()
        return when (audioSel) {
            0 -> null
            -1 -> ext.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                ?: ext.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } ?: ext.firstOrNull()
            else -> ext.firstOrNull { it.id == audioSel }
        }
    }

    private fun cycleAudio() {
        if (recording) { toast("Stop recording to change the microphone"); return }
        val o = audioOptions()
        val i = o.indexOfFirst { it.first == audioSel }.coerceAtLeast(0)
        audioSel = o[(i + 1) % o.size].first
        updateAudioChip()
        toast("Audio: " + o[(i + 1) % o.size].second)
    }

    private fun updateAudioChip() {
        val d = chosenAudio()
        audioChip.text = if (d == null) "MIC PHONE" else "MIC ${typeShort(d.type)}"
        audioChip.setTextColor(if (d != null) Color.parseColor("#4CD964") else Color.WHITE)
        if (::audioRowChip.isInitialized) audioRowChip.text = audioOptions().firstOrNull { it.first == audioSel }?.second ?: "Auto (best available)"
    }

    // =====================================================================
    // Monitor tools: focus assist, LUT, aspect frame, level, FilTarView link
    // =====================================================================
    private val monitor = Monitor()
    private var monThread: HandlerThread? = null
    private var monH: Handler? = null
    private var monBmp: Bitmap? = null
    private val monBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var monScale = 1f
    private val monCrop = Rect()
    private var monPx = IntArray(0)
    private var streamBmp: Bitmap? = null
    private var focusMode = 0
    private var peakSens = 1
    private var peakColIdx = 0
    private val peakCols = intArrayOf(0xFFFF3B30.toInt(), 0xFF4CD964.toInt(), 0xFFFFD60A.toInt(), 0xFFFFFFFF.toInt(), 0xFF40E0FF.toInt())
    private var lutIdx = 0
    private val customLuts = ArrayList<Pair<String, Lut3D>>()
    private var lutKey = ""
    private val ratioNames = listOf("Full frame", "16:9", "2.39:1 Scope", "2.35:1", "2:1 Univisium", "1.85:1", "21:9", "3:2", "4:3", "5:4", "1:1", "Custom")
    private val ratioVals = floatArrayOf(0f, 16f / 9f, 2.39f, 2.35f, 2f, 1.85f, 21f / 9f, 1.5f, 4f / 3f, 1.25f, 1f, 0f)
    private val ratioShort = listOf("FULL", "16:9", "2.39", "2.35", "2:1", "1.85", "21:9", "3:2", "4:3", "5:4", "1:1", "CUSTOM")
    private var ratioIdx = 0
    private var customRatio = 2.2f
    private val barOpts = floatArrayOf(1f, 0.85f, 0.65f, 0.45f, 0.25f)
    private var barIdx = 1
    private var levelOn = true
    private var scoreHold = 0f
    private var holdT = 0L
    private lateinit var remote: RemoteServer
    private lateinit var monChip: TextView
    private lateinit var markTv: TextView
    private var lastState = ""
    private var remoteAxisT = 0L
    private var lastJpegOrient = 0
    private val prefs by lazy { getSharedPreferences("filtarpro", Context.MODE_PRIVATE) }

    private fun lutNames(): List<String> = listOf("Off (flat)") + Looks.list.map { it.name } + customLuts.map { "LUT: ${it.first}" }

    /** Wanted frame width/height as shown (turns with the phone), 0 = full frame. */
    private fun frameRatioEff(): Float {
        val r = if (ratioIdx == ratioVals.size - 1) customRatio else ratioVals[ratioIdx]
        if (r <= 0f) return 0f
        return if (portraitUi == true) 1f / r else r
    }

    private fun applyAspect() {
        if (!::overlay.isInitialized) return
        overlay.frameRatio = frameRatioEff()
        overlay.barAlpha = barOpts[barIdx]
        overlay.invalidate()
        if (::aspectVal.isInitialized) {
            aspectVal.text = if (ratioIdx == ratioVals.size - 1) "%.2f".format(customRatio) else ratioShort[ratioIdx]
            aspectVal.setTextColor(if (ratioIdx == 0) Color.WHITE else amber)
        }
    }

    private fun applyMonitorSettings() {
        monitor.mode = focusMode; monitor.sens = peakSens; monitor.peakColor = peakCols[peakColIdx]
        overlay.showScore = focusMode != 0
        overlay.showLevel = levelOn
        refreshLut()
        applyAspect()
    }

    private fun refreshLut() {
        val useCurve = ::chars.isInitialized && avail(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
        val useGamma = ::chars.isInitialized && !useCurve && avail(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
        val key = "$lutIdx|$profile|$useCurve|$useGamma"
        if (key == lutKey) return
        lutKey = key
        val lk = if (profile > 0 && useCurve) (if (profile == 1) 6f else 24f) else 0f
        val lg = if (profile > 0 && useGamma) (if (profile == 1) 1.6f else 1.3f) else 0f
        monitor.lut = when {
            lutIdx <= 0 -> null
            lutIdx <= Looks.list.size -> Looks.build(Looks.list[lutIdx - 1], lk, lg)
            else -> customLuts.getOrNull(lutIdx - Looks.list.size - 1)?.second
        }
    }

    private fun setCustomRatio(a: Float, b: Float) {
        if (a <= 0f || b <= 0f) return
        customRatio = max(a, b) / min(a, b)
        ratioIdx = ratioVals.size - 1
        applyAspect()
        toast("Frame %.2f:1".format(customRatio))
    }

    private fun promptCustomRatio() {
        val et = android.widget.EditText(this).apply { hint = "e.g. 2.2 or 11:5"; setSingleLine(true) }
        try {
            AlertDialog.Builder(this).setTitle("Custom aspect ratio").setView(et)
                .setPositiveButton("OK") { _, _ ->
                    val t = et.text.toString().trim().replace(',', '.')
                    val p = t.split(':', 'x', '/')
                    val a = p.getOrNull(0)?.toFloatOrNull(); val b = if (p.size > 1) p[1].toFloatOrNull() else 1f
                    if (a != null && b != null) setCustomRatio(a, b) else toast("Could not read that ratio")
                }.setNegativeButton("Cancel", null).show()
        } catch (_: Exception) {}
    }

    private fun pickLut() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            startActivityForResult(i, 77)
        } catch (_: Exception) { toast("No file picker available") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 77 && res == RESULT_OK) {
            val u = data?.data ?: return
            Thread {
                try {
                    val txt = contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: ""
                    val lut = Lut3D.parseCube(txt)
                    val name = (u.lastPathSegment ?: "custom").substringAfterLast('/').substringBeforeLast('.').take(24)
                    runOnUiThread {
                        if (lut == null) toast("That file is not a 3D .cube LUT")
                        else { customLuts.add(name to lut); lutIdx = Looks.list.size + customLuts.size; lutKey = ""; applyMonitorSettings(); toast("LUT loaded: $name") }
                    }
                } catch (_: Exception) { toast("Could not read that LUT file") }
            }.start()
        }
    }

    // ---- monitor loop: grabs the preview, analyses it off the UI thread, feeds the overlay and the remote stream
    private fun monNeeded() = focusMode != 0 || lutIdx != 0 || (::remote.isInitialized && remote.hasClient())

    private val monLoop = object : Runnable {
        override fun run() {
            try { monTick() } catch (_: Throwable) { monBusy.set(false) }
            ui.postDelayed(this, if (monNeeded()) 50 else 300)
        }
    }

    private fun monTick() {
        if (!::tv.isInitialized) return
        if (!monNeeded()) {
            if (overlay.lutBmp != null || overlay.focusBmp != null) { overlay.lutBmp = null; overlay.focusBmp = null; overlay.invalidate() }
            return
        }
        if (!tv.isAvailable || imgRect.width() <= 0f || device == null || monBusy.get()) return
        refreshLut()
        val budget = if (::remote.isInitialized && remote.lowBandwidth()) 120_000f else 300_000f
        val s = kotlin.math.sqrt(budget / (imgRect.width() * imgRect.height()))
        val bw = (tv.width * s).roundToInt().coerceAtLeast(64); val bh = (tv.height * s).roundToInt().coerceAtLeast(64)
        var b = monBmp
        if (b == null || b.width != bw || b.height != bh) { b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); monBmp = b }
        tv.getBitmap(b)
        monScale = s
        val x0 = (imgRect.left * s).roundToInt().coerceIn(0, bw - 2)
        val y0 = (imgRect.top * s).roundToInt().coerceIn(0, bh - 2)
        val cw = (imgRect.width() * s).roundToInt().coerceIn(2, bw - x0)
        val ch = (imgRect.height() * s).roundToInt().coerceIn(2, bh - y0)
        monCrop.set(x0, y0, x0 + cw, y0 + ch)
        val h = monH ?: return
        monBusy.set(true)
        h.post {
            try {
                if (monPx.size < cw * ch) monPx = IntArray(cw * ch)
                b.getPixels(monPx, 0, cw, x0, y0, cw, ch)
                monitor.process(monPx, cw, ch)
                ui.post { onMonDone() }
            } catch (_: Throwable) { ui.post { monBusy.set(false) } }
        }
    }

    private fun onMonDone() {
        try {
            overlay.lutBmp = monitor.lutBmp
            overlay.focusBmp = monitor.focusBmp
            val now = SystemClock.elapsedRealtime()
            val sc = monitor.score
            overlay.score = sc
            if (sc >= scoreHold) { scoreHold = sc; holdT = now } else if (now - holdT > 2500) scoreHold = max(sc, scoreHold * 0.97f)
            overlay.hold = scoreHold
            overlay.invalidate()
            if (::remote.isInitialized && remote.canSendFrame()) {
                val w = monCrop.width(); val h = monCrop.height()
                var sb = streamBmp
                if (sb == null || sb.width != w || sb.height != h) { sb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); streamBmp = sb }
                val c = Canvas(sb)
                c.drawBitmap(monBmp!!, monCrop, Rect(0, 0, w, h), null)
                c.save()
                c.scale(w / imgRect.width(), h / imgRect.height())
                c.translate(-imgRect.left, -imgRect.top)
                overlay.draw(c)
                c.restore()
                remote.sendFrame(sb)
            }
        } finally { monBusy.set(false) }
    }

    // ---- level meter
    private val sm by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private var gx = 0f; private var gy = 0f; private var gz = 9.8f
    private val levelL = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            val k = 0.18f
            gx += (e.values[0] - gx) * k; gy += (e.values[1] - gy) * k; gz += (e.values[2] - gz) * k
            val n = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
            if (n < 1f) return
            val ux = gx / n; val uy = gy / n; val uz = gz / n
            val sx: Float; val sy: Float
            when (displayRot()) {
                Surface.ROTATION_90 -> { sx = -uy; sy = ux }
                Surface.ROTATION_180 -> { sx = -ux; sy = -uy }
                Surface.ROTATION_270 -> { sx = uy; sy = -ux }
                else -> { sx = ux; sy = uy }
            }
            val r = if (kotlin.math.sqrt(sx * sx + sy * sy) < 0.3f) 0f else Math.toDegrees(kotlin.math.atan2(sx.toDouble(), sy.toDouble())).toFloat()
            val dir = if (curFacing == 0) -uz else uz
            val el = Math.toDegrees(kotlin.math.asin(dir.coerceIn(-1f, 1f).toDouble())).toFloat()
            overlay.roll = r; overlay.pitch = el
            if (levelOn) overlay.invalidate()
        }
    }

    // ---- remote link
    private fun updateMonChip() {
        if (!::monChip.isInitialized) return
        val on = remote.hasClient()
        monChip.text = if (on) "● MON" else "MON"
        monChip.setTextColor(if (on) Color.parseColor("#4CD964") else Color.WHITE)
    }

    private fun showMonitorDialog() {
        val ips = RemoteServer.localIps()
        val msg = "Open FilTarView on the other phone or tablet.\n\n" +
            "Wi-Fi or hotspot address: " + (if (ips.isEmpty()) "no network yet" else ips.joinToString("  ")) + "  (port ${Proto.PORT})\n" +
            "PIN: ${remote.pin}\n\n" +
            "Bluetooth link: ${if (remote.btOn) "ON" else "off"} (pair both devices in Android Bluetooth settings first)\n" +
            (remote.clientDesc?.let { "\nConnected: $it" } ?: "\nNot connected")
        try {
            val b = AlertDialog.Builder(this).setTitle("FilTarView monitor").setMessage(msg).setPositiveButton("OK", null)
                .setNeutralButton(if (remote.btOn) "Bluetooth off" else "Bluetooth on") { _, _ -> toggleBt() }
            if (remote.hasClient()) b.setNegativeButton("Disconnect") { _, _ -> remote.disconnect() }
            b.show()
        } catch (_: Exception) {}
    }

    private fun toggleBt() {
        if (remote.btOn) { remote.stopBluetooth(); toast("Bluetooth link off"); return }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2); return
        }
        toast(if (remote.startBluetooth()) "Bluetooth link on" else "Turn Bluetooth on first")
    }

    private fun buildState(): String {
        val sb = StringBuilder()
        fun kv(k: String, v: String) { sb.append(k).append('=').append(v.replace('\n', ' ')).append('\n') }
        kv("rec", if (recording) "1" else "0"); kv("time", timerTv.text.toString()); kv("vid", if (videoMode) "1" else "0")
        kv("fmt", fmtChip.text.toString()); kv("af", afChip.text.toString()); kv("mic", audioChip.text.toString())
        kv("pad", if (padName != null) "1" else "0"); kv("sel", selected.toString())
        for (i in 1..5) kv("p$i", pills[i]?.value?.text?.toString() ?: "-")
        kv("pl", (1..5).joinToString("") { if (isLocked(it)) "1" else "0" })
        kv("sl", stripLabel.text.toString()); kv("sp", stripSeek.progress.toString()); kv("sm", stripMode.text.toString())
        kv("sk", if (isLocked(selected)) "1" else "0")
        kv("pre", (0 until presetRow.childCount).joinToString("\u0001") { (presetRow.getChildAt(it) as? TextView)?.text?.toString() ?: "" })
        kv("marks", markChips.joinToString("\u0001") { it?.text?.toString() ?: "" }); kv("rack", rackChip.text.toString())
        kv("flip", if (flipBtn.visibility == View.VISIBLE) flipBtn.text.toString() else "")
        kv("lens", if (lensBtn.visibility == View.VISIBLE) lensBtn.text.toString() else "")
        val lis = lensItems()
        kv("lenses", lis.joinToString("\u0001") { it.title + "\u0002" + it.sub }); kv("lensi", lensActive(lis).toString())
        kv("ratios", ratioNames.joinToString("\u0001")); kv("ratioi", ratioIdx.toString()); kv("barsi", barIdx.toString())
        kv("rcur", if (ratioIdx == ratioVals.size - 1) "%.2f".format(customRatio) else ratioShort[ratioIdx])
        kv("night", if (videoMode) "-1" else if (nightOn) "1" else "0")
        kv("meter", if (meterCard.visibility == View.VISIBLE) meterText.text.toString() else ""); kv("marm", if (meterArmed) "1" else "0")
        kv("nrows", rows.size.toString())
        rows.forEachIndexed { i, r -> kv("r$i", "${r.kind}\t${r.title}\t${if (r.kind == 0) 0 else r.idx()}\t${if (r.kind == 0) "" else r.opts().joinToString("\u0001")}") }
        return sb.toString()
    }

    private fun remoteTick(now: Long) {
        if (!::remote.isInitialized) return
        if (remote.hasClient() && tickN % 2 == 0) {
            val s = buildState()
            if (s != lastState) { lastState = s; remote.sendState(s) }
        }
        if (remoteAxisT != 0L && now - remoteAxisT > 600) { padRX = 0f; padRY = 0f; padLT = 0f; padRT = 0f; remoteAxisT = 0L }
    }

    private fun onRemote(cmd: String) {
        ui.post {
            try {
                val t = cmd.trim().split(' ')
                fun f(i: Int) = t.getOrNull(i)?.toFloatOrNull() ?: 0f
                val now = SystemClock.elapsedRealtime()
                if (modalOpen() && t[0] !in setOf("set", "ratio", "ratioidx", "bars", "back", "lensidx")) return@post
                when (t[0]) {
                    "shutter" -> onShutter()
                    "mode" -> setMode(t.getOrNull(1) == "v")
                    "sel" -> selectParam(f(1).toInt().coerceIn(0, 5))
                    "slider" -> { val p = f(1).toInt().coerceIn(0, 1000); stripSeek.progress = p; onSliderChanged(p) }
                    "smode" -> onStripMode()
                    "lock" -> onLockClick()
                    "preset" -> presetRow.getChildAt(f(1).toInt())?.performClick()
                    "tap" -> handleTap(imgRect.left + f(1) * imgRect.width(), imgRect.top + f(2) * imgRect.height())
                    "release" -> releaseLock()
                    "flip" -> flipCamera()
                    "lensidx" -> pickLens(f(1).toInt())
                    "ratioidx" -> pickRatio(f(1).toInt())
                    "bars" -> { barIdx = f(1).toInt().coerceIn(0, barOpts.size - 1); applyAspect() }
                    "meter" -> toggleMeter()
                    "apply" -> applyMeter(t.getOrNull(1) == "1")
                    "closemeter" -> closeMeter()
                    "mark" -> markTap(f(1).toInt().coerceIn(0, 2))
                    "markset" -> markSet(f(1).toInt().coerceIn(0, 2))
                    "rack" -> rackChip.performClick()
                    "set" -> rows.getOrNull(f(1).toInt())?.set?.invoke(f(2).toInt())
                    "ratio" -> setCustomRatio(f(1), f(2))
                    "zoomf" -> if (zoomMax > zoomMin) { zoom = (zoom * f(1)).coerceIn(zoomMin, zoomMax); updateRepeating(); refreshInfo() }
                    "adj" -> { padRX = f(1); remoteAxisT = now; padKick() }
                    "pull" -> { val v = f(1); padRT = max(v, 0f); padLT = max(-v, 0f); remoteAxisT = now; padKick() }
                    "zv" -> { padRY = f(1); remoteAxisT = now; padKick() }
                    "vert" -> padVertical(f(1).toInt())
                    "back" -> padBack()
                }
            } catch (_: Exception) {}
        }
    }

    /** Crops a saved JPEG to the chosen aspect frame (stills only; the video file keeps the full frame). */
    private fun cropJpeg(bytes: ByteArray, orient: Int): ByteArray {
        val r = frameRatioEff()
        if (r <= 0f) return bytes
        try {
            val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            val rw = o.outWidth; val rh = o.outHeight
            if (rw <= 0 || rh <= 0 || rw.toLong() * rh > 60_000_000L) return bytes
            val swap = orient == 90 || orient == 270
            val uw = if (swap) rh else rw; val uh = if (swap) rw else rh
            val ua = uw.toFloat() / uh
            if (abs(r - ua) / ua < 0.012f) return bytes
            val cw: Int; val ch: Int
            if (r > ua) { cw = uw; ch = (uw / r).roundToInt() } else { ch = uh; cw = (uh * r).roundToInt() }
            val rcw = if (swap) ch else cw; val rch = if (swap) cw else ch
            val left = (rw - rcw) / 2; val top = (rh - rch) / 2
            val dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return bytes
            val bm = dec.decodeRegion(Rect(left, top, left + rcw, top + rch), null)
            dec.recycle()
            val rot = if (orient == 0) bm else Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(orient.toFloat()) }, true)
            val bo = java.io.ByteArrayOutputStream(bytes.size / 2)
            rot.compress(Bitmap.CompressFormat.JPEG, 100, bo)
            return bo.toByteArray()
        } catch (_: Throwable) { return bytes }
    }

    private fun ratioTag(): String { val r = frameRatioEff(); return if (r <= 0f) "" else "_AR%.2f".format(if (r < 1f) 1f / r else r) }

    // =====================================================================
    // Bluetooth gamepad (PS4 / PS5 / Xbox style) and volume-key shutter
    // =====================================================================
    private val PAD_HELP = "Pair the controller in Android Bluetooth settings first.\n" +
        "Cross / A: record or shoot\n" +
        "Circle / B: back (close panel, release focus lock)\n" +
        "Square / X: light meter on/off\n" +
        "Triangle / Y: video / photo\n" +
        "L1 / R1: previous / next control (ISO, SHUTTER, FOCUS, WB, EV)\n" +
        "Right stick left-right (or D-pad left-right): change the selected value\n" +
        "D-pad up / down: next / previous preset, or next / previous focus mark\n" +
        "L2 / R2 (analog): manual focus pull near / far, speed follows pressure\n" +
        "Left stick: move the on-screen cursor. L3 press: tap there (focus, rack, meter)\n" +
        "Right stick up-down: zoom. R3 press: lock the selected control\n" +
        "Options / Start: settings. Share / Select: flip front and rear camera\n" +
        "Volume keys on the phone also work as the shutter."

    private val inputMgr by lazy { getSystemService(Context.INPUT_SERVICE) as InputManager }
    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { refreshPad() }
        override fun onInputDeviceChanged(id: Int) { refreshPad() }
        override fun onInputDeviceRemoved(id: Int) { refreshPad() }
    }
    private var padName: String? = null
    private var padLX = 0f; private var padLY = 0f; private var padRX = 0f; private var padRY = 0f
    private var padLT = 0f; private var padRT = 0f
    private var padL2Key = false; private var padR2Key = false
    private var padHatX = 0; private var padHatY = 0
    private var padCursor: PointF? = null
    private var padCursorT = 0L
    private var padRunning = false
    private var padLastT = 0L
    private var padApplyT = 0L
    private var padAcc = 0f
    private var padFocusAcc = 0f
    private var padPresetIdx = -1
    private var padInfoT = 0L

    private fun isPadSource(src: Int) =
        (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun refreshPad() {
        runOnUiThread {
            if (!::padChip.isInitialized) return@runOnUiThread
            var name: String? = null
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id) ?: continue
                if (isPadSource(d.sources)) { name = d.name; break }
            }
            if (name != padName) {
                padName = name
                if (name != null) toast("Controller connected: $name") else overlay.cursor = null
            }
            padChip.visibility = if (name != null) View.VISIBLE else View.GONE
            padChip.setTextColor(Color.parseColor("#4CD964"))
        }
    }

    private fun showPadHelp() {
        try { AlertDialog.Builder(this).setTitle("Controller").setMessage(PAD_HELP).setPositiveButton("OK", null).show() } catch (_: Exception) {}
    }

    private fun dead(v: Float): Float { val a = abs(v); return if (a < 0.14f) 0f else Math.signum(v) * (a - 0.14f) / 0.86f }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) onShutter()
            return true
        }
        val fromPad = isPadSource(e.source) || KeyEvent.isGamepadButton(code)
        if (!fromPad) return super.dispatchKeyEvent(e)
        val down = e.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { padHatX = if (down) -1 else if (padHatX == -1) 0 else padHatX; padKick(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { padHatX = if (down) 1 else if (padHatX == 1) 0 else padHatX; padKick(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down && e.repeatCount == 0) padVertical(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down && e.repeatCount == 0) padVertical(-1); return true }
            KeyEvent.KEYCODE_BUTTON_L2 -> { padL2Key = down; padKick(); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { padR2Key = down; padKick(); return true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if (down && e.repeatCount == 0) padButton(code)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (isPadSource(ev.source) && ev.action == MotionEvent.ACTION_MOVE) { readPad(ev); padKick(); return true }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun readPad(ev: MotionEvent) {
        val d = ev.device
        fun has(a: Int) = d?.getMotionRange(a) != null
        fun trig(v: Float, a: Int): Float {
            val lo = d?.getMotionRange(a)?.min ?: 0f
            val n = if (lo < -0.5f) (v + 1f) / 2f else v
            return if (n < 0.05f) 0f else n.coerceAtMost(1f)
        }
        padLX = dead(ev.getAxisValue(MotionEvent.AXIS_X)); padLY = dead(ev.getAxisValue(MotionEvent.AXIS_Y))
        val zrz = has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RZ)
        val rxry = has(MotionEvent.AXIS_RX) && has(MotionEvent.AXIS_RY)
        val stdTrig = has(MotionEvent.AXIS_LTRIGGER) || has(MotionEvent.AXIS_BRAKE)
        if (stdTrig) {
            padLT = trig(max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE)), MotionEvent.AXIS_LTRIGGER)
            padRT = trig(max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS)), MotionEvent.AXIS_RTRIGGER)
            if (zrz) { padRX = dead(ev.getAxisValue(MotionEvent.AXIS_Z)); padRY = dead(ev.getAxisValue(MotionEvent.AXIS_RZ)) }
            else if (rxry) { padRX = dead(ev.getAxisValue(MotionEvent.AXIS_RX)); padRY = dead(ev.getAxisValue(MotionEvent.AXIS_RY)) }
        } else if (zrz && rxry) {
            padRX = dead(ev.getAxisValue(MotionEvent.AXIS_Z)); padRY = dead(ev.getAxisValue(MotionEvent.AXIS_RZ))
            padLT = trig(ev.getAxisValue(MotionEvent.AXIS_RX), MotionEvent.AXIS_RX); padRT = trig(ev.getAxisValue(MotionEvent.AXIS_RY), MotionEvent.AXIS_RY)
        } else if (rxry) {
            padRX = dead(ev.getAxisValue(MotionEvent.AXIS_RX)); padRY = dead(ev.getAxisValue(MotionEvent.AXIS_RY))
        }
        if (padL2Key) padLT = 1f
        if (padR2Key) padRT = 1f
        val hx = Math.round(ev.getAxisValue(MotionEvent.AXIS_HAT_X))
        val hy = Math.round(ev.getAxisValue(MotionEvent.AXIS_HAT_Y))
        if (hx != padHatX && (hx != 0 || has(MotionEvent.AXIS_HAT_X))) padHatX = hx
        if (hy != padHatY) { padHatY = hy; if (hy != 0) padVertical(if (hy < 0) 1 else -1) }
    }

    private fun padKick() {
        val now = SystemClock.elapsedRealtime()
        if (!padRunning) { padRunning = true; padLastT = now; ui.post(padLoop) }
    }

    private val padLoop = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - padLastT) / 1000f).coerceIn(0.001f, 0.1f)
            padLastT = now
            val busy = try { padStep(dt, now) } catch (_: Throwable) { false }
            if (busy) ui.postDelayed(this, 12) else padRunning = false
        }
    }

    /** Runs about 80 times a second while a stick, trigger or D-pad direction is held. Returns false once everything is centred. */
    private fun padStep(dt: Float, now: Long): Boolean {
        if (modalOpen()) return false
        var busy = false
        var dirty = false
        // cursor
        if (padLX != 0f || padLY != 0f) {
            busy = true
            val c = padCursor ?: PointF(imgRect.centerX(), imgRect.centerY()).also { padCursor = it }
            val sp = 0.9f * min(imgRect.width(), imgRect.height())
            c.x = (c.x + padLX * sp * dt).coerceIn(imgRect.left, imgRect.right)
            c.y = (c.y + padLY * sp * dt).coerceIn(imgRect.top, imgRect.bottom)
            overlay.cursor = c; padCursorT = now; overlay.invalidate()
        }
        // selected value
        val rate = if (padRX != 0f) padRX * abs(padRX) * 450f else padHatX * 90f
        if (rate != 0f) {
            busy = true
            if (selected != 0 && !isLocked(selected)) {
                padAcc += rate * dt
                val st = padAcc.toInt()
                if (st != 0 && now - padApplyT >= 28) {
                    padAcc -= st
                    val p = (currentProgress() + st).coerceIn(0, 1000)
                    stripSeek.progress = p
                    onSliderChanged(p)
                    padApplyT = now
                }
            }
        } else padAcc = 0f
        // focus pull with the triggers: R2 far, L2 near
        val pull = padRT - padLT
        if (pull != 0f) {
            busy = true
            if (minFocus() > 0f && !lockFocus) {
                if (!manualFocus || rackAnim?.isRunning == true) {
                    rackAnim?.cancel(); focusP = curFocusP(); manualFocus = true; afScan = false; waitSettle = false
                }
                padFocusAcc += -pull * abs(pull) * 420f * dt
                val st = padFocusAcc.toInt()
                if (st != 0) {
                    padFocusAcc -= st
                    focusP = (focusP + st).coerceIn(0, 1000)
                    dirty = true
                }
            }
        } else padFocusAcc = 0f
        // zoom
        if (padRY != 0f && zoomMax > zoomMin) {
            busy = true
            zoom = (zoom * exp(-padRY * 1.1f * dt)).coerceIn(zoomMin, zoomMax)
            dirty = true
            if (now - padInfoT > 120) { padInfoT = now; refreshInfo() }
        }
        if (padHatX != 0 || padLT != 0f || padRT != 0f) busy = true
        if (dirty && now - padApplyT >= 28) {
            padApplyT = now
            if (selected == 3) { stripSeek.progress = focusP; refreshStrip(false) }
            updateRepeating()
        }
        return busy
    }

    private fun padVertical(dir: Int) {
        if (selected == 3) {
            val set = (0..2).filter { marks[it] >= 0 }.sortedBy { marks[it] }
            if (set.isEmpty()) { toast("No focus marks yet. Tap subjects before recording."); return }
            val cur = curFocusP()
            val ci = set.indices.minByOrNull { abs(marks[set[it]] - cur) } ?: 0
            val ni = (ci + dir).coerceIn(0, set.size - 1)
            val m = if (abs(marks[set[ci]] - cur) > 25 && ((dir > 0 && marks[set[ci]] > cur) || (dir < 0 && marks[set[ci]] < cur))) ci else ni
            val tp = markPts[set[m]]
            if (tp != null) { afMode = 2; tappedSensor = tp }
            rackTo(marks[set[m]])
            return
        }
        if (presetRow.childCount == 0) return
        padPresetIdx = (padPresetIdx + dir).coerceIn(0, presetRow.childCount - 1)
        presetRow.getChildAt(padPresetIdx)?.performClick()
    }

    private fun padButton(code: Int) {
        if (modalOpen() && code != KeyEvent.KEYCODE_BUTTON_B && code != KeyEvent.KEYCODE_BUTTON_START) return
        when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> onShutter()
            KeyEvent.KEYCODE_BUTTON_B -> padBack()
            KeyEvent.KEYCODE_BUTTON_X -> toggleMeter()
            KeyEvent.KEYCODE_BUTTON_Y -> setMode(!videoMode)
            KeyEvent.KEYCODE_BUTTON_L1 -> selectParam((selected + 5) % 6)
            KeyEvent.KEYCODE_BUTTON_R1 -> selectParam((selected + 1) % 6)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                val c = padCursor ?: PointF(imgRect.centerX(), imgRect.centerY()).also { padCursor = it }
                overlay.cursor = c; padCursorT = SystemClock.elapsedRealtime()
                handleTap(c.x, c.y)
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> if (selected != 0) onLockClick() else toast("Pick a control with L1 / R1 first")
            KeyEvent.KEYCODE_BUTTON_START -> settingsBtn.performClick()
            KeyEvent.KEYCODE_BUTTON_SELECT -> flipCamera()
        }
    }

    private fun padBack() {
        when {
            gridOpen -> closeGrid()
            settingsLayer.visibility == View.VISIBLE -> setSettingsOpen(false)
            meterArmed || meterCard.visibility == View.VISIBLE -> closeMeter()
            afMode != 0 || lockFocus || manualFocus -> releaseLock()
            selected != 0 -> selectParam(0)
        }
    }

    // =====================================================================
    // Camera open / options
    // =====================================================================
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (device != null) return
        try {
            chars = cm.getCameraCharacteristics(ids[idIdx])
            computeOptions()
            cm.openCamera(ids[idIdx], object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { device = c; startPreviewSession() }
                override fun onDisconnected(c: CameraDevice) { c.close(); device = null }
                override fun onError(c: CameraDevice, e: Int) { c.close(); device = null; toast("Camera error $e") }
            }, bg)
        } catch (e: Exception) { toast("Open failed: ${e.message}") }
    }

    private fun closeCamera() {
        closeSession(); closeReaders()
        try { device?.close() } catch (_: Exception) {}
        device = null
    }

    private fun closeSession() { try { session?.close() } catch (_: Exception) {}; session = null }
    private fun closeReaders() {
        try { jpegReader?.close(); rawReader?.close(); maxReader?.close() } catch (_: Exception) {}
        jpegReader = null; rawReader = null; maxReader = null
    }

    // ---- encoder capability helpers (this is what fixes "prepare failed") ----
    private fun encoderCaps(mime: String): List<MediaCodecInfo.VideoCapabilities> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { ci -> ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } }
            .mapNotNull { ci -> try { ci.getCapabilitiesForType(mime).videoCapabilities } catch (_: Exception) { null } }
    } catch (_: Exception) { emptyList() }

    private fun sizeOk(mime: String, w: Int, h: Int, f: Int): Boolean =
        encoderCaps(mime).any { c ->
            try { c.isSizeSupported(w, h) && c.areSizeAndRateSupported(w, h, f.toDouble()) } catch (_: Exception) { false }
        }

    private fun maxBitrate(mime: String): Int = encoderCaps(mime).maxOfOrNull { it.bitrateRange.upper } ?: 20_000_000

    private fun computeOptions() {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val all = (map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray())
            .filter { it.width >= 1280 }.sortedByDescending { it.width.toLong() * it.height }.distinct()
        // keep only sizes this phone's video encoder can really record
        val ok = all.filter { sizeOk("video/avc", it.width, it.height, 24) || sizeOk("video/hevc", it.width, it.height, 24) }
        videoSizes = (ok.ifEmpty { all }).take(8)
        videoIdx = 0
        planKey = ""
        val opts = ArrayList<PhotoOpt>()
        if (Build.VERSION.SDK_INT >= 31) {
            val mr = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            val big = mr?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
            if (big != null) opts.add(PhotoOpt(big, true))
        }
        val jpgs = (map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()).sortedByDescending { it.width.toLong() * it.height }
        jpgs.take(3).forEach { opts.add(PhotoOpt(it, false)) }
        // a 16:9 size too, so vertical photos can be true 9:16
        jpgs.firstOrNull { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f && opts.none { o -> o.size == it } }?.let { opts.add(PhotoOpt(it, false)) }
        photoOpts = opts
        photoIdx = 0
        rawSize = if (hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } else null
        maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val fm = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: IntArray(0)
        faceMode = if (fm.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL)) CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
        else if (fm.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)) CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE else 0
        zoom = 1f; zoomMin = 1f; zoomMax = 1f
        if (Build.VERSION.SDK_INT >= 30) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { zoomMin = it.lower; zoomMax = min(it.upper, 10f); zoom = max(1f, zoomMin) }
        }
        if (pendingZoom > 0f) { zoom = pendingZoom.coerceIn(zoomMin, zoomMax); pendingZoom = 0f }
        hasLowBoost = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.contains(6) == true
        afMode = 0; afRegion = null; appliedRegion = null; afScan = false; waitSettle = false
        maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        analogMax = chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: 0
        val cs = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val cr = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        aeCompOk = cs > 0f && cr != null && cr.upper > 0
        val newCam = openedId != ids[idIdx]
        openedId = ids[idIdx]
        if (newCam) {
            // a different physical camera: everything tied to the old lens is stale
            manualFocus = false; lockFocus = false; manualWb = false; lockWb = false; wbAutoHold = false; wbBase = null
            lastGains = null; lastTransform = null; lastFocusDist = 0f
            rackAnim?.cancel()
            for (i in 0..2) { marks[i] = -1; markPts[i] = null }
            markNext = 0
            if (!hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) { expMode = 0; lockExp = false }
        }
        awbSince = SystemClock.elapsedRealtime()
        runOnUiThread {
            if (newCam) { updateMarkChips(); stylePills(); refreshStrip(true) }
            if (!warnedOnce) {
                warnedOnce = true
                val tm = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
                if (!tm.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) && !tm.contains(CameraMetadata.TONEMAP_MODE_GAMMA_VALUE))
                    toast("This phone does not allow custom tone curves: Flat/Log will be only partly flat. RAW DNG photos stay fully unprocessed.")
                else if (!avail(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_OFF))
                    toast("This phone's hardware may still apply some sharpening that apps cannot switch off.")
            }
            rawSwitch.isEnabled = rawSize != null
            lowBoostRow.visibility = if (hasLowBoost) View.VISIBLE else View.GONE
            refreshFlipUi()
            buildPresets(); updateAudioChip()
        }
        refreshInfo()
    }

    private var warnedOnce = false
    private var planKey = ""
    private var planCfg: RecCfg? = null
    private fun plannedCfg(): RecCfg? {
        val key = "$idIdx|$videoIdx|$fpsIdx|$compat264"
        if (key != planKey) { planKey = key; planCfg = buildCandidates().firstOrNull() }
        return planCfg
    }

    private fun refreshInfo() = runOnUiThread {
        if (!::chars.isInitialized) return@runOnUiThread
        val z = if (zoom > 1.01f) "  x%.1f".format(zoom) else ""
        if (videoMode) {
            val cfg = curRec ?: plannedCfg()
            fmtChip.text = if (cfg != null) "${cfg.size.width}x${cfg.size.height}  ${cfg.fps}p  ${if (cfg.hevc) "HEVC" else "H.264"}$z" else "-"
        } else {
            val o = photoOpts.getOrNull(photoIdx)
            fmtChip.text = if (o != null) "%.1fMP  ".format(o.size.width.toLong() * o.size.height / 1e6) + (if (o.maxRes) "MAX  " else "") + (if (rawOn) "JPG+DNG" else "JPG") + z else "-"
        }
        videoChip.text = videoSizes.getOrNull(videoIdx)?.let { "${it.width}x${it.height}" } ?: "-"
        photoChip.text = photoOpts.getOrNull(photoIdx)?.let { "${it.size.width}x${it.size.height}" + if (it.maxRes) " MAX" else "" } ?: "-"
        fpsChip.text = "${fpsOpts[fpsIdx]}"
    }

    // =====================================================================
    // Sessions
    // =====================================================================
    private fun choosePreview(target: Size): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val all = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1920 }
        val same = all.filter { it.width.toLong() * target.height == it.height.toLong() * target.width }
        return (same.ifEmpty { all }).maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)
    }

    private fun mirrorOn() = curFacing == 1 && mirrorPreview

    private fun configureTransform() {
        if (!::tv.isInitialized) return
        val vw = tv.width.toFloat(); val vh = tv.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val rotation = displayRot()
        val m = Matrix()
        val viewRect = RectF(0f, 0f, vw, vh)
        val cx = viewRect.centerX(); val cy = viewRect.centerY()
        val iw: Float
        val ih: Float
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val buf = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
            buf.offset(cx - buf.centerX(), cy - buf.centerY())
            m.setRectToRect(viewRect, buf, Matrix.ScaleToFit.FILL)
            val scale = min(vh / previewSize.height, vw / previewSize.width)
            m.postScale(scale, scale, cx, cy)
            m.postRotate(90f * (rotation - 2), cx, cy)
            iw = previewSize.width * scale; ih = previewSize.height * scale
        } else {
            // upright (portrait) picture: the buffer arrives already turned, so just letterbox it
            val ar = previewSize.height.toFloat() / previewSize.width
            val fw = min(vw, vh * ar); val fh = fw / ar
            m.setRectToRect(viewRect, RectF(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f), Matrix.ScaleToFit.FILL)
            if (rotation == Surface.ROTATION_180) m.postRotate(180f, cx, cy)
            iw = fw; ih = fh
        }
        if (mirrorOn()) m.postScale(-1f, 1f, cx, cy)
        tv.setTransform(m)
        imgRect.set(cx - iw / 2f, cy - ih / 2f, cx + iw / 2f, cy + ih / 2f)
        overlay.img.set(imgRect)
        overlay.invalidate()
    }

    private fun startPreviewSession() {
        val d = device ?: return
        if (!::chars.isInitialized) return
        closeSession(); closeReaders()
        val st = tv.surfaceTexture ?: return
        val target = if (videoMode) videoSizes.getOrNull(videoIdx) ?: Size(1920, 1080)
                     else photoOpts.getOrNull(photoIdx)?.size ?: Size(4000, 3000)
        previewSize = choosePreview(target)
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val ps = Surface(st)
        previewSurface = ps
        runOnUiThread { configureTransform() }

        val outs = ArrayList<OutputConfiguration>()
        outs.add(OutputConfiguration(ps))
        if (!videoMode) {
            val opt = photoOpts.getOrNull(photoIdx)
            if (opt != null && opt.maxRes) {
                val r = ImageReader.newInstance(opt.size.width, opt.size.height, ImageFormat.JPEG, 1)
                r.setOnImageAvailableListener(jpegListener, bg)
                maxReader = r
                val oc = OutputConfiguration(r.surface)
                if (Build.VERSION.SDK_INT >= 31) oc.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                outs.add(oc)
            } else if (opt != null) {
                val r = ImageReader.newInstance(opt.size.width, opt.size.height, ImageFormat.JPEG, 4)
                r.setOnImageAvailableListener(jpegListener, bg)
                jpegReader = r
                outs.add(OutputConfiguration(r.surface))
                val rs = rawSize
                if (rawOn && rs != null) {
                    val rr = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2)
                    rr.setOnImageAvailableListener({ x ->
                        val img = x.acquireNextImage()
                        if (img != null) { pendingRaw?.close(); pendingRaw = img; trySaveRaw() }
                    }, bg)
                    rawReader = rr
                    outs.add(OutputConfiguration(rr.surface))
                }
            }
        }
        createSession(d, outs)
    }

    private fun createSession(d: CameraDevice, outs: List<OutputConfiguration>) {
        try {
            val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; retried = false; awbSince = SystemClock.elapsedRealtime(); updateRepeating() }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        toast("Camera session failed for this combination")
                        if (!videoMode && !retried) {
                            retried = true
                            rawOn = false
                            runOnUiThread { rawSwitch.isChecked = false }
                            if (photoOpts.getOrNull(photoIdx)?.maxRes == true && photoOpts.size > 1) photoIdx = 1
                            startPreviewSession()
                        }
                    }
                })
            d.createCaptureSession(sc)
        } catch (e: Exception) { toast("Session error: ${e.message}") }
    }

    private val previewCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExpNs = it }
            res.get(CaptureResult.LENS_APERTURE)?.let { lastAperture = it }
            val fd = res.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (fd != null) lastFocusDist = fd
            if (!manualWb && res.get(CaptureResult.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_AUTO) {
                res.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { lastGains = it }
                res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastTransform = it }
            }
            val fs = res.get(CaptureResult.STATISTICS_FACES)
            faceRects = if (fs == null) emptyList() else fs.filter { it.score >= 40 }.map { Rect(it.bounds) }

            // tap-to-focus: wait for the phone to finish focusing, then freeze the lens there
            val af = res.get(CaptureResult.CONTROL_AF_STATE)
            if (af != null) {
                if (afScan && now - scanStart > 350 &&
                    (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                    afScan = false
                    val ok = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    ui.post { finishScan(fd, ok) }
                } else if (waitSettle) {
                    if (af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                        if (settledSince == 0L) settledSince = now
                        if (now - settledSince > 800 && now - tapTime > 700) { waitSettle = false; ui.post { finishScan(fd, true) } }
                    } else settledSince = 0L
                }
            }

            // light meter: the phone's exposure engine has been pointed at the chosen spot
            if (meterPhase == 1) {
                val ae = res.get(CaptureResult.CONTROL_AE_STATE)
                val i = res.get(CaptureResult.SENSOR_SENSITIVITY)
                val t = res.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val settled = ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                if (settled && now - meterStart > 800) meterConv++ else if (!settled) meterConv = 0
                if (i != null && t != null && (meterConv >= 4 || now - meterStart > 3500)) {
                    meterPhase = 2
                    ui.post { finishMeter(i, t) }
                }
            }
        }
    }

    private fun updateRepeating() {
        val s = session ?: return
        val d = device ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(if (videoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            if (recording) recorderSurface?.let { b.addTarget(it) }
            applySettings(b, videoMode)
            if (faceAf && faceMode > 0) b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode)
            s.setRepeatingRequest(b.build(), previewCb, bg)
        } catch (e: Exception) { /* session is closing */ }
    }

    // =====================================================================
    // The core: manual controls + processing removal
    // =====================================================================
    private fun applySettings(b: CaptureRequest.Builder, video: Boolean) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

        // exposure: 0 = phone AE, 1 = cine (locked shutter, smart ISO), 2 = manual (EV trims it)
        val canManual = hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val em = effMode()
        if (meterPhase == 1) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            val mr = meterRegion
            if (maxAeRegions > 0 && mr != null) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(toMetering(mr)))
        } else if (em != 0 && canManual) {
            val iso: Int
            val ns: Long
            if (em == 2) { val mm = effectiveManual(); iso = mm.first; ns = mm.second } else { iso = cineIso; ns = cineShutNs }
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(ns, (1e9 / fps).toLong()))
        } else {
            val boost = lowBoost && hasLowBoost
            b.set(CaptureRequest.CONTROL_AE_MODE, if (boost) 6 else CameraMetadata.CONTROL_AE_MODE_ON)
            val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
            val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            if (aeCompOk && step > 0f && range != null) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (ev() / step).roundToInt().coerceIn(range.lower, range.upper))
        }

        if (video) {
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val r = ranges?.firstOrNull { it.lower == fps && it.upper == fps }
                ?: ranges?.filter { it.upper == fps }?.maxByOrNull { it.lower }
            if (r != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
        }

        // focus
        if (manualFocus && minFocus() > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocus() * focusP / 1000f)
        } else {
            val wantAf = if (afScan) CameraMetadata.CONTROL_AF_MODE_AUTO
                else if (video) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            if (avail(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, wantAf)) b.set(CaptureRequest.CONTROL_AF_MODE, wantAf)
            val reg = afRegion
            if (reg != null && maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(reg))
        }

        // white balance: manual Kelvin, or the measured gains held steady while flat colour is on
        val k = satK()
        val flatOn = flatActive()
        val base = lastTransform
        val g = when { manualWb -> gainsFor(kelvinFromP(kelvinP)); flatOn && wbAutoHold -> wbBase; else -> null }
        if (g != null && base != null) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, g)
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, if (flatOn) flatTransform(base, k) else base)
        } else b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

        // processing removal: Flat and Log always force sharpening and fringe correction off
        val edgeSel = if (profile > 0) 0 else edgeLvl
        val edge = intArrayOf(CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST, CameraMetadata.EDGE_MODE_HIGH_QUALITY)[edgeSel]
        if (avail(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, edge)) b.set(CaptureRequest.EDGE_MODE, edge)
        if (profile > 0 && avail(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF))
            b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
        var nrL = nrLvl
        if (expMode == 1 && cineIso > isoRange().lower * 12 && nrL == 0) nrL = 1   // light temporal NR only when really dark
        val nr = intArrayOf(CameraMetadata.NOISE_REDUCTION_MODE_OFF, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_FAST)[nrL]
        if (avail(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, nr)) b.set(CaptureRequest.NOISE_REDUCTION_MODE, nr)

        // flat / log-like tone curve replaces the phone's contrast curve
        if (profile > 0) {
            if (avail(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) {
                val c = logCurve(if (profile == 1) 6f else 24f)
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(c, c, c))
            } else if (avail(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)) {
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                b.set(CaptureRequest.TONEMAP_GAMMA, if (profile == 1) 1.6f else 1.3f)
            }
        }

        // stabilisation
        if (avail(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON))
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        if (video && avail(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON))
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (eis) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

        // zoom
        if (Build.VERSION.SDK_INT >= 30 && zoomMax > zoomMin) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
    }

    private fun satK() = if (profile > 0) satOpts[satIdx] else 1f
    private fun flatActive() = ::chars.isInitialized && satK() < 0.99f && hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)

    private var ftSrc: ColorSpaceTransform? = null
    private var ftK = 1f
    private var ftOut: ColorSpaceTransform? = null

    /** Scales the colour matrix toward luma so colour comes out at k x its normal saturation (k=1 unchanged). */
    private fun flatTransform(t: ColorSpaceTransform, k: Float): ColorSpaceTransform {
        if (ftSrc === t && ftK == k) ftOut?.let { return it }
        val m = Array(3) { r -> DoubleArray(3) { c -> t.getElement(c, r).toDouble() } }
        val lum = doubleArrayOf(0.2126, 0.7152, 0.0722)
        val out = Array(9) { i ->
            val r = i / 3; val c = i % 3
            var sum = 0.0
            for (j in 0..2) sum += ((if (r == j) k.toDouble() else 0.0) + (1.0 - k) * lum[j]) * m[j][c]
            Rational((sum * 10000).roundToInt(), 10000)
        }
        val res = ColorSpaceTransform(out)
        ftSrc = t; ftK = k; ftOut = res
        return res
    }

    /** y = ln(1 + k x) / ln(1 + k): lifts shadows and compresses highlights so more range fits the file. */
    private fun logCurve(k: Float): FloatArray {
        val n = 33
        val a = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = i / (n - 1f)
            a[2 * i] = x
            a[2 * i + 1] = ln(1f + k * x) / ln(1f + k)
        }
        return a
    }

    // =====================================================================
    // Value mapping helpers
    // =====================================================================
    private fun hasCap(c: Int) = ::chars.isInitialized && chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(c) == true
    private fun avail(key: CameraCharacteristics.Key<IntArray>, v: Int) = ::chars.isInitialized && chars.get(key)?.contains(v) == true
    private fun minFocus() = if (::chars.isInitialized) chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f else 0f
    private fun ev() = (evP - 500) / 500f * 3f

    private fun isoRange(): Range<Int> =
        (if (::chars.isInitialized) chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) else null) ?: Range(100, 3200)

    private fun expRange(): Range<Long> =
        (if (::chars.isInitialized) chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null) ?: Range(100_000L, 1_000_000_000L)

    private fun isoVal(): Int {
        val r = isoRange()
        return (r.lower * (r.upper.toDouble() / r.lower).pow(isoP / 1000.0)).roundToInt()
    }

    private fun shutterHi(): Double = min(expRange().upper.toDouble(), if (videoMode) 1e9 / fps else 1e9)

    private fun shutterNs(): Long {
        val lo = expRange().lower.toDouble()
        val hi = max(lo * 1.01, shutterHi())
        return (lo * (hi / lo).pow(shutP / 1000.0)).toLong()
    }

    private fun pFromIso(iso: Int): Int {
        val r = isoRange()
        return (1000.0 * ln(iso.toDouble().coerceIn(r.lower.toDouble(), r.upper.toDouble()) / r.lower) / ln(r.upper.toDouble() / r.lower)).roundToInt().coerceIn(0, 1000)
    }

    private fun pFromShutter(ns: Long): Int {
        val lo = expRange().lower.toDouble()
        val hi = max(lo * 1.01, shutterHi())
        return (1000.0 * ln(ns.toDouble().coerceIn(lo, hi) / lo) / ln(hi / lo)).roundToInt().coerceIn(0, 1000)
    }

    private fun shutterText(ns: Long) = if (ns >= 500_000_000L) "%.1fs".format(ns / 1e9) else "1/${(1e9 / max(ns, 1L)).roundToInt()}"
    private fun shutterAngle(ns: Long) = "${(ns / 1e9 * fps * 360).roundToInt()}°"

    private fun kelvinFromP(p: Int) = 2000f + 8f * p

    private fun kelvinToRgb(k: Float): FloatArray {
        val t = k / 100f
        val r = if (t <= 66f) 255f else 329.698727446f * (t - 60f).pow(-0.1332047592f)
        val g = if (t <= 66f) 99.4708025861f * ln(t) - 161.1195681661f else 288.1221695283f * (t - 60f).pow(-0.0755148492f)
        val b = if (t >= 66f) 255f else if (t <= 19f) 1f else 138.5177312231f * ln(t - 10f) - 305.0447927307f
        return floatArrayOf(r.coerceIn(1f, 255f) / 255f, g.coerceIn(1f, 255f) / 255f, b.coerceIn(1f, 255f) / 255f)
    }

    /** Relative neutralising gains for a colour temperature, applied on top of the auto-WB gains captured when manual WB was switched on. */
    private fun gainsFor(k: Float): RggbChannelVector? {
        val base = wbBase ?: return null
        fun n(x: Float): FloatArray { val c = kelvinToRgb(x); return floatArrayOf(c[1] / c[0], c[1] / c[2]) }
        val a = n(k); val ref = n(kelvinFromP(437))
        return RggbChannelVector(base.red * a[0] / ref[0], base.greenEven, base.greenOdd, base.blue * a[1] / ref[1])
    }

    /** EV only works through the phone's AE when it supports compensation; otherwise cine auto-ISO provides it. */
    private fun effMode(): Int =
        if (expMode == 0 && !aeCompOk && abs(ev()) > 0.05f && hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) 1 else expMode

    /** Manual ISO / shutter with the EV slider applied as a trim (ISO first, then shutter when ISO runs out). */
    private fun effectiveManual(): Pair<Int, Long> {
        val r = isoRange(); val e = expRange()
        var iso = isoVal().toDouble()
        var ns = shutterNs().toDouble()
        if (abs(ev()) > 0.01f) {
            iso *= 2.0.pow(ev().toDouble())
            if (iso > r.upper) { ns = min(ns * iso / r.upper, shutterHi()); iso = r.upper.toDouble() }
            else if (iso < r.lower) { ns = max(ns * iso / r.lower, e.lower.toDouble()); iso = r.lower.toDouble() }
        }
        return Pair(iso.roundToInt().coerceIn(r.lower, r.upper), ns.toLong().coerceIn(e.lower, e.upper))
    }

    private fun pFromDist(d: Float): Int {
        val mf = minFocus()
        return if (mf <= 0f) 0 else (d / mf * 1000f).roundToInt().coerceIn(0, 1000)
    }

    // =====================================================================
    // Geometry: view <-> sensor
    // =====================================================================
    /** Clockwise rotation (0/90/180/270) that turns the sensor picture upright for the current display rotation. */
    private fun orient(): Int {
        val deg = when (displayRot()) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        return ((chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) - deg + 360) % 360
    }

    /** The part of the sensor that is visible in the preview, in sensor coordinates (accounts for crop and zoom). */
    private fun visibleRect(): RectF {
        val a = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
        val target = previewSize.width.toFloat() / previewSize.height
        val arr = a.width().toFloat() / a.height()
        var vw: Float
        var vh: Float
        if (arr > target) { vh = a.height().toFloat(); vw = vh * target } else { vw = a.width().toFloat(); vh = vw / target }
        vw /= zoom; vh /= zoom
        val cx = a.exactCenterX(); val cy = a.exactCenterY()
        return RectF(cx - vw / 2, cy - vh / 2, cx + vw / 2, cy + vh / 2)
    }

    private fun viewToSensor(x: Float, y: Float): PointF {
        var u = ((x - imgRect.left) / imgRect.width()).coerceIn(0f, 1f)
        val v = ((y - imgRect.top) / imgRect.height()).coerceIn(0f, 1f)
        if (mirrorOn()) u = 1f - u
        val bu: Float
        val bv: Float
        when (orient()) {
            90 -> { bu = v; bv = 1f - u }
            180 -> { bu = 1f - u; bv = 1f - v }
            270 -> { bu = 1f - v; bv = u }
            else -> { bu = u; bv = v }
        }
        val vis = visibleRect()
        return PointF(vis.left + bu * vis.width(), vis.top + bv * vis.height())
    }

    private fun sensorToView(sx: Float, sy: Float): PointF {
        val vis = visibleRect()
        val bu = (sx - vis.left) / vis.width()
        val bv = (sy - vis.top) / vis.height()
        var u: Float
        val v: Float
        when (orient()) {
            90 -> { u = 1f - bv; v = bu }
            180 -> { u = 1f - bu; v = 1f - bv }
            270 -> { u = bv; v = 1f - bu }
            else -> { u = bu; v = bv }
        }
        if (mirrorOn()) u = 1f - u
        return PointF(imgRect.left + u * imgRect.width(), imgRect.top + v * imgRect.height())
    }

    private fun sensorRectToView(r: Rect): RectF {
        val a = sensorToView(r.left.toFloat(), r.top.toFloat())
        val b = sensorToView(r.right.toFloat(), r.bottom.toFloat())
        return RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    private fun toMetering(r: Rect): MeteringRectangle {
        val a = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
        val l = r.left.coerceIn(a.left, a.right - 2)
        val t = r.top.coerceIn(a.top, a.bottom - 2)
        val w = r.width().coerceIn(2, a.right - l)
        val h = r.height().coerceIn(2, a.bottom - t)
        return MeteringRectangle(l, t, w, h, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    // =====================================================================
    // Smart AF: face detection, face lock, tap-to-focus lock, light meter, histogram, cine auto-ISO
    // =====================================================================
    private val tick = object : Runnable {
        override fun run() {
            try { doTick() } catch (_: Throwable) {}
            ui.postDelayed(this, 140)
        }
    }

    private fun doTick() {
        tickN++
        if (recording) {
            val s = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            timerTv.text = "● %02d:%02d".format(s / 60, s % 60)
            updateVu()
        } else { timerTv.text = ""; vu.level = 0f }
        if (tickN % 2 == 0) updatePills()
        if (tickN % 14 == 1) updateAudioChip()
        val now = SystemClock.elapsedRealtime()
        if (afScan && now - scanStart > 3000) { afScan = false; toast("Focus search timed out"); updateRepeating() }
        if (waitSettle && now - tapTime > 4500) { waitSettle = false; finishScan(lastFocusDist, true) }
        val vw = tv.width; val vh = tv.height
        if (!tv.isAvailable || vw == 0 || vh == 0 || device == null || !::chars.isInitialized) return
        if (imgRect.width() <= 0f) configureTransform()
        // flat colour: once auto white balance has settled, freeze it so the grade stays consistent
        if (flatActive()) {
            if (!manualWb && !wbAutoHold && lastGains != null && lastTransform != null && now - awbSince > 1200) {
                wbBase = lastGains; wbAutoHold = true; updateRepeating()
            }
        } else if (wbAutoHold) { wbAutoHold = false; awbSince = now }
        remoteTick(now)
        if (padCursorT != 0L && now - padCursorT > 4000) { overlay.cursor = null; padCursorT = 0L }
        grabGray(vw, vh)
        val stats = lumaStats(vw, vh)
        if (showHist) hist.update(stats.bins, stats.clip, stats.crush)
        val em = effMode()
        if (em == 1 && prevEff != 1) { autoE = lastIso.toDouble() * lastExpNs; wide = false; cineIso = lastIso; cineShutNs = lastExpNs }
        prevEff = em
        if (em == 1 && tickN % 2 == 0 && stats.valid && meterPhase == 0) cineStep(stats.mean, stats.clip)
        trackAndFocus(vw, vh)
        updateAfChip()
    }

    private class Stats(val bins: IntArray, val mean: Float, val clip: Float, val crush: Float, val valid: Boolean)

    private fun grabGray(vw: Int, vh: Int) {
        val w = 160
        val h = (160f * vh / vw).roundToInt().coerceAtLeast(40)
        if (gbmp == null || gw != w || gh != h) {
            gbmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            gpx = IntArray(w * h); gray = IntArray(w * h); gw = w; gh = h
        }
        tv.getBitmap(gbmp!!)
        gbmp!!.getPixels(gpx, 0, w, 0, 0, w, h)
        for (i in gpx.indices) {
            val p = gpx[i]
            gray[i] = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
        }
    }

    private fun lumaStats(vw: Int, vh: Int): Stats {
        val bins = IntArray(48)
        val x0 = (imgRect.left / vw * gw).toInt().coerceIn(0, gw - 1)
        val x1 = (imgRect.right / vw * gw).toInt().coerceIn(x0 + 1, gw)
        val y0 = (imgRect.top / vh * gh).toInt().coerceIn(0, gh - 1)
        val y1 = (imgRect.bottom / vh * gh).toInt().coerceIn(y0 + 1, gh)
        var sum = 0L; var n = 0; var clip = 0; var crush = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val g = gray[y * gw + x]
            bins[g * 48 / 256]++
            sum += g; n++
            if (g >= 250) clip++
            if (g <= 6) crush++
        }
        val nn = max(n, 1)
        val mean = sum / (nn * 255f)
        return Stats(bins, mean, clip.toFloat() / nn, crush.toFloat() / nn, mean > 0.004f || clip > 0)
    }

    /**
     * Cine auto-ISO: shutter stays at 180 degrees (like a cinema camera) and ISO follows the scene smoothly.
     * When ISO would climb high, the shutter opens to 360 degrees first, which halves the ISO needed and
     * keeps the image clean. Exposure aims slightly bright (expose-to-the-right) because the flat/log curve
     * pulls highlights back down, and this puts the shadows well above the noise floor.
     */
    private fun cineStep(mean: Float, clip: Float) {
        val r = isoRange()
        val e = expRange()
        if (autoE <= 0.0) autoE = lastIso.toDouble() * lastExpNs
        val target = (0.44 * 2.0.pow(ev() * 0.6)).coerceIn(0.10, 0.88)
        var err = ln(target / max(mean.toDouble(), 0.03)) / ln(2.0)
        if (clip > 0.02f) err = min(err, -0.25)
        var step = (0.4 * err).coerceIn(-0.3, 0.3)
        if (abs(err) < 0.06) step = 0.0
        autoE *= 2.0.pow(step * 1.5)
        val frameNs = 1e9 / fps
        var t = 0.5 * frameNs
        if (!wide && autoE / t > r.lower * 8.0) wide = true
        if (wide && autoE / (0.5 * frameNs) < r.lower * 4.0) wide = false
        if (wide) t = min(frameNs, e.upper.toDouble())
        var iso = autoE / t
        if (iso > r.upper) { iso = r.upper.toDouble(); autoE = iso * t }
        if (iso < r.lower) { iso = r.lower.toDouble(); t = (autoE / iso).coerceIn(e.lower.toDouble(), t); autoE = iso * t }
        val ni = iso.roundToInt()
        val nt = t.toLong()
        if (abs(ni - cineIso) > cineIso * 0.03 || abs(nt - cineShutNs) > cineShutNs * 0.03) {
            cineIso = ni; cineShutNs = nt
            updateRepeating()
        }
    }

    // ---- tapping ------------------------------------------------------------------
    private fun handleTap(x: Float, y: Float) {
        if (modalOpen() || !imgRect.contains(x, y) || gw == 0) return
        overlay.ripple(x, y)
        val hit = faceViewRects.indexOfFirst { r ->
            val e = RectF(r); e.inset(-r.width() * 0.2f, -r.height() * 0.2f); e.contains(x, y)
        }
        if (meterArmed) { startMeter(x, y, hit); return }
        if (lockFocus) { toast("Focus is locked. Unlock it in the FOCUS strip."); return }
        val mi = nearestMark(x, y)
        if (mi >= 0) {
            // a subject that was focused before: pull to it smoothly instead of hunting
            afMode = 2; lockCenter = null; lostTicks = 0
            val v = sensorToView(markPts[mi]!!.x, markPts[mi]!!.y)
            val half = 0.075f * imgRect.height()
            pointRect = RectF(v.x - half, v.y - half, v.x + half, v.y + half)
            tappedSensor = markPts[mi]
            rackTo(marks[mi])
            return
        }
        lostTicks = 0; lastLockRegion = null; lockRect = null
        rackAnim?.cancel()
        if (hit >= 0 && faceAf) {
            // follow this face with real face detection
            manualFocus = false; afScan = false; waitSettle = false
            afMode = 1
            lockCenter = PointF(faceViewRects[hit].centerX(), faceViewRects[hit].centerY())
            afRegion = null; appliedRegion = null
            updateRepeating()
        } else {
            // focus on exactly this spot (person facing away, object, background) and hold it there
            afMode = 2
            val p = viewToSensor(x, y)
            tappedSensor = p
            val side = 0.15f * visibleRect().height()
            val region = Rect((p.x - side / 2).toInt(), (p.y - side / 2).toInt(), (p.x + side / 2).toInt(), (p.y + side / 2).toInt())
            val half = 0.075f * imgRect.height()
            pointRect = RectF(x - half, y - half, x + half, y + half)
            focusAt(region)
        }
    }

    private fun focusAt(region: Rect) {
        if (!avail(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, CameraMetadata.CONTROL_AF_MODE_AUTO)) { toast("This lens has fixed focus"); return }
        if (recording && minFocus() > 0f && marks.any { it >= 0 }) toast("New subject while recording: the phone's own focus will move. Tap subjects before recording for smooth pulls.")
        if (maxAfRegions <= 0) toast("This phone ignores tap regions, so it will focus on the centre")
        appliedRegion = region
        afRegion = toMetering(region)
        manualFocus = false
        tapTime = SystemClock.elapsedRealtime()
        if (recording) { afScan = false; waitSettle = true; settledSince = 0L }
        else { afScan = true; waitSettle = false; scanStart = tapTime }
        updateRepeating()
        if (afScan) sendAfTrigger()
    }

    private fun sendAfTrigger() {
        val s = session ?: return
        val d = device ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(if (videoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            if (recording) recorderSurface?.let { b.addTarget(it) }
            applySettings(b, videoMode)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), previewCb, bg)
        } catch (_: Exception) {}
    }

    /** Called when the phone has finished focusing on the tapped spot: freeze the lens there. */
    private fun finishScan(dist: Float?, ok: Boolean) {
        afScan = false; waitSettle = false
        if (dist != null && minFocus() > 0f && !lockFocus) {
            focusP = pFromDist(dist)
            manualFocus = true
            if (afMode == 2) autoMark(focusP, tappedSensor)
        }
        if (!ok) toast("Could not lock focus there. Try a spot with more texture or light.")
        updateRepeating()
        refreshStrip(true)
        updatePills()
    }

    private fun releaseLock() {
        afMode = 0; lockCenter = null; lockRect = null; lastLockRegion = null; pointRect = null
        primaryCenter = null; afScan = false; waitSettle = false
        manualFocus = false; lockFocus = false
        afRegion = null; appliedRegion = null
        rackAnim?.cancel()
        overlay.target = null; overlay.targetState = 0
        refreshStrip(true); stylePills()
        updateRepeating()
        toast("Auto face focus")
    }

    // ---- light meter -----------------------------------------------------------------
    private fun toggleMeter() {
        meterArmed = !meterArmed
        meterBtn.background = round(if (meterArmed) amber else glass, 14, hair)
        meterBtn.setTextColor(if (meterArmed) Color.BLACK else Color.WHITE)
        if (meterArmed) toast("Tap a face or object to read the exposure for it")
        else closeMeter()
    }

    private fun closeMeter() {
        meterArmed = false; meterPhase = 0
        meterBtn.background = round(glass, 14, hair); meterBtn.setTextColor(Color.WHITE)
        meterCard.visibility = View.GONE
        overlay.meter = null; overlay.invalidate()
        updateRepeating()
    }

    private fun startMeter(x: Float, y: Float, hit: Int) {
        val region: Rect
        val face = hit >= 0 && hit < facesSensor.size
        if (face) region = expand(facesSensor[hit], 0.8f)
        else {
            val p = viewToSensor(x, y)
            val side = 0.10f * visibleRect().height()
            region = Rect((p.x - side / 2).toInt(), (p.y - side / 2).toInt(), (p.x + side / 2).toInt(), (p.y + side / 2).toInt())
        }
        val half = if (face) max(faceViewRects[hit].width(), faceViewRects[hit].height()) * 0.4f else 0.05f * imgRect.height()
        overlay.meter = RectF(x - half, y - half, x + half, y + half)
        overlay.invalidate()
        meterFace = face
        meterRegion = region
        meterConv = 0
        meterStart = SystemClock.elapsedRealtime()
        meterPhase = 1
        meterText.text = if (face) "Metering the face..." else "Metering the spot..."
        meterCard.visibility = View.VISIBLE
        if (maxAeRegions <= 0) toast("This phone cannot spot-meter, so the reading covers the whole frame")
        updateRepeating()
    }

    private fun ndName(stops: Double): String {
        val n = kotlin.math.ceil(stops).toInt().coerceIn(1, 10)
        return "ND${if (n == 10) 1000 else 1 shl n}"
    }

    /** The phone's own exposure engine metered the chosen spot; turn that into usable ISO / shutter numbers. */
    private fun finishMeter(iso: Int, ns: Long) {
        meterPhase = 0
        val r = isoRange(); val e = expRange()
        var total = iso.toDouble() * ns
        if (meterFace) total *= 2.0.pow(0.6)            // skin sits about 0.6 stop above middle grey
        val frameNs = 1e9 / fps
        val tPref = 0.5 * frameNs                        // 180 degree shutter
        var i2 = total / tPref
        var t2 = tPref
        val advice: String
        if (i2 < r.lower) {
            val stops = ln(r.lower / i2) / ln(2.0)
            i2 = r.lower.toDouble()
            t2 = (total / i2).coerceAtLeast(e.lower.toDouble())
            advice = "Bright: for ${shutterText(tPref.toLong())} at ISO ${r.lower} add ${ndName(stops)} (-%.1f stops) or accept ${shutterText(t2.toLong())}".format(stops)
        } else if (i2 > r.upper) {
            i2 = r.upper.toDouble()
            t2 = min(total / i2, frameNs)
            advice = "Dark: at max ISO. Add light, or accept ${shutterText(t2.toLong())}"
        } else {
            advice = if (analogMax > 0 && i2 > analogMax) "ISO is above the analog limit ($analogMax): expect some noise" else "Clean exposure: ISO is within the analog range"
        }
        meterIso = i2.roundToInt()
        meterNs = t2.toLong()
        val ap = lastAperture
        val ev100 = if (ap > 0f) "  EV100 %.1f".format(ln((ap * ap).toDouble() / (ns / 1e9)) / ln(2.0) - ln(iso / 100.0) / ln(2.0)) else ""
        meterText.text = (if (meterFace) "FACE " else "SPOT ") + "ISO $meterIso  ${shutterText(meterNs)} ${shutterAngle(meterNs)}$ev100\n$advice"
        updateRepeating()
    }

    private fun applyMeter(lock: Boolean) {
        if (meterPhase != 0) return
        expMode = 2
        isoP = pFromIso(meterIso); shutP = pFromShutter(meterNs); evP = 500
        if (lock) lockExp = true
        meterCard.visibility = View.GONE
        overlay.meter = null
        meterArmed = false
        meterBtn.background = round(glass, 14, hair); meterBtn.setTextColor(Color.WHITE)
        selectParam(if (selected == 0) 1 else selected)
        updatePills(); updateRepeating()
        toast("Applied ISO $meterIso, ${shutterText(meterNs)}" + if (lock) " (locked)" else "")
    }

    // ---- face / focus overlay ---------------------------------------------------------------
    private fun trackAndFocus(vw: Int, vh: Int) {
        val fr = faceRects
        val frv = fr.map { sensorRectToView(it) }
        faceViewRects = frv
        facesSensor = fr
        var primary = -1
        var region: Rect? = null
        var state = 0
        var targetRect: RectF? = null

        when (afMode) {
            0 -> {
                if (faceAf && frv.isNotEmpty() && !manualFocus) {
                    val pc = primaryCenter
                    var idx = -1
                    if (pc != null) {
                        var bd = Float.MAX_VALUE
                        frv.forEachIndexed { i, r -> val d = hypot(r.centerX() - pc.x, r.centerY() - pc.y); if (d < bd) { bd = d; idx = i } }
                        if (bd > 0.15f * vw) idx = -1
                    }
                    if (idx < 0) idx = frv.indices.maxByOrNull { frv[it].width() * frv[it].height() } ?: 0
                    primary = idx
                    primaryCenter = PointF(frv[idx].centerX(), frv[idx].centerY())
                    region = expand(fr[idx], 1.1f)
                } else primaryCenter = null
                lockRect = null
            }
            1 -> {
                val lc = lockCenter
                if (lc != null) {
                    var idx = -1
                    var bd = Float.MAX_VALUE
                    frv.forEachIndexed { i, r -> val d = hypot(r.centerX() - lc.x, r.centerY() - lc.y); if (d < bd) { bd = d; idx = i } }
                    if (idx >= 0 && bd < 0.22f * vw) {
                        lockCenter = PointF(frv[idx].centerX(), frv[idx].centerY())
                        lastLockRegion = expand(fr[idx], 1.1f)
                        lockRect = smooth(lockRect, frv[idx])
                        lostTicks = 0; primary = idx; state = 1
                    } else { lostTicks++; state = if (lostTicks > 8) 3 else 1 }
                    region = lastLockRegion
                    targetRect = lockRect
                }
            }
            2 -> {
                targetRect = pointRect
                state = if (afScan || waitSettle) 4 else 2
            }
        }

        overlay.marks.clear()
        for (i in 0..2) {
            val pt = markPts[i] ?: continue
            if (marks[i] < 0) continue
            val v = sensorToView(pt.x, pt.y)
            overlay.marks.add(OverlayView.MarkPt("ABC"[i].toString(), v.x, v.y, manualFocus && abs(focusP - marks[i]) < 25))
        }
        overlay.faceRects.clear(); overlay.faceRects.addAll(frv)
        overlay.primary = primary
        overlay.target = if (afMode != 0) targetRect else null
        overlay.targetState = if (afMode != 0) state else 0
        overlay.invalidate()
        if (afMode != 2) applyRegion(region)
    }

    private fun expand(r: Rect, f: Float): Rect {
        val dw = (r.width() * (f - 1f) / 2f).toInt(); val dh = (r.height() * (f - 1f) / 2f).toInt()
        return Rect(r.left - dw, r.top - dh, r.right + dw, r.bottom + dh)
    }

    private fun smooth(old: RectF?, new: RectF): RectF {
        if (old == null) return RectF(new)
        val a = 0.5f
        return RectF(old.left + (new.left - old.left) * a, old.top + (new.top - old.top) * a,
            old.right + (new.right - old.right) * a, old.bottom + (new.bottom - old.bottom) * a)
    }

    private fun applyRegion(r: Rect?) {
        if (maxAfRegions <= 0 || manualFocus || afScan || waitSettle) return
        val last = appliedRegion
        val vis = visibleRect()
        val changed = when {
            r == null && last == null -> false
            r == null || last == null -> true
            else -> hypot((r.exactCenterX() - last.exactCenterX()).toDouble(), (r.exactCenterY() - last.exactCenterY()).toDouble()) > 0.02 * vis.width() ||
                abs(r.width() - last.width()) > 0.25f * last.width()
        }
        val now = SystemClock.elapsedRealtime()
        if (changed && now - lastAfUpdate > 250) {
            appliedRegion = r
            afRegion = r?.let { toMetering(it) }
            lastAfUpdate = now
            updateRepeating()
        }
    }

    private fun updateAfChip() {
        val txt = when {
            lockFocus -> "FOCUS LOCKED"
            manualFocus && afMode == 2 -> "FOCUS LOCK"
            manualFocus -> "MF"
            afScan || waitSettle -> "FOCUSING"
            afMode == 1 -> "FACE LOCK"
            faceRects.isNotEmpty() && faceAf -> "AF  FACE x${faceRects.size}"
            else -> "AF  AUTO"
        }
        if (afChip.text != txt) afChip.text = txt
        afChip.setTextColor(if (afMode != 0 || lockFocus || manualFocus) Color.parseColor("#4CD964") else Color.WHITE)
    }

    private fun updateVu() {
        val a = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
        val db = if (a <= 0) -60.0 else 20.0 * kotlin.math.log10(a / 32767.0)
        val target = ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
        vu.level = if (target > vu.level) target else vu.level * 0.85f
    }

    // =====================================================================
    // Stills
    // =====================================================================
    private fun flash() { flashView.animate().cancel(); flashView.alpha = 0.55f; flashView.animate().alpha(0f).setDuration(220).start() }

    private fun capture() {
        if (nightOn && !videoMode) { captureNight(); return }
        val d = device ?: return
        val s = session ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            applySettings(b, false)
            b.set(CaptureRequest.JPEG_QUALITY, 100)
            b.set(CaptureRequest.JPEG_ORIENTATION, orient())
            lastJpegOrient = orient()
            awaitingRaw = false
            val mx = maxReader
            if (mx != null) {
                b.addTarget(mx.surface)
                if (Build.VERSION.SDK_INT >= 31) b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            } else {
                jpegReader?.let { b.addTarget(it.surface) }
                rawReader?.let { b.addTarget(it.surface); awaitingRaw = true }
            }
            flash()
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ss: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (awaitingRaw) { pendingResult = res; trySaveRaw() }
                }
            }, bg)
        } catch (e: Exception) { toast("Capture failed: ${e.message}") }
    }

    /**
     * Night stack: N identical short frames, aligned and averaged on the phone. The exposure is split so each frame
     * is short enough to hold by hand (about 1/15 s), and the stack recovers the light and cuts noise by ~sqrt(N).
     */
    private fun captureNight() {
        val d = device ?: return
        val s = session ?: return
        val rd = jpegReader
        if (rd == null || !hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            toast("Night stack needs a non-MAX photo size and manual sensor support"); nightOn = false; return
        }
        val r = isoRange(); val e = expRange()
        val iso0 = when (expMode) { 2 -> isoVal(); 1 -> cineIso; else -> lastIso }
        val t0 = when (expMode) { 2 -> shutterNs(); 1 -> cineShutNs; else -> lastExpNs }
        val total = iso0.toDouble() * t0
        var t = min(66_000_000.0, e.upper.toDouble())
        var iso = total / t
        if (iso < r.lower * 1.5) { toast("Bright scene: normal shot is enough"); nightOn = false; capture(); return }
        if (iso > r.upper) { iso = r.upper.toDouble(); t = min(125_000_000.0, min(e.upper.toDouble(), total / iso)) }
        t = t.coerceIn(e.lower.toDouble(), e.upper.toDouble())
        val n = nightFrames[nightIdx]
        try {
            val reqs = ArrayList<CaptureRequest>()
            for (i in 0 until n) {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                applySettings(b, false)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.roundToInt().coerceIn(r.lower, r.upper))
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, t.toLong())
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(t.toLong(), 33_000_000L))
                if (avail(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_OFF)) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                if (avail(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL))
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                b.set(CaptureRequest.JPEG_QUALITY, 97)
                b.set(CaptureRequest.JPEG_ORIENTATION, orient())
                b.addTarget(rd.surface)
                reqs.add(b.build())
            }
            synchronized(burstFrames) { burstFrames.clear(); burstExpected = n; burstOrient = orient() }
            flash()
            toast("Hold still: capturing $n frames")
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {}, bg)
        } catch (ex: Exception) { toast("Night capture failed: ${ex.message}"); synchronized(burstFrames) { burstExpected = 0 } }
    }

    private val jpegListener = ImageReader.OnImageAvailableListener { r ->
        val img = r.acquireNextImage() ?: return@OnImageAvailableListener
        try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()); buf.get(bytes)
            var stackNow: List<ByteArray>? = null
            synchronized(burstFrames) {
                if (burstExpected > 0) {
                    burstFrames.add(bytes)
                    if (burstFrames.size >= burstExpected) { stackNow = ArrayList(burstFrames); burstFrames.clear(); burstExpected = 0 }
                    if (stackNow == null) return@OnImageAvailableListener
                }
            }
            val frames = stackNow
            if (frames != null) { processStack(frames, burstOrient); return@OnImageAvailableListener }
            val outBytes = cropJpeg(bytes, lastJpegOrient)
            val uri = createMedia("FilTarPro_${stamp()}${ratioTag()}.jpg", "image/jpeg", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES + "/FilTarPro")
            contentResolver.openOutputStream(uri)?.use { it.write(outBytes) }
            finishMedia(uri)
            toast("Saved ${img.width}x${img.height} JPEG")
        } catch (e: Exception) { toast("Save failed: ${e.message}") } finally { img.close() }
    }

    private fun processStack(frames: List<ByteArray>, rot: Int) {
        toast("Stacking ${frames.size} frames...")
        Thread {
            var out: ByteArray? = null
            try { out = NightStack.merge(frames, rot) } catch (t: Throwable) { toast("Stack failed, saving single frame") }
            try {
                val bytes = cropJpeg(out ?: frames[0], 0)
                val uri = createMedia("FilTarPro_NIGHT_${stamp()}${ratioTag()}.jpg", "image/jpeg", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES + "/FilTarPro")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                finishMedia(uri)
                toast(if (out != null) "Saved night stack" else "Saved single frame")
            } catch (e: Exception) { toast("Save failed: ${e.message}") }
        }.start()
    }

    @Synchronized private fun trySaveRaw() {
        val img = pendingRaw ?: return
        val res = pendingResult ?: return
        pendingRaw = null; pendingResult = null; awaitingRaw = false
        try {
            val dng = DngCreator(chars, res)
            val uri = createMedia("FilTarPro_${stamp()}.dng", "image/x-adobe-dng", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES + "/FilTarPro")
            contentResolver.openOutputStream(uri)?.use { dng.writeImage(it, img) }
            dng.close()
            finishMedia(uri)
            toast("Saved RAW DNG")
        } catch (e: Exception) { toast("DNG failed: ${e.message}") } finally { img.close() }
    }

    // =====================================================================
    // Video (with automatic fallback so the encoder can never block recording)
    // =====================================================================
    private fun buildCandidates(): List<RecCfg> {
        val sel = videoSizes.getOrNull(videoIdx) ?: Size(1920, 1080)
        val sizes = ArrayList<Size>()
        sizes.add(sel)
        videoSizes.filter { it.width.toLong() * it.height < sel.width.toLong() * sel.height }.take(5).forEach { sizes.add(it) }
        val f0 = fpsOpts[fpsIdx]
        val out = ArrayList<RecCfg>()
        for ((i, sz) in sizes.withIndex()) {
            val f = if (i == 0) f0 else min(f0, 30)
            if (!compat264 && sizeOk("video/hevc", sz.width, sz.height, f)) out.add(RecCfg(sz, f, true))
            if (sizeOk("video/avc", sz.width, sz.height, f)) out.add(RecCfg(sz, f, false))
            if (f > 30 && sizeOk("video/avc", sz.width, sz.height, 30)) out.add(RecCfg(sz, 30, false))
        }
        // last resorts: let the phone try, even if it did not advertise support
        out.add(RecCfg(sel, min(f0, 30), false))
        out.add(RecCfg(Size(1920, 1080), 30, false))
        out.add(RecCfg(Size(1280, 720), 30, false))
        return out.distinct()
    }

    private var recCands: List<RecCfg> = emptyList()

    /** audioMode: 0 none, 1 phone mic, 2 external mic (stereo 48k), 3 external mic (mono 48k). */
    private fun prepareOnce(cfg: RecCfg, audioMode: Int): Boolean {
        val mr = MediaRecorder()
        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        val dev = if (audioMode >= 2) chosenAudio() else null
        return try {
            if (audioMode > 0) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (dev != null) {
                if (dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !scoOn) {
                    @Suppress("DEPRECATION") audioMgr.startBluetoothSco()
                    scoOn = true
                    Thread.sleep(800)
                }
                mr.setPreferredDevice(dev)
            }
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            uri = createMedia("FilTarPro_${stamp()}${ratioTag()}.mp4", "video/mp4", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES + "/FilTarPro")
            pfd = contentResolver.openFileDescriptor(uri, "w")!!
            mr.setOutputFile(pfd.fileDescriptor)
            val mime = if (cfg.hevc) "video/hevc" else "video/avc"
            val want = (cfg.size.width.toLong() * cfg.size.height * cfg.fps * (if (cfg.hevc) 0.22 else 0.30)).toLong()
            val br = min(want, maxBitrate(mime).toLong()).coerceIn(4_000_000L, 200_000_000L).toInt()
            mr.setVideoEncodingBitRate(br)
            mr.setVideoFrameRate(cfg.fps)
            mr.setVideoSize(cfg.size.width, cfg.size.height)
            mr.setVideoEncoder(if (cfg.hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            if (audioMode > 0) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(192_000)
                if (dev != null && dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { mr.setAudioSamplingRate(16_000); mr.setAudioChannels(1) }
                else if (dev != null) { mr.setAudioSamplingRate(48_000); mr.setAudioChannels(if (audioMode == 2) 2 else 1) }
                else mr.setAudioSamplingRate(44_100)
            }
            mr.setOrientationHint(orient())
            mr.prepare()
            recorder = mr; recPfd = pfd; recUri = uri
            true
        } catch (e: Exception) {
            try { mr.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            uri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            false
        }
    }

    private fun startRecording(from: Int) {
        val d = device ?: return
        if (from == 0) recCands = buildCandidates()
        closeSession()
        var i = from
        while (i < recCands.size) {
            val cfg = recCands[i]
            val modes = if (chosenAudio() != null) intArrayOf(2, 3, 1, 0) else intArrayOf(1, 0)
            var okMode = -1
            for (m in modes) { if (prepareOnce(cfg, m)) { okMode = m; break } }
            if (okMode >= 0) { usedAudioMode = okMode; beginRecordSession(d, cfg, i); return }
            i++
        }
        toast("This phone's encoder refused every video setting")
        startPreviewSession()
    }

    private fun discardRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null; recorderSurface = null
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        recUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        recUri = null
    }

    private fun beginRecordSession(d: CameraDevice, cfg: RecCfg, idx: Int) {
        val mr = recorder ?: return
        recorderSurface = mr.surface
        val st = tv.surfaceTexture ?: run { discardRecorder(); return }
        previewSize = choosePreview(cfg.size)
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val ps = Surface(st); previewSurface = ps
        runOnUiThread { configureTransform() }
        curRec = cfg
        try {
            val outs = listOf(OutputConfiguration(ps), OutputConfiguration(mr.surface))
            val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        recording = true
                        runOnUiThread { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED }
                        updateRepeating()
                        try {
                            mr.start()
                            recStartMs = SystemClock.elapsedRealtime()
                            runOnUiThread { recBtn.recording = true }
                            refreshInfo()
                            if (chosenAudio() != null && usedAudioMode < 2) toast("External mic could not be used. Recording with the phone mic.")
                            else if (usedAudioMode == 0) toast("Recording without audio")
                            val chosen = videoSizes.getOrNull(videoIdx)
                            if (idx > 0 || (chosen != null && chosen != cfg.size)) toast("Recording ${cfg.size.width}x${cfg.size.height} ${cfg.fps}p (auto-adjusted for this phone's encoder)")
                        } catch (e: Exception) {
                            recording = false
                            discardRecorder(); closeSession()
                            startRecording(idx + 1)
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        discardRecorder(); closeSession()
                        startRecording(idx + 1)
                    }
                })
            d.createCaptureSession(sc)
        } catch (e: Exception) {
            discardRecorder()
            startRecording(idx + 1)
        }
    }

    private fun stopRecording() { stopRecordingQuiet(); startPreviewSession(); toast("Video saved") }

    private fun stopRecordingQuiet() {
        val wasRec = recording
        recording = false
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { if (wasRec) recorder?.stop() } catch (_: RuntimeException) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null; recorderSurface = null
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        recUri?.let { finishMedia(it) }; recUri = null
        curRec = null
        if (scoOn) { try { @Suppress("DEPRECATION") audioMgr.stopBluetoothSco() } catch (_: Exception) {}; scoOn = false }
        runOnUiThread { recBtn.recording = false; requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }
        refreshInfo()
    }

    // =====================================================================
    // Misc helpers
    // =====================================================================
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun createMedia(name: String, mime: String, collection: Uri, relPath: String): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return contentResolver.insert(collection, cv)!!
    }

    private fun finishMedia(uri: Uri) {
        val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        contentResolver.update(uri, cv, null, null)
        loadThumb(uri)
    }

    private fun loadThumb(uri: Uri) {
        Thread {
            try {
                val b = contentResolver.loadThumbnail(uri, Size(192, 192), null)
                runOnUiThread { thumbView.setImageBitmap(b) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
