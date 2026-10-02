import 'package:flutter/material.dart';
import 'package:mycrmsync/models/add_contact_form.dart';
import 'package:mycrmsync/theme/app_theme.dart';
import 'package:mycrmsync/ui/widgets/tag_selection_dialog.dart';

/// Bottom sheet matching native [AddContactSheet] for create / edit / scan-prefill.
///
/// Returns the saved [AddContactForm] on success, or `null` if dismissed.
Future<AddContactForm?> showAddContactSheet({
  required BuildContext context,
  required List<String> availableTags,
  AddContactForm? initialForm,
  bool isLoadingPrefill = false,
  Future<void> Function(AddContactForm form)? onSubmit,
  Future<void> Function(String contactId)? onDelete,
  void Function(AddContactSheetController controller)? onController,
}) {
  return showModalBottomSheet<AddContactForm>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.cardWhite,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (ctx) {
      return _AddContactSheetScaffold(
        availableTags: availableTags,
        initialForm: initialForm ?? const AddContactForm(),
        isLoadingPrefill: isLoadingPrefill,
        onSubmit: onSubmit,
        onDelete: onDelete,
        onController: onController,
      );
    },
  );
}

/// Allows the caller to push scan-prefill while the sheet is already open.
class AddContactSheetController {
  _AddContactSheetScaffoldState? _state;

  void setLoadingPrefill(bool loading) => _state?.setLoadingPrefill(loading);

  void applyPrefill(AddContactForm form) => _state?.applyPrefill(form);
}

class _AddContactSheetScaffold extends StatefulWidget {
  const _AddContactSheetScaffold({
    required this.availableTags,
    required this.initialForm,
    required this.isLoadingPrefill,
    this.onSubmit,
    this.onDelete,
    this.onController,
  });

  final List<String> availableTags;
  final AddContactForm initialForm;
  final bool isLoadingPrefill;
  final Future<void> Function(AddContactForm form)? onSubmit;
  final Future<void> Function(String contactId)? onDelete;
  final void Function(AddContactSheetController controller)? onController;

  @override
  State<_AddContactSheetScaffold> createState() => _AddContactSheetScaffoldState();
}

class _AddContactSheetScaffoldState extends State<_AddContactSheetScaffold> {
  final _controller = AddContactSheetController();

  late final TextEditingController _firstName;
  late final TextEditingController _lastName;
  late final TextEditingController _phone;
  late final TextEditingController _email;
  late final TextEditingController _company;
  late final TextEditingController _address;
  late final TextEditingController _city;
  late final TextEditingController _stateField;
  late final TextEditingController _postal;
  late final TextEditingController _country;

  late List<String> _selectedTags;
  late String? _contactId;
  late bool _loadingPrefill;
  bool _submitting = false;
  String? _error;

  bool get _isEdit => _contactId != null && _contactId!.trim().isNotEmpty;

