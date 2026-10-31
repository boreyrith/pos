// The API's JSON shapes, mirroring frontend/src/lib/types.ts. Money comes as JSON numbers.

double _d(dynamic v) => v == null ? 0 : (v as num).toDouble();
double? _dn(dynamic v) => v == null ? null : (v as num).toDouble();
List<T> _list<T>(dynamic v, T Function(Map<String, dynamic>) f) =>
    (v as List? ?? const []).map((e) => f(e as Map<String, dynamic>)).toList();

class RoleRef {
  RoleRef({required this.name, required this.superAdmin, this.maxItemDiscountPct});

  final String name;
  final bool superAdmin;

  /// The most "Discount items" may take off a line, in %; null = no limit.
  final double? maxItemDiscountPct;

  factory RoleRef.fromJson(Map<String, dynamic> j) =>
      RoleRef(name: j['name'] as String, superAdmin: j['superAdmin'] as bool, maxItemDiscountPct: _dn(j['maxItemDiscountPct']));
}

class User {
  User({
    required this.id,
    required this.username,
    required this.fullName,
    required this.language,
    required this.role,
    required this.permissions,
    required this.branchId,
    required this.branchName,
    required this.mustChangePassword,
  });

  final int id;
  final String username;
  final String fullName;
  final String language;
  final RoleRef role;
  final Set<String> permissions;
  final int branchId;
  final String branchName;
  final bool mustChangePassword;

  bool can(String permission) => permissions.contains(permission);

  factory User.fromJson(Map<String, dynamic> j) => User(
        id: j['id'] as int,
        username: j['username'] as String,
        fullName: j['fullName'] as String,
        language: j['language'] as String? ?? 'en',
        role: RoleRef.fromJson(j['role'] as Map<String, dynamic>),
        permissions: {...(j['permissions'] as List).cast<String>()},
        branchId: j['branchId'] as int,
        branchName: j['branchName'] as String,
        mustChangePassword: j['mustChangePassword'] as bool? ?? false,
      );
}

class TokenResponse {
  TokenResponse(this.accessToken, this.user);

  final String accessToken;
  final User user;

  factory TokenResponse.fromJson(Map<String, dynamic> j) =>
      TokenResponse(j['accessToken'] as String, User.fromJson(j['user'] as Map<String, dynamic>));
}

class StoreSettings {
  StoreSettings({
    required this.name,
    this.legalName,
    this.address,
    this.phone,
    this.taxId,
    this.receiptFooter,
    required this.currency,
    required this.taxMode,
    required this.taxLabel,
    required this.taxScope,
    this.defaultTaxRateId,
    required this.timezone,
    required this.khqrSimulated,
    required this.hasLogo,
    required this.logoVersion,
  });

  final String name;
  final String? legalName;
  final String? address;
  final String? phone;
  final String? taxId;
  final String? receiptFooter;

  /// The base currency: prices, totals and reports are in it.
  final String currency;

  /// NONE, INCLUSIVE or EXCLUSIVE.
  final String taxMode;
  final String taxLabel;
  final Set<String> taxScope;
  final int? defaultTaxRateId;
  final String timezone;
  final bool khqrSimulated;
  final bool hasLogo;
  final int logoVersion;

  factory StoreSettings.fromJson(Map<String, dynamic> j) => StoreSettings(
        name: j['name'] as String,
        legalName: j['legalName'] as String?,
        address: j['address'] as String?,
        phone: j['phone'] as String?,
        taxId: j['taxId'] as String?,
        receiptFooter: j['receiptFooter'] as String?,
        currency: j['currency'] as String,
        taxMode: j['taxMode'] as String,
        taxLabel: j['taxLabel'] as String,
        taxScope: {...(j['taxScope'] as List).cast<String>()},
        defaultTaxRateId: j['defaultTaxRateId'] as int?,
        timezone: j['timezone'] as String,
        khqrSimulated: j['khqrSimulated'] as bool? ?? false,
        hasLogo: j['hasLogo'] as bool? ?? false,
        logoVersion: j['logoVersion'] as int? ?? 0,
      );
}

