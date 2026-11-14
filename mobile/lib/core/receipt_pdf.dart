import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:printing/printing.dart';

/// 3 × the receipt as drawn (320 wide for 80mm paper, 232 for 58mm) is about 300 dpi.
const _pixelRatio = 3.0;

/// A receipt as a picture. It goes into the PDF as one because PDF text can't shape Khmer;
/// Flutter's own text drawing can.
class ReceiptImage {
  const ReceiptImage(this.png, this.width, this.height, this.paperMm);

  final Uint8List png;
  final int width;
  final int height;

  /// The receipt paper's width in mm (the store's setting).
  final int paperMm;

  double get _receiptWidth => paperMm * PdfPageFormat.mm;

  static Future<ReceiptImage> capture(RenderRepaintBoundary receipt, int paperMm) async {
    final image = await receipt.toImage(pixelRatio: _pixelRatio);
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    final captured = ReceiptImage(bytes!.buffer.asUint8List(), image.width, image.height, paperMm);
    image.dispose();
    return captured;
  }

  /// One page exactly the receipt's size, as wide as its paper.
  Future<Uint8List> receiptSizedPdf() {
    final doc = pw.Document();
    doc.addPage(pw.Page(
      pageFormat: PdfPageFormat(_receiptWidth, _receiptWidth * height / width),
      build: (_) => pw.Image(pw.MemoryImage(png), fit: pw.BoxFit.fill),
    ));
    return doc.save();
  }

  /// For the printer's paper: a roll gets the receipt as it is; a sheet (A4...) gets it at its paper's width
  /// at the top centre, made smaller only if it is too long for the page.
  Future<Uint8List> pdfFor(PdfPageFormat paper) {
    if (paper.width <= _receiptWidth + 10 * PdfPageFormat.mm || paper.height == double.infinity) return receiptSizedPdf();
    final doc = pw.Document();
    doc.addPage(pw.Page(
      pageFormat: paper,
      margin: const pw.EdgeInsets.all(10 * PdfPageFormat.mm),
      build: (_) => pw.Align(
        alignment: pw.Alignment.topCenter,
        child: pw.SizedBox(width: _receiptWidth, child: pw.Image(pw.MemoryImage(png), fit: pw.BoxFit.contain)),
      ),
    ));
    return doc.save();
  }
}

/// Opens the phone's print dialog (AirPrint on iOS, the print service on Android).
Future<void> printReceipt(ReceiptImage receipt, String name) => Printing.layoutPdf(name: name, onLayout: receipt.pdfFor);

/// Opens the share sheet, to send the receipt by Telegram, email and so on.
Future<void> shareReceipt(ReceiptImage receipt, String name) async =>
    Printing.sharePdf(bytes: await receipt.receiptSizedPdf(), filename: '$name.pdf');
