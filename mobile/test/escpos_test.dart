import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:pos_mobile/core/escpos.dart';

/// [width] × [height] white pixels, with the given (x, y) black.
Uint8List picture(int width, int height, Set<(int, int)> black) {
  final rgba = Uint8List(width * height * 4);
  for (var y = 0; y < height; y++) {
    for (var x = 0; x < width; x++) {
      final p = (y * width + x) * 4;
      final v = black.contains((x, y)) ? 0 : 255;
      rgba
        ..[p] = v
        ..[p + 1] = v
        ..[p + 2] = v
        ..[p + 3] = 255;
    }
  }
  return rgba;
}

void main() {
  test('paper widths in printer dots', () {
    expect(printerDots(80), 576);
    expect(printerDots(58), 384);
  });

  test('a picture becomes one raster band, then a feed and a cut', () {
    final out = rasterReceipt(picture(10, 2, {(0, 0), (8, 0), (9, 1)}), 10, 2, 16);
    expect(out.sublist(0, 2), [0x1B, 0x40]); // reset
    expect(out.sublist(2, 10), [0x1D, 0x76, 0x30, 0x00, 2, 0, 2, 0]); // GS v 0: 2 bytes a row, 2 rows
    expect(out.sublist(10, 14), [0x80, 0x80, 0x00, 0x40]); // dots, left to right, high bit first
    expect(out.sublist(14), [0x1B, 0x64, 0x04, 0x1D, 0x56, 0x42, 0x00]);
  });

  test('wider than the paper is cropped; long receipts go in bands of 256 rows', () {
    final out = rasterReceipt(picture(20, 300, {(19, 0)}), 20, 300, 8);
    expect(out.sublist(2, 10), [0x1D, 0x76, 0x30, 0x00, 1, 0, 0, 1]); // 256 rows
    expect(out[10], 0x00); // x = 19 is off the 8-dot paper
    final second = 10 + 256;
    expect(out.sublist(second, second + 8), [0x1D, 0x76, 0x30, 0x00, 1, 0, 44, 0]); // the other 44
    expect(out.length, 2 + 8 + 256 + 8 + 44 + 7);
  });

  test('test page: centred ASCII lines, anything else as ?', () {
    final out = testPage(['Shop', 'ខ']);
    expect(out.sublist(0, 5), [0x1B, 0x40, 0x1B, 0x61, 0x01]);
    expect(String.fromCharCodes(out.sublist(5, 12)), 'Shop\n?\n');
  });
}