class Currency {
  Currency({
    required this.code,
    required this.symbol,
    required this.decimals,
    required this.cashRounding,
    required this.base,
    required this.active,
    required this.sortOrder,
    this.rate,
  });

  final String code;
  final String symbol;
  final int decimals;

  /// Smallest amount handed over in cash, e.g. 100 for riel.
  final double cashRounding;
  final bool base;
  final bool active;
  final int sortOrder;

  /// Units of this currency per 1 base unit; 1 for the base.
  final double? rate;

  factory Currency.fromJson(Map<String, dynamic> j) => Currency(
        code: j['code'] as String,
        symbol: j['symbol'] as String,
        decimals: j['decimals'] as int,
        cashRounding: _d(j['cashRounding']),
        base: j['base'] as bool,
        active: j['active'] as bool,
        sortOrder: j['sortOrder'] as int? ?? 0,
        rate: _dn(j['rate']),
      );
}

class Category {
  Category(this.id, this.name);

  final int id;
  final String name;

  factory Category.fromJson(Map<String, dynamic> j) => Category(j['id'] as int, j['name'] as String);
}

class ApiPage<T> {
  ApiPage(this.items, this.totalItems);

  final List<T> items;
  final int totalItems;

  static ApiPage<T> parse<T>(dynamic json, T Function(Map<String, dynamic>) f) {
    final j = json as Map<String, dynamic>;
    return ApiPage(_list(j['items'], f), j['totalItems'] as int? ?? 0);
  }
}

/// A catalog entry. Stock and unit are null for services.
class Offering {
  Offering({
    required this.id,
    required this.type,
    required this.sku,
    this.barcode,
    required this.name,
    this.nameKm,
    required this.hasImage,
    required this.imageVersion,
    this.unit,
    this.categoryId,
    required this.price,
    this.pricing,
    required this.taxRateId,
    required this.taxRateName,
    required this.taxRate,
    this.stock,
    required this.sellable,
  });

  final int id;

  /// PRODUCT, SERVICE, ITEM or PACKAGE.
  final String type;
  final String sku;
  final String? barcode;
  final String name;
  final String? nameKm;
  final bool hasImage;
  final int imageVersion;
  final String? unit;
  final int? categoryId;
  final double price;

  /// FIXED or HOURLY (services).
  final String? pricing;
  final int taxRateId;
  final String taxRateName;
  final double taxRate;
  final int? stock;
  final bool sellable;

  bool get hourly => pricing == 'HOURLY';

  /// Khmer name when the app is in Khmer and the entry has one.
  String displayName(String lang) => lang == 'km' && (nameKm?.isNotEmpty ?? false) ? nameKm! : name;

  factory Offering.fromJson(Map<String, dynamic> j) => Offering(
        id: j['id'] as int,
        type: j['type'] as String,
        sku: j['sku'] as String,
        barcode: j['barcode'] as String?,
        name: j['name'] as String,
        nameKm: j['nameKm'] as String?,
        hasImage: j['hasImage'] as bool? ?? false,
        imageVersion: j['imageVersion'] as int? ?? 0,
        unit: j['unit'] as String?,
        categoryId: j['categoryId'] as int?,
        price: _d(j['price']),
        pricing: j['pricing'] as String?,
        taxRateId: j['taxRateId'] as int,
        taxRateName: j['taxRateName'] as String,
        taxRate: _d(j['taxRate']),
        stock: j['stock'] as int?,
        sellable: j['sellable'] as bool? ?? true,
      );
}

class Customer {
  Customer({required this.id, this.code, required this.name, this.phone, required this.walkIn});

  final int id;
  final String? code;
  final String name;
  final String? phone;
  final bool walkIn;

  factory Customer.fromJson(Map<String, dynamic> j) => Customer(
        id: j['id'] as int,
        code: j['code'] as String?,
        name: j['name'] as String,
        phone: j['phone'] as String?,
        walkIn: j['walkIn'] as bool? ?? false,
      );
}

class Tender {
  Tender(this.currency, this.amount);

  final String currency;
  final double amount;

  factory Tender.fromJson(Map<String, dynamic> j) => Tender(j['currency'] as String, _d(j['amount']));
}