  @override
  void initState() {
    super.initState();
    final form = widget.initialForm;
    _contactId = form.contactId;
    _firstName = TextEditingController(text: form.firstName);
    _lastName = TextEditingController(text: form.lastName);
    _phone = TextEditingController(text: form.phone);
    _email = TextEditingController(text: form.email);
    _company = TextEditingController(text: form.companyName);
    _address = TextEditingController(text: form.address);
    _city = TextEditingController(text: form.city);
    _stateField = TextEditingController(text: form.state);
    _postal = TextEditingController(text: form.postalCode);
    _country = TextEditingController(text: form.country);
    _selectedTags = List<String>.from(form.tags);
    _loadingPrefill = widget.isLoadingPrefill;
    _controller._state = this;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      widget.onController?.call(_controller);
    });
  }

  @override
  void dispose() {
    _controller._state = null;
    _firstName.dispose();
    _lastName.dispose();
    _phone.dispose();
    _email.dispose();
    _company.dispose();
    _address.dispose();
    _city.dispose();
    _stateField.dispose();
    _postal.dispose();
    _country.dispose();
    super.dispose();
  }

  void setLoadingPrefill(bool loading) {
    if (!mounted) return;
    setState(() => _loadingPrefill = loading);
  }

  void applyPrefill(AddContactForm form) {
    if (!mounted) return;
    setState(() {
      _firstName.text = form.firstName;
      _lastName.text = form.lastName;
      _phone.text = form.phone;
      _email.text = form.email;
      _company.text = form.companyName;
      _address.text = form.address;
      _city.text = form.city;
      _stateField.text = form.state;
      _postal.text = form.postalCode;
      _country.text = form.country;
      _selectedTags = List<String>.from(form.tags);
      _loadingPrefill = false;
    });
  }

  AddContactForm _buildForm() {
    return AddContactForm(
      contactId: _contactId,
      firstName: _firstName.text,
      lastName: _lastName.text,
      email: _email.text,
      phone: _phone.text,
      companyName: _company.text,
      address: _address.text,
      city: _city.text,
      state: _stateField.text,
      postalCode: _postal.text,
      country: _country.text,
      tags: _selectedTags,
    );
  }

  String? _validate(AddContactForm form) {
    if (form.firstName.trim().isEmpty) return 'First name is required.';
    if (form.phone.trim().isEmpty && form.email.trim().isEmpty) {
      return 'Phone or email is required.';
    }
    return null;
  }

  Future<void> _save() async {
    final form = _buildForm();
    final err = _validate(form);
    if (err != null) {
      setState(() => _error = err);
      return;
    }
    setState(() {
      _error = null;
      _submitting = true;
    });
    try {
      if (widget.onSubmit != null) {
        await widget.onSubmit!(form);
      }
      if (mounted) Navigator.pop(context, form);
    } catch (e) {
      if (mounted) {
        setState(() {
          _submitting = false;
          _error = '$e';
        });
      }
    }
  }

  Future<void> _confirmDelete() async {
    final id = _contactId;
    if (id == null || widget.onDelete == null) return;
    final name = '${_firstName.text} ${_lastName.text}'.trim();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete contact?', style: TextStyle(fontWeight: FontWeight.bold)),
        content: Text(
          name.isEmpty
              ? 'This contact will be permanently removed from your CRM.'
              : '$name will be permanently removed from your CRM.',
          style: const TextStyle(color: AppColors.textSec, fontSize: 14),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delete', style: TextStyle(color: AppColors.errorRed)),
          ),
        ],
      ),
    );
    if (ok != true) return;
    setState(() => _submitting = true);
    try {
      await widget.onDelete!(id);
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) {
        setState(() {
          _submitting = false;
          _error = '$e';
        });
      }
    }
  }

  Future<void> _editTags() async {
    final result = await showTagSelectionDialog(
      context: context,
      title: 'Select Tags',
      confirmLabel: 'Done',
      availableTags: widget.availableTags,
      currentTags: _selectedTags,
    );
    if (result != null && mounted) {
      setState(() => _selectedTags = result);
    }
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.viewInsetsOf(context).bottom;
    final hasScanHints = !_isEdit &&
        (_firstName.text.isNotEmpty ||
            _phone.text.isNotEmpty ||
            _email.text.isNotEmpty ||
            _company.text.isNotEmpty);
    final subtitle = _loadingPrefill
        ? 'Reading your business card…'
        : _isEdit
            ? 'Update contact details. First name and phone or email are required.'
            : hasScanHints
                ? 'Review scanned details and edit anything before saving.'
                : 'Create a new CRM contact. First name and phone or email are required.';

    return Padding(
      padding: EdgeInsets.only(bottom: bottom),
      child: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
          child: Column(
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
                      _isEdit ? 'Edit Contact' : 'Add Contact',
                      style: const TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.bold,
                        color: AppColors.textMain,
                      ),
                    ),
                  ),
                  IconButton(
                    onPressed: _submitting || _loadingPrefill
                        ? null
                        : () => Navigator.pop(context),
                    icon: const Icon(Icons.close, color: AppColors.textSec),
                  ),
                ],
              ),
              Text(subtitle, style: const TextStyle(fontSize: 14, color: AppColors.textSec)),
              const SizedBox(height: 16),
              if (_loadingPrefill)
                const SizedBox(
                  height: 280,
                  child: Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        CircularProgressIndicator(color: AppColors.primaryBlue),
                        SizedBox(height: 16),
                        Text(
                          'Parsing business card…',
                          style: TextStyle(color: AppColors.textSec, fontSize: 14),
                        ),
                      ],
                    ),
                  ),
                )
              else ...[
                _Field(controller: _firstName, label: 'First name *', enabled: !_submitting),
                const SizedBox(height: 12),
                _Field(controller: _lastName, label: 'Last name', enabled: !_submitting),
                const SizedBox(height: 12),
                _Field(
                  controller: _phone,
                  label: 'Phone',
                  enabled: !_submitting,
                  keyboardType: TextInputType.phone,
                ),
                const SizedBox(height: 12),
                _Field(
                  controller: _email,
                  label: 'Email',
                  enabled: !_submitting,
                  keyboardType: TextInputType.emailAddress,
                ),
                const SizedBox(height: 12),
                _Field(controller: _company, label: 'Company', enabled: !_submitting),
                const SizedBox(height: 12),
                _Field(controller: _address, label: 'Address', enabled: !_submitting),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: _Field(controller: _city, label: 'City', enabled: !_submitting),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: _Field(
                        controller: _stateField,
                        label: 'State',
                        enabled: !_submitting,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: _Field(
                        controller: _postal,
                        label: 'Postal code',
                        enabled: !_submitting,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: _Field(
                        controller: _country,
                        label: 'Country',
                        enabled: !_submitting,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                _TagsSection(
                  selectedTags: _selectedTags,
                  enabled: !_submitting,
                  onEditTags: _editTags,
                ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(_error!, style: const TextStyle(color: AppColors.errorRed, fontSize: 13)),
                ],
                const SizedBox(height: 24),
                SizedBox(
                  height: 52,
                  child: FilledButton(
                    onPressed: _submitting ? null : _save,
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.primaryBlue,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                    child: _submitting
                        ? const SizedBox(
                            width: 22,
                            height: 22,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : Text(
                            _isEdit ? 'Save Changes' : 'Save Contact',
                            style: const TextStyle(fontWeight: FontWeight.w600),
                          ),
                  ),
                ),
                const SizedBox(height: 8),
                SizedBox(
                  height: 48,
                  child: OutlinedButton(
                    onPressed: _submitting ? null : () => Navigator.pop(context),
                    style: OutlinedButton.styleFrom(
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                    child: const Text('Cancel', style: TextStyle(color: AppColors.textSec)),
                  ),
                ),
                if (_isEdit && widget.onDelete != null) ...[
                  const SizedBox(height: 16),
                  SizedBox(
                    height: 48,
                    child: OutlinedButton.icon(
                      onPressed: _submitting ? null : _confirmDelete,
                      style: OutlinedButton.styleFrom(
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                      ),
                      icon: const Icon(Icons.delete, color: AppColors.errorRed, size: 18),
                      label: const Text(
                        'Delete Contact',
                        style: TextStyle(
                          color: AppColors.errorRed,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _Field extends StatelessWidget {
  const _Field({
    required this.controller,
    required this.label,
    this.enabled = true,
    this.keyboardType,
  });

  final TextEditingController controller;
  final String label;
  final bool enabled;
  final TextInputType? keyboardType;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w500,
            color: AppColors.textSec,
          ),
        ),
        const SizedBox(height: 6),
        TextField(
          controller: controller,
          enabled: enabled,
          keyboardType: keyboardType,
          decoration: InputDecoration(
            filled: true,
            fillColor: AppColors.lightIndigo.withValues(alpha: 0.4),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide.none,
            ),
            contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
          ),
        ),
      ],
    );
  }
}

class _TagsSection extends StatelessWidget {
  const _TagsSection({
    required this.selectedTags,
    required this.enabled,
    required this.onEditTags,
  });

  final List<String> selectedTags;
  final bool enabled;
  final VoidCallback onEditTags;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.lightIndigo,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Tags',
                  style: TextStyle(
                    fontWeight: FontWeight.w600,
                    color: AppColors.textMain,
                    fontSize: 15,
                  ),
                ),
              ),
              OutlinedButton.icon(
                onPressed: enabled ? onEditTags : null,
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                  visualDensity: VisualDensity.compact,
                ),
                icon: const Icon(Icons.label_outline, size: 16),
                label: const Text('Edit tags', style: TextStyle(fontSize: 13)),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (selectedTags.isEmpty)
            const Text(
              'No tags selected. Tap Edit tags to add.',
              style: TextStyle(fontSize: 13, color: AppColors.textSec),
            )
          else
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: selectedTags
                  .map(
                    (t) => ActionChip(
                      label: Text(t, style: const TextStyle(fontSize: 13)),
                      backgroundColor: Colors.white,
                      onPressed: enabled ? onEditTags : null,
                    ),
                  )
                  .toList(),
            ),
        ],
      ),
    );
  }
}
