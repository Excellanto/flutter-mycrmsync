import 'package:flutter/material.dart';
import 'package:mycrmsync/models/add_contact_form.dart';
import 'package:mycrmsync/services/external_links.dart';
import 'package:mycrmsync/theme/app_theme.dart';

/// Contact card matching the Compose [ContactListCard] / screenshot layout.
class ContactListCard extends StatelessWidget {
  const ContactListCard({
    super.key,
    required this.contact,
    required this.onCall,
    required this.onNotes,
    this.onEdit,
    this.onTagTap,
  });

  final Map<String, dynamic> contact;
  final VoidCallback onCall;
  final VoidCallback onNotes;
  final VoidCallback? onEdit;
  final VoidCallback? onTagTap;

  static String fullName(Map<String, dynamic> c) {
    final first = '${c['firstName'] ?? c['first_name'] ?? ''}'.trim();
    final last = '${c['lastName'] ?? c['last_name'] ?? ''}'.trim();
    final combined = '$first $last'.trim();
    if (combined.isNotEmpty) return combined;
    final name = '${c['name'] ?? c['contact_name'] ?? c['companyName'] ?? c['company_name'] ?? ''}'
        .trim();
    return name.isEmpty ? 'Unknown' : name;
  }

  static String initials(Map<String, dynamic> c) {
    final first = '${c['firstName'] ?? c['first_name'] ?? ''}'.trim();
    final last = '${c['lastName'] ?? c['last_name'] ?? ''}'.trim();
    if (first.isNotEmpty || last.isNotEmpty) {
      final a = first.isNotEmpty ? first[0] : '';
      final b = last.isNotEmpty ? last[0] : '';
      return '$a$b'.toUpperCase();
    }
    final name = fullName(c);
    final parts = name.split(RegExp(r'\s+')).where((p) => p.isNotEmpty).take(2);
    return parts.map((p) => p[0].toUpperCase()).join().ifEmpty('?');
  }

  static String? phoneOf(Map<String, dynamic> c) {
    final p = c['phone'] ?? c['phone_number'] ?? c['mobile'];
    if (p != null && '$p'.trim().isNotEmpty) return '$p'.trim();
    final phones = c['phones'];
    if (phones is List && phones.isNotEmpty) {
      final first = phones.first;
      if (first is Map) {
        final v = '${first['phone'] ?? first['number'] ?? ''}'.trim();
        return v.isEmpty ? null : v;
      }
      final v = first.toString().trim();
      return v.isEmpty ? null : v;
    }
    return null;
  }

  static String? emailOf(Map<String, dynamic> c) {
    final e = c['email']?.toString().trim();
    return (e == null || e.isEmpty) ? null : e;
  }

  static List<String> tagsOf(Map<String, dynamic> c) {
    final tags = <String>[];
    final type = '${c['type'] ?? ''}'.trim();
    if (type.isNotEmpty) tags.add(type);
    for (final label in tagLabelsOf(c['tags'])) {
      if (!tags.any((x) => x.toLowerCase() == label.toLowerCase())) {
        tags.add(label);
      }
    }
    return tags;
  }