/// amount, tendered and changeDue are in the base currency.
class Payment {
  Payment({
    required this.id,
    required this.method,
    required this.status,
    required this.amount,
    this.currency,
    this.currencyAmount,
    this.tendered,
    this.changeDue,
    this.changeCurrency,
    this.changeAmount,
    required this.tenders,
    this.khqrPayload,
    this.expiresAt,
  });

  final int id;

  /// CASH or KHQR.
  final String method;

  /// PENDING, PAID, EXPIRED, CANCELLED or REFUNDED.
  final String status;
  final double amount;
  final String? currency;
  final double? currencyAmount;
  final double? tendered;
  final double? changeDue;
  final String? changeCurrency;
  final double? changeAmount;
  final List<Tender> tenders;
  final String? khqrPayload;
  final DateTime? expiresAt;

  factory Payment.fromJson(Map<String, dynamic> j) => Payment(
        id: j['id'] as int,
        method: j['method'] as String,
        status: j['status'] as String,
        amount: _d(j['amount']),
        currency: j['currency'] as String?,
        currencyAmount: _dn(j['currencyAmount']),
        tendered: _dn(j['tendered']),
        changeDue: _dn(j['changeDue']),
        changeCurrency: j['changeCurrency'] as String?,
        changeAmount: _dn(j['changeAmount']),
        tenders: _list(j['tenders'], Tender.fromJson),
        khqrPayload: j['khqrPayload'] as String?,
        expiresAt: j['expiresAt'] == null ? null : DateTime.parse(j['expiresAt'] as String),
      );
}

/// Something inside a sold package; [depth] > 0 means inside a nested package.
class OrderItemContent {
  OrderItemContent(this.name, this.nameKm, this.quantity, this.unit, this.depth);

  final String name;
  final String? nameKm;
  final double quantity;
  final String? unit;
  final int depth;

  factory OrderItemContent.fromJson(Map<String, dynamic> j) => OrderItemContent(
      j['name'] as String, j['nameKm'] as String?, _d(j['quantity']), j['unit'] as String?, j['depth'] as int? ?? 0);
}

class OrderItem {
  OrderItem({
    required this.name,
    this.nameKm,
    this.unit,
    required this.unitPrice,
    required this.listPrice,
    required this.priceOverridden,
    required this.quantity,
    this.hours,
    required this.taxName,
    required this.taxRate,
    required this.lineTotal,
    this.itemDiscountType,
    this.itemDiscountValue,
    required this.itemDiscount,
    this.staffName,
    required this.contents,
  });

  final String name;
  final String? nameKm;
  final String? unit;
  final double unitPrice;

  /// The catalog price at the time of the sale.
  final double listPrice;
  final bool priceOverridden;
  final int quantity;
  final double? hours;
  final String taxName;

  /// The rate actually charged (0 when the line wasn't taxed).
  final double taxRate;

  /// Before any discount.
  final double lineTotal;
  final String? itemDiscountType;
  final double? itemDiscountValue;
  final double itemDiscount;
  final String? staffName;
  final List<OrderItemContent> contents;

  String displayName(String lang) => lang == 'km' && (nameKm?.isNotEmpty ?? false) ? nameKm! : name;

  factory OrderItem.fromJson(Map<String, dynamic> j) => OrderItem(
        name: j['name'] as String,
        nameKm: j['nameKm'] as String?,
        unit: j['unit'] as String?,
        unitPrice: _d(j['unitPrice']),
        listPrice: _d(j['listPrice']),
        priceOverridden: j['priceOverridden'] as bool? ?? false,
        quantity: j['quantity'] as int,
        hours: _dn(j['hours']),
        taxName: j['taxName'] as String? ?? '',
        taxRate: _d(j['taxRate']),
        lineTotal: _d(j['lineTotal']),
        itemDiscountType: j['itemDiscountType'] as String?,
        itemDiscountValue: _dn(j['itemDiscountValue']),
        itemDiscount: _d(j['itemDiscount']),
        staffName: j['staffName'] as String?,
        contents: _list(j['contents'], OrderItemContent.fromJson),
      );
}

class OrderTax {
  OrderTax(this.name, this.rate, this.included, this.base, this.tax);

  final String name;
  final double rate;
  final bool included;

  /// The net amount after discount.
  final double base;
  final double tax;

