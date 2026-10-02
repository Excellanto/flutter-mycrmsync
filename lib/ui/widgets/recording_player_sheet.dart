import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/theme/app_theme.dart';

class RecordingQueueItem {
  const RecordingQueueItem({
    required this.uri,
    required this.contactName,
    required this.phone,
    this.recordedAtIso,
  });

  final String uri;
  final String contactName;
  final String phone;
  final String? recordedAtIso;
}

/// Bottom-sheet player matching the Android [RecordingPlayerBottomSheet] UX.
Future<void> showRecordingPlayerSheet({
  required BuildContext context,
  required List<RecordingQueueItem> queue,
  int startIndex = 0,
}) {
  if (queue.isEmpty) return Future.value();
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.cardWhite,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (ctx) => _RecordingPlayerSheetBody(
      queue: queue,
      startIndex: startIndex.clamp(0, queue.length - 1),
    ),
  );
}

class _RecordingPlayerSheetBody extends StatefulWidget {
  const _RecordingPlayerSheetBody({
    required this.queue,
    required this.startIndex,
  });

  final List<RecordingQueueItem> queue;
  final int startIndex;

  @override
  State<_RecordingPlayerSheetBody> createState() => _RecordingPlayerSheetBodyState();
}

class _RecordingPlayerSheetBodyState extends State<_RecordingPlayerSheetBody> {
  late final AudioPlayer _player;
  late int _index;
  double _speed = 1.0;
  bool _playing = false;
  bool _loading = true;
  String? _error;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;

  RecordingQueueItem get _item => widget.queue[_index];

  @override
  void initState() {
    super.initState();
    _index = widget.startIndex;
    _player = AudioPlayer();
    _player.setReleaseMode(ReleaseMode.stop);
    _player.onPlayerStateChanged.listen((state) {
      if (!mounted) return;
      setState(() => _playing = state == PlayerState.playing);
    });
    _player.onDurationChanged.listen((d) {
      if (!mounted) return;
      setState(() => _duration = d);
    });
    _player.onPositionChanged.listen((p) {
      if (!mounted) return;
      setState(() => _position = p);
    });
    _player.onPlayerComplete.listen((_) {
      if (!mounted) return;
      setState(() {
        _playing = false;
        _position = _duration;
      });
    });
    _loadCurrent();
  }

  @override
  void dispose() {
    _player.dispose();
    super.dispose();
  }

