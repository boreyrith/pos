import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';

import 'escpos.dart';
import 'i18n.dart';

/// Draws the receipt on screen at the printer's resolution ([printerDots] across) as raster commands.
Future<Uint8List> receiptForPrinter(RenderRepaintBoundary receipt, int paperMm) async {
  final dots = printerDots(paperMm);
  final image = await receipt.toImage(pixelRatio: dots / receipt.size.width);
  try {
    final rgba = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
    return rasterReceipt(rgba!.buffer.asUint8List(), image.width, image.height, dots);
  } finally {
    image.dispose();
  }
}

/// Sends [bytes] to a network receipt printer (raw TCP, usually port 9100). Throws a message for the
/// cashier when it can't be reached.
Future<void> sendToPrinter(String host, int port, Uint8List bytes) async {
  Socket? socket;
  try {
    socket = await Socket.connect(host, port, timeout: const Duration(seconds: 5));
    socket.add(bytes);
    await socket.flush().timeout(const Duration(seconds: 20));
  } on Object {
    throw Exception(t("Couldn't reach the printer at {address}. Check that it is on and on the same network.",
        {'address': '$host:$port'}));
  } finally {
    await socket?.close();
  }
}
