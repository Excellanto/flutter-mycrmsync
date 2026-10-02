import 'package:flutter/material.dart';
import 'package:mycrmsync/theme/app_theme.dart';

/// Modal matching native [TagSelectionDialog]: pick existing tags and/or add new ones.
Future<List<String>?> showTagSelectionDialog({
  required BuildContext context,
  required List<String> availableTags,
  required List<String> currentTags,
  String title = 'Change Tags',
  String confirmLabel = 'Save',
  Future<void> Function(List<String> tags)? onSave,
}) {
  return showDialog<List<String>>(
    context: context,
    barrierDismissible: false,
    builder: (ctx) => _TagSelectionDialog(
      availableTags: availableTags,
      currentTags: currentTags,
      title: title,
      confirmLabel: confirmLabel,
      onSave: onSave,
    ),
  );
}

class _TagSelectionDialog extends StatefulWidget {
  const _TagSelectionDialog({
    required this.availableTags,
    required this.currentTags,
    required this.title,
    required this.confirmLabel,
    this.onSave,
  });

  final List<String> availableTags;
  final List<String> currentTags;
  final String title;
  final String confirmLabel;
  final Future<void> Function(List<String> tags)? onSave;

  @override
  State<_TagSelectionDialog> createState() => _TagSelectionDialogState();
}

class _TagSelectionDialogState extends State<_TagSelectionDialog> {
  late List<String> _selected;
  final _newTag = TextEditingController();
  bool _updating = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _selected = List<String>.from(widget.currentTags);
  }

  @override
  void dispose() {
    _newTag.dispose();
    super.dispose();
  }

  bool _isSelected(String name) =>
      _selected.any((t) => t.toLowerCase() == name.toLowerCase());

  void _setSelected(String name, bool selected) {
    final trimmed = name.trim();
    if (trimmed.isEmpty) return;
    setState(() {
      final exists = _isSelected(trimmed);
      if (selected && !exists) {
        _selected = [..._selected, trimmed];
      } else if (!selected && exists) {
        _selected = _selected
            .where((t) => t.toLowerCase() != trimmed.toLowerCase())
            .toList();
      }
    });
  }

  void _addNewTag() {
    final trimmed = _newTag.text.trim();
    if (trimmed.isEmpty) return;
    _setSelected(trimmed, true);
    _newTag.clear();
  }

  List<String> get _displayTags {
    final fromApi = widget.availableTags;
    final custom = _selected.where(
      (s) => fromApi.every((a) => a.toLowerCase() != s.toLowerCase()),
    );
    return [...fromApi, ...custom];
  }

  Future<void> _save() async {
    final pending = _newTag.text.trim();
    if (pending.isNotEmpty && !_isSelected(pending)) {
      _selected = [..._selected, pending];
    }
    final tags = _selected
        .map((t) => t.trim())
        .where((t) => t.isNotEmpty)
        .fold<List<String>>([], (acc, t) {
          if (acc.every((x) => x.toLowerCase() != t.toLowerCase())) {
            acc.add(t);
          }
          return acc;
        });
    if (widget.onSave == null) {
      Navigator.pop(context, tags);
      return;
    }
    setState(() {
      _updating = true;
      _error = null;
    });
    try {
      await widget.onSave!(tags);
      if (mounted) Navigator.pop(context, tags);
    } catch (e) {
      if (mounted) {
        setState(() {
          _updating = false;
          _error = '$e'.replaceFirst(RegExp(r'^Exception:\s*'), '');
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final display = _displayTags;
    return AlertDialog(
      scrollable: true,
      title: Text(widget.title, style: const TextStyle(fontWeight: FontWeight.bold)),
      content: SizedBox(
        width: double.maxFinite,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _newTag,
                    enabled: !_updating,
                    textInputAction: TextInputAction.done,
                    onSubmitted: (_) => _addNewTag(),
                    onChanged: (_) => setState(() {}),
                    decoration: const InputDecoration(
                      hintText: 'Add new tag',
                      isDense: true,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                TextButton(
                  onPressed: _updating || _newTag.text.trim().isEmpty
                      ? null
                      : _addNewTag,
                  child: const Text('Add'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (display.isEmpty)
              const Text(
                'No tags yet. Add one above.',
                style: TextStyle(fontSize: 14, color: AppColors.textSec),
              )
            else
              Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final tag in display)
                    CheckboxListTile(
                      value: _isSelected(tag),
                      dense: true,
                      visualDensity: const VisualDensity(horizontal: 0, vertical: -3),
                      contentPadding: EdgeInsets.zero,
                      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      controlAffinity: ListTileControlAffinity.leading,
                      title: Text(tag, style: const TextStyle(fontSize: 15, height: 1.1)),
                      onChanged: _updating ? null : (v) => _setSelected(tag, v == true),
                    ),
                ],
              ),
            if (_updating) ...[
              const SizedBox(height: 12),
              const Row(
                children: [
                  SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: AppColors.primaryBlue,
                    ),
                  ),
                  SizedBox(width: 12),
                  Text(
                    'Updating tags…',
                    style: TextStyle(fontSize: 13, color: AppColors.textMain),
                  ),
                ],
              ),
            ],
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(
                _error!,
                style: const TextStyle(color: AppColors.errorRed, fontSize: 13),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _updating ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        TextButton(
          onPressed: _updating ? null : _save,
          child: Text(widget.confirmLabel),
        ),
      ],
    );
  }
}