  factory OrderTax.fromJson(Map<String, dynamic> j) =>
      OrderTax(j['name'] as String, _d(j['rate']), j['included'] as bool, _d(j['base']), _d(j['tax']));
}

/// A rate an order was sold at, or a return on it.
class OrderRate {
  OrderRate(this.currency, this.rate);

  final String currency;
  final double rate;

  factory OrderRate.fromJson(Map<String, dynamic> j) => OrderRate(j['currency'] as String, _d(j['rate']));
}

class OrderReturn {
  OrderReturn(this.returnNumber, this.total);

  final String returnNumber;
  final double total;

  factory OrderReturn.fromJson(Map<String, dynamic> j) => OrderReturn(j['returnNumber'] as String, _d(j['total']));
}

class Order {
  Order({
    required this.id,
    required this.orderNumber,
    required this.status,
    required this.subtotal,
    this.discountType,
    this.discountValue,
    required this.discountAmount,
    required this.itemDiscount,
    required this.taxIncluded,
    required this.taxMode,
    required this.taxLabel,
    required this.taxableAmount,
    required this.total,
    required this.refundedTotal,
    required this.currency,
    this.note,
    required this.walkIn,
    this.customerName,
    this.customerPhone,
    this.customerTaxId,
    required this.cashierName,
    required this.branchName,
    required this.createdAt,
    this.completedAt,
    required this.items,
    required this.payments,
    required this.taxes,
    required this.rates,
    required this.returns,
  });

  final int id;
  final String orderNumber;

  /// PENDING_PAYMENT, COMPLETED, CANCELLED, PARTIALLY_REFUNDED or REFUNDED.
  final String status;
  final double subtotal;
  final String? discountType;
  final double? discountValue;
  final double discountAmount;
  final double itemDiscount;

  /// The part of the tax already inside the prices.
  final double taxIncluded;

  /// The tax mode and label when sold; later setting changes don't affect them, so reprints never change.
  final String taxMode;
  final String taxLabel;
  final double taxableAmount;
  final double total;
  final double refundedTotal;
  final String currency;
  final String? note;
  final bool walkIn;
  final String? customerName;
  final String? customerPhone;
  final String? customerTaxId;
  final String cashierName;
  final String branchName;
  final DateTime createdAt;
  final DateTime? completedAt;
  final List<OrderItem> items;
  final List<Payment> payments;
  final List<OrderTax> taxes;

  /// The rates the order was sold at.
  final List<OrderRate> rates;
  final List<OrderReturn> returns;

  /// Cancelled and unpaid orders are not sales: they get no receipt to hand over.
  bool get isPaid => status != 'CANCELLED' && status != 'PENDING_PAYMENT';

  factory Order.fromJson(Map<String, dynamic> j) => Order(
        id: j['id'] as int,
        orderNumber: j['orderNumber'] as String,
        status: j['status'] as String,
        subtotal: _d(j['subtotal']),
        discountType: j['discountType'] as String?,
        discountValue: _dn(j['discountValue']),
        discountAmount: _d(j['discountAmount']),
        itemDiscount: _d(j['itemDiscount']),
        taxIncluded: _d(j['taxIncluded']),
        taxMode: j['taxMode'] as String? ?? 'NONE',
        taxLabel: j['taxLabel'] as String? ?? '',
        taxableAmount: _d(j['taxableAmount']),
        total: _d(j['total']),
        refundedTotal: _d(j['refundedTotal']),
        currency: j['currency'] as String,
        note: j['note'] as String?,
        walkIn: j['walkIn'] as bool? ?? true,
        customerName: j['customerName'] as String?,
        customerPhone: j['customerPhone'] as String?,
        customerTaxId: j['customerTaxId'] as String?,
        cashierName: j['cashierName'] as String,
        branchName: j['branchName'] as String,
        createdAt: DateTime.parse(j['createdAt'] as String),
        completedAt: j['completedAt'] == null ? null : DateTime.parse(j['completedAt'] as String),
        items: _list(j['items'], OrderItem.fromJson),
        payments: _list(j['payments'], Payment.fromJson),
        taxes: _list(j['taxes'], OrderTax.fromJson),
        rates: _list(j['rates'], OrderRate.fromJson),
        returns: _list(j['returns'], OrderReturn.fromJson),
      );
}

