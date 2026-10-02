import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/models/add_contact_form.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/providers/providers.dart';
import 'package:mycrmsync/services/business_card_scan_service.dart';
import 'package:mycrmsync/theme/app_theme.dart';
import 'package:mycrmsync/ui/widgets/add_contact_sheet.dart';
import 'package:mycrmsync/ui/widgets/app_shell.dart';
import 'package:mycrmsync/ui/widgets/contact_list_card.dart';
import 'package:mycrmsync/ui/widgets/tag_selection_dialog.dart';
import 'package:url_launcher/url_launcher.dart';

class ContactsScreen extends ConsumerStatefulWidget {
  const ContactsScreen({
    super.key,
    required this.onNavigate,
    required this.onOpenNotes,
  });

  final void Function(String route) onNavigate;
  final void Function(String id, String name) onOpenNotes;

  @override
  ConsumerState<ContactsScreen> createState() => _ContactsScreenState();
}

enum _ContactSortBy {
  name('name', 'Name', 'asc'),
  dateAdded('date_added', 'Date added', 'desc'),
  activity('activity', 'Activity', 'desc');

  const _ContactSortBy(this.apiValue, this.label, this.defaultDir);

  final String apiValue;
  final String label;
  final String defaultDir;
}

class _ContactsScreenState extends ConsumerState<ContactsScreen> {
  final _search = TextEditingController();
  final _fabKey = GlobalKey();
  final _sortKey = GlobalKey();
  bool _loading = true;
  String? _error;
  List<Map<String, dynamic>> _contacts = [];
  List<String> _tagOptions = const ['All Tags'];
  List<String> _availableTags = const [];
  /// Optimistic tag list per contact until the next CRM list refresh catches up.
  final Map<String, List<String>> _tagOverrides = {};
  String _selectedTag = 'All Tags';
  String _reachedFilter = 'All Contacts';
  _ContactSortBy _sortBy = _ContactSortBy.activity;

  static const _reachedOptions = [
    'All Contacts',
    'Zero Calls Made',
    'At least 1 Call Received',
  ];

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _bootstrap() async {
    await Future.wait([_loadTags(), _load()]);
  }

  Future<void> _loadTags() async {
    try {
      final tags = await ref.read(crmApiProvider).getTags();
      final names = tags
          .map((t) => '${t['name'] ?? t['label'] ?? ''}'.trim())
          .where((n) => n.isNotEmpty)
          .toList();
      if (!mounted) return;
      setState(() {
        _availableTags = names;
        _tagOptions = ['All Tags', ...names];
      });
    } catch (_) {
      // Keep default All Tags only.
    }
  }

