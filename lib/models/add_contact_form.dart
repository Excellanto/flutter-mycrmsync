/// Reads CRM tag labels from a list, a comma-separated string, or tag objects.
List<String> tagLabelsOf(dynamic raw) {
  final tags = <String>[];
  void add(String label) {
    final trimmed = label.trim();
    if (trimmed.isEmpty || trimmed.toLowerCase() == 'null') return;
    if (tags.any((existing) => existing.toLowerCase() == trimmed.toLowerCase())) {
      return;
    }
    tags.add(trimmed);
  }

  if (raw is String) {
    for (final part in raw.split(RegExp(r'[,|]'))) {
      add(part);
    }
    return tags;
  }
  if (raw is List) {
    for (final item in raw) {
      if (item is Map) {
        add(
          '${item['name'] ?? item['label'] ?? item['tag'] ?? item['title'] ?? item['value'] ?? ''}',
        );
      } else {
        add('$item');
      }
    }
  }
  return tags;
}

class AddContactForm {
  const AddContactForm({
    this.contactId,
    this.firstName = '',
    this.lastName = '',
    this.email = '',
    this.phone = '',
    this.companyName = '',
    this.address = '',
    this.city = '',
    this.state = '',
    this.postalCode = '',
    this.country = '',
    this.tags = const [],
  });

  final String? contactId;
  final String firstName;
  final String lastName;
  final String email;
  final String phone;
  final String companyName;
  final String address;
  final String city;
  final String state;
  final String postalCode;
  final String country;
  final List<String> tags;

  bool get isEdit => contactId != null && contactId!.trim().isNotEmpty;

  factory AddContactForm.fromContact(Map<String, dynamic> c) {
    final id = '${c['id'] ?? c['contact_id'] ?? c['contactId'] ?? ''}'.trim();
    final first = '${c['firstName'] ?? c['first_name'] ?? ''}'.trim();
    final last = '${c['lastName'] ?? c['last_name'] ?? ''}'.trim();
    var resolvedFirst = first;
    var resolvedLast = last;
    if (resolvedFirst.isEmpty && resolvedLast.isEmpty) {
      final name = '${c['name'] ?? c['contact_name'] ?? ''}'.trim();
      if (name.isNotEmpty) {
        final parts = name.split(RegExp(r'\s+'));
        resolvedFirst = parts.first;
        resolvedLast = parts.length > 1 ? parts.sublist(1).join(' ') : '';
      }
    }
    final tags = tagLabelsOf(c['tags']);
    return AddContactForm(
      contactId: id.isEmpty ? null : id,
      firstName: resolvedFirst,
      lastName: resolvedLast,
      email: '${c['email'] ?? ''}'.trim(),
      phone: '${c['phone'] ?? c['phone_number'] ?? c['mobile'] ?? ''}'.trim(),
      companyName: '${c['companyName'] ?? c['company_name'] ?? ''}'.trim(),
      address: '${c['address'] ?? ''}'.trim(),
      city: '${c['city'] ?? ''}'.trim(),
      state: '${c['state'] ?? ''}'.trim(),
      postalCode: '${c['postalCode'] ?? c['postal_code'] ?? ''}'.trim(),
      country: '${c['country'] ?? ''}'.trim(),
      tags: tags,
    );
  }

  factory AddContactForm.fromMap(Map<String, dynamic> m) {
    final tags = tagLabelsOf(m['tags']);
    final id = '${m['contactId'] ?? m['contact_id'] ?? ''}'.trim();
    return AddContactForm(
      contactId: id.isEmpty ? null : id,
      firstName: '${m['firstName'] ?? m['first_name'] ?? ''}'.trim(),
      lastName: '${m['lastName'] ?? m['last_name'] ?? ''}'.trim(),
      email: '${m['email'] ?? ''}'.trim(),
      phone: '${m['phone'] ?? ''}'.trim(),
      companyName: '${m['companyName'] ?? m['company_name'] ?? ''}'.trim(),
      address: '${m['address'] ?? ''}'.trim(),
      city: '${m['city'] ?? ''}'.trim(),
      state: '${m['state'] ?? ''}'.trim(),
      postalCode: '${m['postalCode'] ?? m['postal_code'] ?? ''}'.trim(),
      country: '${m['country'] ?? ''}'.trim(),
      tags: tags,
    );
  }

  Map<String, dynamic> toApiBody({required dynamic userId}) {
    return {
      'user_id': userId,
      if (isEdit) 'contactid': contactId,
      if (isEdit) 'contactId': contactId,
      'firstName': firstName.trim(),
      'lastName': lastName.trim().isEmpty ? null : lastName.trim(),
      'email': email.trim().isEmpty ? null : email.trim(),
      'phone': phone.trim().isEmpty ? null : phone.trim(),
      'companyName': companyName.trim().isEmpty ? null : companyName.trim(),
      'address': address.trim().isEmpty ? null : address.trim(),
      'city': city.trim().isEmpty ? null : city.trim(),
      'state': state.trim().isEmpty ? null : state.trim(),
      'postalCode': postalCode.trim().isEmpty ? null : postalCode.trim(),
      'country': country.trim().isEmpty ? null : country.trim(),
      'tags': tags
          .map((t) => t.trim())
          .where((t) => t.isNotEmpty)
          .toSet()
          .toList(),
    };
  }

  AddContactForm copyWith({
    String? contactId,
    String? firstName,
    String? lastName,
    String? email,
    String? phone,
    String? companyName,
    String? address,
    String? city,
    String? state,
    String? postalCode,
    String? country,
    List<String>? tags,
  }) {
    return AddContactForm(
      contactId: contactId ?? this.contactId,
      firstName: firstName ?? this.firstName,
      lastName: lastName ?? this.lastName,
      email: email ?? this.email,
      phone: phone ?? this.phone,
      companyName: companyName ?? this.companyName,
      address: address ?? this.address,
      city: city ?? this.city,
      state: state ?? this.state,
      postalCode: postalCode ?? this.postalCode,
      country: country ?? this.country,
      tags: tags ?? this.tags,
    );
  }
}
