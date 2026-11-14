import 'dart:typed_data';

/// ESC/POS commands for thermal receipt printers (Rongta and other Epson-compatible ones).
/// Receipts go as a picture (raster), because the printers' own fonts have no Khmer.

/// Printer dots across the paper at 203 dpi: 72mm printed on 80mm paper, 48mm on 58mm.
int printerDots(int paperMm) => paperMm == 58 ? 384 : 576;

const _init = [0x1B, 0x40]; // ESC @: reset
const _feedAndCut = [0x1B, 0x64, 0x04, 0x1D, 0x56, 0x42, 0x00]; // ESC d 4, GS V 66 0: feed, partial cut

/// The rows sent per raster command, so a long receipt never overflows the printer's buffer.
const _band = 256;

/// A picture as raster commands, then a feed and a cut. [rgba] is [width] × [height] pixels, 4 bytes
/// each; it is printed [dots] wide (cropped or padded with white), dark pixels black.
Uint8List rasterReceipt(Uint8List rgba, int width, int height, int dots) {
  final rowBytes = (dots + 7) ~/ 8;
  final out = BytesBuilder(copy: false)..add(_init);
  for (var top = 0; top < height; top += _band) {
    final rows = height - top < _band ? height - top : _band;
    // GS v 0, normal size, then bytes per row and rows, low byte first.
    out.add([0x1D, 0x76, 0x30, 0x00, rowBytes & 0xFF, rowBytes >> 8, rows & 0xFF, rows >> 8]);
    final band = Uint8List(rowBytes * rows);
    for (var y = 0; y < rows; y++) {
      for (var x = 0; x < width && x < dots; x++) {
        final p = ((top + y) * width + x) * 4;
        // Transparent counts as white; otherwise luminance below half is black.
        final a = rgba[p + 3];
        final lum = (rgba[p] * 299 + rgba[p + 1] * 587 + rgba[p + 2] * 114) ~/ 1000;
        if (a >= 128 && lum < 128) band[y * rowBytes + (x >> 3)] |= 0x80 >> (x & 7);
      }
    }
    out.add(band);
  }
  out.add(_feedAndCut);
  return out.takeBytes();
}

/// A short plain-text page to check the connection (ASCII only: printer fonts have no Khmer).
Uint8List testPage(List<String> lines) {
  final out = BytesBuilder(copy: false)
    ..add(_init)
    ..add([0x1B, 0x61, 0x01]); // ESC a 1: centre
  for (final line in lines) {
    out.add([for (final c in line.codeUnits) c < 128 ? c : 0x3F]);
    out.addByte(0x0A);
  }
  out.add(_feedAndCut);
  return out.takeBytes();
}
