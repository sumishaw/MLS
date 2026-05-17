// nihongo_v7/test/widget_test.dart

import 'package:flutter_test/flutter_test.dart';
import 'package:nihongo_v7/main.dart';

void main() {
  testWidgets('App renders without crashing', (WidgetTester tester) async {
    await tester.pumpWidget(const CaptionTranslatorApp());
    await tester.pump(const Duration(milliseconds: 100));
    // Header title visible in the widget tree
    expect(find.text('Caption Lens'), findsOneWidget);
  });

  testWidgets('HomePage shows permission card', (WidgetTester tester) async {
    await tester.pumpWidget(const CaptionTranslatorApp());
    await tester.pump(const Duration(milliseconds: 100));
    // Only one permission card now — overlay (microphone card removed)
    expect(find.text('Overlay Permission'), findsOneWidget);
  });

  testWidgets('Language chips are present', (WidgetTester tester) async {
    await tester.pumpWidget(const CaptionTranslatorApp());
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.textContaining('English'), findsWidgets);
    expect(find.textContaining('Hindi'),   findsWidgets);
  });
}
