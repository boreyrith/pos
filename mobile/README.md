# POS mobile (Flutter)

A cashier app for phones and tablets (iOS and Android) that talks to the existing POS backend.
No backend changes are needed.

## What it does

- **Sign in** against your POS server. Pick the server on the sign-in screen, for example
  `api-munita.virtual-assistant.space`. The app adds `https://` and `/api` itself. The
  session survives app restarts, because the refresh token is kept in the iOS keychain or
  Android keystore. Accounts that must change their password are asked to do that first.
- **Sell:**
  - Catalog grid with pictures and stock, search, categories, and favourites (long-press an
    item to star it).
  - Barcode scanning with the camera. A Bluetooth or USB scanner that types into the search
    box also works.
  - Hourly services ask for the hours worked.
- **Cart:**
  - Change quantities and hours.
  - Item discounts, for roles with "Discount items", within the role's limit.
  - Order discount (% or amount), customer, and note.
  - The cart is saved on the device, per user.
- **Totals:** worked out exactly as the server charges them. `lib/core/pricing.dart` mirrors
  `PricingCalculator.java` and is tested against the same `pricing-cases.json`. If prices
  change before checkout, the server refuses the sale and the app reloads today's prices.
- **Cash:** in any mix of active currencies (for example USD and KHR), with change given in
  the currency the cashier picks. Change is rounded to the cash step.
- **KHQR:**
  - Shows the QR code, checks every 3 seconds whether it was paid, and lets the cashier
    cancel.
  - In test mode, a button confirms the payment by hand.
  - If the app is closed while a payment is waiting, it shows the QR again on return
    instead of charging twice.
- **Receipts:** shown after each sale and on every paid order, with the same details as the
  web receipt. Print goes through the phone's print dialog (AirPrint on iOS, the print
  service on Android); Share sends an 80mm PDF to Telegram, email and so on. The receipt goes
  into the PDF as a picture, because PDF text can't shape Khmer. Printing is off for cancelled
  and unpaid orders.
- **Orders:** a day's orders, with search and details.
- **Held sales:**
  - Hold a sale with a label, then resume, take over, put back, or void it.
  - Holds are shared with the web POS.
- **English and Khmer.** The language is saved on the account. Khmer texts come from the
  web app's translations (`tool/gen_km.py`), and server error messages are translated too.
- **Layout:** a phone shows a bottom tab bar, with the cart behind a bar. A tablet (700 px
  and wider, so every iPad upright or sideways) shows a side rail, with the catalog and cart side by side.

The app is for selling. Back-office work (catalog, stock, purchasing, reports, settings,
users) stays in the web app.

## Run it (development)

Start the backend (`../backend`, dev profile on port 8081). Then run the app with one of
these:

```bash
flutter run
```

The iOS simulator reaches the Mac at `http://localhost:8081/api`. The Android emulator
reaches it at `http://10.0.2.2:8081/api`. Those are the defaults. On a real phone, set the
server on the sign-in screen to the Mac's LAN address, for example `http://192.168.1.20:8081`.
Plain HTTP is allowed only in debug builds on Android, and only for local network addresses
on iOS.

The dev database has demo logins, listed in `DevDataSeeder.java`.

## Build for a store

Give the build your API address so staff don't have to type it:

```bash
flutter build apk --release --dart-define=API_URL=https://api-munita.virtual-assistant.space/api
```

```bash
flutter build ipa --release --dart-define=API_URL=https://api-munita.virtual-assistant.space/api
```

Before publishing:

- **Signing:**
  - Android needs a release signing key; see the Flutter docs on "Build and release an
    Android app". The template signs release builds with the debug key until you add one.
  - iOS needs your Apple team set in Xcode (`ios/Runner.xcworkspace` → Signing &
    Capabilities).
- **App id:** the app id is `com.pos.posMobile` (iOS) and `com.pos.pos_mobile` (Android).
  Change it to your own reverse domain before the first store upload.

## Checks

```bash
flutter analyze
```

```bash
flutter test
```

```bash
python3 tool/check_km.py
```

- `flutter test` runs the pricing tests, against `../backend/src/test/resources/pricing-cases.json`.
- `check_km.py` checks that every text in the app has Khmer.

The tablet test runs the real app on a tablet or iPad simulator against a dev server. It uses one
of the demo logins, and fails on a layout overflow:

```bash
flutter test integration_test/tablet_test.dart -d DEVICE --dart-define=TEST_USER=cashier --dart-define=TEST_PASSWORD=...
```

After the web app's Khmer changes, regenerate `lib/core/km_web.g.dart`:

```bash
python3 tool/gen_km.py
```

App icons come from `assets/icon/app_icon.png`. After changing it, regenerate them:

```bash
dart run flutter_launcher_icons
```

## Layout

```
lib/
  api/        api_client.dart (HTTP, tokens, errors), models.dart (JSON shapes)
  core/       pricing.dart, money.dart (formatting, cash rounding), i18n.dart + Khmer maps
  state/      session.dart (server, sign-in, language), cart.dart, data.dart (store, catalog, orders...)
  screens/    login, change password, home tabs, sell/ (catalog, cart, payment, KHQR, scanner),
              orders, held sales, receipt, more
  widgets/    common.dart (theme, errors, amounts, images)
```
