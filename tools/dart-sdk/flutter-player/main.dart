// The program the player starts with, before CodeAssist sends it the app to run. dart:ui only, so the
// player carries a few kilobytes of kernel rather than the framework.
import 'dart:ui' as ui;

void main() {
  final dispatcher = ui.PlatformDispatcher.instance;
  dispatcher.onBeginFrame = (_) {
    final view = dispatcher.views.first;
    final size = view.physicalSize;
    final ratio = view.devicePixelRatio;
    final recorder = ui.PictureRecorder();
    final canvas = ui.Canvas(recorder);
    canvas.drawColor(const ui.Color(0xFF121212), ui.BlendMode.src);
    final builder = ui.ParagraphBuilder(ui.ParagraphStyle(textAlign: ui.TextAlign.center, fontSize: 16 * ratio))
      ..pushStyle(ui.TextStyle(color: const ui.Color(0xFFBDBDBD)))
      ..addText('Waiting for CodeAssist...');
    final paragraph = builder.build()..layout(ui.ParagraphConstraints(width: size.width));
    canvas.drawParagraph(paragraph, ui.Offset(0, (size.height - paragraph.height) / 2));
    final scene = (ui.SceneBuilder()..addPicture(ui.Offset.zero, recorder.endRecording())).build();
    view.render(scene);
  };
  dispatcher.scheduleFrame();
}