  Future<void> _load([String? q]) async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final api = ref.read(crmApiProvider);
      final query = (q ?? _search.text).trim();
      final list = await api.searchContacts(
        query: query,
        pageLimit: 100,
        sortBy: _sortBy.apiValue,
        sortDir: _sortBy.defaultDir,
      );
      if (!mounted) return;
      setState(() {
        _contacts = [
          for (final contact in list) _contactWithTagOverride(contact),
        ];
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _showSortMenu() async {
    final box = _sortKey.currentContext?.findRenderObject() as RenderBox?;
    final overlay = Overlay.of(context).context.findRenderObject() as RenderBox?;
    if (box == null || overlay == null) return;
    final topLeft = box.localToGlobal(Offset.zero, ancestor: overlay);
    final selected = await showMenu<_ContactSortBy>(
      context: context,
      position: RelativeRect.fromLTRB(
        topLeft.dx,
        topLeft.dy + box.size.height,
        overlay.size.width - topLeft.dx - box.size.width,
        0,
      ),
      items: _ContactSortBy.values
          .map(
            (s) => PopupMenuItem<_ContactSortBy>(
              value: s,
              height: 40,
              child: Row(
                children: [
                  Expanded(child: Text(s.label)),
                  if (_sortBy == s)
                    const Icon(Icons.check, size: 18, color: AppColors.primaryBlue),
                ],
              ),
            ),
          )
          .toList(),
    );
    if (selected == null || !mounted) return;
    if (selected == _sortBy) return;
    setState(() => _sortBy = selected);
    await _load();
  }

  List<Map<String, dynamic>> get _filtered {
    return _contacts.where((c) {
      final display = _displayContact(c);
      if (_selectedTag != 'All Tags') {
        final tags = ContactListCard.tagsOf(display);
        if (!tags.any((t) => t.toLowerCase() == _selectedTag.toLowerCase())) {
          return false;
        }
      }
      final calls = ContactListCard.callsOf(display);
      final dialed = _stat(calls, ['total_dialed', 'totalDialed']);
      final received = _stat(calls, ['total_received', 'totalReceived']);
      switch (_reachedFilter) {
        case 'Zero Calls Made':
          return dialed == 0;
        case 'At least 1 Call Received':
          return received >= 1;
        default:
          return true;
      }
    }).toList();
  }

  int _stat(Map<String, dynamic>? m, List<String> keys) {
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

  Future<void> _call(String? phone) async {
    if (phone == null || phone.isEmpty) return;
    if (AppConfig.isAndroid) {
      try {
        await NativeBridges.placeCall(phone);
        return;
      } catch (_) {}
    }
    await launchUrl(Uri(scheme: 'tel', path: phone));
  }

  String _id(Map<String, dynamic> c) =>
      '${c['id'] ?? c['contact_id'] ?? c['contactId'] ?? ''}'.trim();

  Map<String, dynamic> _contactWithTagOverride(Map<String, dynamic> c) {
    final id = _id(c);
    final override = _tagOverrides[id];
    if (override == null) return c;
    final server = tagLabelsOf(c['tags']);
    final same = server.length == override.length &&
        server.every(
          (tag) => override.any((saved) => saved.toLowerCase() == tag.toLowerCase()),
        );
    if (same) {
      _tagOverrides.remove(id);
      return c;
    }
    return {...c, 'tags': List<String>.from(override)};
  }

  Map<String, dynamic> _displayContact(Map<String, dynamic> c) {
    final id = _id(c);
    final override = _tagOverrides[id];
    if (override == null) return c;
    return {...c, 'tags': override};
  }

  List<String> _currentTagsOf(Map<String, dynamic> c) {
    final id = _id(c);
    final override = _tagOverrides[id];
    if (override != null) return List<String>.from(override);
    return AddContactForm.fromContact(c).tags;
  }

  Future<void> _editTags(Map<String, dynamic> c) async {
    final id = _id(c);
    if (id.isEmpty) {
      _snack('Cannot edit tags: missing contact id.');
      return;
    }
    final saved = await showTagSelectionDialog(
      context: context,
      availableTags: _availableTags,
      currentTags: _currentTagsOf(c),
      onSave: (tags) async {
        final serverTags = await ref.read(crmApiProvider).updateContactTags(
              contactId: id,
              tags: tags,
            );
        if (!mounted) return;
        setState(() {
          _tagOverrides[id] = serverTags;
          _contacts = [
            for (final contact in _contacts)
              if (_id(contact) == id) {...contact, 'tags': serverTags} else contact,
          ];
          // Surface newly created tags in the filter dropdown.
          for (final t in serverTags) {
            if (_availableTags.every((a) => a.toLowerCase() != t.toLowerCase())) {
              _availableTags = [..._availableTags, t];
            }
          }
          _tagOptions = ['All Tags', ..._availableTags];
        });
      },
    );
    if (saved != null && mounted) {
      // Soft refresh so list eventually matches CRM search index.
      await Future<void>.delayed(const Duration(milliseconds: 400));
      if (mounted) await _load();
    }
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _persistContact(AddContactForm form) async {
    final api = ref.read(crmApiProvider);
    final body = form.toApiBody(userId: null)..remove('user_id');
    if (form.isEdit) {
      await api.updateContact(body);
    } else {
      await api.addContact(body);
    }
  }

  Future<void> _deleteContact(String contactId) async {
    await ref.read(crmApiProvider).deleteContact(contactId);
  }

  Future<void> _openContactForm({AddContactForm? initial}) async {
    final saved = await showAddContactSheet(
      context: context,
      availableTags: _availableTags,
      initialForm: initial,
      onSubmit: _persistContact,
      onDelete: _deleteContact,
    );
    if (saved != null || initial?.isEdit == true) {
      await _load();
      await _loadTags();
    }
  }

  Future<void> _showFabMenu() async {
    final box = _fabKey.currentContext?.findRenderObject() as RenderBox?;
    final overlay = Overlay.of(context).context.findRenderObject() as RenderBox?;
    if (box == null || overlay == null) {
      await _openContactForm();
      return;
    }
    final topLeft = box.localToGlobal(Offset.zero, ancestor: overlay);
    final selected = await showMenu<String>(
      context: context,
      position: RelativeRect.fromLTRB(
        topLeft.dx,
        topLeft.dy - 8,
        overlay.size.width - topLeft.dx - box.size.width,
        overlay.size.height - topLeft.dy,
      ),
      items: const [
        PopupMenuItem(
          value: 'add',
          child: Row(
            children: [
              Icon(Icons.person_add_outlined),
              SizedBox(width: 12),
              Text('Add Contact'),
            ],
          ),
        ),
        PopupMenuItem(
          value: 'scan',
          child: Row(
            children: [
              Icon(Icons.document_scanner_outlined),
              SizedBox(width: 12),
              Text('Scan Business Card'),
            ],
          ),
        ),
      ],
    );
    if (!mounted) return;
    if (selected == 'add') {
      await _openContactForm();
    } else if (selected == 'scan') {
      await _scanBusinessCard();
    }
  }

  Future<void> _scanBusinessCard() async {
    final service = BusinessCardScanService(ref.read(crmApiProvider));

    late final String imagePath;
    try {
      final captured = await service.captureImage(context);
      if (captured == null || !mounted) return;
      imagePath = captured;
    } on PlatformException catch (e) {
      _snack(e.message ?? 'Unable to open scanner.');
      return;
    } catch (e) {
      _snack('$e');
      return;
    }

    AddContactSheetController? sheetController;
    final sheetFuture = showAddContactSheet(
      context: context,
      availableTags: _availableTags,
      isLoadingPrefill: true,
      onController: (c) => sheetController = c,
      onSubmit: _persistContact,
      onDelete: _deleteContact,
    );

    try {
      final scanResult = await service.parseImage(imagePath);
      for (var i = 0; i < 30 && sheetController == null; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 16));
      }
      if (scanResult.warning != null && scanResult.warning!.isNotEmpty) {
        _snack(scanResult.warning!);
      }
      sheetController?.applyPrefill(scanResult.form);
    } catch (e) {
      sheetController?.applyPrefill(const AddContactForm());
      _snack('$e You can enter details manually.');
    }

    await sheetFuture;
    if (mounted) {
      await _load();
      await _loadTags();
    }
  }

  @override
  Widget build(BuildContext context) {
    final visible = _filtered;
    return AppShell(
      title: 'Contacts',
      currentRoute: 'contacts',
      onNavigate: widget.onNavigate,
      floatingActionButton: FloatingActionButton(
        key: _fabKey,
        backgroundColor: AppColors.primaryBlue,
        foregroundColor: Colors.white,
        onPressed: _showFabMenu,
        child: const Icon(Icons.add, size: 28),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
            child: Text(
              'Total Contacts: ${visible.length}',
              style: const TextStyle(color: AppColors.textSec, fontSize: 14),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
            child: Row(
              children: [
                Expanded(
                  child: Container(
                    height: 52,
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    alignment: Alignment.center,
                    child: TextField(
                      controller: _search,
                      textInputAction: TextInputAction.search,
                      onSubmitted: _load,
                      decoration: const InputDecoration(
                        hintText: 'Search contacts, companies...',
                        hintStyle: TextStyle(color: AppColors.textSec, fontSize: 14),
                        prefixIcon: Icon(Icons.search, color: AppColors.textSec),
                        filled: true,
                        fillColor: Colors.white,
                        border: InputBorder.none,
                        enabledBorder: InputBorder.none,
                        focusedBorder: InputBorder.none,
                        contentPadding: EdgeInsets.symmetric(vertical: 14),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Container(
                  key: _sortKey,
                  width: 52,
                  height: 52,
                  decoration: BoxDecoration(
                    color: AppColors.lightIndigo,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: IconButton(
                    onPressed: _showSortMenu,
                    icon: const Icon(Icons.sort, color: AppColors.darkBlue),
                    tooltip: 'Sort by ${_sortBy.label}',
                  ),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
            child: Row(
              children: [
                Expanded(
                  child: _FilterDropdown(
                    label: _selectedTag == 'All Tags' ? 'Tags' : _selectedTag,
                    options: _tagOptions,
                    onSelected: (v) => setState(() => _selectedTag = v),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _FilterDropdown(
                    label: _reachedFilter == 'All Contacts'
                        ? 'Contact Reached'
                        : _reachedFilter,
                    options: _reachedOptions,
                    onSelected: (v) => setState(() => _reachedFilter = v),
                  ),
                ),
              ],
            ),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
              child: Text(_error!, style: const TextStyle(color: AppColors.errorRed)),
            ),
          const SizedBox(height: 16),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : RefreshIndicator(
                    onRefresh: _load,
                    child: visible.isEmpty
                        ? ListView(
                            children: const [
                              SizedBox(height: 80),
                              Center(
                                child: Text(
                                  'No contacts found',
                                  style: TextStyle(color: AppColors.textSec),
                                ),
                              ),
                            ],
                          )
                        : ListView.separated(
                            padding: const EdgeInsets.fromLTRB(20, 0, 20, 88),
                            itemCount: visible.length,
                            separatorBuilder: (_, __) => const SizedBox(height: 12),
                            itemBuilder: (context, i) {
                              final raw = visible[i];
                              final c = _displayContact(raw);
                              final id = _id(c);
                              final name = ContactListCard.fullName(c);
                              final phone = ContactListCard.phoneOf(c);
                              return ContactListCard(
                                contact: c,
                                onCall: () => _call(phone),
                                onNotes: id.isEmpty
                                    ? () {}
                                    : () => widget.onOpenNotes(id, name),
                                onEdit: () => _openContactForm(
                                  initial: AddContactForm.fromContact(c),
                                ),
                                onTagTap: id.isEmpty ? null : () => _editTags(raw),
                              );
                            },
                          ),
                  ),
          ),
        ],
      ),
    );
  }
}

class _FilterDropdown extends StatelessWidget {
  const _FilterDropdown({
    required this.label,
    required this.options,
    required this.onSelected,
  });

  final String label;
  final List<String> options;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    return PopupMenuButton<String>(
      onSelected: onSelected,
      offset: const Offset(0, 48),
      itemBuilder: (context) => options
          .map(
            (o) => PopupMenuItem<String>(
              value: o,
              height: 36,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 0),
              child: Text(o, style: const TextStyle(fontSize: 14, height: 1.2)),
            ),
          )
          .toList(),
      child: Container(
        height: 44,
        padding: const EdgeInsets.symmetric(horizontal: 14),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: const Color(0xFFE2E8F0)),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: AppColors.textMain,
                  fontSize: 13,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
            const Icon(Icons.keyboard_arrow_down, color: AppColors.textSec),
          ],
        ),
      ),
    );
  }
}
