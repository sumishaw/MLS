import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const CaptionLensApp());
}

class CaptionLensApp extends StatelessWidget {
  const CaptionLensApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Caption Lens',
        debugShowCheckedModeBanner: false,
        theme: ThemeData.dark().copyWith(
          scaffoldBackgroundColor: Colors.black,
          colorScheme: const ColorScheme.dark(primary: Color(0xFFFF3B3B)),
        ),
        home: const HomePage(),
      );
}

// ── Model state machine ────────────────────────────────────────────────────────

enum ModelState { checking, notDownloaded, downloading, ready, error }

// ── Home page ──────────────────────────────────────────────────────────────────

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  static const _ch = MethodChannel('overlay_channel');

  // UI state
  String originalText     = '';
  String displayText      = 'Tap START → approve screen capture → play any video…';
  bool   isRunning        = false;
  bool   hasOverlay       = false;
  String targetLang       = 'english';
  String statusMsg        = '';
  int    translationCount = 0;
  bool   _micPulse        = false;
  Timer? _pulseTimer;

  // Model download state
  ModelState modelState   = ModelState.checking;
  int downloadPercent     = 0;
  int downloadedMb        = 0;
  int totalMb             = 0;
  String modelErrorMsg    = '';

  // ── init ──────────────────────────────────────────────────────────────────

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    _ch.setMethodCallHandler((call) async {
      if (!mounted) return;
      switch (call.method) {
        case 'onTranslation':
          final a = call.arguments as Map? ?? {};
          _applyTranslation(
            a['original']?.toString() ?? '',
            a['english']?.toString()  ?? '',
            a['hindi']?.toString()    ?? '',
          );
          break;
        case 'onDownloadProgress':
          final a = call.arguments as Map? ?? {};
          if (mounted) setState(() {
            modelState      = ModelState.downloading;
            downloadPercent = (a['percent']     as num?)?.toInt() ?? 0;
            downloadedMb    = (a['downloadedMb'] as num?)?.toInt() ?? 0;
            totalMb         = (a['totalMb']      as num?)?.toInt() ?? 0;
          });
          break;
        case 'onModelReady':
          if (mounted) setState(() {
            modelState      = ModelState.ready;
            downloadPercent = 100;
          });
          break;
        case 'onModelError':
          final a = call.arguments as Map? ?? {};
          if (mounted) setState(() {
            modelState    = ModelState.error;
            modelErrorMsg = a['message']?.toString() ?? 'Unknown error';
          });
          break;
      }
    });

    _checkPermissions();
    _checkModelStatus();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkPermissions();
      _checkModelStatus();
    }
  }

  // ── Platform calls ─────────────────────────────────────────────────────────

  Future<void> _checkPermissions() async {
    try {
      final ok = await _ch.invokeMethod<bool>('hasOverlayPermission') ?? false;
      if (mounted) setState(() => hasOverlay = ok);
    } catch (_) {}
  }

  Future<void> _checkModelStatus() async {
    try {
      if (mounted) setState(() => modelState = ModelState.checking);
      final ready = await _ch.invokeMethod<bool>('isModelReady') ?? false;
      if (mounted) setState(() => modelState = ready ? ModelState.ready : ModelState.notDownloaded);
    } catch (_) {
      if (mounted) setState(() => modelState = ModelState.notDownloaded);
    }
  }

  Future<void> _startDownload() async {
    try {
      setState(() {
        modelState      = ModelState.downloading;
        downloadPercent = 0;
        downloadedMb    = 0;
        totalMb         = 1800;
        modelErrorMsg   = '';
      });
      await _ch.invokeMethod('startModelDownload');
    } catch (e) {
      setState(() {
        modelState    = ModelState.error;
        modelErrorMsg = e.toString();
      });
    }
  }

  void _applyTranslation(String orig, String en, String hi) {
    if (!mounted) return;
    final show = (targetLang == 'hindi' && hi.isNotEmpty) ? hi : en;
    if (show.isEmpty) return;
    setState(() {
      originalText     = orig;
      displayText      = show;
      translationCount++;
    });
  }

  Future<void> _setLanguage(String lang) async {
    await _ch.invokeMethod('setTargetLanguage', {'language': lang});
    if (mounted) setState(() {
      targetLang  = lang;
      displayText = isRunning
          ? 'Listening…'
          : 'Tap START → approve screen capture → play any video…';
    });
  }

  Future<void> _start() async {
    if (modelState != ModelState.ready) {
      setState(() => statusMsg = '⚠️ Please download the speech model first');
      return;
    }
    if (!hasOverlay) {
      await _ch.invokeMethod('requestOverlayPermission');
      if (mounted) setState(() =>
          statusMsg = '⚠️ Allow "Display over other apps" → come back → tap START');
      return;
    }

    // Start overlay first so subtitles appear as soon as capture begins
    await _ch.invokeMethod('startOverlay');
    if (mounted) setState(() => statusMsg = '⏳ Approve "Start recording" in system dialog…');

    final ok = await _ch.invokeMethod<bool>('startSpeechCapture') ?? false;
    if (!ok) {
      if (mounted) setState(() =>
          statusMsg = '⚠️ Screen capture not approved — tap START and allow the prompt');
      return;
    }

    _pulseTimer?.cancel();
    _pulseTimer = Timer.periodic(const Duration(milliseconds: 700), (_) {
      if (mounted) setState(() => _micPulse = !_micPulse);
    });

    if (mounted) setState(() {
      isRunning        = true;
      translationCount = 0;
      statusMsg        = '';
      displayText      = 'Listening to video audio…';
      originalText     = '';
    });
  }

  Future<void> _stop() async {
    _pulseTimer?.cancel();
    _micPulse = false;
    await _ch.invokeMethod('stopSpeechCapture');
    await _ch.invokeMethod('stopOverlay');
    if (mounted) setState(() {
      isRunning    = false;
      statusMsg    = '';
      displayText  = 'Tap START → approve screen capture → play any video…';
      originalText = '';
    });
  }

  @override
  void dispose() {
    _pulseTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  // ── Build ──────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildHeader(),
              const SizedBox(height: 16),
              _buildInfoBanner(),
              const SizedBox(height: 16),
              _buildModelCard(),
              const SizedBox(height: 16),
              _buildOverlayPermRow(),
              const SizedBox(height: 16),
              _buildLanguageChips(),
              const SizedBox(height: 16),
              _buildLangSelector(),
              const SizedBox(height: 16),
              if (originalText.isNotEmpty && originalText != displayText) ...[
                _buildDetectedAudio(),
                const SizedBox(height: 10),
              ],
              _buildTranslationOutput(),
              if (statusMsg.isNotEmpty) ...[
                const SizedBox(height: 12),
                _buildStatusBanner(),
              ],
              const SizedBox(height: 20),
              _buildStartStopButton(),
              const SizedBox(height: 8),
              const Center(
                child: Text(
                  'Captures internal phone audio — microphone stays off',
                  style: TextStyle(color: Colors.white24, fontSize: 11),
                ),
              ),
              const SizedBox(height: 20),
            ],
          ),
        ),
      ),
    );
  }

  // ── Header ─────────────────────────────────────────────────────────────────

  Widget _buildHeader() {
    return Row(children: [
      AnimatedContainer(
        duration: const Duration(milliseconds: 400),
        width: 36,
        height: 36,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: isRunning
              ? (_micPulse ? Colors.red : Colors.red.withOpacity(0.5))
              : Colors.white12,
        ),
        child: const Icon(Icons.subtitles, color: Colors.white, size: 20),
      ),
      const SizedBox(width: 12),
      const Expanded(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Caption Lens',
              style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold)),
          Text('Translates internal video audio — no microphone used',
              style: TextStyle(color: Colors.white38, fontSize: 11)),
        ]),
      ),
      if (isRunning)
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          decoration: BoxDecoration(
              color: Colors.red, borderRadius: BorderRadius.circular(12)),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.fiber_manual_record, color: Colors.white, size: 8),
            const SizedBox(width: 4),
            Text('$translationCount',
                style: const TextStyle(
                    color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold)),
          ]),
        ),
    ]);
  }

  // ── Info banner ────────────────────────────────────────────────────────────

  Widget _buildInfoBanner() => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: Colors.blue.withOpacity(0.08),
      borderRadius: BorderRadius.circular(10),
      border: Border.all(color: Colors.blue.withOpacity(0.2)),
    ),
    child: const Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Icon(Icons.info_outline, color: Colors.blue, size: 16),
      SizedBox(width: 8),
      Expanded(
        child: Text(
          'Captures audio playing on the tablet internally — works with YouTube, VLC, '
          'Chrome, offline videos. Microphone NOT used. Approve the screen capture '
          'dialog when you tap START.',
          style: TextStyle(color: Colors.white60, fontSize: 12, height: 1.5),
        ),
      ),
    ]),
  );

  // ── Model card ─────────────────────────────────────────────────────────────

  Widget _buildModelCard() {
    switch (modelState) {
      case ModelState.checking:
        return _cardShell(
          icon: Icons.hourglass_top,
          iconColor: Colors.white38,
          borderColor: Colors.white12,
          bgColor: const Color(0xFF111111),
          title: 'Checking speech model…',
          subtitle: 'Please wait',
          trailing: const SizedBox(
            width: 18, height: 18,
            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white38),
          ),
        );

      case ModelState.notDownloaded:
        return _cardShell(
          icon: Icons.download_for_offline,
          iconColor: Colors.orangeAccent,
          borderColor: Colors.orange.withOpacity(0.4),
          bgColor: Colors.orange.withOpacity(0.06),
          title: 'Speech Model Required',
          subtitle: 'vosk-model-en-us-0.22 · ~1.8 GB\nKeep on WiFi · plugged in during download',
          trailing: ElevatedButton(
            onPressed: _startDownload,
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.orangeAccent,
              foregroundColor: Colors.black,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: const Text('Download', style: TextStyle(fontWeight: FontWeight.bold)),
          ),
        );

      case ModelState.downloading:
        return Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: Colors.blue.withOpacity(0.07),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: Colors.blue.withOpacity(0.35)),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Icon(Icons.downloading, color: Colors.blueAccent, size: 20),
              const SizedBox(width: 10),
              const Expanded(
                child: Text('Downloading Speech Model…',
                    style: TextStyle(
                        color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600)),
              ),
              Text('$downloadPercent%',
                  style: const TextStyle(
                      color: Colors.blueAccent, fontSize: 15, fontWeight: FontWeight.bold)),
            ]),
            const SizedBox(height: 10),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                value: downloadPercent / 100.0,
                minHeight: 10,
                backgroundColor: Colors.white12,
                valueColor: const AlwaysStoppedAnimation<Color>(Colors.blueAccent),
              ),
            ),
            const SizedBox(height: 8),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Text(
                totalMb > 0
                    ? '$downloadedMb MB  /  $totalMb MB'
                    : '$downloadedMb MB downloaded',
                style: const TextStyle(color: Colors.white54, fontSize: 12),
              ),
              const Text('Stay on WiFi & keep plugged in',
                  style: TextStyle(color: Colors.white38, fontSize: 11)),
            ]),
          ]),
        );

      case ModelState.ready:
        return _cardShell(
          icon: Icons.check_circle,
          iconColor: Colors.greenAccent,
          borderColor: Colors.greenAccent.withOpacity(0.3),
          bgColor: Colors.green.withOpacity(0.06),
          title: 'Speech Model Ready',
          subtitle: 'vosk-model-en-us-0.22 · Full vocabulary · No censoring',
          trailing: const Icon(Icons.check, color: Colors.greenAccent, size: 20),
        );

      case ModelState.error:
        return _cardShell(
          icon: Icons.error_outline,
          iconColor: Colors.redAccent,
          borderColor: Colors.red.withOpacity(0.4),
          bgColor: Colors.red.withOpacity(0.06),
          title: 'Download Failed',
          subtitle: modelErrorMsg.isNotEmpty ? modelErrorMsg : 'Tap Retry to try again',
          trailing: ElevatedButton(
            onPressed: _startDownload,
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.redAccent,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: const Text('Retry', style: TextStyle(fontWeight: FontWeight.bold)),
          ),
        );
    }
  }

  Widget _cardShell({
    required IconData icon,
    required Color iconColor,
    required Color borderColor,
    required Color bgColor,
    required String title,
    required String subtitle,
    Widget? trailing,
  }) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: bgColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: borderColor),
      ),
      child: Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
        Icon(icon, color: iconColor, size: 22),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title,
                style: const TextStyle(
                    color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600)),
            const SizedBox(height: 3),
            Text(subtitle,
                style: const TextStyle(color: Colors.white54, fontSize: 11, height: 1.4)),
          ]),
        ),
        if (trailing != null) ...[const SizedBox(width: 10), trailing],
      ]),
    );
  }

  // ── Overlay permission row ─────────────────────────────────────────────────

  Widget _buildOverlayPermRow() => Container(
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
    decoration: BoxDecoration(
      color: const Color(0xFF111111),
      borderRadius: BorderRadius.circular(10),
      border: Border.all(
          color: hasOverlay ? Colors.greenAccent.withOpacity(0.3) : Colors.white12),
    ),
    child: Row(children: [
      Icon(
        hasOverlay ? Icons.check_circle : Icons.radio_button_unchecked,
        color: hasOverlay ? Colors.greenAccent : Colors.white30,
        size: 20,
      ),
      const SizedBox(width: 10),
      const Expanded(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Overlay Permission',
              style: TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w500)),
          Text('Required to show floating subtitles over other apps',
              style: TextStyle(color: Colors.white38, fontSize: 11)),
        ]),
      ),
      if (!hasOverlay)
        GestureDetector(
          onTap: () async {
            await _ch.invokeMethod('requestOverlayPermission');
            await _checkPermissions();
          },
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
                color: const Color(0xFFFF3B3B), borderRadius: BorderRadius.circular(6)),
            child: const Text('Allow',
                style: TextStyle(
                    color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold)),
          ),
        ),
      if (hasOverlay) const Icon(Icons.check, color: Colors.greenAccent, size: 16),
    ]),
  );

  // ── Language chips ─────────────────────────────────────────────────────────

  Widget _buildLanguageChips() => Container(
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: const Color(0xFF0a1628),
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: Colors.blue.withOpacity(0.2)),
    ),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const Text('Detects & Translates',
          style: TextStyle(
              color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
      const SizedBox(height: 10),
      Wrap(spacing: 8, runSpacing: 8, children: [
        _chip('🇯🇵 Japanese'), _chip('🇨🇳 Chinese'),    _chip('🇰🇷 Korean'),
        _chip('🇫🇷 French'),   _chip('🇩🇪 German'),    _chip('🇪🇸 Spanish'),
        _chip('🇹🇷 Turkish'),  _chip('🇸🇦 Arabic'),    _chip('🇧🇷 Portuguese'),
        _chip('🇷🇺 Russian'),  _chip('🇮🇩 Indonesian'), _chip('🇬🇧 English'),
      ]),
      const SizedBox(height: 10),
      const Text('Works with',
          style: TextStyle(
              color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
      const SizedBox(height: 8),
      Wrap(spacing: 8, runSpacing: 8, children: [
        _chip('📺 YouTube'), _chip('🌐 Chrome'), _chip('🦊 Firefox'),
        _chip('🎬 VLC'),     _chip('📁 Offline'), _chip('+ Any app'),
      ]),
    ]),
  );

  // ── Language selector ──────────────────────────────────────────────────────

  Widget _buildLangSelector() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Text('Translate to',
          style: TextStyle(
              color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
      const SizedBox(height: 8),
      Row(children: [
        _langBtn('🇬🇧 English', 'english'),
        const SizedBox(width: 10),
        _langBtn('🇮🇳 Hindi', 'hindi'),
      ]),
    ],
  );

  // ── Detected audio ─────────────────────────────────────────────────────────

  Widget _buildDetectedAudio() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Text('Detected audio',
          style: TextStyle(color: Colors.white38, fontSize: 12)),
      const SizedBox(height: 6),
      Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
            color: Colors.white10, borderRadius: BorderRadius.circular(10)),
        child: Text(originalText,
            style: const TextStyle(color: Colors.white60, fontSize: 15, letterSpacing: 0.3)),
      ),
    ],
  );

  // ── Translation output ─────────────────────────────────────────────────────

  Widget _buildTranslationOutput() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        targetLang == 'hindi' ? '🇮🇳 Hindi Translation' : '🇬🇧 English Translation',
        style: const TextStyle(
            color: Colors.greenAccent, fontSize: 12, fontWeight: FontWeight.bold),
      ),
      const SizedBox(height: 6),
      AnimatedSwitcher(
        duration: const Duration(milliseconds: 200),
        child: Container(
          key: ValueKey(displayText),
          width: double.infinity,
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Colors.green.withOpacity(0.08),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: Colors.greenAccent.withOpacity(0.3)),
          ),
          child: Text(displayText,
              style: const TextStyle(
                  color: Colors.greenAccent,
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                  height: 1.4)),
        ),
      ),
    ],
  );

  // ── Status banner ──────────────────────────────────────────────────────────

  Widget _buildStatusBanner() => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: Colors.orange.withOpacity(0.1),
      borderRadius: BorderRadius.circular(8),
      border: Border.all(color: Colors.orange.withOpacity(0.3)),
    ),
    child: Row(children: [
      const Icon(Icons.info_outline, color: Colors.orange, size: 16),
      const SizedBox(width: 8),
      Expanded(
        child: Text(statusMsg,
            style: const TextStyle(color: Colors.orange, fontSize: 13)),
      ),
    ]),
  );

  // ── Start / Stop button ────────────────────────────────────────────────────

  Widget _buildStartStopButton() {
    final busy = modelState == ModelState.downloading || modelState == ModelState.checking;
    return SizedBox(
      width: double.infinity,
      child: ElevatedButton.icon(
        onPressed: busy ? null : (isRunning ? _stop : _start),
        icon: Icon(
          isRunning ? Icons.stop_circle_outlined : Icons.play_circle_outline,
          size: 26,
        ),
        label: Text(
          isRunning ? 'STOP' : 'START — CAPTURE VIDEO AUDIO',
          style: const TextStyle(
              fontSize: 16, fontWeight: FontWeight.bold, letterSpacing: 0.5),
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor: isRunning ? const Color(0xFF333333) : const Color(0xFFFF3B3B),
          foregroundColor: Colors.white,
          disabledBackgroundColor: Colors.grey.shade800,
          disabledForegroundColor: Colors.white38,
          padding: const EdgeInsets.symmetric(vertical: 18),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          elevation: 4,
        ),
      ),
    );
  }

  // ── Small widgets ──────────────────────────────────────────────────────────

  Widget _chip(String label) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
    decoration: BoxDecoration(
      color: Colors.white.withOpacity(0.06),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: Colors.white12),
    ),
    child: Text(label, style: const TextStyle(color: Colors.white60, fontSize: 12)),
  );

  Widget _langBtn(String label, String value) {
    final selected = targetLang == value;
    return GestureDetector(
      onTap: () => _setLanguage(value),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
        decoration: BoxDecoration(
          color: selected ? const Color(0xFFFF3B3B) : const Color(0xFF1E1E1E),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(
              color: selected ? const Color(0xFFFF3B3B) : Colors.white12),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: selected ? Colors.white : Colors.white54,
            fontWeight: selected ? FontWeight.bold : FontWeight.normal,
            fontSize: 14,
          ),
        ),
      ),
    );
  }
}