  static Map<String, dynamic>? callsOf(Map<String, dynamic> c) {
    final calls = c['calls'];
    if (calls is Map) return Map<String, dynamic>.from(calls);
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final name = fullName(contact);
    final tags = tagsOf(contact);
    final calls = callsOf(contact);
    final phone = phoneOf(contact);
    final email = emailOf(contact);
    final noteCount = _int(calls, ['total_notes', 'totalNotes']);
    final dialed = _int(calls, ['total_dialed', 'totalDialed']);
    final received = _int(calls, ['total_received', 'totalReceived']);
    final talkSec = _int(calls, ['total_talk_time', 'totalTalkTime']);

    return Material(
      color: AppColors.cardWhite,
      elevation: 1,
      shadowColor: Colors.black12,
      borderRadius: BorderRadius.circular(16),
      clipBehavior: Clip.none,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 4, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Stack(
              clipBehavior: Clip.none,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    CircleAvatar(
                      radius: 22,
                      backgroundColor: AppColors.lightBg,
                      child: Text(
                        initials(contact),
                        style: const TextStyle(
                          color: AppColors.textSec,
                          fontWeight: FontWeight.bold,
                          fontSize: 14,
                        ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.only(right: 56, top: 2),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: AppColors.textMain,
                                fontSize: 15,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            const SizedBox(height: 6),
                            GestureDetector(
                              onTap: onTagTap,
                              behavior: HitTestBehavior.opaque,
                              child: Wrap(
                                spacing: 6,
                                runSpacing: 4,
                                children: [
                                  if (tags.isEmpty)
                                    const _TagChip(label: 'NO TAG')
                                  else
                                    ...tags.map(
                                      (t) => _TagChip(label: t.toUpperCase()),
                                    ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
                Positioned(
                  top: -6,
                  right: -2,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        visualDensity: VisualDensity.compact,
                        padding: EdgeInsets.zero,
                        constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
                        onPressed: onEdit,
                        icon: const Icon(Icons.edit_outlined, size: 16, color: AppColors.textGray),
                        tooltip: 'Edit contact',
                      ),
                      SizedBox(
                        width: 30,
                        height: 28,
                        child: Stack(
                          clipBehavior: Clip.none,
                          alignment: Alignment.center,
                          children: [
                            IconButton(
                              visualDensity: VisualDensity.compact,
                              padding: EdgeInsets.zero,
                              constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
                              onPressed: onNotes,
                              icon: const Icon(
                                Icons.sticky_note_2_outlined,
                                size: 17,
                                color: AppColors.primaryBlue,
                              ),
                              tooltip: 'Notes',
                            ),
                            if (noteCount > 0)
                              Positioned(
                                right: 0,
                                top: -1,
                                child: _NotesCountBadge(count: noteCount),
                              ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Divider(height: 1, color: AppColors.lightBg),
            const SizedBox(height: 6),
            Row(
              children: [
                Expanded(
                  child: Row(
                    children: [
                      _StatChip(
                        icon: Icons.call_made,
                        iconColor: AppColors.primaryBlue,
                        bg: AppColors.primaryBlue.withValues(alpha: 0.12),
                        value: '$dialed',
                      ),
                      const SizedBox(width: 8),
                      _StatChip(
                        icon: Icons.access_time,
                        iconColor: AppColors.textSec,
                        bg: AppColors.textSec.withValues(alpha: 0.12),
                        value: _formatTalk(talkSec),
                      ),
                      const SizedBox(width: 8),
                      _StatChip(
                        icon: Icons.call_received,
                        iconColor: AppColors.successGreen,
                        bg: AppColors.successGreen.withValues(alpha: 0.12),
                        value: '$received',
                      ),
                    ],
                  ),
                ),
                _ActionIcon(
                  icon: Icons.sms_outlined,
                  onTap: phone == null ? null : () => _sms(context, phone),
                ),
                _ActionIcon(
                  icon: Icons.email_outlined,
                  onTap: email == null ? null : () => _email(context, email),
                ),
                _ActionIcon(
                  iconAsset: 'assets/whatsapp_icon.png',
                  onTap: phone == null ? null : () => _whatsapp(context, phone),
                ),
                _ActionIcon(
                  icon: Icons.phone_in_talk,
                  color: AppColors.primaryBlue,
                  onTap: phone == null ? null : onCall,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  static int _int(Map<String, dynamic>? m, List<String> keys) {
    if (m == null) return 0;
    for (final k in keys) {
      final v = m[k];
      if (v is int) return v;
      if (v is num) return v.toInt();
      final p = int.tryParse('$v');
      if (p != null) return p;
    }
    return 0;
  }

  static String _formatTalk(int seconds) {
    if (seconds <= 0) return '0m';
    final m = seconds ~/ 60;
    final s = seconds % 60;
    if (m > 0 && s > 0) return '${m}m ${s}s';
    if (m > 0) return '${m}m';
    return '${s}s';
  }

  Future<void> _sms(BuildContext context, String phone) {
    return openExternalLink(
      context,
      Uri.parse('sms:$phone'),
      failureMessage: 'Could not open the Messages app for this number.',
    );
  }

  Future<void> _email(BuildContext context, String email) {
    return openExternalLink(
      context,
      Uri.parse('mailto:$email'),
      failureMessage: 'Could not open an email app for this address.',
    );
  }

  Future<void> _whatsapp(BuildContext context, String phone) {
    final digits = phone.replaceAll(RegExp(r'[^\d+]'), '').replaceFirst('+', '');
    if (digits.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('This contact does not have a usable phone number.')),
      );
      return Future.value();
    }
    return openExternalLink(
      context,
      Uri.parse('https://wa.me/$digits'),
      failureMessage: 'Could not open WhatsApp.',
    );
  }
}

extension on String {
  String ifEmpty(String fallback) => isEmpty ? fallback : this;
}

class _NotesCountBadge extends StatelessWidget {
  const _NotesCountBadge({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final label = count > 99 ? '99+' : '$count';
    final wide = label.length > 1;
    return Container(
      width: wide ? null : 14,
      height: 14,
      padding: wide ? const EdgeInsets.symmetric(horizontal: 3) : EdgeInsets.zero,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: AppColors.primaryBlue,
        borderRadius: BorderRadius.circular(7),
        border: Border.all(color: Colors.white, width: 1.5),
      ),
      child: Text(
        label,
        textAlign: TextAlign.center,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 8,
          fontWeight: FontWeight.bold,
          height: 1,
          leadingDistribution: TextLeadingDistribution.even,
        ),
      ),
    );
  }
}

class _TagChip extends StatelessWidget {
  const _TagChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(3),
        border: Border.all(color: const Color(0xFFCBD5E1)),
      ),
      child: Text(
        label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          fontSize: 9,
          fontWeight: FontWeight.bold,
          color: AppColors.textSec,
        ),
      ),
    );
  }
}

class _StatChip extends StatelessWidget {
  const _StatChip({
    required this.icon,
    required this.iconColor,
    required this.bg,
    required this.value,
  });

  final IconData icon;
  final Color iconColor;
  final Color bg;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 22,
          height: 22,
          decoration: BoxDecoration(color: bg, shape: BoxShape.circle),
          child: Icon(icon, size: 12, color: iconColor),
        ),
        const SizedBox(width: 4),
        Text(
          value,
          style: const TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.bold,
            color: AppColors.textMain,
          ),
        ),
      ],
    );
  }
}

class _ActionIcon extends StatelessWidget {
  const _ActionIcon({
    required this.onTap,
    this.icon,
    this.iconAsset,
    this.color = AppColors.primaryBlue,
  });

  final IconData? icon;
  final String? iconAsset;
  final VoidCallback? onTap;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    final Widget glyph;
    if (iconAsset != null) {
      glyph = Opacity(
        opacity: enabled ? 1 : 0.4,
        child: Image.asset(
          iconAsset!,
          width: 22,
          height: 22,
          fit: BoxFit.contain,
        ),
      );
    } else {
      glyph = Icon(
        icon,
        size: 22,
        color: enabled ? color : AppColors.textGray,
      );
    }
    return IconButton(
      visualDensity: VisualDensity.compact,
      padding: EdgeInsets.zero,
      constraints: const BoxConstraints(minWidth: 36, minHeight: 36),
      onPressed: onTap,
      icon: glyph,
    );
  }
}
