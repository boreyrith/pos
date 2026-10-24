/** What a role can be allowed to do (fixed in the backend: com.pos.access.Permission). */
export type Permission =
  | "SALES_SELL"
  | "ORDERS_VIEW"
  | "ORDERS_CANCEL"
  | "ORDERS_REFUND"
  | "DISCOUNT_ITEMS"
  | "PRICE_OVERRIDE"
  | "PRICE_BELOW_COST"
  | "HOLDS_MANAGE"
  | "CUSTOMERS_VIEW"
  | "CUSTOMERS_MANAGE"
  | "CATALOG_MANAGE"
  | "INVENTORY_VIEW"
  | "STOCK_ADJUST"
  | "STOCK_COUNT"
  | "STOCK_TRANSFER"
  | "PURCHASE_VIEW"
  | "VENDORS_MANAGE"
  | "PURCHASE_ORDER"
  | "PURCHASE_RECEIVE"
  | "PURCHASE_INVOICE"
  | "PURCHASE_PAY"
  | "REPORTS_DASHBOARD"
  | "REPORTS_TAX"
  | "REPORTS_SALES"
  | "REPORTS_INVENTORY"
  | "REPORTS_PURCHASING"
  | "SETTINGS_VIEW"
  | "SETTINGS_COMPANY"
  | "SETTINGS_TAX"
  | "SETTINGS_KHQR"
  | "SETTINGS_CURRENCY"
  | "CURRENCY_RATES"
  | "BRANCHES_MANAGE"
  | "STORAGES_MANAGE"
  | "USERS_VIEW"
  | "USERS_MANAGE"
  | "ROLES_MANAGE"
  | "AUDIT_VIEW"
  | "ALL_BRANCHES";

/** A user's role, as shown on accounts. */
export interface RoleRef {
  id: number;
  code: string;
  name: string;
  superAdmin: boolean;
  /** The most "Discount items" may take off a line, in %; null = no limit. */
  maxItemDiscountPct: number | null;
}