class OrderSummary {
  OrderSummary({
    required this.id,
    required this.orderNumber,
    required this.status,
    required this.total,
    required this.currency,
    required this.walkIn,
    this.customerName,
    required this.cashierName,
    required this.createdAt,
  });

  final int id;
  final String orderNumber;
  final String status;
  final double total;
  final String currency;
  final bool walkIn;
  final String? customerName;
  final String cashierName;
  final DateTime createdAt;

  factory OrderSummary.fromJson(Map<String, dynamic> j) => OrderSummary(
        id: j['id'] as int,
        orderNumber: j['orderNumber'] as String,
        status: j['status'] as String,
        total: _d(j['total']),
        currency: j['currency'] as String,
        walkIn: j['walkIn'] as bool? ?? true,
        customerName: j['customerName'] as String?,
        cashierName: j['cashierName'] as String,
        createdAt: DateTime.parse(j['createdAt'] as String),
      );
}

/// A held (parked) sale.
class HeldSale {
  HeldSale({
    required this.id,
    required this.holdNumber,
    required this.status,
    this.label,
    this.customerId,
    this.customerName,
    this.note,
    required this.itemCount,
    required this.estimatedTotal,
    required this.createdBy,
    required this.updatedAt,
    this.lockedBy,
    required this.lockedByMe,
  });

  final int id;
  final String holdNumber;

  /// HELD, IN_USE, COMPLETED, VOIDED or EXPIRED.
  final String status;
  final String? label;
  final int? customerId;
  final String? customerName;
  final String? note;
  final int itemCount;
  final double estimatedTotal;
  final String createdBy;
  final DateTime updatedAt;
  final String? lockedBy;
  final bool lockedByMe;

  factory HeldSale.fromJson(Map<String, dynamic> j) => HeldSale(
        id: j['id'] as int,
        holdNumber: j['holdNumber'] as String,
        status: j['status'] as String,
        label: j['label'] as String?,
        customerId: j['customerId'] as int?,
        customerName: j['customerName'] as String?,
        note: j['note'] as String?,
        itemCount: j['itemCount'] as int? ?? 0,
        estimatedTotal: _d(j['estimatedTotal']),
        createdBy: j['createdBy'] as String? ?? '',
        updatedAt: DateTime.parse(j['updatedAt'] as String),
        lockedBy: j['lockedBy'] as String?,
        lockedByMe: j['lockedByMe'] as bool? ?? false,
      );
}

class HeldLine {
  HeldLine({
    required this.offeringId,
    required this.quantity,
    this.hours,
    this.staffId,
    this.unitPrice,
    this.priceReason,
    this.discountType,
    this.discountValue,
  });

  final int offeringId;
  final int quantity;
  final double? hours;
  final int? staffId;
  final double? unitPrice;
  final String? priceReason;
  final String? discountType;
  final double? discountValue;

  factory HeldLine.fromJson(Map<String, dynamic> j) => HeldLine(
        offeringId: j['offeringId'] as int,
        quantity: j['quantity'] as int,
        hours: _dn(j['hours']),
        staffId: j['staffId'] as int?,
        unitPrice: _dn(j['unitPrice']),
        priceReason: j['priceReason'] as String?,
        discountType: j['discountType'] as String?,
        discountValue: _dn(j['discountValue']),
      );
}

/// A resumed hold: the cart to load and what changed since it was held.
class ResumedHold {
  ResumedHold(this.hold, this.lines, this.discountType, this.discountValue, this.warnings);

  final HeldSale hold;
  final List<HeldLine> lines;
  final String? discountType;
  final double? discountValue;
  final List<String> warnings;

  factory ResumedHold.fromJson(Map<String, dynamic> j) {
    final cart = j['cart'] as Map<String, dynamic>;
    return ResumedHold(
      HeldSale.fromJson(j['hold'] as Map<String, dynamic>),
      _list(cart['lines'], HeldLine.fromJson),
      cart['discountType'] as String?,
      _dn(cart['discountValue']),
      (j['warnings'] as List? ?? const []).cast<String>(),
    );
  }
}
