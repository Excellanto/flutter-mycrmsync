import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mycrmsync/ui/widgets/tag_selection_dialog.dart';

void main() {
  testWidgets('selecting an existing tag and adding a new tag updates the result', (tester) async {
    List<String>? saved;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) {
            return TextButton(
              onPressed: () async {
                saved = await showTagSelectionDialog(
                  context: context,
                  availableTags: const ['VIP', 'Lead'],
                  currentTags: const ['VIP'],
                );
              },
              child: const Text('open'),
            );
          },
        ),
      ),
    );

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Lead'));
    await tester.pump();

    await tester.enterText(find.byType(TextField), 'Hot');
    await tester.pump();
    await tester.tap(find.text('Add'));
    await tester.pump();

    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(saved, ['VIP', 'Lead', 'Hot']);
  });

  testWidgets('a typed tag is saved even if Add was not pressed', (tester) async {
    List<String>? saved;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) {
            return TextButton(
              onPressed: () async {
                saved = await showTagSelectionDialog(
                  context: context,
                  availableTags: const ['VIP'],
                  currentTags: const <String>[],
                );
              },
              child: const Text('open'),
            );
          },
        ),
      ),
    );

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'Follow up');
    await tester.pump();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(saved, ['Follow up']);
  });
}