  Future<void> _loadCurrent() async {
    setState(() {
      _loading = true;
      _error = null;
      _position = Duration.zero;
      _duration = Duration.zero;
    });
    try {
      await _player.stop();
      var uri = _item.uri.trim();
      if (AppConfig.isAndroid) {
        uri = (await NativeBridges.resolvePlayablePath(uri)) ?? uri;
      }
      Source source;
      if (uri.startsWith('http://') || uri.startsWith('https://')) {
        source = UrlSource(uri);
      } else if (uri.startsWith('file://')) {
        source = DeviceFileSource(Uri.parse(uri).toFilePath());
      } else {
        source = DeviceFileSource(uri);
      }
      await _player.setPlaybackRate(_speed);
      await _player.play(source);
      if (mounted) setState(() => _loading = false);
    } catch (e) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = '$e';
          _playing = false;
        });
      }
    }
  }

  Future<void> _togglePlay() async {
    if (_loading) return;
    if (_playing) {
      await _player.pause();
    } else {
      if (_duration.inMilliseconds > 0 &&
          _position.inMilliseconds >= _duration.inMilliseconds - 200) {
        await _player.seek(Duration.zero);
      }
      await _player.resume();
    }
  }

  Future<void> _setSpeed(double speed) async {
    setState(() => _speed = speed);
    await _player.setPlaybackRate(speed);
  }

  Future<void> _goTo(int index) async {
    if (index < 0 || index >= widget.queue.length || index == _index) return;
    setState(() => _index = index);
    await _loadCurrent();
  }

  String _fmt(Duration d) {
    final total = d.inSeconds.clamp(0, 24 * 3600);
    final m = total ~/ 60;
    final s = total % 60;
    return '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }

  String get _recordedLabel {
    final iso = _item.recordedAtIso?.trim() ?? '';
    if (iso.isEmpty) return '—';
    try {
      final dt = DateTime.parse(iso).toLocal();
      final h = dt.hour % 12 == 0 ? 12 : dt.hour % 12;
      final m = dt.minute.toString().padLeft(2, '0');
      final ampm = dt.hour >= 12 ? 'PM' : 'AM';
      const months = [
        'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
        'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
      ];
      return '${months[dt.month - 1]} ${dt.day}, $h:$m $ampm';
    } catch (_) {
      return iso;
    }
  }

  @override
  Widget build(BuildContext context) {
    final maxMs = _duration.inMilliseconds <= 0 ? 1.0 : _duration.inMilliseconds.toDouble();
    final posMs = _position.inMilliseconds.toDouble().clamp(0.0, maxMs);
    final bottom = MediaQuery.viewPaddingOf(context).bottom;

    return SafeArea(
      child: Padding(
        padding: EdgeInsets.fromLTRB(20, 8, 20, 20 + bottom),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 40,
                height: 4,
                margin: const EdgeInsets.only(bottom: 12),
                decoration: BoxDecoration(
                  color: const Color(0xFFE2E8F0),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            Row(
              children: [
                Expanded(
                  child: Text(
                    _item.contactName.isEmpty ? 'Unknown contact' : _item.contactName,
                    style: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                      color: AppColors.textMain,
                    ),
                  ),
                ),
                IconButton(
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.close, color: AppColors.textSec),
                ),
              ],
            ),
            Text(
              _item.phone.isEmpty ? '—' : _item.phone,
              style: const TextStyle(fontSize: 14, color: AppColors.textSec),
            ),
            Text(
              _recordedLabel,
              style: const TextStyle(fontSize: 12, color: AppColors.textSec),
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              children: [0.75, 1.0, 1.25, 1.5, 2.0].map((s) {
                final selected = (_speed - s).abs() < 0.01;
                final label = s == 1.0 ? '1x' : '${s}x';
                return ChoiceChip(
                  label: Text(label, style: const TextStyle(fontSize: 12)),
                  selected: selected,
                  onSelected: (_) => _setSpeed(s),
                  selectedColor: AppColors.lightIndigo,
                  labelStyle: TextStyle(
                    color: selected ? AppColors.primaryBlue : AppColors.textSec,
                    fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                  ),
                );
              }).toList(),
            ),
            const SizedBox(height: 16),
            if (_loading)
              const SizedBox(
                height: 72,
                child: Center(
                  child: CircularProgressIndicator(color: AppColors.primaryBlue),
                ),
              )
            else if (_error != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Text(
                  _error!,
                  style: const TextStyle(color: AppColors.errorRed, fontSize: 13),
                ),
              )
            else ...[
              SliderTheme(
                data: SliderTheme.of(context).copyWith(
                  trackHeight: 4,
                  thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 7),
                ),
                child: Slider(
                  value: posMs,
                  max: maxMs,
                  activeColor: AppColors.primaryBlue,
                  inactiveColor: AppColors.textSec.withValues(alpha: 0.25),
                  onChanged: (v) {
                    setState(() => _position = Duration(milliseconds: v.round()));
                  },
                  onChangeEnd: (v) => _player.seek(Duration(milliseconds: v.round())),
                ),
              ),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    _fmt(_position),
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                      color: AppColors.textMain,
                    ),
                  ),
                  Text(
                    _fmt(_duration),
                    style: const TextStyle(fontSize: 13, color: AppColors.textSec),
                  ),
                ],
              ),
            ],
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (widget.queue.length > 1)
                  IconButton(
                    onPressed: _index > 0 ? () => _goTo(_index - 1) : null,
                    icon: const Icon(Icons.skip_previous, size: 32),
                    color: AppColors.textMain,
                  ),
                const SizedBox(width: 8),
                IconButton(
                  onPressed: _loading || _error != null ? null : _togglePlay,
                  iconSize: 56,
                  icon: Icon(
                    _playing ? Icons.pause_circle_filled : Icons.play_circle_filled,
                    color: AppColors.primaryBlue,
                    size: 56,
                  ),
                ),
                const SizedBox(width: 8),
                if (widget.queue.length > 1)
                  IconButton(
                    onPressed: _index < widget.queue.length - 1
                        ? () => _goTo(_index + 1)
                        : null,
                    icon: const Icon(Icons.skip_next, size: 32),
                    color: AppColors.textMain,
                  ),
              ],
            ),
            if (widget.queue.length > 1)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  'Recording ${_index + 1} of ${widget.queue.length}',
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 12, color: AppColors.textSec),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