/** Users > Roles. `permissions` is what was ticked; `effective` adds what they imply (all for Super Admin). */
export interface RoleDetail extends RoleRef {
  description: string | null;
  builtIn: boolean;
  permissions: Permission[];
  effective: Permission[];
  users: number;
  /** Whether the current user may give this role to someone. */
  assignable: boolean;
  /** Whether the current user may change it. */
  editable: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface PermissionInfo {
  code: Permission;
  module: string;
  moduleLabel: string;
  label: string;
  description: string;
  implies: Permission[];
}

export interface User {
  id: number;
  username: string;
  fullName: string;
  email: string | null;
  phone: string | null;
  /** Interface language: English (default) or Khmer. */
  language: "en" | "km";
  role: RoleRef;
  /** What they can do (every permission for a Super Admin). */
  permissions: Permission[];
  active: boolean;
  branchId: number;
  branchName: string;
  lastLoginAt: string | null;
  /** Locked after too many wrong passwords, until `lockedUntil`. */
  locked: boolean;
  lockedUntil: string | null;
  /** Must choose a new password before using the app. */
  mustChangePassword: boolean;
  passwordChangedAt: string | null;
}

export type AuditCategory = "AUTH" | "USERS" | "SETTINGS" | "CATALOG" | "INVENTORY" | "PURCHASING" | "SALES";

export interface AuditEntry {
  id: number;
  createdAt: string;
  userId: number | null;
  username: string | null;
  branchId: number | null;
  action: string;
  category: AuditCategory;
  entityType: string | null;
  entityId: string | null;
  summary: string;
  /** JSON text, e.g. {"changes":[{"field":"Price","from":"2.5","to":"2.75"}]} */
  details: string | null;
  ip: string | null;
  userAgent: string | null;
}

export interface Storage {
  id: number;
  branchId: number;
  name: string;
  /** Sales take stock from the branch's one selling storage. */
  selling: boolean;
  active: boolean;
}

export interface Branch {
  id: number;
  code: string;
  name: string;
  address: string | null;
  active: boolean;
  storages: Storage[];
}

/** A storage with its branch, as shown on transfers and history. */
export interface Place {
  storageId: number;
  storageName: string;
  branchId: number;
  branchName: string;
}

export interface StockLevel {
  branchId: number;
  branchName: string;
  storageId: number;
  storageName: string;
  selling: boolean;
  storageActive: boolean;
  quantity: number;
}

export type MovementType =
  | "OPENING"
  | "SALE"
  | "SALE_RETURN"
  | "REFUND"
  | "PURCHASE"
  | "RECEIVE"
  | "REMOVE"
  | "COUNT"
  | "TRANSFER_OUT"
  | "TRANSFER_IN"
  | "TRANSFER_RETURN"
  | "ADJUSTMENT_IN"
  | "ADJUSTMENT_OUT";

export type AdjustmentReason =
  | "RECEIVED"
  | "FOUND"
  | "CUSTOMER_RETURN"
  | "DAMAGED"
  | "EXPIRED"
  | "LOST"
  | "THEFT"
  | "INTERNAL_USE"
  | "CORRECTION"
  | "COUNT"
  | "OTHER";

export interface ReasonOption {
  code: AdjustmentReason;
  label: string;
  /** Fits a stock increase / decrease. */
  increase: boolean;
  decrease: boolean;
}

export type AdjustmentKind = "ADJUSTMENT" | "COUNT";

/** All quantities in base units. */
export interface AdjustmentLine {
  offeringId: number;
  name: string;
  sku: string;
  unit: string | null;
  reason: AdjustmentReason;
  reasonLabel: string;
  note: string | null;
  beforeQty: number;
  changeQty: number;
  afterQty: number;
  /** Counts: what the count sheet showed when loaded. */
  expectedQty: number | null;
  unitCost: number;
  valueChange: number;
}

export interface StockAdjustment {
  id: number;
  adjustmentNumber: string;
  kind: AdjustmentKind;
  place: Place;
  note: string | null;
  lineCount: number;
  /** Cost value of the change; negative for losses. */
  valueChange: number;
  createdBy: string;
  createdAt: string;
  lines: AdjustmentLine[] | null;
}

export interface CountSheetRow {
  offeringId: number;
  type: OfferingType;
  name: string;
  nameKm: string | null;
  sku: string;
  unit: string | null;
  categoryName: string | null;
  systemQty: number;
  unitCost: number;
}

export interface StockMovement {
  id: number;
  createdAt: string;
  type: MovementType;
  reason: AdjustmentReason | null;
  offeringId: number;
  offeringName: string;
  sku: string;
  unit: string | null;
  place: Place;
  /** Signed change in base units. */
  quantity: number;
  balance: number;
  userName: string | null;
  orderId: number | null;
  transferId: number | null;
  adjustmentId: number | null;
  adjustmentNumber: string | null;
  /** For deliveries from a vendor. */
  purchaseOrderId: number | null;
  receiptNumber: string | null;
  note: string | null;
}

// ---------------------------------------------------------------- customers

export interface Customer {
  id: number;
  code: string | null;
  name: string;
  phone: string | null;
  email: string | null;
  address: string | null;
  taxId: string | null;
  note: string | null;
  active: boolean;
  /** The default Walk-in Customer. */
  walkIn: boolean;
  createdAt: string;
}

export interface CustomerDetail {
  customer: Customer;
  stats: { orders: number; spent: number; refunded: number; firstVisit: string | null; lastVisit: string | null };
}

// ---------------------------------------------------------------- purchasing

export interface Vendor {
  id: number;
  code: string;
  name: string;
  contactName: string | null;
  phone: string | null;
  email: string | null;
  address: string | null;
  taxId: string | null;
  paymentTermsDays: number;
  note: string | null;
  active: boolean;
  /** Owed on unpaid invoices. */
  balance: number;
  /** Orders not yet fully received. */
  openOrders: number;
}

export type PurchaseStatus = "DRAFT" | "ORDERED" | "PARTIALLY_RECEIVED" | "RECEIVED" | "CANCELLED";

export interface PurchaseOrderSummary {
  id: number;
  poNumber: string;
  status: PurchaseStatus;
  vendorId: number;
  vendorName: string;
  branchName: string;
  total: number;
  expectedDate: string | null;
  createdAt: string;
  lines: number;
  linesOpen: number;
}

/** Quantities are in `unit` (`factor` base units each); `unitCost` is per that unit. */
export interface PurchaseOrderLine {
  id: number;
  offeringId: number;
  name: string;
  sku: string;
  unitId: number | null;
  unit: string | null;
  factor: number;
  quantity: number;
  receivedQty: number;
  remaining: number;
  unitCost: number;
  lineTotal: number;
}

export interface GoodsReceipt {
  id: number;
  receiptNumber: string;
  storageName: string;
  deliveryRef: string | null;
  note: string | null;
  receivedBy: string;
  receivedAt: string;
  lines: { lineId: number; name: string; unit: string | null; quantity: number; baseQuantity: number; unitCost: number }[];
}

export interface PurchaseOrder {
  id: number;
  poNumber: string;
  status: PurchaseStatus;
  vendorId: number;
  vendorName: string;
  branchId: number;
  branchName: string;
  storageId: number;
  storageName: string;
  expectedDate: string | null;
  vendorRef: string | null;
  note: string | null;
  total: number;
  receivedValue: number;
  createdBy: string;
  createdAt: string;
  orderedAt: string | null;
  closedAt: string | null;
  lines: PurchaseOrderLine[];
  receipts: GoodsReceipt[];
  invoices: VendorInvoice[];
}

export type InvoiceStatus = "UNPAID" | "PARTIALLY_PAID" | "PAID" | "VOID";
export type VendorPaymentMethod = "CASH" | "BANK_TRANSFER" | "KHQR" | "CHEQUE" | "OTHER";

export interface VendorInvoice {
  id: number;
  vendorId: number;
  vendorName: string;
  purchaseOrderId: number | null;
  poNumber: string | null;
  invoiceNumber: string;
  invoiceDate: string;
  dueDate: string;
  subtotal: number;
  tax: number;
  total: number;
  paid: number;
  balance: number;
  status: InvoiceStatus;
  overdue: boolean;
  note: string | null;
  createdBy: string;
  createdAt: string;
  payments: { id: number; amount: number; method: VendorPaymentMethod; paidOn: string; reference: string | null; note: string | null; createdBy: string; createdAt: string }[];
}

export interface PayablesSummary {
  owed: number;
  overdue: number;
  unpaidInvoices: number;
  openOrders: number;
}

export type TransferStatus = "IN_TRANSIT" | "RECEIVED" | "CANCELLED";

export interface Transfer {
  id: number;
  transferNumber: string;
  status: TransferStatus;
  from: Place;
  to: Place;
  note: string | null;
  createdBy: string;
  createdAt: string;
  closedBy: string | null;
  closedAt: string | null;
  lines: { offeringId: number; name: string; sku: string; unit: string | null; quantity: number }[];
}

export interface TokenResponse {
  accessToken: string;
  expiresIn: number;
  user: User;
}

/**
 * NONE: no tax. INCLUSIVE: listed prices include the tax (tax = price x r / (1 + r)).
 * EXCLUSIVE: tax is added on top of listed prices (tax = price x r).
 */
export type TaxMode = "NONE" | "INCLUSIVE" | "EXCLUSIVE";

/** Settings > Taxes. Rates are fractions: 0.1 = 10%. */
export interface TaxSettings {
  mode: TaxMode;
  label: string;
  /** The default tax rate's rate. */
  vatRate: number;
  defaultRateId: number | null;
  defaultRateName: string | null;
  maxRate: number;
  taxProducts: boolean;
  taxServices: boolean;
  taxItems: boolean;
  taxPackages: boolean;
  taxId: string | null;
  updatedBy: string | null;
  updatedAt: string;
}

/** Settings > KHQR payments. The token itself is never sent; `tokenHint` is its last 4 characters. */
export interface KhqrSettings {
  /** SIMULATED: test mode, payments confirmed by hand. BAKONG: live, confirmed with the Bakong API. */
  mode: "SIMULATED" | "BAKONG";
  /** false: following the server default (environment). */
  modeSaved: boolean;
  accountId: string;
  merchantName: string;
  merchantCity: string;
  apiBaseUrl: string;
  apiBaseUrlSaved: boolean;
  expiryMinutes: number;
  expirySaved: boolean;
  tokenSource: "SAVED" | "SERVER" | "NONE";
  tokenHint: string | null;
  tokenExpiresAt: string | null;
  tokenUpdatedAt: string | null;
  updatedBy: string | null;
  updatedAt: string;
}

/** Company details and settings every screen needs. */
export interface StoreSettings {
  name: string;
  legalName: string | null;
  taxId: string | null;
  address: string | null;
  phone: string | null;
  email: string | null;
  website: string | null;
  receiptFooter: string | null;
  /** The base currency: prices, totals and reports are in it. */
  currency: string;
  /** How prices are taxed; set under Settings > Taxes. */
  taxMode: TaxMode;
  /** What tax is called on screens and receipts, e.g. "VAT". */
  taxLabel: string;
  /** Entry types that are taxed (unless the mode is NONE). */
  taxScope: OfferingType[];
  defaultTaxRateId: number | null;
  timezone: string;
  khqrSimulated: boolean;
  hasLogo: boolean;
  logoVersion: number;
}

/** Settings > Company, as managers and admins see it. */
export interface CompanySettings {
  name: string;
  legalName: string | null;
  taxId: string | null;
  address: string | null;
  phone: string | null;
  email: string | null;
  website: string | null;
  receiptFooter: string | null;
  timezone: string;
  hasLogo: boolean;
  logoVersion: number;
  updatedBy: string | null;
  updatedAt: string;
}

export interface Currency {
  code: string;
  name: string;
  symbol: string;
  decimals: number;
  /** Smallest amount handed over in cash, e.g. 100 for riel. */
  cashRounding: number;
  base: boolean;
  active: boolean;
  sortOrder: number;
  /** Units of this currency per 1 base unit; 1 for the base. */
  rate: number | null;
  rateSetAt: string | null;
  rateSetBy: string | null;
}

export interface RateChange {
  id: number;
  rate: number;
  note: string | null;
  setBy: string | null;
  setAt: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Category {
  id: number;
  name: string;
}

export type OfferingType = "PRODUCT" | "SERVICE" | "ITEM" | "PACKAGE";

/** One line of a package. `lineValue` = component price x quantity. */
export interface PackageLine {
  componentId: number;
  type: OfferingType;
  name: string;
  sku: string;
  quantity: number;
  unitId: number | null;
  /** pcs, box, job, h... */
  unit: string | null;
  lineValue: number;
  componentActive: boolean;
}

export interface PackageInfo {
  /** How many can be made from stock; null if nothing inside is stocked. */
  available: number | null;
  containsServices: boolean;
  /** What the contents cost on their own. */
  separatePrice: number;
  /** Only from GET /offerings/{id}. */
  lines: PackageLine[] | null;
}
export type PricingMode = "FIXED" | "HOURLY";

export interface Unit {
  id: number;
  symbol: string;
  name: string;
}

export interface UnitWithUsage extends Unit {
  /** How many catalog entries use it, as base unit or pack. */
  usage: number;
}

/** One pack `symbol` holds `factor` base units. */
export interface Pack {
  unitId: number;
  symbol: string;
  name: string;
  factor: number;
}

export interface ServiceComponent {
  itemId: number;
  itemName: string;
  sku: string;
  unit: string | null;
  quantity: number;
  itemStock: number;
  itemActive: boolean;
}

/**
 * A catalog entry. Stock, unit and packs are null for services; `stock` is in the base unit.
 * `packs` and `components` only come from GET /offerings/{id}.
 */
export interface Offering {
  id: number;
  type: OfferingType;
  sku: string;
  /** The main barcode, printed on labels. */
  barcode: string | null;
  /** Every barcode, the main one first. */
  barcodes: string[];
  /** English name. */
  name: string;
  /** Khmer name; null on entries made before it was required. */
  nameKm: string | null;
  description: string | null;
  hasImage: boolean;
  imageVersion: number;
  unitId: number | null;
  /** Base unit symbol, e.g. "pcs". */
  unit: string | null;
  categoryId: number | null;
  categoryName: string | null;
  price: number;
  pricing: PricingMode | null;
  durationMinutes: number | null;
  cost: number;
  taxRateId: number;
  taxRateName: string;
  /** The entry's assigned rate. */
  taxRate: number;
  /** What the entry is actually charged under the tax mode and scope ("Not taxed" and 0 when outside). */
  effectiveTaxName: string;
  effectiveTaxRate: number;
  /** Whether the listed price includes that tax (VAT included mode). */
  includesTax: boolean;
  stock: number | null;
  lowStockThreshold: number | null;
  active: boolean;
  sellable: boolean;
  packs: Pack[] | null;
  components: ServiceComponent[] | null;
  /** Packages only. */
  packageInfo: PackageInfo | null;
}

export interface StaffMember {
  id: number;
  fullName: string;
}

export type DiscountType = "PERCENT" | "AMOUNT";
export type PaymentMethod = "CASH" | "KHQR";
export type OrderStatus = "PENDING_PAYMENT" | "COMPLETED" | "CANCELLED" | "PARTIALLY_REFUNDED" | "REFUNDED";
export type PaymentStatus = "PENDING" | "PAID" | "EXPIRED" | "CANCELLED" | "REFUNDED";

/** `amount`, `tendered` and `changeDue` are in the base currency. */
export interface Payment {
  id: number;
  orderId: number;
  method: PaymentMethod;
  status: PaymentStatus;
  amount: number;
  /** What a KHQR code charges, or the base currency for cash. */
  currency: string | null;
  currencyAmount: number | null;
  tendered: number | null;
  changeDue: number | null;
  changeCurrency: string | null;
  changeAmount: number | null;
  tenders: { currency: string; amount: number; rate: number; baseAmount: number }[];
  khqrPayload: string | null;
  expiresAt: string | null;
  paidAt: string | null;
}

export interface OrderItem {
  /** The order line's id, for returns. */
  id: number;
  offeringId: number;
  type: OfferingType;
  name: string;
  nameKm: string | null;
  sku: string;
  unit: string | null;
  /** What it sold at; `listPrice` is the catalog price at the time. */
  unitPrice: number;
  listPrice: number;
  priceOverridden: boolean;
  priceOverrideReason: string | null;
  priceOverrideBy: string | null;
  quantity: number;
  hours: number | null;
  durationMinutes: number | null;
  taxName: string;
  /** The rate actually charged (0 when the line wasn't taxed). */
  taxRate: number;
  /** Whether lineTotal includes the tax. */
  priceIncludesTax: boolean;
  /** Before any discount. */
  lineTotal: number;
  /** An item discount as entered, and the money it took off. */
  itemDiscountType: DiscountType | null;
  itemDiscountValue: number | null;
  itemDiscount: number;
  /** The line's share of the order discount, its net (no tax), tax, and what was paid for it. */
  lineDiscount: number;
  lineNet: number;
  lineTax: number;
  linePaid: number;
  refundedQty: number;
  refundedAmount: number;
  refundedTax: number;
  staffName: string | null;
  partsUsed: { itemId: number; itemName: string; quantity: number }[];
  /** Packages: what was inside (depth > 0 = inside a nested package). */
  contents: { offeringId: number; type: OfferingType; name: string; nameKm: string | null; quantity: number; unit: string | null; depth: number }[];
}

export interface Order {
  id: number;
  orderNumber: string;
  status: OrderStatus;
  subtotal: number;
  discountType: DiscountType | null;
  discountValue: number | null;
  /** The order discount (after item discounts). */
  discountAmount: number;
  /** Item discounts on all lines. */
  itemDiscount: number;
  /** All tax; `taxIncluded` of it was already inside the prices. */
  taxAmount: number;
  taxIncluded: number;
  /** The tax mode and label when sold; later setting changes don't affect them. */
  taxMode: TaxMode;
  taxLabel: string;
  /** Net amounts (after discount, before tax) of lines that were and weren't taxed. */
  taxableAmount: number;
  nonTaxableAmount: number;
  total: number;
  refundedTotal: number;
  refundedTax: number;
  currency: string;
  note: string | null;
  customerId: number;
  customerCode: string | null;
  /** Sold to the default Walk-in Customer. */
  walkIn: boolean;
  /** The customer's name at the sale, or a name typed for a walk-in. */
  customerName: string | null;
  customerPhone: string | null;
  customerTaxId: string | null;
  cashierName: string;
  branchId: number;
  branchName: string;
  storageName: string;
  createdAt: string;
  completedAt: string | null;
  items: OrderItem[];
  payments: Payment[];
  /** The rates the order was sold at. */
  rates: { currency: string; rate: number }[];
  /** Per tax rate: the net base after discount and the tax, included or added. */
  taxes: { name: string; rate: number; included: boolean; base: number; tax: number }[];
  returns: OrderReturn[];
}

/** A row in Sales > Returns & refunds. `fullRefund` = it refunded everything that was left on the order. */
export interface ReturnSummary {
  id: number;
  returnNumber: string;
  orderId: number;
  orderNumber: string;
  customerId: number;
  walkIn: boolean;
  customerName: string | null;
  branchName: string;
  total: number;
  tax: number;
  reason: string | null;
  items: string;
  createdBy: string;
  createdAt: string;
  fullRefund: boolean;
}

/** Goods taken back and refunded: `total` refunded, `tax` the tax inside it. */
export interface OrderReturn {
  id: number;
  returnNumber: string;
  reason: string | null;
  total: number;
  tax: number;
  net: number;
  createdBy: string;
  createdAt: string;
  lines: { orderItemId: number; name: string; quantity: number; total: number; tax: number }[];
}

export interface TaxReport {
  currency: string;
  from: string;
  to: string;
  summary: {
    orders: number;
    returns: number;
    grossSales: number;
    discounts: number;
    netSales: number;
    taxAdded: number;
    taxIncluded: number;
    taxCollected: number;
    taxableSales: number;
    nonTaxableSales: number;
    refunds: number;
    taxRefunded: number;
    netRefunds: number;
    netTax: number;
  };
  byRate: { name: string; rate: number; included: boolean; taxableBase: number; tax: number; refundedBase: number; refundedTax: number; netTax: number }[];
  byMode: { mode: TaxMode; orders: number; grossSales: number; tax: number }[];
  daily: { date: string; grossSales: number; taxCollected: number; refunds: number; taxRefunded: number }[];
}

export interface OrderSummary {
  id: number;
  orderNumber: string;
  status: OrderStatus;
  total: number;
  refundedTotal: number;
  currency: string;
  customerId: number;
  walkIn: boolean;
  customerName: string | null;
  cashierName: string;
  branchName: string;
  createdAt: string;
}

/**
 * Headline figures. `sales` = paid (tax incl.) minus refunds made in the period; `netSales` the same
 * without tax; `averageOrder` = what an order came to before later refunds; `margin` in % or null.
 */
export interface Kpis {
  orders: number;
  items: number;
  sales: number;
  netSales: number;
  tax: number;
  discounts: number;
  returns: number;
  refunds: number;
  cost: number;
  profit: number;
  margin: number | null;
  averageOrder: number;
}

export interface Dashboard {
  currency: string;
  from: string;
  to: string;
  today: Kpis;
  period: Kpis;
  /** The same number of days just before `from`. */
  previous: Kpis;
  daily: { date: string; sales: number; orders: number; profit: number }[];
  topSellers: { offeringId: number; type: OfferingType; name: string; quantity: number; netSales: number; profit: number }[];
  /** Money received by method, by when it was paid. */
  paymentMethods: { method: PaymentMethod; amount: number; count: number }[];
  refundsPaid: number;
  cashByCurrency: { currency: string; received: number; changeGiven: number; net: number }[];
  lowStock: number;
  lowStockItems: { offeringId: number; name: string; unit: string | null; stock: number; threshold: number }[];
  pendingOrders: number;
  incomingTransfers: number;
  newCustomers: number;
  topCustomers: { customerId: number; name: string; orders: number; spent: number }[];
  /** Only for people who can see purchasing. */
  payables: { owed: number; overdue: number; openOrders: number } | null;
}

/** Reports module: one report as a table. Column types: text, int, decimal, money, percent, date, datetime. */
export interface ReportColumn {
  key: string;
  label: string;
  type: "text" | "int" | "decimal" | "money" | "percent" | "date" | "datetime";
}

export interface ReportResult {
  key: string;
  title: string;
  description: string | null;
  columns: ReportColumn[];
  rows: Record<string, string | number | null>[];
  totals: Record<string, string | number | null> | null;
  notes: string[];
  currency: string;
}

export interface ReportInfo {
  key: string;
  title: string;
  group: string;
  description: string;
  permission: Permission;
  /** Uses the date range (otherwise "as of now"). */
  dated: boolean;
}

export interface TaxRate {
  id: number;
  name: string;
  /** A fraction: 0.1 for 10%. */
  rate: number;
  defaultRate: boolean;
  active: boolean;
  sortOrder: number;
  /** Catalog entries using it. */
  usage: number;
}

export type HoldStatus = "HELD" | "IN_USE" | "COMPLETED" | "VOIDED" | "EXPIRED";

/** A held (parked) sale. `expiresAt` is set while it waits to be resumed. */
export interface HeldSale {
  id: number;
  holdNumber: string;
  status: HoldStatus;
  label: string | null;
  customerId: number | null;
  customerCode: string | null;
  customerName: string | null;
  customerPhone: string | null;
  note: string | null;
  itemCount: number;
  estimatedTotal: number;
  createdBy: string;
  createdByMe: boolean;
  createdAt: string;
  updatedBy: string | null;
  updatedAt: string;
  lockedBy: string | null;
  lockedByMe: boolean;
  lockedAt: string | null;
  expiresAt: string | null;
  orderId: number | null;
  voidReason: string | null;
  closedAt: string | null;
}

export interface HeldLine {
  offeringId: number;
  name: string;
  quantity: number;
  hours: number | null;
  staffId: number | null;
  unitPrice: number | null;
  priceReason: string | null;
  discountType: DiscountType | null;
  discountValue: number | null;
  catalogPrice: number | null;
}

/** A resumed hold: the cart to load and what changed since it was held. */
export interface ResumedHold {
  hold: HeldSale;
  cart: { lines: HeldLine[]; discountType: DiscountType | null; discountValue: number | null };
  warnings: string[];
}
